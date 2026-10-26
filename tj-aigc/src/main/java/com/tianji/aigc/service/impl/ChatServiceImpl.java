package com.tianji.aigc.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.ChatRedisConstants;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.ai",name = "chat-type",havingValue = "ENHANCE")
public class ChatServiceImpl implements ChatService {

    /**
     * 兜底过期时间：实例宕机、异常退出时，避免标识一直残留在 Redis 里
     */
    private static final Duration GENERATE_STATUS_TTL = Duration.ofHours(1);

    private final SystemPromptConfig systemPromptConfig;
    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;
    private final ChatMemory chatMemory;
    // 输出结束的标记
    private static final ChatEventVO STOP_EVENT = ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();

    private final VectorStore vectorStore;
    /**
     * 会话记忆 advisor：默认 advisor 里已经不再挂载，这里显式加入
     */
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;



    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        String statusKey = ChatRedisConstants.CHAT_GENERATE_STATUS_KEY_PREFIX + sessionId;
        // 获取对话id
        var conversationId = ChatService.getConversationId(sessionId);
        // 大模型输出内容的缓存器，用于在输出中断后的数据存储
        // 用 StringBuffer 而不是 StringBuilder：map 里写入、doOnCancel 里读取，
        // 客户端断开时 cancel 可能来自 Tomcat 线程，StringBuilder 既不安全也可能读到旧值
        var outputBuilder = new StringBuffer();

        // 生成请求id
        var requestId = IdUtil.fastSimpleUUID();

        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.6d)
                        .topK(6).build())
                                .build();


        return this.chatClient.prompt()
                .system(promptSystem -> promptSystem
                        .text(this.systemPromptConfig.getChatSystemMessage().get()) // 设置系统提示语
                        .param("now", DateUtil.now())) // 设置当前时间的参数)
                .advisors(advisor -> advisor
                        .advisors(this.messageChatMemoryAdvisor, qaAdvisor)
                        .param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(Map.of(Constant.REQUEST_ID, requestId))//通过工具上下文传递参数
                .user(question)
                .stream()
                .chatResponse()
                // 标记开始生成，写入 Redis，其他实例的 /chat/stop 也能看到并删除
                .doFirst(() -> markGenerating(statusKey, sessionId))
                // 模型流由 WebClient 的 Netty 线程驱动，后面要同步访问 Redis，先切到弹性线程池，避免阻塞事件循环
                .publishOn(Schedulers.boundedElastic())
                // 每收到一个分片就检查一次 Redis 标识：一旦被 /chat/stop 删除（或过期），立即结束上游流
                .doOnCancel(() -> {
                    // 当输出被取消时，保存输出的内容到历史记录中
                    this.saveStopHistoryRecord(conversationId, outputBuilder.toString());
                })
                .takeWhile(chatResponse -> isGenerating(statusKey))
                // 个别分片只携带 usage、没有实际内容，先过滤掉，避免后面取 result/output 时 NPE
                .filter(chatResponse -> chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null)
                .map(chatResponse -> {
                    // 对于响应结果进行处理，如果是最后一条数据，就把此次消息id与请求id的映射放到内存中
                    // 主要用于把消息存储到 redis 时，可以根据消息id获取到请求id，再通过请求id获取到参数列表，
                    // 从而解决在历史聊天记录中没有额外参数（如课程卡片）的问题
                    var result = chatResponse.getResult();
                    if (result != null && StrUtil.equals(Constant.STOP, result.getMetadata().getFinishReason())) {
                        var messageId = chatResponse.getMetadata().getId();
                        ToolResultHolder.put(messageId, Constant.REQUEST_ID, requestId);
                    }
                    // 获取大模型的输出的内容
                    String text = chatResponse.getResult().getOutput().getText();
                    // 追加到输出内容中（注意：append(null) 会写入字面量 "null"，必须先判空）
                    if (text != null) {
                        outputBuilder.append(text);
                    }
                    // 封装响应对象
                    return ChatEventVO.builder()
                            .eventData(text)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })
                .concatWith(
                        Flux.defer(()->{
                            // 通过请求id获取到参数列表，如果不为空，就将其追加到返回结果中
                            var map = ToolResultHolder.get(requestId);
                            if (CollUtil.isNotEmpty(map)) {
                                ToolResultHolder.remove(requestId); // 清除参数列表

                                // 响应给前端的参数数据
                                var chatEventVO = ChatEventVO.builder()
                                        .eventData(map)
                                        .eventType(ChatEventTypeEnum.PARAM.getValue())
                                        .build();
                                return Flux.just(chatEventVO, STOP_EVENT);
                            }
                            return Flux.just(STOP_EVENT);

                        })
                )
                // 正常结束、异常、客户端断开（取消订阅）都要清理标识，避免残留
                .doFinally(signal -> {
                    clearGenerating(statusKey, sessionId);
                    // 兜底清理工具参数：取消/异常时 concatWith 不会执行，不清理会一直留在内存里
                    ToolResultHolder.remove(requestId);
                });
    }

    @Override
    public void stop(String sessionId) {
        String statusKey = ChatRedisConstants.CHAT_GENERATE_STATUS_KEY_PREFIX + sessionId;
        try {
            Boolean deleted = this.redisTemplate.delete(statusKey);
            log.info("停止对话, sessionId: {}, 生成标识是否已删除: {}", sessionId, deleted);
        } catch (Exception e) {
            log.error("停止对话失败, sessionId: {}", sessionId, e);
        }
    }

    private void markGenerating(String statusKey, String sessionId) {
        try {
            this.redisTemplate.opsForValue().set(statusKey, "1", GENERATE_STATUS_TTL);
            log.debug("开始生成对话, sessionId: {}", sessionId);
        } catch (Exception e) {
            // Redis 异常时不中断对话，退化为“不可停止”
            log.warn("写入对话生成标识失败, sessionId: {}", sessionId, e);
        }
    }

    private boolean isGenerating(String statusKey) {
        try {
            return Boolean.TRUE.equals(this.redisTemplate.hasKey(statusKey));
        } catch (Exception e) {
            // Redis 异常时不中断对话
            log.debug("查询对话生成标识失败, key: {}", statusKey, e);
            return true;
        }
    }

    private void clearGenerating(String statusKey, String sessionId) {
        try {
            this.redisTemplate.delete(statusKey);
            log.debug("对话生成结束, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.warn("清理对话生成标识失败, sessionId: {}", sessionId, e);
        }
    }

    /**
     * 保存停止输出的记录
     *
     * @param conversationId 会话id
     * @param content        大模型输出的内容
     */
    private void saveStopHistoryRecord(String conversationId, String content) {
        if (!StringUtils.hasText(content)) {
            // 还没输出任何内容就点了停止，没必要存一条空的助手消息
            return;
        }
        try {
            this.chatMemory.add(conversationId, new AssistantMessage(content));
            log.info("已保存中断前的对话内容, conversationId: {}, 长度: {}", conversationId, content.length());
        } catch (Exception e) {
            // doOnCancel 里抛出的异常会被 Reactor 当作 onErrorDropped 丢弃，这里显式记录
            log.error("保存中断前的对话内容失败, conversationId: {}", conversationId, e);
        }
    }


}

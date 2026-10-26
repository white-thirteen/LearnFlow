package com.tianji.aigc.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.mcp.tool.McpToolRegistry;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public abstract class AbstractAgent implements Agent {

    @Resource
    private ChatSessionService chatSessionService;
    @Resource
    private ChatClient chatClient;
    @Resource
    private ChatMemory chatMemory;
    @Resource
    private MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    @Resource
    private McpToolRegistry mcpToolRegistry;

    /**
     * 只读历史时，注入到系统提示词中的最近消息条数
     */
    private static final int HISTORY_MESSAGE_LIMIT = 4;

    /**
     * 只读历史时，单条消息的最大长度，避免路由提示词过长
     */
    private static final int HISTORY_MESSAGE_MAX_LENGTH = 200;

    // 输出结束的标记
    public static final ChatEventVO STOP_EVENT = ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();

    // 存储大模型的生成状态，这里采用ConcurrentHashMap是确保线程安全
    // 目前的版本暂时用Map实现，如果考虑分布式环境的话，可以考虑用redis来实现
    public static final Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>();

    @Override
    public String process(String question, String sessionId) {
        // 获取用户id
        var userId = UserContext.getUser();
        var requestId = this.generateRequestId();

        //更新会话时间
        this.chatSessionService.updateSession(sessionId, question, userId);

        return this.getChatClientRequest(sessionId, requestId, question)
                .call()
                .content();
    }

    public Flux<ChatEventVO> processStream(String question, String sessionId) {
        // 获取用户id
        var userId = UserContext.getUser();
        var requestId = this.generateRequestId();
        // 大模型输出内容的缓存器，用于在输出中断后的数据存储
        var outputBuilder = new StringBuilder();
        // 获取对话id
        var conversationId = ChatService.getConversationId(sessionId);

        //更新会话时间
        this.chatSessionService.updateSession(sessionId, question, userId);

        return this.getChatClientRequest(sessionId, requestId, question)
                .stream()
                .chatResponse()
                .doFirst(() -> GENERATE_STATUS.put(sessionId, true)) // 第一次输出内容时执行
                .doOnError(throwable -> GENERATE_STATUS.remove(sessionId)) // 出现异常时，删除标识
                .doOnComplete(() -> GENERATE_STATUS.remove(sessionId)) // 完成时执行，删除标识
                .doOnCancel(() -> {
                    // 当输出被取消时，保存输出的内容到历史记录中
                    this.saveStopHistoryRecord(conversationId, outputBuilder.toString());
                })
                .takeWhile(response -> { // 通过返回值来控制Flux流是否继续，true：继续，false：终止
                    return GENERATE_STATUS.getOrDefault(sessionId, false);
                })
                .map(chatResponse -> {
                    var finishReason = chatResponse.getResult().getMetadata().getFinishReason();
                    if (StrUtil.equals(Constant.STOP, finishReason)) {
                        var messageId = chatResponse.getMetadata().getId();
                        ToolResultHolder.put(messageId, Constant.REQUEST_ID, requestId);
                    }

                    // 获取大模型的输出的内容
                    var text = chatResponse.getResult().getOutput().getText();
                    // 追加到输出内容中
                    outputBuilder.append(text);
                    // 封装响应对象
                    return ChatEventVO.builder()
                            .eventData(text)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })
                .concatWith(Flux.defer(() -> {
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
                }));
    }

    /**
     * 是否启用会话记忆（读写）。
     * <p>业务智能体需要记忆，路由智能体只需要"只读"上下文，覆写为 false 即可。</p>
     */
    protected boolean memoryEnabled() {
        return true;
    }

    private ChatClient.ChatClientRequestSpec getChatClientRequest(String sessionId, String requestId, String question) {
        // 组装 advisor：业务智能体在最前面加入会话记忆 advisor（读写），路由智能体不加
        var advisors = new ArrayList<Advisor>(this.advisors());
        if (this.memoryEnabled()) {
            advisors.add(0, this.messageChatMemoryAdvisor);
        }
        // 本地 @Tool 与 MCP 远程回调统一挂载，避免默认工具与请求工具互相覆盖。
        var callbacks = new ArrayList<ToolCallback>(Arrays.asList(ToolCallbacks.from(this.tools())));
        callbacks.addAll(Arrays.asList(this.mcpToolRegistry.getToolCallbacks(this.getAgentType())));
        var names = callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList();
        if (names.stream().distinct().count() != names.size()) {
            throw new IllegalStateException("Agent 工具名称重复: " + names);
        }
        log.debug("Agent {} request tools: {}", this.getAgentType(), names);
        return this.chatClient.prompt()
                .system(promptSystem -> promptSystem
                        .text(this.systemMessageWithHistory(sessionId))
                        .params(this.systemMessageParams()))
                .advisors(advisor -> advisor.advisors(advisors).params(this.advisorParams(sessionId, requestId)))
                .toolCallbacks(callbacks)
                .toolContext(this.toolContext(sessionId, requestId))
                .user(question);
    }

    /**
     * 组装最终的系统提示词。
     * <p>业务智能体：原样返回，历史由 MessageChatMemoryAdvisor 自动注入；</p>
     * <p>路由智能体：以只读方式读取最近几条会话历史，格式化后拼进系统提示词，
     * 只用于理解用户指代，不参与对话延续。</p>
     */
    private String systemMessageWithHistory(String sessionId) {
        var systemMessage = this.systemMessage();
        if (this.memoryEnabled()) {
            return systemMessage;
        }
        var history = this.recentHistory(sessionId);
        if (StrUtil.isBlank(history)) {
            return systemMessage;
        }
        return systemMessage
                + "\n\n## 最近对话（仅用于理解用户指代，不要延续对话，也不要复述）\n"
                + history;
    }

    /**
     * 只读方式读取最近的会话历史，格式化为"角色：内容"的文本
     */
    private String recentHistory(String sessionId) {
        try {
            var conversationId = ChatService.getConversationId(sessionId);
            var messages = this.chatMemory.get(conversationId);
            if (CollUtil.isEmpty(messages)) {
                return "";
            }
            // 只保留用户和助手的消息，过滤掉系统消息、工具消息
            var history = messages.stream()
                    .filter(message -> message.getMessageType() == MessageType.USER
                            || message.getMessageType() == MessageType.ASSISTANT)
                    .toList();
            // 只取最近几条
            var fromIndex = Math.max(0, history.size() - HISTORY_MESSAGE_LIMIT);
            var builder = new StringBuilder();
            for (var message : history.subList(fromIndex, history.size())) {
                var role = message.getMessageType() == MessageType.USER ? "用户" : "助手";
                builder.append(role)
                        .append("：")
                        .append(this.truncate(message.getText()))
                        .append('\n');
            }
            return builder.toString();
        } catch (Exception e) {
            // 读取历史失败不影响路由，退化为无上下文
            log.warn("读取会话历史失败, sessionId: {}", sessionId, e);
            return "";
        }
    }

    /**
     * 截断过长的消息，控制提示词长度
     */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= HISTORY_MESSAGE_MAX_LENGTH
                ? text
                : text.substring(0, HISTORY_MESSAGE_MAX_LENGTH) + "...";
    }

    /**
     * 保存停止输出的记录
     *
     * @param conversationId 对话id
     * @param content   大模型输出的内容
     */
    private void saveStopHistoryRecord(String conversationId, String content) {
        this.chatMemory.add(conversationId, new AssistantMessage(content));
    }

    private String generateRequestId() {
        return IdUtil.fastSimpleUUID();
    }

    @Override
    public Map<String, Object> advisorParams(String sessionId, String requestId) {
        var conversationId = ChatService.getConversationId(sessionId);
        return Map.of(ChatMemory.CONVERSATION_ID, conversationId);
    }

    @Override
    public void stop(String sessionId) {
        GENERATE_STATUS.remove(sessionId);
    }
}


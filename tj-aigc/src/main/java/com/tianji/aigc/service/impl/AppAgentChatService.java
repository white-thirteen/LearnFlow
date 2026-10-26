package com.tianji.aigc.service.impl;

import com.alibaba.dashscope.app.ApplicationParam;
import com.alibaba.dashscope.app.ApplicationResult;
import com.alibaba.dashscope.utils.JsonUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tianji.aigc.config.DashScopeProperties;
import com.tianji.aigc.constants.ChatRedisConstants;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.CommonException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.TokenContext;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.ai", name = "chat-type", havingValue = "APP")
public class AppAgentChatService implements ChatService {

    private static final Duration GENERATING_TTL = Duration.ofHours(1);
    // 百炼会话闲置一小时过期，提前失效后可用本地历史恢复。
    private static final Duration SESSION_TTL = Duration.ofMinutes(55);
    private static final Duration STOP_POLL_INTERVAL = Duration.ofMillis(200);
    private static final ChatEventVO STOP_EVENT = ChatEventVO.builder()
            .eventType(ChatEventTypeEnum.STOP.getValue()).build();
    // 保留停止中的锁，直到原请求清理完毕，避免新请求与旧请求同时写历史。
    private static final DefaultRedisScript<Long> STOP_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]); "
                    + "if v and string.sub(v, 1, 5) ~= 'stop:' then "
                    + "return redis.call('set', KEYS[1], 'stop:' .. v, 'KEEPTTL') and 1 end; return 0", Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]); "
                    + "if v == ARGV[1] or v == 'stop:' .. ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) end; return 0", Long.class);

    private final DashScopeProperties dashScopeProperties;
    private final StringRedisTemplate redisTemplate;
    private final ChatMemory chatMemory;
    private final ChatSessionService chatSessionService;
    private final DashScopeApplicationClient applicationClient;

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        // ThreadLocal 只在 MVC 请求线程读取，后续 Redis/SDK 操作在线程池执行。
        Long userId = UserContext.getUser();
        String token = TokenContext.getToken();
        return Flux.using(
                        () -> acquire(question, sessionId, userId, token),
                        state -> stream(question, sessionId, userId, token, state),
                        this::finish,
                        true)
                .onErrorResume(error -> {
                    log.error("APP 对话失败, sessionId: {}", sessionId, error);
                    int code = error instanceof CommonException ? ((CommonException) error).getCode() : 500;
                    String message = error instanceof CommonException
                            ? error.getMessage() : "智能体调用失败，请稍后重试";
                    return Flux.just(ChatEventVO.builder()
                            .eventType(ChatEventTypeEnum.ERROR.getValue())
                            .eventData(Map.of("code", code, "message", message)).build());
                })
                .concatWithValues(STOP_EVENT)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Generation acquire(String question, String sessionId, Long userId, String token) {
        requireSession(sessionId, userId);
        if (!StringUtils.hasText(question)) {
            throw new BadRequestException("问题不能为空");
        }
        var app = dashScopeProperties.getAppAgent();
        if (app == null || !StringUtils.hasText(app.getId()) || !StringUtils.hasText(dashScopeProperties.getKey())) {
            throw new CommonException(503, "未配置百炼应用，请检查 tj.ai.dashscope.key 和 app-agent.id");
        }
        if (app.getTools() != null && !app.getTools().isEmpty()) {
            if (!StringUtils.hasText(token)) {
                throw new UnauthorizedException("缺少插件鉴权 Token，请通过网关登录后重试");
            }
            if (app.getTools().stream().anyMatch(tool -> !StringUtils.hasText(tool))) {
                throw new CommonException(503, "百炼插件 ID 不能为空，请检查 app-agent.tools");
            }
        }
        String conversationId = userId + "_" + sessionId;
        var state = new Generation(conversationId, app.getId(), sessionId, userId);
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(state.statusKey, state.requestId, GENERATING_TTL))) {
            throw new BadRequestException("该会话正在生成，请等待完成或停止后重试");
        }
        return state;
    }

    private Flux<ChatEventVO> stream(String question, String sessionId, Long userId, String token, Generation state) {
        var app = dashScopeProperties.getAppAgent();
        var tools = app.getTools() == null ? List.<String>of() : app.getTools();
        var toolsMap = new HashMap<String, Object>();
        for (String tool : tools) {
            toolsMap.put(tool, Map.of("user_token", token));
        }
        var builder = ApplicationParam.builder()
                .apiKey(dashScopeProperties.getKey())
                .appId(app.getId())
                .prompt(question)
                .incrementalOutput(true);
        if (!toolsMap.isEmpty()) {
            builder.bizParams(JsonUtils.toJsonObject(Map.of("user_defined_tokens", toolsMap)));
        }
        String cloudSessionId = redisTemplate.opsForValue().get(state.sessionKey);
        if (StringUtils.hasText(cloudSessionId)) {
            builder.sessionId(cloudSessionId);
        } else {
            // 首次请求不传 sessionId；映射过期时带上本地问答历史，SDK 会追加本次 prompt。
            var history = chatMemory.get(state.conversationId).stream()
                    .filter(message -> message.getMessageType() == MessageType.USER
                            || message.getMessageType() == MessageType.ASSISTANT)
                    .<com.alibaba.dashscope.common.Message>map(message -> com.alibaba.dashscope.common.Message.builder()
                            .role(message.getMessageType().getValue()).content(message.getText()).build())
                    .toList();
            if (!history.isEmpty()) {
                builder.messages(history);
            }
        }
        chatSessionService.updateSession(sessionId, question, userId);
        state.question = question;

        var stopped = Flux.interval(STOP_POLL_INTERVAL)
                .publishOn(Schedulers.boundedElastic())
                .filter(tick -> !state.requestId.equals(redisTemplate.opsForValue().get(state.statusKey)))
                .next();
        return applicationClient.stream(builder.build())
                .doOnSubscribe(subscription -> state.requested = true)
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(result -> recordOutput(state, result))
                .doOnComplete(() -> state.completed = true)
                .filter(result -> result.getOutput() != null && StringUtils.hasLength(result.getOutput().getText()))
                .map(result -> ChatEventVO.builder()
                        .eventType(ChatEventTypeEnum.DATA.getValue())
                        .eventData(result.getOutput().getText()).build())
                .takeUntilOther(stopped);
    }

    private void recordOutput(Generation state, ApplicationResult result) {
        synchronized (state) {
            if (state.finished || result.getOutput() == null) {
                return;
            }
            if (StringUtils.hasText(result.getOutput().getSessionId())) {
                state.cloudSessionId = result.getOutput().getSessionId();
            }
            if (result.getOutput().getText() != null) {
                state.output.append(result.getOutput().getText());
            }
        }
    }

    private void finish(Generation state) {
        synchronized (state) {
            state.finished = true;
            try {
                String activeRequest = redisTemplate.opsForValue().get(state.statusKey);
                if (!state.requestId.equals(activeRequest) && !("stop:" + state.requestId).equals(activeRequest)) {
                    log.warn("APP 生成锁已失效，跳过旧请求写入, conversationId: {}", state.conversationId);
                    return;
                }
                if (chatSessionService.count(sessionQuery(state.sessionId, state.userId)) == 0) {
                    // 用户已删除会话，不能在断连或完成后重新创建历史。
                    redisTemplate.delete(state.sessionKey);
                    return;
                }
                if (state.requested) {
                    List<Message> messages = new ArrayList<>();
                    messages.add(new UserMessage(state.question));
                    if (!state.output.isEmpty()) {
                        messages.add(new AssistantMessage(state.output.toString()));
                    }
                    chatMemory.add(state.conversationId, messages);
                }
                if (state.completed && StringUtils.hasText(state.cloudSessionId)) {
                    redisTemplate.opsForValue().set(state.sessionKey, state.cloudSessionId, SESSION_TTL);
                } else {
                    // 中断后云端可能比前端多生成内容，下一轮从实际保存的本地记录恢复。
                    redisTemplate.delete(state.sessionKey);
                }
            } catch (Exception e) {
                log.error("保存 APP 会话失败, conversationId: {}", state.conversationId, e);
                try {
                    redisTemplate.delete(state.sessionKey);
                } catch (Exception cleanupError) {
                    e.addSuppressed(cleanupError);
                }
                throw e;
            } finally {
                try {
                    redisTemplate.execute(RELEASE_SCRIPT, List.of(state.statusKey), state.requestId);
                } catch (Exception e) {
                    log.error("清理 APP 生成状态失败, conversationId: {}", state.conversationId, e);
                }
            }
        }
    }

    @Override
    public void stop(String sessionId) {
        Long userId = UserContext.getUser();
        requireSession(sessionId, userId);
        redisTemplate.execute(STOP_SCRIPT,
                List.of(ChatRedisConstants.APP_GENERATE_STATUS_KEY_PREFIX + userId + "_" + sessionId));
    }

    private void requireSession(String sessionId, Long userId) {
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        if (!StringUtils.hasText(sessionId)) {
            throw new BadRequestException("会话 ID 不能为空");
        }
        if (chatSessionService.count(sessionQuery(sessionId, userId)) == 0) {
            throw new BadRequestException("会话不存在或无权访问");
        }
    }

    private LambdaQueryWrapper<ChatSession> sessionQuery(String sessionId, Long userId) {
        return new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId).eq(ChatSession::getUserId, userId);
    }

    private static class Generation {
        private final String conversationId;
        private final String statusKey;
        private final String sessionKey;
        private final String sessionId;
        private final Long userId;
        private final String requestId = UUID.randomUUID().toString();
        private final StringBuilder output = new StringBuilder();
        private String question;
        private String cloudSessionId;
        private volatile boolean requested;
        private volatile boolean completed;
        private boolean finished;

        private Generation(String conversationId, String appId, String sessionId, Long userId) {
            this.conversationId = conversationId;
            this.sessionId = sessionId;
            this.userId = userId;
            this.statusKey = ChatRedisConstants.APP_GENERATE_STATUS_KEY_PREFIX + conversationId;
            this.sessionKey = ChatRedisConstants.APP_SESSION_KEY_PREFIX + appId + ":" + conversationId;
        }
    }
}

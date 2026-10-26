package com.tianji.aigc.service.impl;

import com.alibaba.dashscope.app.ApplicationOutput;
import com.alibaba.dashscope.app.ApplicationParam;
import com.alibaba.dashscope.app.ApplicationResult;
import com.tianji.aigc.config.DashScopeProperties;
import com.tianji.aigc.constants.ChatRedisConstants;
import com.tianji.aigc.controller.ChatController;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.TokenContext;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AppAgentChatServiceTest {

    private static final String STATUS_KEY = ChatRedisConstants.APP_GENERATE_STATUS_KEY_PREFIX + "2_local-session";
    private static final String CLOUD_KEY = ChatRedisConstants.APP_SESSION_KEY_PREFIX + "test-app:2_local-session";
    private final Map<String, String> redisData = new ConcurrentHashMap<>();
    private DashScopeProperties properties;
    private StringRedisTemplate redis;
    private ChatMemory memory;
    private ChatSessionService sessions;
    private DashScopeApplicationClient client;
    private AppAgentChatService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new DashScopeProperties();
        properties.setKey("test-api-key");
        properties.getAppAgent().setId("test-app");
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> redisData.get(call.getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(call -> redisData.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        doAnswer(call -> {
            redisData.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(call -> redisData.remove(call.getArgument(0)) != null);
        // 模拟 Redis 原子释放；实际 Lua 另行用 Redis 验证。
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenAnswer(call -> {
            String key = ((List<String>) call.getArgument(1)).get(0);
            String requestId = call.getArgument(2);
            String value = redisData.get(key);
            if (requestId.equals(value) || ("stop:" + requestId).equals(value)) {
                redisData.remove(key);
                return 1L;
            }
            return 0L;
        });
        memory = MessageWindowChatMemory.builder().maxMessages(100).build();
        sessions = mock(ChatSessionService.class);
        when(sessions.count(any())).thenReturn(1L);
        client = mock(DashScopeApplicationClient.class);
        when(client.stream(any())).thenReturn(Flux.just(result("你好", "cloud-session")));
        service = new AppAgentChatService(properties, redis, memory, sessions, client);
        UserContext.setUser(2L);
        TokenContext.removeToken();
    }

    @AfterEach
    void clearContext() {
        UserContext.removeUser();
        TokenContext.removeToken();
    }

    @Test
    void invalidQuestionReturnsSseErrorAndStopInsteadOf406() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ChatController(service)).build();
        var pending = mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"question\":\"\",\"sessionId\":\"local-session\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(5000);
        var response = mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString();
        assertTrue(response.contains("\"eventType\":1004"));
        assertTrue(response.contains("\"eventType\":1002"));
        verifyNoInteractions(client);
    }

    @Test
    void firstTurnAllowsMissingToolsAndPersistsCloudIdAndHistory() {
        properties.getAppAgent().setTools(null);
        var events = chat("你好");
        assertEquals(List.of(1001, 1002), events.stream().map(ChatEventVO::getEventType).toList());
        var parameter = parameter();
        assertNull(parameter.getSessionId());
        assertNull(parameter.getBizParams());
        assertEquals("cloud-session", redisData.get(CLOUD_KEY));
        assertEquals(List.of("你好", "你好"), memory.get("2_local-session").stream().map(m -> m.getText()).toList());
        assertFalse(redisData.containsKey(STATUS_KEY));
        verify(sessions).updateSession("local-session", "你好", 2L);
    }

    @Test
    void nextTurnUsesReturnedCloudSession() {
        redisData.put(CLOUD_KEY, "returned-cloud-id");
        chat("继续");
        assertEquals("returned-cloud-id", parameter().getSessionId());
        assertNull(parameter().getMessages());
    }

    @Test
    void expiredMappingRestoresHistoryAndAddsCurrentQuestionOnce() {
        memory.add("2_local-session", List.of(new UserMessage("我叫小明"), new AssistantMessage("记住了")));
        chat("我叫什么？");
        var input = parameter().getInput();
        assertNull(parameter().getSessionId());
        var messages = input.getAsJsonArray("messages");
        assertEquals(3, messages.size());
        assertEquals("我叫什么？", messages.get(2).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void capturesPluginTokenBeforeLeavingRequestThread() {
        properties.getAppAgent().setTools(List.of("plugin-id"));
        TokenContext.setToken("user-token");
        var response = service.chat("查询课程", "local-session");
        UserContext.removeUser();
        TokenContext.removeToken();
        response.collectList().block(Duration.ofSeconds(5));
        assertEquals("user-token", parameter().getBizParams().getAsJsonObject("user_defined_tokens")
                .getAsJsonObject("plugin-id").get("user_token").getAsString());
    }

    @Test
    void missingPluginTokenProducesErrorAndStopWithoutCallingCloud() {
        properties.getAppAgent().setTools(List.of("plugin-id"));
        var events = chat("你好");
        assertError(events, 401);
        verifyNoInteractions(client);
        assertFalse(redisData.containsKey(STATUS_KEY));
    }

    @Test
    void missingConfigurationProducesReadableStreamError() {
        properties.getAppAgent().setId(null);
        assertError(chat("你好"), 503);
        verifyNoInteractions(client);
    }

    @Test
    void rejectsConcurrentTurnWithoutClearingExistingRequest() {
        redisData.put(STATUS_KEY, "other-request");
        assertError(chat("重复问题"), 400);
        assertEquals("other-request", redisData.get(STATUS_KEY));
        verifyNoInteractions(client);
    }

    @Test
    void missingSessionOrOwnerNeverCallsCloud() {
        when(sessions.count(any())).thenReturn(0L);
        assertError(chat("你好"), 400);
        verifyNoInteractions(client);
        assertTrue(memory.get("2_local-session").isEmpty());
    }

    @Test
    void deletingSessionDuringGenerationDoesNotRecreateHistory() {
        when(sessions.count(any())).thenReturn(1L, 0L);
        chat("问题");
        assertTrue(memory.get("2_local-session").isEmpty());
        assertFalse(redisData.containsKey(CLOUD_KEY));
        assertFalse(redisData.containsKey(STATUS_KEY));
    }

    @Test
    void cloudFailureSendsErrorThenStopAndInvalidatesCloudContext() {
        redisData.put(CLOUD_KEY, "old-cloud-id");
        when(client.stream(any())).thenReturn(Flux.concat(
                Flux.just(result("部分回答", "old-cloud-id")), Flux.error(new IllegalStateException("upstream failed"))));
        var events = chat("问题");
        assertEquals(List.of(1001, 1004, 1002), events.stream().map(ChatEventVO::getEventType).toList());
        assertFalse(redisData.containsKey(CLOUD_KEY));
        assertFalse(redisData.containsKey(STATUS_KEY));
        assertEquals("部分回答", memory.get("2_local-session").get(1).getText());
    }

    @Test
    void disconnectSavesPartialAnswerAndReleasesLock() throws Exception {
        var firstChunk = new CountDownLatch(1);
        when(client.stream(any())).thenReturn(Flux.concat(Flux.just(result("已输出", "cloud-session")), Flux.never()));
        var subscription = service.chat("问题", "local-session").subscribe(event -> firstChunk.countDown());
        try {
            assertTrue(firstChunk.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertFalse(redisData.containsKey(STATUS_KEY));
        assertFalse(redisData.containsKey(CLOUD_KEY));
        assertEquals(List.of("问题", "已输出"), memory.get("2_local-session").stream().map(m -> m.getText()).toList());
    }

    @Test
    void stopOnAnotherInstanceCancelsEvenWhenCloudSendsNoMoreChunks() throws Exception {
        var firstChunk = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var events = new java.util.concurrent.CopyOnWriteArrayList<ChatEventVO>();
        when(client.stream(any())).thenReturn(Flux.concat(Flux.just(result("部分", "cloud-session")), Flux.never()));
        var subscription = service.chat("问题", "local-session").subscribe(event -> {
            events.add(event);
            firstChunk.countDown();
        }, error -> completed.countDown(), completed::countDown);
        try {
            assertTrue(firstChunk.await(5, TimeUnit.SECONDS));
            // 模拟另一个实例的 /chat/stop 写入 Redis。
            redisData.computeIfPresent(STATUS_KEY, (key, value) -> "stop:" + value);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(1001, 1002), events.stream().map(ChatEventVO::getEventType).toList());
            assertFalse(redisData.containsKey(STATUS_KEY));
            assertFalse(redisData.containsKey(CLOUD_KEY));
            assertEquals("部分", memory.get("2_local-session").get(1).getText());
        } finally {
            subscription.dispose();
        }
    }

    private List<ChatEventVO> chat(String question) {
        return service.chat(question, "local-session").collectList().block(Duration.ofSeconds(5));
    }

    private ApplicationParam parameter() {
        var captor = ArgumentCaptor.forClass(ApplicationParam.class);
        verify(client).stream(captor.capture());
        return captor.getValue();
    }

    private void assertError(List<ChatEventVO> events, int code) {
        assertEquals(List.of(1004, 1002), events.stream().map(ChatEventVO::getEventType).toList());
        assertEquals(code, ((Map<?, ?>) events.get(0).getEventData()).get("code"));
    }

    private ApplicationResult result(String text, String cloudSessionId) {
        var output = ApplicationOutput.builder().text(text).sessionId(cloudSessionId).build();
        var result = new ApplicationResult();
        result.setOutput(output);
        return result;
    }
}

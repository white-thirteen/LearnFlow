package com.tianji.aigc.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.DashScopeProperties;
import com.tianji.aigc.controller.AudioController;
import com.tianji.aigc.service.AudioService;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.CommonException;
import com.tianji.common.exceptions.RequestTimeoutException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AudioServiceTest {

    private DashScopeProperties properties;
    private DashScopeTtsClient client;
    private AudioServiceImpl service;
    private RecordingEmitter emitter;

    @BeforeEach
    void setUp() {
        properties = new DashScopeProperties();
        properties.setKey("test-api-key");
        client = mock(DashScopeTtsClient.class);
        service = spy(new AudioServiceImpl(properties, client));
        emitter = new RecordingEmitter();
        doReturn(emitter).when(service).createEmitter(anyLong());
        UserContext.setUser(2L);
    }

    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    @Test
    void requiresLoginBeforeCallingCloud() {
        UserContext.removeUser();
        assertThrows(UnauthorizedException.class, () -> service.synthesize("你好"));
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\r\n\t"})
    void rejectsBlankInput(String text) {
        assertThrows(BadRequestException.class, () -> service.synthesize(text));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsOverlongInputButAllowsExactLimitAndUnicodeCharacters() throws Exception {
        properties.getTts().setMaxTextLength(2);
        assertThrows(BadRequestException.class, () -> service.synthesize("你好啊"));
        verifyNoInteractions(client);
        when(client.synthesize("你😀", "test-api-key", properties.getTts())).thenReturn(Flux.just(new byte[]{1}));
        service.synthesize("你😀");
        emitter.awaitEnd();
        assertNull(emitter.error);
        assertEquals(1, emitter.frames.size());
    }

    @Test
    void missingKeyFailsAtRequestTime() {
        properties.setKey(null);
        var error = assertThrows(CommonException.class, () -> service.synthesize("你好"));
        assertEquals(500, error.getCode());
        assertTrue(error.getMessage().contains("配置"));
        verifyNoInteractions(client);
    }

    @Test
    void invalidConfigurationDoesNotCallCloud() {
        properties.getTts().setTimeoutMs(0);
        assertThrows(CommonException.class, () -> service.synthesize("你好"));
        properties.getTts().setTimeoutMs(60000);
        properties.getTts().setMaxTextLength(20001);
        assertThrows(CommonException.class, () -> service.synthesize("你好"));
        properties.getTts().setMaxTextLength(2000);
        properties.getTts().setVoice(" ");
        assertThrows(CommonException.class, () -> service.synthesize("你好"));
        verifyNoInteractions(client);
    }

    @Test
    void sendsFirstFrameBeforeUpstreamCompletesAndPreservesTextAndOrder() throws Exception {
        String text = " 你好，Java!\n第二行。 ";
        Sinks.Many<byte[]> source = Sinks.many().unicast().onBackpressureBuffer();
        when(client.synthesize(text, "test-api-key", properties.getTts())).thenReturn(source.asFlux());
        assertSame(emitter, service.synthesize(text));
        UserContext.removeUser(); // 后续的异步发送不再依赖 ThreadLocal。
        source.tryEmitNext(new byte[]{1, 2}).orThrow();
        assertTrue(emitter.firstFrame.await(5, TimeUnit.SECONDS));
        assertEquals(1, emitter.frames.size());
        assertEquals(1, emitter.ended.getCount());
        source.tryEmitNext(new byte[]{3, 4}).orThrow();
        source.tryEmitComplete().orThrow();
        emitter.awaitEnd();
        assertArrayEquals(new byte[]{1, 2}, emitter.frames.get(0));
        assertArrayEquals(new byte[]{3, 4}, emitter.frames.get(1));
        assertEquals(1, emitter.completed.get());
        assertTrue(emitter.writerThreads.stream().allMatch(name -> name.startsWith("boundedElastic-")));
        verify(client).synthesize(text, "test-api-key", properties.getTts());
    }

    @Test
    void upstreamErrorKeepsCauseAndDoesNotExposeDetailsOrRetry() throws Exception {
        var upstream = new IllegalStateException("private upstream details");
        when(client.synthesize(anyString(), anyString(), any())).thenReturn(Flux.error(upstream));
        service.synthesize("你好");
        emitter.awaitEnd();
        CommonException error = assertInstanceOf(CommonException.class, emitter.error);
        assertEquals(500, error.getCode());
        assertSame(upstream, error.getCause());
        assertFalse(error.getMessage().contains("private"));
        assertEquals(0, emitter.completed.get());
        verify(client).synthesize("你好", "test-api-key", properties.getTts());
    }

    @Test
    void synchronousClientFailureIsForwardedToEmitter() throws Exception {
        when(client.synthesize(anyString(), anyString(), any())).thenThrow(new IllegalStateException("setup failed"));
        service.synthesize("你好");
        emitter.awaitEnd();
        assertInstanceOf(CommonException.class, emitter.error);
    }

    @Test
    void totalTimeoutCancelsEvenWhileFramesContinueArriving() throws Exception {
        properties.getTts().setTimeoutMs(250);
        CountDownLatch canceled = new CountDownLatch(1);
        when(client.synthesize(anyString(), anyString(), any())).thenReturn(
                Flux.interval(Duration.ofMillis(10)).map(index -> new byte[]{1}).doOnCancel(canceled::countDown));
        service.synthesize("你好");
        emitter.awaitEnd();
        assertEquals(504, assertInstanceOf(RequestTimeoutException.class, emitter.error).getCode());
        assertTrue(canceled.await(5, TimeUnit.SECONDS));
    }

    @Test
    void emptyAndZeroLengthAudioFailInsteadOfCompletingSuccessfully() throws Exception {
        when(client.synthesize(anyString(), anyString(), any())).thenReturn(Flux.just(new byte[0]));
        service.synthesize("你好");
        emitter.awaitEnd();
        assertEquals(500, assertInstanceOf(CommonException.class, emitter.error).getCode());
        assertTrue(emitter.frames.isEmpty());
        assertEquals(0, emitter.completed.get());
    }

    @Test
    void sendIOExceptionCancelsSourceWithoutCallingCompleteWithError() throws Exception {
        CountDownLatch canceled = new CountDownLatch(1);
        emitter.sendError = new IOException("client disconnected");
        when(client.synthesize(anyString(), anyString(), any())).thenReturn(
                Flux.concat(Flux.just(new byte[]{1}), Flux.never()).doOnCancel(canceled::countDown));
        service.synthesize("你好");
        assertTrue(canceled.await(5, TimeUnit.SECONDS));
        assertNull(emitter.error);
        assertEquals(0, emitter.completed.get());
    }

    @Test
    void nonIoSendFailureIsForwardedToEmitter() throws Exception {
        emitter.sendRuntimeError = new IllegalStateException("converter failed");
        when(client.synthesize(anyString(), anyString(), any())).thenReturn(Flux.just(new byte[]{1}));
        service.synthesize("你好");
        emitter.awaitEnd();
        assertEquals(500, assertInstanceOf(CommonException.class, emitter.error).getCode());
    }

    @Test
    void containerCompletionErrorAndTimeoutCancelUpstream() throws Exception {
        for (int signal = 0; signal < 3; signal++) {
            RecordingEmitter current = new RecordingEmitter();
            doReturn(current).when(service).createEmitter(anyLong());
            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch canceled = new CountDownLatch(1);
            when(client.synthesize(anyString(), anyString(), any())).thenReturn(Flux.<byte[]>never()
                    .doOnSubscribe(subscription -> subscribed.countDown()).doOnCancel(canceled::countDown));
            service.synthesize("你好");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            if (signal == 0) current.completionCallback.run();
            if (signal == 1) current.errorCallback.accept(new IOException("disconnect"));
            if (signal == 2) current.timeoutCallback.run();
            assertTrue(canceled.await(5, TimeUnit.SECONDS));
            if (signal == 2) {
                current.awaitEnd();
                assertInstanceOf(RequestTimeoutException.class, current.error);
            } else {
                assertNull(current.error);
            }
        }
    }

    @Test
    void terminationBeforeSubscriptionAssignmentDoesNotLeakTheSubscription() throws Exception {
        CountDownLatch canceled = new CountDownLatch(1);
        // 客户端方法在 subscribeOn 线程运行，可能在 subscribe 返回/赋值前触发超时。
        when(client.synthesize(anyString(), anyString(), any())).thenAnswer(call -> {
            emitter.timeoutCallback.run();
            return Flux.<byte[]>never().doOnCancel(canceled::countDown);
        });
        service.synthesize("你好");
        emitter.awaitEnd();
        assertTrue(canceled.await(5, TimeUnit.SECONDS));
        assertInstanceOf(RequestTimeoutException.class, emitter.error);
        assertTrue(emitter.frames.isEmpty());
    }

    @Test
    void ttsBindsConfigurationAndStartsWithoutKeyInEveryChatMode() {
        for (String mode : new String[]{"ROUTE", "ENHANCE", "APP"}) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                    .withUserConfiguration(DashScopeProperties.class, AudioServiceImpl.class, AudioController.class)
                    .withBean(DashScopeTtsClient.class, () -> client)
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withPropertyValues("tj.ai.chat-type=" + mode,
                            "tj.ai.dashscope.tts.voice=test-voice",
                            "tj.ai.dashscope.tts.connect-timeout-ms=1234")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertNotNull(context.getBean(AudioController.class));
                        assertNotNull(context.getBean(AudioService.class));
                        var bound = context.getBean(DashScopeProperties.class);
                        assertNull(bound.getKey());
                        assertEquals("test-voice", bound.getTts().getVoice());
                        assertEquals(1234, bound.getTts().getConnectTimeoutMs());
                    });
        }
    }

    private static class RecordingEmitter extends ResponseBodyEmitter {
        final List<byte[]> frames = new CopyOnWriteArrayList<>();
        final List<String> writerThreads = new CopyOnWriteArrayList<>();
        final CountDownLatch firstFrame = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        final AtomicInteger completed = new AtomicInteger();
        volatile Throwable error;
        volatile IOException sendError;
        volatile RuntimeException sendRuntimeError;
        Runnable completionCallback;
        Runnable timeoutCallback;
        Consumer<Throwable> errorCallback;

        @Override
        public void send(Object object, MediaType mediaType) throws IOException {
            assertEquals("audio/mp3", mediaType.toString());
            if (sendError != null) throw sendError;
            if (sendRuntimeError != null) throw sendRuntimeError;
            frames.add((byte[]) object);
            writerThreads.add(Thread.currentThread().getName());
            firstFrame.countDown();
        }

        @Override
        public void complete() {
            completed.incrementAndGet();
            ended.countDown();
        }

        @Override
        public void completeWithError(Throwable error) {
            this.error = error;
            ended.countDown();
        }

        @Override
        public void onCompletion(Runnable callback) { completionCallback = callback; }

        @Override
        public void onTimeout(Runnable callback) { timeoutCallback = callback; }

        @Override
        public void onError(Consumer<Throwable> callback) { errorCallback = callback; }

        void awaitEnd() throws InterruptedException {
            assertTrue(ended.await(5, TimeUnit.SECONDS), "emitter did not terminate");
        }
    }
}

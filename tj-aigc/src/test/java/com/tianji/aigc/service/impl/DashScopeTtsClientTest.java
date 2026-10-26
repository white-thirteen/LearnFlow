package com.tianji.aigc.service.impl;

import com.alibaba.dashscope.api.SynchronizeFullDuplexApi;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.tianji.aigc.config.DashScopeProperties;
import io.reactivex.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashScopeTtsClientTest {

    private final DashScopeProperties.Tts tts = new DashScopeProperties.Tts();
    private DashScopeTtsClient client;
    private SpeechSynthesizer synthesizer;
    private SynchronizeFullDuplexApi<SpeechSynthesisParam> connection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = spy(new DashScopeTtsClient());
        synthesizer = mock(SpeechSynthesizer.class);
        connection = mock(SynchronizeFullDuplexApi.class);
        when(synthesizer.getDuplexApi()).thenReturn(connection);
        doReturn(synthesizer).when(client).createSynthesizer(any(), any());
    }

    @Test
    void copiesRemainingBytesIntoSeparateFramesWithoutChangingBufferPositions() throws Exception {
        ByteBuffer heap = ByteBuffer.wrap(new byte[]{99, 1, 2, 99});
        heap.position(1).limit(3);
        ByteBuffer direct = ByteBuffer.allocateDirect(4);
        direct.put(new byte[]{99, 3, 4, 99}).flip();
        direct.position(1).limit(3);
        when(synthesizer.callAsFlowable("你好")).thenReturn(Flowable.just(
                new SpeechSynthesisResult(), frame(ByteBuffer.allocate(0)), frame(heap), frame(direct.asReadOnlyBuffer())));
        var stream = client.synthesize("你好", "test-key", tts);
        verify(client, never()).createSynthesizer(any(), any());
        var frames = stream.collectList().block(Duration.ofSeconds(5));
        assertNotNull(frames);
        assertEquals(2, frames.size());
        assertArrayEquals(new byte[]{1, 2}, frames.get(0));
        assertArrayEquals(new byte[]{3, 4}, frames.get(1));
        assertEquals(1, heap.position());
        assertEquals(1, direct.position());
        var param = ArgumentCaptor.forClass(SpeechSynthesisParam.class);
        verify(client).createSynthesizer(param.capture(), same(tts));
        assertEquals("test-key", param.getValue().getApiKey());
        assertEquals("cosyvoice-v3-plus", param.getValue().getModel());
        assertEquals("longanyang", param.getValue().getVoice());
        assertEquals(SpeechSynthesisAudioFormat.MP3_22050HZ_MONO_256KBPS, param.getValue().getFormat());
        verify(connection).close(1000, "bye");
    }

    @Test
    void upstreamFailurePreservesAlreadyEmittedAudioAndClosesConnection() throws Exception {
        var upstream = new IllegalStateException("upstream failed");
        when(synthesizer.callAsFlowable("你好")).thenReturn(
                Flowable.concat(Flowable.just(frame(ByteBuffer.wrap(new byte[]{1}))), Flowable.error(upstream)));
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        assertSame(upstream, assertThrows(IllegalStateException.class,
                () -> client.synthesize("你好", "test-key", tts).doOnNext(sent::add).blockLast(Duration.ofSeconds(5))));
        assertArrayEquals(new byte[]{1}, sent.get(0));
        verify(synthesizer).callAsFlowable("你好");
        verify(connection).close(1000, "bye");
    }

    @Test
    void synchronousSdkFailureStillClosesConnection() throws Exception {
        when(synthesizer.callAsFlowable("你好")).thenThrow(new IllegalArgumentException("invalid model"));
        assertThrows(IllegalArgumentException.class,
                () -> client.synthesize("你好", "test-key", tts).blockLast(Duration.ofSeconds(5)));
        verify(connection).close(1000, "bye");
    }

    @Test
    void subscriberCancellationClosesSdkAndCancelsItsStream() throws Exception {
        CountDownLatch canceled = new CountDownLatch(1);
        when(synthesizer.callAsFlowable("你好")).thenReturn(Flowable.<SpeechSynthesisResult>never()
                .doOnCancel(canceled::countDown));
        var subscription = client.synthesize("你好", "test-key", tts).subscribe();
        subscription.dispose();
        assertTrue(canceled.await(5, TimeUnit.SECONDS));
        verify(connection).close(1000, "bye");
    }

    @Test
    void emptyStreamIsClosedAndReturnedForServiceValidation() throws Exception {
        when(synthesizer.callAsFlowable("你好")).thenReturn(Flowable.empty());
        assertTrue(client.synthesize("你好", "test-key", tts).collectList().block(Duration.ofSeconds(5)).isEmpty());
        verify(connection).close(1000, "bye");
    }

    @Test
    void closeFailureDoesNotReplaceOriginalError() throws Exception {
        var upstream = new IllegalStateException("upstream failed");
        when(synthesizer.callAsFlowable("你好")).thenReturn(Flowable.error(upstream));
        when(connection.close(1000, "bye")).thenThrow(new IllegalArgumentException("close failed"));
        assertSame(upstream, assertThrows(IllegalStateException.class,
                () -> client.synthesize("你好", "test-key", tts).blockLast(Duration.ofSeconds(5))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentRequestsUseSeparateSynthesizersAndAudioBuffers() throws Exception {
        List<SpeechSynthesizer> synthesizers = new CopyOnWriteArrayList<>();
        CountDownLatch bothStarted = new CountDownLatch(2);
        doAnswer(call -> {
            SpeechSynthesizer separate = mock(SpeechSynthesizer.class);
            SynchronizeFullDuplexApi<SpeechSynthesisParam> separateConnection = mock(SynchronizeFullDuplexApi.class);
            when(separate.getDuplexApi()).thenReturn(separateConnection);
            when(separate.callAsFlowable(anyString())).thenAnswer(request -> Flowable.fromCallable(() -> {
                bothStarted.countDown();
                assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
                return frame(ByteBuffer.wrap(((String) request.getArgument(0)).getBytes(StandardCharsets.UTF_8)));
            }));
            synthesizers.add(separate);
            return separate;
        }).when(client).createSynthesizer(any(), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> client.synthesize("第一段", "key", tts).blockFirst(Duration.ofSeconds(5)));
            var second = executor.submit(() -> client.synthesize("第二段", "key", tts).blockFirst(Duration.ofSeconds(5)));
            assertArrayEquals("第一段".getBytes(StandardCharsets.UTF_8), first.get(5, TimeUnit.SECONDS));
            assertArrayEquals("第二段".getBytes(StandardCharsets.UTF_8), second.get(5, TimeUnit.SECONDS));
            assertEquals(2, synthesizers.size());
            assertNotSame(synthesizers.get(0), synthesizers.get(1));
            for (var separate : synthesizers) {
                verify(separate.getDuplexApi()).close(1000, "bye");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private SpeechSynthesisResult frame(ByteBuffer audio) {
        var result = new SpeechSynthesisResult();
        result.setAudioFrame(audio);
        return result;
    }
}

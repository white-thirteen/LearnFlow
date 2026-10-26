package com.tianji.aigc.service.impl;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.tianji.aigc.config.DashScopeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;
import java.time.Duration;

@Slf4j
@Component
public class DashScopeTtsClient {

    public Flux<byte[]> synthesize(String text, String apiKey, DashScopeProperties.Tts tts) {
        return Flux.using(
                () -> createSynthesizer(SpeechSynthesisParam.builder()
                        .apiKey(apiKey)
                        .model(tts.getModel())
                        .voice(tts.getVoice())
                        .format(SpeechSynthesisAudioFormat.MP3_22050HZ_MONO_256KBPS)
                        .build(), tts),
                synthesizer -> Flux.defer(() -> {
                            try {
                                return Flux.from(synthesizer.callAsFlowable(text));
                            } catch (Exception e) {
                                return Flux.error(e);
                            }
                        })
                        .filter(result -> result.getAudioFrame() != null && result.getAudioFrame().hasRemaining())
                        .map(result -> copyAudio(result.getAudioFrame()))
                        .doOnError(error -> log.error("百炼 TTS 调用失败, requestId: {}",
                                synthesizer.getLastRequestId(), error)),
                this::close,
                true);
    }

    SpeechSynthesizer createSynthesizer(SpeechSynthesisParam param, DashScopeProperties.Tts tts) {
        ConnectionOptions options = ConnectionOptions.builder()
                .connectTimeout(Duration.ofMillis(tts.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(tts.getTimeoutMs()))
                .writeTimeout(Duration.ofMillis(tts.getTimeoutMs()))
                .build();
        return new SpeechSynthesizer(param, null, tts.getBaseUrl(), options);
    }

    private static byte[] copyAudio(ByteBuffer audio) {
        ByteBuffer frame = audio.duplicate();
        byte[] bytes = new byte[frame.remaining()];
        frame.get(bytes);
        return bytes;
    }

    private void close(SpeechSynthesizer synthesizer) {
        try {
            synthesizer.getDuplexApi().close(1000, "bye");
        } catch (Exception e) {
            log.warn("关闭百炼 TTS 连接失败, requestId: {}", synthesizer.getLastRequestId(), e);
        }
    }
}

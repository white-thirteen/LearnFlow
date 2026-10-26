package com.tianji.aigc.service.impl;

import com.tianji.aigc.config.DashScopeProperties;
import com.tianji.aigc.service.AudioService;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.CommonException;
import com.tianji.common.exceptions.RequestTimeoutException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioServiceImpl implements AudioService {

    private final DashScopeProperties properties;
    private final DashScopeTtsClient client;
    private static final MediaType AUDIO_MP3 = MediaType.parseMediaType("audio/mp3");

    @Override
    public ResponseBodyEmitter synthesize(String text) {
        // 必须在请求线程读取身份并完成校验，后续线程没有 UserContext。
        if (UserContext.getUser() == null) {
            throw new UnauthorizedException("请先登录");
        }
        if (!StringUtils.hasText(text)) {
            throw new BadRequestException("合成文本不能为空");
        }
        DashScopeProperties.Tts tts = properties.getTts();
        requireConfiguration(tts);
        if (text.codePointCount(0, text.length()) > tts.getMaxTextLength()) {
            throw new BadRequestException("合成文本不能超过" + tts.getMaxTextLength() + "字符");
        }
        String apiKey = properties.getKey();
        ResponseBodyEmitter emitter = createEmitter(tts.getTimeoutMs());
        Emission emission = new Emission(emitter);
        emitter.onCompletion(emission::cancel);
        emitter.onError(error -> emission.cancel());
        emitter.onTimeout(() -> emission.fail(timeoutError()));

        // SDK 建连和 Servlet 写入均可能阻塞，按序发送且只预取一个音频分片。
        // 用独立截止信号限制总耗时，不因持续收到分片而重置超时。
        AtomicBoolean timedOut = new AtomicBoolean();
        var deadline = Mono.delay(Duration.ofMillis(tts.getTimeoutMs()))
                .doOnNext(tick -> timedOut.set(true));
        Disposable subscription = Flux.defer(() -> client.synthesize(text, apiKey, tts))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(bytes -> bytes.length > 0)
                .switchIfEmpty(Flux.error(new CommonException(500, "语音合成未返回音频，请稍后重试")))
                .publishOn(Schedulers.boundedElastic(), 1)
                .takeUntilOther(deadline)
                // 截止信号先通过 onNext 取消主流，再转成错误；不能直接让 other 抛错。
                .concatWith(Flux.defer(() -> timedOut.get() ? Flux.error(timeoutError()) : Flux.empty()))
                .onErrorMap(error -> error instanceof CommonException ? error
                        : new CommonException(500, "语音合成失败，请稍后重试", error))
                .subscribe(emission::send, emission::fail, emission::complete);
        // 断连/超时可能早于 subscribe 返回；swap 已关闭时会立即取消新订阅。
        emission.subscription.update(subscription);
        return emitter;
    }

    ResponseBodyEmitter createEmitter(long timeoutMs) {
        return new ResponseBodyEmitter(timeoutMs) {
            @Override
            protected void extendResponse(ServerHttpResponse response) {
                // Emitter 的转换器不会把分片响应头写回 Servlet，需在首片前设置。
                response.getHeaders().setContentType(AUDIO_MP3);
                response.getHeaders().setCacheControl("no-store");
            }
        };
    }

    private static RequestTimeoutException timeoutError() {
        return new RequestTimeoutException(504, "语音合成超时，请稍后重试");
    }

    private static class Emission {
        private final ResponseBodyEmitter emitter;
        private final Disposable.Swap subscription = Disposables.swap();
        private final AtomicBoolean terminated = new AtomicBoolean();

        private Emission(ResponseBodyEmitter emitter) {
            this.emitter = emitter;
        }

        private void send(byte[] bytes) {
            if (terminated.get()) {
                return;
            }
            try {
                emitter.send(bytes, AUDIO_MP3);
            } catch (IOException e) {
                // Servlet 容器负责 I/O 错误的异步派发，不重复 completeWithError。
                log.debug("TTS 音频连接写入失败，取消上游", e);
                cancel();
            } catch (Exception e) {
                fail(new CommonException(500, "语音发送失败，请稍后重试", e));
            }
        }

        private void fail(Throwable error) {
            if (terminated.compareAndSet(false, true)) {
                subscription.dispose();
                emitter.completeWithError(error);
            }
        }

        private void complete() {
            if (terminated.compareAndSet(false, true)) {
                subscription.dispose();
                emitter.complete();
            }
        }

        private void cancel() {
            terminated.set(true);
            subscription.dispose();
        }
    }

    private void requireConfiguration(DashScopeProperties.Tts tts) {
        if (!StringUtils.hasText(properties.getKey()) || tts == null
                || !StringUtils.hasText(tts.getModel()) || !StringUtils.hasText(tts.getVoice())
                || !StringUtils.hasText(tts.getBaseUrl())) {
            throw new CommonException(500, "语音合成配置不完整，请检查 tj.ai.dashscope.key 和 tts 配置");
        }
        if (tts.getTimeoutMs() <= 0 || tts.getConnectTimeoutMs() <= 0
                || tts.getMaxTextLength() <= 0 || tts.getMaxTextLength() > 20000) {
            throw new CommonException(500, "语音合成配置无效：超时必须大于0，文本上限须为1至20000字符");
        }
    }
}

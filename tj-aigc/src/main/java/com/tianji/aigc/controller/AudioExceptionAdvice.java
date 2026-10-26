package com.tianji.aigc.controller;

import com.tianji.common.annotations.NoWrapper;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.exceptions.CommonException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 仅适配音频响应的内容类型，业务错误仍复用项目统一异常规则。 */
@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AudioController.class)
public class AudioExceptionAdvice {

    private final CommonExceptionAdvice commonExceptionAdvice;

    @NoWrapper
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(Exception error, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.warn("TTS 音频发送后发生异常，结束传输", error);
            return null;
        }
        Object result;
        if (error instanceof CommonException businessError) {
            result = commonExceptionAdvice.handleBadRequestException(businessError);
        } else if (error instanceof ServletException requestError) {
            result = commonExceptionAdvice.handleServletException(requestError);
        } else {
            result = commonExceptionAdvice.handleRuntimeException(error);
        }
        if (result instanceof ResponseEntity<?> entity) {
            // 直连微服务保留原来的 HTTP 状态和文本响应。
            return ResponseEntity.status(entity.getStatusCode()).headers(entity.getHeaders())
                    .contentType(MediaType.TEXT_PLAIN).body(entity.getBody());
        }
        // 显式指定 JSON，覆盖音频 produces/异步派发前的 Content-Type。
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
    }
}

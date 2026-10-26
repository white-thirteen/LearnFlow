package com.tianji.aigc.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.service.AudioService;
import com.tianji.common.annotations.NoWrapper;
import com.tianji.common.exceptions.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/audio")
public class AudioController {

    private final AudioService audioService;
    private final ObjectMapper objectMapper;

    @NoWrapper
    @PostMapping(value = {"/tts", "/tts-stream"},
            consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE}, produces = "audio/mp3")
    public ResponseBodyEmitter tts(@RequestBody(required = false) String text, HttpServletRequest request) {
        if (StringUtils.hasText(text) && request.getContentType() != null
                && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) {
            text = decodeJsonText(text);
        }
        return audioService.synthesize(text);
    }

    private String decodeJsonText(String body) {
        String value = body.strip();
        if (value.startsWith("\"")) {
            try {
                return objectMapper.readerFor(String.class)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(value);
            } catch (JsonProcessingException e) {
                throw new BadRequestException("TTS JSON 字符串格式错误");
            }
        }
        if (value.startsWith("{") || value.startsWith("[")) {
            throw new BadRequestException("TTS 请求体应为原始文本或 JSON 字符串，不能为对象或数组");
        }
        // 兼容前端请求工具默认设置 application/json、实际仍提交原始文本的情况。
        return body;
    }
}

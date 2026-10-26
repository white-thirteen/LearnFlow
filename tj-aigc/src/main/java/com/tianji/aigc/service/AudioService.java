package com.tianji.aigc.service;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

public interface AudioService {

    /** 将当前登录用户提交的文本合成为 MP3，通过 emitter 逐片发送，不保存聊天记录。 */
    ResponseBodyEmitter synthesize(String text);
}

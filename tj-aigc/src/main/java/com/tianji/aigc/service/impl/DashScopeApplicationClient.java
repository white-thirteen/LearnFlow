package com.tianji.aigc.service.impl;

import com.alibaba.dashscope.app.Application;
import com.alibaba.dashscope.app.ApplicationParam;
import com.alibaba.dashscope.app.ApplicationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@ConditionalOnProperty(prefix = "tj.ai", name = "chat-type", havingValue = "APP")
public class DashScopeApplicationClient {

    public Flux<ApplicationResult> stream(ApplicationParam param) {
        return Flux.defer(() -> {
            try {
                // SDK 的 Application 包含可变请求选项，每次调用创建独立实例。
                return Flux.from(new Application().streamCall(param));
            } catch (Exception e) {
                return Flux.error(e);
            }
        });
    }
}

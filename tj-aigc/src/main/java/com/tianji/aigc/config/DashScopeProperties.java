package com.tianji.aigc.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.ArrayList;

@Data
@Configuration
@ConfigurationProperties(prefix = "tj.ai.dashscope")
public class DashScopeProperties {

    private String key;
    private AppAgent appAgent = new AppAgent();
    private Tts tts = new Tts();

    @Data
    public static class Tts {
        private String model = "cosyvoice-v3-plus";
        private String voice = "longanyang";
        private String baseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
        private long timeoutMs = 60000;
        private long connectTimeoutMs = 10000;
        private int maxTextLength = 2000;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AppAgent {
        private String id;
        @Builder.Default
        private List<String> tools = new ArrayList<>();
    }

}

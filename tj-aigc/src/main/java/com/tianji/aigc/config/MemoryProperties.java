package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆相关配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "tj.ai.memory")
public class MemoryProperties {

    /**
     * 会话记忆的存储方式：REDIS（默认）或 MYSQL
     */
    private MemoryType type = MemoryType.REDIS;

    /**
     * 会话记忆窗口中保留的最大消息数
     */
    private Integer max = 100;

    public enum MemoryType {
        REDIS, MYSQL
    }
}

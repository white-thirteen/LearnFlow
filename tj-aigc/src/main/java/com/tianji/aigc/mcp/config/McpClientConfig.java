package com.tianji.aigc.mcp.config;

import com.tianji.aigc.mcp.tool.McpToolRegistry;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpToolProperties.class)
public class McpClientConfig {

    /**
     * 连接及远程 Provider 由 Starter 创建；这里只负责向 Agent 提供按需选择工具的入口。
     * 使用延迟获取，关闭 MCP 时不要求容器中存在远程 Provider。
     */
    @Bean
    public McpToolRegistry mcpToolRegistry(
            McpToolProperties properties,
            ObjectProvider<SyncMcpToolCallbackProvider> remoteProvider,
            @Value("${spring.ai.mcp.client.enabled:false}") boolean enabled) {
        return new McpToolRegistry(properties, enabled, () -> {
            var provider = remoteProvider.getIfAvailable();
            return provider == null ? new ToolCallback[0] : provider.getToolCallbacks();
        });
    }
}

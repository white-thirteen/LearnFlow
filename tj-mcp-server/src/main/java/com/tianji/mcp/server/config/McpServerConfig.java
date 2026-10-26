package com.tianji.mcp.server.config;

import com.tianji.mcp.server.tool.CourseQueryTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpServerConfig {

    /**
     * 把本地 @Tool 方法发布为 MCP 工具；Starter 负责 tools/list 和 tools/call 协议处理。
     */
    @Bean
    public ToolCallbackProvider courseQueryToolProvider(CourseQueryTools courseQueryTools) {
        return MethodToolCallbackProvider.builder().toolObjects(courseQueryTools).build();
    }
}

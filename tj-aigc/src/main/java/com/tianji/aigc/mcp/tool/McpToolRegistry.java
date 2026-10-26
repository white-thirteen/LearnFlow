package com.tianji.aigc.mcp.tool;

import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.mcp.config.McpToolProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
@RequiredArgsConstructor
public class McpToolRegistry {

    private final McpToolProperties properties;
    private final boolean enabled;
    private final Supplier<ToolCallback[]> remoteCallbacks;

    public ToolCallback[] getToolCallbacks(AgentTypeEnum agentType) {
        var allowed = properties.getAgentTools().getOrDefault(agentType, List.of());
        if (!enabled || allowed.isEmpty()) {
            return new ToolCallback[0];
        }
        var available = new LinkedHashMap<String, ToolCallback>();
        for (var callback : remoteCallbacks.get()) {
            String name = callback.getToolDefinition().name();
            if (available.putIfAbsent(name, callback) != null) {
                throw new IllegalStateException("MCP 工具名称重复: " + name);
            }
        }
        var selected = allowed.stream().distinct().map(name -> {
            var callback = available.get(name);
            if (callback == null) {
                throw new IllegalStateException("Agent " + agentType + " 配置的 MCP 工具不存在: " + name
                        + "; 已发现工具: " + available.keySet());
            }
            return callback;
        }).toArray(ToolCallback[]::new);
        log.debug("Agent {} mounts MCP tools: {}", agentType, allowed);
        return selected;
    }
}

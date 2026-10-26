package com.tianji.aigc.mcp.tool;

import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.mcp.config.McpToolProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolRegistryTest {

    @Test
    void disabledAndUnconfiguredAgentsDoNotDiscoverRemoteTools() {
        var properties = new McpToolProperties();
        properties.getAgentTools().put(AgentTypeEnum.CONSULT, List.of("course_search"));
        var calls = new AtomicInteger();
        var disabled = new McpToolRegistry(properties, false, () -> {
            calls.incrementAndGet();
            return new ToolCallback[0];
        });
        assertEquals(0, disabled.getToolCallbacks(AgentTypeEnum.CONSULT).length);
        var enabled = new McpToolRegistry(properties, true, () -> {
            calls.incrementAndGet();
            return new ToolCallback[0];
        });
        assertEquals(0, enabled.getToolCallbacks(AgentTypeEnum.ROUTE).length);
        assertEquals(0, calls.get());
    }

    @Test
    void selectsOnlyAllowedToolsAndFailsForMisconfiguredNames() {
        var properties = new McpToolProperties();
        properties.getAgentTools().put(AgentTypeEnum.CONSULT, List.of("course_search"));
        var allowed = callback("course_search");
        var registry = new McpToolRegistry(properties, true,
                () -> new ToolCallback[]{allowed, callback("other_tool")});
        assertArrayEquals(new ToolCallback[]{allowed}, registry.getToolCallbacks(AgentTypeEnum.CONSULT));
        properties.getAgentTools().put(AgentTypeEnum.CONSULT, List.of("missing_tool"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> registry.getToolCallbacks(AgentTypeEnum.CONSULT)).getMessage().contains("missing_tool"));
    }

    private static ToolCallback callback(String name) {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description("test").inputSchema("{\"type\":\"object\"}").build());
        return callback;
    }
}

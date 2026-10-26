package com.tianji.aigc.mcp.config;

import com.tianji.aigc.enums.AgentTypeEnum;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "tj.ai.mcp")
public class McpToolProperties {

    /**
     * 按 Agent 配置允许使用的 MCP 回调名称；未配置的 Agent 不获得远程工具。
     */
    private Map<AgentTypeEnum, List<String>> agentTools = new EnumMap<>(AgentTypeEnum.class);
}

package com.tianji.aigc.agent;

import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.enums.AgentTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 路由智能体
 */
@Component
@RequiredArgsConstructor
public class RouteAgent extends AbstractAgent {

    private final SystemPromptConfig systemPromptConfig;

    @Override
    public String systemMessage() {
        return this.systemPromptConfig.getRouteAgentSystemMessage().get();
    }

    @Override
    public AgentTypeEnum getAgentType() {
        return AgentTypeEnum.ROUTE;
    }

    /**
     * 路由智能体不写会话记忆，避免把分类结果（RECOMMEND/BUY...）和用户问题写进历史；
     * 需要上下文时由 AbstractAgent 以只读方式注入最近几条历史。
     */
    @Override
    protected boolean memoryEnabled() {
        return false;
    }

}

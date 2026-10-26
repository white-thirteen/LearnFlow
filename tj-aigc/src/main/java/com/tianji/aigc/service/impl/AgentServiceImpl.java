package com.tianji.aigc.service.impl;

import com.tianji.aigc.agent.AbstractAgent;
import com.tianji.aigc.agent.Agent;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.ai", name = "chat-type", havingValue = "ROUTE")
public class AgentServiceImpl implements ChatService {

    /**
     * 所有智能体，由Spring自动注入，避免使用SpringUtil静态工具（需要额外注册，容易踩空指针）
     */
    private final List<Agent> agents;

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        // 先通过路由智能体，分析用户的意图，再执行后面的逻辑
        var result = this.findAgentByType(AgentTypeEnum.ROUTE).process(question, sessionId);
        var agentTypeEnum = AgentTypeEnum.agentNameOf(result);

        var agent = this.findAgentByType(agentTypeEnum);
        if (agent == null) {
            // 找不到对应的智能体（例如问候语、无关问题），直接把路由智能体的回答返回
            var chatEventVO = ChatEventVO.builder()
                    .eventType(ChatEventTypeEnum.DATA.getValue())
                    .eventData(result)
                    .build();
            return Flux.just(chatEventVO, AbstractAgent.STOP_EVENT);
        }
        // 执行智能体的逻辑
        return agent.processStream(question, sessionId);
    }

    /**
     * 根据代理类型查找对应的Agent实例
     *
     * @param agentTypeEnum 要查找的代理类型
     * @return 与给定类型匹配的Agent实例，如果未找到或类型为null则返回null
     */
    private Agent findAgentByType(AgentTypeEnum agentTypeEnum) {
        if (agentTypeEnum == null) {
            return null;
        }
        return this.agents.stream()
                .filter(agent -> agent.getAgentType() == agentTypeEnum)
                .findFirst()
                .orElse(null);
    }

    /**
     * 停止生成
     *
     * @param sessionId 会话ID
     */
    @Override
    public void stop(String sessionId) {
        this.findAgentByType(AgentTypeEnum.ROUTE).stop(sessionId);
    }
}

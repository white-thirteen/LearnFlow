package com.tianji.aigc.agent;

import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.tools.CourseTools;
import com.tianji.aigc.tools.TradeTools;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 课程购买智能体
 */
@Component
@RequiredArgsConstructor
public class BuyAgent extends AbstractAgent {

    private final SystemPromptConfig systemPromptConfig;
    private final CourseTools courseTools;
    private final TradeTools tradeTools;

    @Override
    public String systemMessage() {
        return this.systemPromptConfig.getBuyAgentSystemMessage().get();
    }

    @Override
    public AgentTypeEnum getAgentType() {
        return AgentTypeEnum.BUY;
    }

    @Override
    public Object[] tools() {
        // 查询课程详情 + 预下单
        return new Object[]{this.courseTools, this.tradeTools};
    }

    @Override
    public Map<String, Object> toolContext(String sessionId, String requestId) {
        return Map.of(
                Constant.USER_ID, UserContext.getUser(), // 设置用户id参数，工具线程中需要用它还原登录态
                Constant.REQUEST_ID, requestId // 设置请求id参数
        );
    }
}

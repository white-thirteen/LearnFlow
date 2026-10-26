package com.tianji.aigc.tools;

import cn.hutool.core.convert.Convert;
import com.tianji.aigc.constants.Constant;
import com.tianji.api.client.trade.TradeClient;
import com.tianji.api.dto.trade.OrderConfirmVO;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 交易相关工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeTools {

    private final TradeClient tradeClient;

    @Tool(description = Constant.Tools.PRE_PLACE_ORDER)
    public OrderConfirmVO prePlaceOrder(@ToolParam(description = Constant.ToolParams.COURSE_IDS) List<Long> courseIds,
                                        ToolContext toolContext) {
        // 工具在独立线程执行，UserContext 是 ThreadLocal，那个线程里取不到当前用户。
        // 这里从 toolContext 取出用户id补进去，FeignRelayUserInterceptor 才会带上 user-info 头，
        // 否则下游 trade-service 会按未登录处理。
        var userId = Convert.toLong(toolContext.getContext().get(Constant.USER_ID));
        UserContext.setUser(userId);
        try {
            return this.tradeClient.prePlaceOrder(courseIds);
        } finally {
            UserContext.removeUser();
        }
    }
}

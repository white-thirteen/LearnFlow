package com.tianji.aigc.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史会话列表项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionHistoryVO {

    /**
     * 会话id
     */
    private String sessionId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 更新时间，格式：yyyy-MM-dd HH:mm:ss
     */
    private String updateTime;
}

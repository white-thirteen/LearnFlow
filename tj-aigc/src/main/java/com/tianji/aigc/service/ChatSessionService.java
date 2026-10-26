package com.tianji.aigc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.vo.MessageVO;
import com.tianji.aigc.vo.SessionHistoryVO;
import com.tianji.aigc.vo.SessionVO;

import java.util.List;
import java.util.Map;

public interface ChatSessionService extends IService<ChatSession> {

    /**
     * 创建会话session
     *
     * @param num 热门问题的数量
     * @return 会话信息
     */
    SessionVO createSession(Integer num);

    /**
     * 获取热门会话
     *
     * @return 热门会话列表
     */
    List<SessionVO.Example> hotExamples(Integer num);

    /**
     * 查询会话的聊天记录
     *
     * @param sessionId 会话id
     * @return 聊天记录
     */
    List<MessageVO> queryBySessionId(String sessionId);

    /**
     * 查询历史会话，按更新时间倒序，最多30条，并按时间分组
     *
     * @return key为时间分组（当天/最近30天/最近1年/1年以上），value为该分组下的会话
     */
    Map<String, List<SessionHistoryVO>> queryHistory();

    /**
     * 更新会话标题（异步执行）
     *
     * @param sessionId 会话id
     * @param title     新标题
     * @param userId    当前用户id（异步线程中取不到UserContext，需要显式传入）
     */
    void updateTitle(String sessionId, String title, Long userId);

    /**
     * 会话活动后刷新：更新会话的更新时间，首次提问时用问题作为标题（异步执行）
     *
     * @param sessionId 会话id
     * @param question  用户本次的问题
     * @param userId    当前用户id（异步线程中取不到UserContext，需要显式传入）
     */
    void updateSession(String sessionId, String question, Long userId);

    /**
     * 删除会话，同时清理会话记忆
     *
     * @param sessionId 会话id
     */
    void deleteSession(String sessionId);
}

package com.tianji.aigc.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.aigc.config.SessionProperties;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.enums.MessageTypeEnum;
import com.tianji.aigc.mapper.ChatSessionMapper;
import com.tianji.aigc.memory.MyAssistantMessage;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.vo.MessageVO;
import com.tianji.aigc.vo.SessionHistoryVO;
import com.tianji.aigc.vo.SessionVO;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatSessionService {

    /**
     * 历史会话按时间分组的 key
     */
    private static final String GROUP_TODAY = "当天";
    private static final String GROUP_LAST_30_DAYS = "最近30天";
    private static final String GROUP_LAST_YEAR = "最近1年";
    private static final String GROUP_OLDER = "1年以上";

    private static final DateTimeFormatter HISTORY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 历史会话最多返回的条数
     */
    private static final int HISTORY_LIMIT = 30;

    private final SessionProperties sessionProperties;
    private final ChatMemory chatMemory;

    @Override
    public SessionVO createSession(Integer num) {
        var sessionVO = BeanUtil.toBean(sessionProperties, SessionVO.class);
        // 随机获取examples
        sessionVO.setExamples(RandomUtil.randomEleList(sessionProperties.getExamples(), num));

        // 随机生成sessionId
        sessionVO.setSessionId(IdUtil.fastSimpleUUID());

        // 构建持久化对象，并持久化
        var chatSession = ChatSession.builder()
                .sessionId(sessionVO.getSessionId())
                .userId(UserContext.getUser())
                .build();
        super.save(chatSession);

        return sessionVO;
    }

    @Override
    public List<SessionVO.Example> hotExamples(Integer num) {
        return  RandomUtil.randomEleList(sessionProperties.getExamples(), num);
    }

    @Override
    public List<MessageVO> queryBySessionId(String sessionId) {
        // 根据会话ID获取对话ID
        String conversationId = ChatService.getConversationId(sessionId);
        // 从Redis中获取历史消息
        List<Message> messageList = this.chatMemory.get(conversationId);
        // 过滤并转换消息列表
        return StreamUtil.of(messageList)
                // 过滤掉非用户消息和助手消息
                .filter(message -> message.getMessageType() == MessageType.ASSISTANT || message.getMessageType() == MessageType.USER)
                // 转换为MessageVO对象
                .map(message -> {
                    if (message instanceof MyAssistantMessage) {
                        return MessageVO.builder()
                                .content(message.getText())
                                .type(MessageTypeEnum.valueOf(message.getMessageType().name()))
                                .params(((MyAssistantMessage) message).getParams())
                                .build();
                    }
                    return MessageVO.builder()
                            .content(message.getText())
                            .type(MessageTypeEnum.valueOf(message.getMessageType().name()))
                            .build();
                })
                .toList();
    }

    @Override
    public Map<String, List<SessionHistoryVO>> queryHistory() {
        Long userId = UserContext.getUser();
        // 查询当前用户最近的会话，按更新时间倒序，最多30条
        List<ChatSession> sessions = this.lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getUpdateTime)
                .last("LIMIT " + HISTORY_LIMIT)
                .list();

        // 按时间分组，固定4个分组，保证前端取值时不会出现key缺失
        Map<String, List<SessionHistoryVO>> result = new LinkedHashMap<>(4);
        result.put(GROUP_TODAY, new ArrayList<>());
        result.put(GROUP_LAST_30_DAYS, new ArrayList<>());
        result.put(GROUP_LAST_YEAR, new ArrayList<>());
        result.put(GROUP_OLDER, new ArrayList<>());

        LocalDateTime now = LocalDateTime.now();
        for (ChatSession session : sessions) {
            LocalDateTime updateTime = session.getUpdateTime();
            SessionHistoryVO historyVO = SessionHistoryVO.builder()
                    .sessionId(session.getSessionId())
                    .title(session.getTitle() == null ? "新会话" : session.getTitle())
                    .updateTime(updateTime == null ? null : updateTime.format(HISTORY_TIME_FORMATTER))
                    .build();
            result.get(groupOf(updateTime, now)).add(historyVO);
        }
        return result;
    }

    /**
     * 计算会话所属的时间分组
     */
    private String groupOf(LocalDateTime updateTime, LocalDateTime now) {
        if (updateTime == null) {
            return GROUP_OLDER;
        }
        if (!updateTime.toLocalDate().isBefore(now.toLocalDate())) {
            return GROUP_TODAY;
        }
        if (!updateTime.isBefore(now.minusDays(30))) {
            return GROUP_LAST_30_DAYS;
        }
        if (!updateTime.isBefore(now.minusYears(1))) {
            return GROUP_LAST_YEAR;
        }
        return GROUP_OLDER;
    }

    @Override
    @Async
    public void updateTitle(String sessionId, String title, Long userId) {
        // @Async 方法运行在独立线程，UserContext 是 ThreadLocal，取不到用户，所以由调用方传入 userId
        try {
            boolean updated = this.lambdaUpdate()
                    .set(ChatSession::getTitle, title)
                    .eq(ChatSession::getSessionId, sessionId)
                    .eq(ChatSession::getUserId, userId)
                    .update();
            log.info("异步更新会话标题完成, sessionId: {}, title: {}, 是否更新: {}", sessionId, title, updated);
        } catch (Exception e) {
            log.error("异步更新会话标题失败, sessionId: {}, title: {}", sessionId, title, e);
        }
    }

    @Override
    @Async
    public void updateSession(String sessionId, String question, Long userId) {
        // 异步执行，不阻塞对话；失败只记录日志
        try {
            // 1.查询会话，顺带做用户校验
            ChatSession session = this.lambdaQuery()
                    .eq(ChatSession::getSessionId, sessionId)
                    .eq(ChatSession::getUserId, userId)
                    .one();
            if (session == null) {
                log.warn("刷新会话失败，会话不存在, sessionId: {}, userId: {}", sessionId, userId);
                return;
            }
            // 2.刷新更新时间；首次提问（标题为空）时，用问题作为标题
            var update = this.lambdaUpdate()
                    .eq(ChatSession::getId, session.getId())
                    .set(ChatSession::getUpdateTime, LocalDateTime.now());
            if (StrUtil.isBlank(session.getTitle()) && StrUtil.isNotBlank(question)) {
                update.set(ChatSession::getTitle, StrUtil.subPre(question, 100));
            }
            boolean updated = update.update();
            log.debug("刷新会话完成, sessionId: {}, 是否更新: {}", sessionId, updated);
        } catch (Exception e) {
            log.error("刷新会话失败, sessionId: {}", sessionId, e);
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        Long userId = UserContext.getUser();
        boolean removed = this.remove(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, userId));
        if (removed) {
            // 同步清理会话记忆，避免残留
            this.chatMemory.clear(ChatService.getConversationId(sessionId));
        }
        log.info("删除会话, sessionId: {}, 是否删除: {}", sessionId, removed);
    }

}

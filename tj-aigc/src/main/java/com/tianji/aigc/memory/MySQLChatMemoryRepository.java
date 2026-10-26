package com.tianji.aigc.memory;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.tianji.aigc.entity.ChatMessage;
import com.tianji.aigc.mapper.ChatMessageMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MySQL 实现的 ChatMemoryRepository
 */
public class MySQLChatMemoryRepository implements ChatMemoryRepository {

    private final ChatMessageMapper chatMessageMapper;

    public MySQLChatMemoryRepository(ChatMessageMapper chatMessageMapper) {
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public List<String> findConversationIds() {
        return this.chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .select(ChatMessage::getConversationId)
                        .groupBy(ChatMessage::getConversationId))
                .stream()
                .map(ChatMessage::getConversationId)
                .toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        var chatMessages = this.chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getSeq));
        // 按 seq 顺序反序列化成 Message
        return CollStreamUtil.toList(chatMessages, chatMessage -> MessageUtil.toMessage(chatMessage.getContent()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.notEmpty(messages, "消息列表不能为空");
        // 与 Redis 实现保持一致：saveAll 传入的是全量消息，先删除旧数据再全量写入
        this.chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));

        var seq = 0;
        var chatMessages = new ArrayList<ChatMessage>(messages.size());
        for (Message message : messages) {
            chatMessages.add(ChatMessage.builder()
                    .conversationId(conversationId)
                    .messageType(message.getMessageType().name())
                    .content(MessageUtil.toJson(message))
                    .seq(seq++)
                    .build());
        }
        // 批量插入，避免逐条 insert 的多次网络往返
        Db.saveBatch(chatMessages);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        this.chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));
    }
}

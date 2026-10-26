package com.tianji.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 会话消息，用于 MySQL 方式存储会话记忆
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_message")
public class ChatMessage implements Serializable {

    /**
     * 数据id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话id，格式：用户id_会话id
     */
    private String conversationId;

    /**
     * 消息类型：USER、ASSISTANT、SYSTEM、TOOL
     */
    private String messageType;

    /**
     * 消息内容，保存 MessageUtil 序列化后的 JSON
     */
    private String content;

    /**
     * 消息在会话中的顺序，从 0 开始
     */
    private Integer seq;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 创建人
     */
    private Long creater;

    /**
     * 更新人
     */
    private Long updater;
}

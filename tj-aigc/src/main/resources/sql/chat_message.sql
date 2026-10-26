-- AI 会话消息表：用于 MySQL 方式的会话记忆存储
-- 使用方式：tj.ai.memory.type=mysql 时，ChatMemory 会读写这张表
CREATE TABLE IF NOT EXISTS `chat_message`
(
    `id`              bigint       NOT NULL COMMENT '主键',
    `conversation_id` varchar(64)  NOT NULL COMMENT '会话id，格式：用户id_会话id',
    `message_type`    varchar(20)  NOT NULL COMMENT '消息类型：USER/ASSISTANT/SYSTEM/TOOL',
    `content`         mediumtext   NOT NULL COMMENT '消息内容，MessageUtil 序列化后的 JSON',
    `seq`             int          NOT NULL COMMENT '消息在会话中的顺序，从0开始',
    `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `creater`         bigint                DEFAULT NULL COMMENT '创建人',
    `updater`         bigint                DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `conversation_id_index` (`conversation_id`, `seq`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT ='AI会话消息';

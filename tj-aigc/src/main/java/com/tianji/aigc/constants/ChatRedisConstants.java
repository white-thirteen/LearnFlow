package com.tianji.aigc.constants;

/**
 * 聊天相关 Redis key 常量
 */
public class ChatRedisConstants {

    /**
     * 对话生成状态标识前缀，完整 key 为：前缀 + sessionId
     * <p>key 存在表示该会话仍在生成中；删除即表示要求中断本次生成</p>
     */
    public static final String CHAT_GENERATE_STATUS_KEY_PREFIX = "tj:aigc:chat:generating:";

    /** APP 模式使用用户维度的键，和其他聊天模式的生成标识隔离。 */
    public static final String APP_GENERATE_STATUS_KEY_PREFIX = "tj:aigc:app:generating:";

    /** 完整 key：前缀 + appId + ':' + 用户id_本地会话id。 */
    public static final String APP_SESSION_KEY_PREFIX = "tj:aigc:app:session:";

    private ChatRedisConstants() {
    }
}

package com.tianji.aigc.memory;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import org.springframework.ai.chat.messages.*;

/**
 * 消息转换工具类，提供消息对象与JSON字符串之间的转换功能，主要用于Redis存储格式转换
 */
public class MessageUtil {

    /**
     * 将Message对象转换为Redis存储格式的JSON字符串
     *
     * @param message 需要转换的原始消息对象
     * @return 符合Redis存储规范的JSON字符串
     */
    public static String toJson(Message message) {
        var myMessage = BeanUtil.toBean(message, MyMessage.class);
        // 设置消息内容
        myMessage.setTextContent(message.getText());
        if (message instanceof AssistantMessage assistantMessage) {
            myMessage.setToolCalls(assistantMessage.getToolCalls());
            // 填充消息的额外参数（例如课程卡片）
            fillParams(myMessage, assistantMessage);
        }

        if (message instanceof ToolResponseMessage toolResponseMessage) {
            myMessage.setToolResponses(toolResponseMessage.getResponses());
        }

        return JSONUtil.toJsonStr(myMessage);
    }

    /**
     * 填充消息的额外参数（例如课程卡片数据）
     * <p>实时对话保存时：通过 messageId 找到 requestId，再通过 requestId 找到工具调用产生的参数；</p>
     * <p>历史消息重写时：MyAssistantMessage 中已经带有参数，直接复用。</p>
     *
     * @param myMessage          待持久化的消息
     * @param assistantMessage   原始助手消息
     */
    private static void fillParams(MyMessage myMessage, AssistantMessage assistantMessage) {
        // 1.历史消息（MyAssistantMessage）反序列化后已经带有参数，直接保留
        if (ObjectUtil.isNotEmpty(myMessage.getParams())) {
            return;
        }
        // 2.实时对话结束时，通过 messageId -> requestId -> params 找到参数
        var messageId = Convert.toStr(assistantMessage.getMetadata().get(Constant.ID));
        if (StrUtil.isBlank(messageId)) {
            // 被中断保存的消息没有消息id，直接跳过
            return;
        }
        var requestId = Convert.toStr(ToolResultHolder.get(messageId, Constant.REQUEST_ID));
        var params = ToolResultHolder.get(requestId);
        if (ObjectUtil.isNotEmpty(params)) {
            myMessage.setParams(params);
        }
        // 用完即删，避免内存中持续堆积
        ToolResultHolder.remove(messageId);
    }


    /**
     * 将Redis存储的JSON字符串反序列化为对应的Message对象
     *
     * @param json Redis存储的JSON格式消息数据
     * @return 对应类型的Message对象
     * @throws RuntimeException 当无法识别的消息类型时抛出异常
     */
    public static Message toMessage(String json) {
        var myMessage = JSONUtil.toBean(json, MyMessage.class);
        var messageType = MessageType.valueOf(myMessage.getMessageType());
        switch (messageType) {
            case SYSTEM -> {
                return new SystemMessage(myMessage.getTextContent());
            }
            case USER -> {
                return UserMessage.builder()
                        .text(myMessage.getTextContent())
                        .metadata(myMessage.getMetadata())
                        .media(myMessage.getMedia())
                        .build();
            }
            case ASSISTANT -> {
                // 使用 MyAssistantMessage 承载额外参数，历史记录查询时才能拿到课程卡片
                return new MyAssistantMessage(
                        myMessage.getTextContent(),
                        myMessage.getMetadata(),
                        myMessage.getToolCalls(),
                        myMessage.getMedia(),
                        myMessage.getParams());
            }
            case TOOL -> {
                return new ToolResponseMessage(myMessage.getToolResponses(), myMessage.getMetadata());
            }
        }

        throw new RuntimeException("Message data conversion failed.");
    }


}

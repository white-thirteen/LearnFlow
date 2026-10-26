package com.tianji.aigc.config;

import com.tianji.aigc.mapper.ChatMessageMapper;
import com.tianji.aigc.memory.MySQLChatMemoryRepository;
import com.tianji.aigc.memory.RedisChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAIConfig {

    /**
     * 默认只挂日志 advisor。
     * <p>会话记忆由各 Agent 按需显式添加（见 AbstractAgent#memoryEnabled），
     * 避免路由智能体把分类结果、用户问题写进会话记忆；</p>
     * <p>AbstractAgent 在每次请求中合并 Agent.tools() 和按 Agent 分配的 MCP 回调，
     * 统一通过 toolCallbacks() 挂载，避免路由等场景误带业务工具。</p>
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatclientbulider, SimpleLoggerAdvisor loggerAdvisor) {
        return chatclientbulider
                .defaultAdvisors(loggerAdvisor)
                .build();

    }

    @Bean
    public SimpleLoggerAdvisor loggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    /**
     * 基于 Redis 的会话记忆，tj.ai.memory.type 未配置或为 redis 时生效
     */
    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.memory", name = "type", havingValue = "redis", matchIfMissing = true)
    public RedisChatMemoryRepository redisChatMemoryRepository() {
        return new RedisChatMemoryRepository();
    }

    /**
     * 基于 MySQL 的会话记忆，tj.ai.memory.type=mysql 时生效
     */
    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.memory", name = "type", havingValue = "mysql")
    public MySQLChatMemoryRepository mysqlChatMemoryRepository(ChatMessageMapper chatMessageMapper) {
        return new MySQLChatMemoryRepository(chatMessageMapper);
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, MemoryProperties memoryProperties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(memoryProperties.getMax())
                .build();
    }

    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();

    }


}

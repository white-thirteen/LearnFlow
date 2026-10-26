package com.tianji.aigc.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Getter
@Configuration
@RequiredArgsConstructor
public class SystemPromptConfig {

    private final NacosConfigManager nacosConfigManager;
    private final AIProperties aiProperties;

    // 使用原子引用，保证线程安全
    private final AtomicReference<String> chatSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> routeAgentSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> recommendAgentSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> consultAgentSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> buyAgentSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> knowledgeAgentSystemMessage = new AtomicReference<>();

    @PostConstruct // 初始化时加载配置
    public void init() {
        // 读取配置文件
        loadConfig(aiProperties.getSystem().getChat(), chatSystemMessage);
        loadConfig(aiProperties.getSystem().getRouteAgent(), routeAgentSystemMessage);
        loadConfig(aiProperties.getSystem().getRecommendAgent(), recommendAgentSystemMessage);
        loadConfig(aiProperties.getSystem().getConsultAgent(), consultAgentSystemMessage);
        loadConfig(aiProperties.getSystem().getBuyAgent(), buyAgentSystemMessage);
        loadConfig(aiProperties.getSystem().getKnowledgeAgent(), knowledgeAgentSystemMessage);



    }

    private void loadConfig(AIProperties.System.Chat chatConfig, AtomicReference<String> target) {
        // 没有配置的智能体直接跳过，避免一个提示词缺失导致整个应用启动失败
        if (chatConfig == null || !StringUtils.hasText(chatConfig.getDataId())) {
            log.warn("系统提示词未配置，跳过加载, target: {}", target);
            return;
        }
        var dataId = chatConfig.getDataId();
        var group = chatConfig.getGroup();
        var timeoutMs = chatConfig.getTimeoutMs();
        try {
            var config = nacosConfigManager.getConfigService().getConfig(dataId, group, timeoutMs);
            if (StringUtils.hasText(config)) {
                target.set(config);
                log.info("加载系统提示词成功, dataId={}, group={}, 长度={}", dataId, group, config.length());
            } else {
                // 不要把 null 塞进去，否则对话时 .text(null) 会直接抛 IllegalArgumentException
                log.error("加载系统提示词失败, dataId={}, group={}, Nacos 返回内容为空，请检查配置是否存在", dataId, group);
            }

            nacosConfigManager.getConfigService().addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String info) {
                    if (StringUtils.hasText(info)) {
                        target.set(info);
                        log.info("系统提示词已更新, dataId={}, 长度={}", dataId, info.length());
                    } else {
                        log.warn("系统提示词被清空, dataId={}, 继续使用旧内容", dataId);
                    }
                }
            });
        } catch (Exception e) {
            log.error("加载系统提示词失败, dataId={}, group={}", dataId, group, e);
        }
    }

}

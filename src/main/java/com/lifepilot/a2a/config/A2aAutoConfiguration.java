package com.lifepilot.a2a.config;

import com.lifepilot.a2a.client.A2aClientService;
import com.lifepilot.a2a.client.RemoteAgentRegistry;
import com.lifepilot.a2a.client.RemoteAgentToolFactory;
import com.lifepilot.a2a.server.*;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.multiagent.config.MultiAgentAutoConfiguration;
import com.lifepilot.multiagent.execution.AgentExecutor;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.util.concurrent.TimeUnit;

/**
 * A2A 模块 Spring Boot 自动装配。
 *
 * <p>根据配置条件注册 Server / Client 相关 Bean。
 * 依赖 MultiAgentAutoConfiguration 提供的 AgentRegistry 和 AgentExecutor。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@AutoConfiguration(after = MultiAgentAutoConfiguration.class)
@EnableConfigurationProperties(A2aProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.a2a", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class A2aAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(A2aAutoConfiguration.class);

    private final SharedScheduler sharedScheduler;

    public A2aAutoConfiguration(SharedScheduler sharedScheduler) {
        this.sharedScheduler = sharedScheduler;
    }

    // ── Server Bean（lifepilot.a2a.server.enabled=true）──

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AgentCardGenerator agentCardGenerator(AgentRegistry agentRegistry,
                                                  A2aProperties properties) {
        log.info("A2A Server: 注册 AgentCardGenerator");
        return new AgentCardGenerator(agentRegistry, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aTaskStore a2aTaskStore(A2aProperties properties) {
        log.info("A2A Server: 注册 A2aTaskStore");
        return new A2aTaskStore(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aAgentExecutor a2aAgentExecutor(AgentRegistry agentRegistry,
                                              AgentExecutor agentExecutor,
                                              AgentOrchestrator agentOrchestrator,
                                              A2aTaskStore taskStore) {
        log.info("A2A Server: 注册 A2aAgentExecutor");
        return new A2aAgentExecutor(agentRegistry, agentExecutor, agentOrchestrator, taskStore);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AgentCardController agentCardController(AgentCardGenerator generator) {
        log.info("A2A Server: 注册 AgentCardController");
        return new AgentCardController(generator);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aMessageController a2aMessageController(A2aAgentExecutor executor,
                                                      A2aProperties properties) {
        log.info("A2A Server: 注册 A2aMessageController");
        return new A2aMessageController(executor, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aTaskController a2aTaskController(A2aTaskStore taskStore) {
        log.info("A2A Server: 注册 A2aTaskController");
        return new A2aTaskController(taskStore);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aApiKeyFilter a2aApiKeyFilter(A2aProperties properties) {
        log.info("A2A Server: 注册 A2aApiKeyFilter");
        return new A2aApiKeyFilter(properties);
    }

    // ── Client Bean（lifepilot.a2a.client.enabled=true）──

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.client", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aClientService a2aClientService(A2aProperties properties) {
        log.info("A2A Client: 注册 A2aClientService");
        return new A2aClientService(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.client", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public RemoteAgentRegistry remoteAgentRegistry(A2aClientService clientService,
                                                    A2aProperties properties) {
        log.info("A2A Client: 注册 RemoteAgentRegistry");
        return new RemoteAgentRegistry(clientService, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.client", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public RemoteAgentToolFactory remoteAgentToolFactory(A2aClientService clientService,
                                                          DynamicToolRegistry toolRegistry) {
        log.info("A2A Client: 注册 RemoteAgentToolFactory");
        return new RemoteAgentToolFactory(clientService, toolRegistry);
    }

    // ── 启动后初始化 ──

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        var properties = ctx.getBean(A2aProperties.class);

        // 1. Client 启动时自动发现配置的远程 Agent
        if (properties.getClient().isEnabled() && ctx.containsBean("remoteAgentRegistry")) {
            var registry = ctx.getBean(RemoteAgentRegistry.class);
            var toolFactory = ctx.getBean(RemoteAgentToolFactory.class);
            registry.discoverConfiguredAgents();

            // 为已发现的远程 Agent 注册工具
            registry.listAll().forEach(card -> {
                String agentUrl = properties.getClient().getRemoteAgents().stream()
                        .filter(url -> registry.findByUrl(url)
                                .map(c -> c.name().equals(card.name()))
                                .orElse(false))
                        .findFirst()
                        .orElse("");
                if (!agentUrl.isEmpty()) {
                    toolFactory.registerRemoteTool(agentUrl, card);
                }
            });
        }

        // 2. Server 启动 TTL 清理定时任务
        if (properties.getServer().isEnabled() && ctx.containsBean("a2aTaskStore")) {
            var taskStore = ctx.getBean(A2aTaskStore.class);
            int ttlMinutes = properties.getTask().getTtlMinutes();
            sharedScheduler.cleanup().scheduleAtFixedRate(() -> {
                int cleaned = taskStore.cleanupExpired();
                if (cleaned > 0) {
                    log.info("A2A Task TTL 清理完成: 清理 {} 个过期 Task", cleaned);
                }
            }, ttlMinutes, ttlMinutes, TimeUnit.MINUTES);
            log.info("A2A Server: TTL 清理定时任务已启动, 间隔={}分钟", ttlMinutes);
        }

        log.info("A2A 模块初始化完成");
    }
}

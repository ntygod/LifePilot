package com.lifepilot.meta.config;

import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.meta.infra.InfraToolProvider;
import com.lifepilot.meta.infra.browser.BrowserSessionManager;
import com.lifepilot.meta.infra.interaction.CliInteractionHandler;
import com.lifepilot.meta.infra.interaction.InteractionBridge;
import com.lifepilot.sandbox.booter.SandboxBooter;
import jakarta.annotation.Nullable;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * 元能力系统 Spring Boot 自动配置。
 *
 * <p>注册元能力模块所有核心 Bean：InfraToolProvider、BrowserSessionManager、
 * InteractionBridge、CapabilityAggregator、IntrospectionSkillProvider、
 * SkillDiscoveryRegistrar、McpInstallerRegistrar。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
@AutoConfiguration
@EnableConfigurationProperties(MetaProperties.class)
public class MetaAutoConfiguration {

    /**
     * 注册交互桥接器 — 管理 Agent 与用户之间的交互请求/响应生命周期。
     *
     * <p>SseSessionManager 为可选依赖（Web Channel），CliInteractionHandler 为可选依赖（CLI Channel）。</p>
     */
    @Bean
    InteractionBridge interactionBridge(MetaProperties properties,
                                        @Nullable SseSessionManager sseSessionManager,
                                        @Nullable CliInteractionHandler cliInteractionHandler) {
        return new InteractionBridge(properties, sseSessionManager, cliInteractionHandler);
    }

    /**
     * 注册基础工具提供者。
     *
     * <p>SandboxBooter 为可选依赖，仅在沙箱模块可用时注入。
     * InteractionBridge 注入交互桥接器。
     * BrowserSessionManager 为可选依赖，仅在 Playwright 可用时注入。</p>
     */
    @Bean
    InfraToolProvider infraToolProvider(MetaProperties properties,
                                        RestClient.Builder restClientBuilder,
                                        @Nullable SandboxBooter sandboxBooter,
                                        @Nullable InteractionBridge interactionBridge,
                                        @Nullable BrowserSessionManager browserSessionManager) {
        return new InfraToolProvider(properties, restClientBuilder, sandboxBooter, interactionBridge, browserSessionManager);
    }

    /**
     * 注册浏览器会话管理器 — 仅在 Playwright 类可用时注册。
     */
    @Bean
    @ConditionalOnClass(name = "com.microsoft.playwright.Playwright")
    BrowserSessionManager browserSessionManager(MetaProperties properties) {
        return new BrowserSessionManager(properties);
    }

    // Bean 注册将在后续任务中随实现类创建逐步添加：
    // - Task 10: CapabilityAggregator + IntrospectionSkillProvider
    // - Task 11: SkillDiscoveryRegistrar (@ConditionalOnProperty)
    // - Task 12: McpInstallerRegistrar (@ConditionalOnProperty)
}

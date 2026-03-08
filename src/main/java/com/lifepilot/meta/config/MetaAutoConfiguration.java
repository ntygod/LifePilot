package com.lifepilot.meta.config;

import com.lifepilot.meta.infra.InfraToolProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 元能力系统 Spring Boot 自动配置。
 *
 * <p>注册元能力模块所有核心 Bean：InfraToolProvider、InteractionBridge、
 * CapabilityAggregator、IntrospectionSkillProvider、SkillDiscoveryRegistrar、
 * McpInstallerRegistrar。实现类将在后续任务中逐步创建，届时补充 Bean 注册。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
@AutoConfiguration
@EnableConfigurationProperties(MetaProperties.class)
public class MetaAutoConfiguration {

    /**
     * 注册基础工具提供者。
     *
     * <p>SandboxBooter 和 InteractionBridge 尚未作为 Bean 注入，
     * 后续任务中将逐步替换为实际类型。</p>
     */
    @Bean
    InfraToolProvider infraToolProvider(MetaProperties properties) {
        return new InfraToolProvider(properties, null, null);
    }

    // Bean 注册将在后续任务中随实现类创建逐步添加：
    // - Task 9: InteractionBridge
    // - Task 10: CapabilityAggregator + IntrospectionSkillProvider
    // - Task 11: SkillDiscoveryRegistrar (@ConditionalOnProperty)
    // - Task 12: McpInstallerRegistrar (@ConditionalOnProperty)
}

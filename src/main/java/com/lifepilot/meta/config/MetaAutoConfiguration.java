package com.lifepilot.meta.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

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

    // Bean 注册将在后续任务中随实现类创建逐步添加：
    // - Task 2: InfraToolProvider
    // - Task 9: InteractionBridge
    // - Task 10: CapabilityAggregator + IntrospectionSkillProvider
    // - Task 11: SkillDiscoveryRegistrar (@ConditionalOnProperty)
    // - Task 12: McpInstallerRegistrar (@ConditionalOnProperty)
}

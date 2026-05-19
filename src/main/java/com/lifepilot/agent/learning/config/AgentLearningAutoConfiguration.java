package com.lifepilot.agent.learning.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Agent 学习层自动装配 — 注册巩固、遗忘、提取、经验总结、反馈等学习组件。
 *
 * <p>Phase A：与旧 {@code MemoryAutoConfiguration} 并存，通过 {@code @ConditionalOnMissingBean}
 * 确保不重复注册。旧配置中的同名 Bean 优先，本配置作为补充。
 * Phase B 后旧配置删除 Bean 定义，本配置接管。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentLearningProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AgentLearningAutoConfiguration {
}

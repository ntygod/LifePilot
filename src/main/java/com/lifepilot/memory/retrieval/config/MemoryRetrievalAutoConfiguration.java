package com.lifepilot.memory.retrieval.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 记忆检索层自动装配 — 注册检索、精排、编排等组件。
 *
 * <p>Phase A：与旧 {@code MemoryAutoConfiguration} 并存，通过 {@code @ConditionalOnMissingBean}
 * 确保不重复注册。旧配置中的同名 Bean 优先，本配置作为补充。
 * Phase B 后旧配置删除 Bean 定义，本配置接管。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
@AutoConfiguration
@EnableConfigurationProperties(MemoryRetrievalProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryRetrievalAutoConfiguration {
}

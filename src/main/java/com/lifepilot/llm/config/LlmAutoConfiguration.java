package com.lifepilot.llm.config;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.adapter.ProviderAdapterFactory;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.registry.ProviderHealthChecker;
import com.lifepilot.llm.registry.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * LLM Router 自动配置。
 *
 * <p>通过 {@code lifepilot.llm.enabled=true}（默认）激活，
 * 注册所有 LLM Router 核心 Bean，每个 Bean 使用
 * {@link ConditionalOnMissingBean} 允许用户覆盖。
 *
 * @author zsg
 * @since 2026-02-24
 */
@AutoConfiguration
@EnableConfigurationProperties(LlmConfigProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.llm", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LlmAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ProviderAdapterFactory providerAdapterFactory() {
        return new ProviderAdapterFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderHealthChecker providerHealthChecker() {
        return new ProviderHealthChecker();
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerConfig circuitBreakerConfig(LlmConfigProperties properties) {
        return properties.toCircuitBreakerConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerManager circuitBreakerManager(CircuitBreakerConfig config,
                                                       JdbcTemplate jdbcTemplate) {
        return new CircuitBreakerManager(config, jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderRegistry providerRegistry(ProviderAdapterFactory adapterFactory,
                                             ProviderHealthChecker healthChecker,
                                             LlmConfigProperties properties) {
        var registry = new ProviderRegistry(adapterFactory, healthChecker);
        // 自动注册已启用的 Provider
        properties.getProviders().forEach((id, entry) -> {
            if (entry.isEnabled()) {
                try {
                    var config = LlmConfigProperties.toProviderConfig(id, entry);
                    registry.register(config);
                } catch (Exception e) {
                    log.warn("Provider 自动注册失败: id={}, error={}", id, e.getMessage());
                }
            }
        });
        if (registry.registeredIds().isEmpty()) {
            log.warn("LLM Router: 无可用 Provider，所有 LLM 依赖功能将不可用");
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmRouter llmRouter(ProviderRegistry providerRegistry,
                                CircuitBreakerManager circuitBreakerManager) {
        return new LlmRouter(providerRegistry, circuitBreakerManager);
    }
}

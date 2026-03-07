package com.lifepilot.llm.config;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.adapter.ProviderAdapterFactory;
import com.lifepilot.llm.cache.SemanticCache;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.registry.ProviderHealthChecker;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.llm.service.LlmProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * LLM Router 自动配置。
 *
 * <p>通过 {@code lifepilot.llm.enabled=true}（默认）激活，
 * 注册所有 LLM Router 核心 Bean，每个 Bean 使用
 * {@link ConditionalOnMissingBean} 允许用户覆盖。
 *
 * <p>启动时从数据库读取 LLM Provider 配置并注册，不再从配置文件读取。
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
    public ProviderAdapterFactory providerAdapterFactory(@Nullable List<CallAdvisor> advisors) {
        return new ProviderAdapterFactory(advisors);
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
                                             ProviderHealthChecker healthChecker) {
        // 创建空的注册表，启动时从数据库加载
        return new ProviderRegistry(adapterFactory, healthChecker);
    }

    /**
     * 应用启动完成后，从数据库加载并注册所有已启用的 Provider。
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerProvidersFromDatabase(ApplicationReadyEvent event) {
        ApplicationContext ctx = event.getApplicationContext();
        if (ctx.getBeanNamesForType(LlmProviderService.class).length > 0) {
            LlmProviderService providerService = ctx.getBean(LlmProviderService.class);
            log.info("开始从数据库加载 LLM Provider 配置...");
            providerService.registerAllEnabled();
            log.info("LLM Provider 配置加载完成");
        } else {
            log.warn("LlmProviderService 不可用，跳过 Provider 注册");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmRouter llmRouter(ProviderRegistry providerRegistry,
                                CircuitBreakerManager circuitBreakerManager) {
        return new LlmRouter(providerRegistry, circuitBreakerManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.llm.cache", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SemanticCache semanticCache(LlmConfigProperties properties,
                                       LlmRouter llmRouter,
                                       JdbcTemplate jdbcTemplate) {
        var cache = new SemanticCache(properties.getCache(), llmRouter, jdbcTemplate);
        // 延迟注入，避免构造函数循环依赖
        llmRouter.setSemanticCache(cache);
        return cache;
    }
}

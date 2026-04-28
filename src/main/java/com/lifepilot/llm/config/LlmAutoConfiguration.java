package com.lifepilot.llm.config;

import com.lifepilot.llm.adapter.ProviderAdapterFactory;
import com.lifepilot.llm.cache.SemanticCache;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.profile.BaseAdapterType;
import com.lifepilot.llm.profile.ProviderProfileRegistry;
import com.lifepilot.llm.registry.ProviderHealthChecker;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.llm.thinking.AnthropicThinkingProtocol;
import com.lifepilot.llm.thinking.DeepSeekThinkingProtocol;
import com.lifepilot.llm.thinking.NoopThinkingProtocol;
import com.lifepilot.llm.thinking.OpenAiReasoningEffortProtocol;
import com.lifepilot.llm.thinking.QwenThinkingProtocol;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.modelservice.probe.ProbeModelsService;
import com.lifepilot.modelservice.service.ModelServiceRegistrationService;
import com.lifepilot.skill.registry.SkillSearchIndex;
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

import java.util.HashSet;
import java.util.List;

/**
 * 生成与嵌入运行时的自动配置。
 *
 * <p>当前 ProviderRegistry 只从 model_services 载入 generation / embedding 服务。
 * rerank 服务不进入 ProviderRegistry，而是由独立的 RerankRouter 处理。</p>
 *
 * @author zsg
 * @since 2026-03-24
 */
@AutoConfiguration
@EnableConfigurationProperties(LlmConfigProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.llm", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LlmAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public NoopThinkingProtocol noopThinkingProtocol() {
        return new NoopThinkingProtocol();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeepSeekThinkingProtocol deepSeekThinkingProtocol() {
        return new DeepSeekThinkingProtocol();
    }

    @Bean
    @ConditionalOnMissingBean
    public QwenThinkingProtocol qwenThinkingProtocol() {
        return new QwenThinkingProtocol();
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAiReasoningEffortProtocol openAiReasoningEffortProtocol() {
        return new OpenAiReasoningEffortProtocol();
    }

    @Bean
    @ConditionalOnMissingBean
    public AnthropicThinkingProtocol anthropicThinkingProtocol() {
        return new AnthropicThinkingProtocol();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderAdapterFactory providerAdapterFactory(@Nullable List<CallAdvisor> advisors,
                                                         LlmConfigProperties properties,
                                                         ProviderProfileRegistry profileRegistry,
                                                         List<ThinkingProtocol> thinkingProtocols,
                                                         ProbeModelsService probeModelsService) {
        return new ProviderAdapterFactory(advisors, properties.getConnectionPool(),
                profileRegistry, thinkingProtocols, probeModelsService);
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
    public CircuitBreakerManager circuitBreakerManager(CircuitBreakerConfig config, JdbcTemplate jdbcTemplate) {
        return new CircuitBreakerManager(config, jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderRegistry providerRegistry(ProviderAdapterFactory adapterFactory,
                                             ProviderHealthChecker healthChecker) {
        return new ProviderRegistry(adapterFactory, healthChecker);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerProvidersFromDatabase(ApplicationReadyEvent event) {
        ApplicationContext context = event.getApplicationContext();

        if (context.getBeanNamesForType(ModelServiceRegistrationService.class).length > 0) {
            ModelServiceRegistrationService registrationService = context.getBean(ModelServiceRegistrationService.class);
            log.info("开始从 model_services 注册运行时生成与向量服务");
            registrationService.registerAllEnabled();
            log.info("运行时模型服务注册完成");
        } else {
            log.warn("ModelServiceRegistrationService 不可用，跳过运行时模型服务注册");
        }

        purgeStaleCircuitBreakers(context);
        rebuildSkillIndex(context);
        warmupCloudProviders(context);
    }

    private void purgeStaleCircuitBreakers(ApplicationContext context) {
        if (context.getBeanNamesForType(CircuitBreakerManager.class).length == 0
                || context.getBeanNamesForType(ProviderRegistry.class).length == 0) {
            return;
        }
        var circuitBreakerManager = context.getBean(CircuitBreakerManager.class);
        var providerRegistry = context.getBean(ProviderRegistry.class);
        var activeKeys = new HashSet<String>();
        for (String providerId : providerRegistry.registeredIds()) {
            providerRegistry.getConfig(providerId).ifPresent(config ->
                    config.capabilities().forEach(capability ->
                            activeKeys.add(providerId + ":" + capability.name())));
        }
        circuitBreakerManager.purgeStaleBreakers(activeKeys);
    }

    private void rebuildSkillIndex(ApplicationContext context) {
        if (context.getBeanNamesForType(SkillSearchIndex.class).length == 0) {
            return;
        }
        var searchIndex = context.getBean(SkillSearchIndex.class);
        int rebuilt = searchIndex.reindexAll();
        if (rebuilt > 0) {
            log.info("Skill Embedding 索引重建完成: 数量={}", rebuilt);
        }
    }

    private void warmupCloudProviders(ApplicationContext context) {
        if (context.getBeanNamesForType(ProviderRegistry.class).length == 0) {
            return;
        }
        var providerRegistry = context.getBean(ProviderRegistry.class);
        if (providerRegistry.registeredIds().isEmpty()) {
            log.debug("当前无已注册模型服务，跳过连接预热");
            return;
        }
        // 通过 profile.baseAdapter() 区分本地 / 云端：本地模型（OLLAMA）不参与预热，
        // 避免在用户未启动本地 Ollama 时产生噪音日志。
        var profileRegistry = context.getBean(ProviderProfileRegistry.class);

        int warmupCount = 0;
        for (String providerId : providerRegistry.registeredIds()) {
            var config = providerRegistry.getConfig(providerId);
            if (config.isEmpty()) {
                continue;
            }
            BaseAdapterType baseAdapter = profileRegistry.get(config.get().profileId()).baseAdapter();
            if (baseAdapter == BaseAdapterType.OLLAMA) {
                continue;
            }
            try {
                boolean healthy = providerRegistry.healthCheck(providerId);
                if (healthy) {
                    log.info("云端模型服务预热成功: id={}", providerId);
                } else {
                    log.warn("云端模型服务预热返回异常状态: id={}", providerId);
                }
                warmupCount++;
            } catch (Exception e) {
                log.warn("云端模型服务预热失败: id={}, error={}", providerId, e.getMessage());
            }
        }
        if (warmupCount > 0) {
            log.info("模型服务预热完成: count={}", warmupCount);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.llm.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SemanticCache semanticCache(LlmConfigProperties properties,
                                       GenerationRouter generationRouter,
                                       EmbeddingRouter embeddingRouter,
                                       JdbcTemplate jdbcTemplate) {
        var cache = new SemanticCache(properties.getCache(), embeddingRouter, jdbcTemplate);
        generationRouter.setSemanticCache(cache);
        return cache;
    }
}

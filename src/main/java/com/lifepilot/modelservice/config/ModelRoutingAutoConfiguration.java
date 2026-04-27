package com.lifepilot.modelservice.config;

import com.lifepilot.embedding.client.EmbeddingClientFactory;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.client.GenerationClientFactory;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.adapter.ProviderAdapterFactory;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.profile.ProviderProfileRegistry;
import com.lifepilot.modelservice.registry.ModelServiceRegistry;
import com.lifepilot.modelservice.repository.EmbeddingSettingsRepository;
import com.lifepilot.modelservice.repository.GenerationSettingsRepository;
import com.lifepilot.modelservice.repository.RerankSettingsRepository;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.rerank.client.RerankClientFactory;
import com.lifepilot.rerank.router.RerankRouter;
import com.lifepilot.rerank.strategy.LlmListwiseRerankStrategy;
import com.lifepilot.rerank.strategy.LlmPointwiseRerankStrategy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 模型路由自动配置。
 *
 * @author zsg
 * @since 2026-03-24
 */
@AutoConfiguration(after = com.lifepilot.llm.config.LlmAutoConfiguration.class)
@ConditionalOnProperty(prefix = "lifepilot.llm", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(ProviderAdapterFactory.class)
public class ModelRoutingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GenerationClientFactory generationClientFactory(ProviderAdapterFactory providerAdapterFactory) {
        return new GenerationClientFactory(providerAdapterFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingClientFactory embeddingClientFactory(ProviderAdapterFactory providerAdapterFactory) {
        return new EmbeddingClientFactory(providerAdapterFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public GenerationRouter generationRouter(ModelServiceRegistry registry,
                                             GenerationSettingsRepository settingsRepository,
                                             GenerationClientFactory clientFactory,
                                             CircuitBreakerManager circuitBreakerManager,
                                             ProviderProfileRegistry profileRegistry) {
        return new GenerationRouter(registry, settingsRepository, clientFactory,
                circuitBreakerManager, profileRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingRouter embeddingRouter(ModelServiceRegistry registry,
                                           EmbeddingSettingsRepository settingsRepository,
                                           EmbeddingClientFactory clientFactory,
                                           CircuitBreakerManager circuitBreakerManager) {
        return new EmbeddingRouter(registry, settingsRepository, clientFactory, circuitBreakerManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public RerankClientFactory rerankClientFactory(com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                                   ProviderProfileRegistry profileRegistry) {
        return new RerankClientFactory(objectMapper, profileRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmPointwiseRerankStrategy llmPointwiseRerankStrategy(GenerationRouter generationRouter,
                                                                 PromptRegistry promptRegistry) {
        return new LlmPointwiseRerankStrategy(generationRouter, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmListwiseRerankStrategy llmListwiseRerankStrategy(GenerationRouter generationRouter,
                                                               PromptRegistry promptRegistry) {
        return new LlmListwiseRerankStrategy(generationRouter, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RerankRouter rerankRouter(ModelServiceRegistry registry,
                                     RerankSettingsRepository settingsRepository,
                                     RerankClientFactory rerankClientFactory,
                                     LlmPointwiseRerankStrategy pointwiseStrategy,
                                     LlmListwiseRerankStrategy listwiseStrategy) {
        return new RerankRouter(registry, settingsRepository, rerankClientFactory, pointwiseStrategy, listwiseStrategy);
    }
}

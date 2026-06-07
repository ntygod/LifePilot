package com.lifepilot.memory.retrieval.config;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import com.lifepilot.memory.retrieval.FtsSearcher;
import com.lifepilot.memory.retrieval.GraphTraverser;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.QueryRefiner;
import com.lifepilot.memory.retrieval.QueryRewriter;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.retrieval.orchestrator.ExperienceRetrievalSource;
import com.lifepilot.memory.retrieval.orchestrator.HybridRetrievalSource;
import com.lifepilot.memory.retrieval.orchestrator.KnowledgeBaseSource;
import com.lifepilot.memory.retrieval.orchestrator.QueryPlanner;
import com.lifepilot.memory.retrieval.orchestrator.RetrievalOrchestrator;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.config.MemoryStoreAutoConfiguration;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.rerank.router.RerankRouter;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 记忆检索层自动装配 — 注册检索、精排、编排等组件。
 *
 * @author zsg
 * @since 2026-06-01
 */
@AutoConfiguration(after = MemoryStoreAutoConfiguration.class)
@EnableConfigurationProperties(MemoryRetrievalProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryRetrievalAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetrievalAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public QueryRefiner queryRefiner(MemoryRetrievalProperties properties) {
        log.info("记忆模块: 注册 QueryRefiner");
        return new QueryRefiner(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryRewriter queryRewriter(@Nullable GenerationRouter generationRouter,
                                       @Nullable EmbeddingRouter embeddingRouter,
                                       MemoryRetrievalProperties properties,
                                       PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 QueryRewriter, mode={}, generationRouterAvailable={}, embeddingRouterAvailable={}",
                properties.getQueryRewriteMode(),
                generationRouter != null ? "yes" : "no",
                embeddingRouter != null ? "yes" : "no");
        return new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public FtsSearcher ftsSearcher(JdbcTemplate jdbcTemplate) {
        log.info("记忆模块: 注册 FtsSearcher");
        return new FtsSearcher(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphTraverser graphTraverser(JdbcTemplate jdbcTemplate, MemoryRetrievalProperties properties) {
        log.info("记忆模块: 注册 GraphTraverser, minRelationTrust={}", properties.getMinRelationTrust());
        return new GraphTraverser(jdbcTemplate, properties.getMinRelationTrust());
    }

    @Bean
    @ConditionalOnMissingBean
    public HybridRetriever hybridRetriever(
            VectorSearcher vectorSearcher,
            FtsSearcher ftsSearcher,
            GraphTraverser graphTraverser,
            SemanticMemory semanticMemory,
            EpisodicMemory episodicMemory,
            @Nullable IntentMatcher intentMatcher,
            @Nullable RerankRouter rerankRouter,
            JdbcTemplate jdbcTemplate,
            MemoryRetrievalProperties properties,
            @Nullable MemoryProvenanceRepository provenanceRepository) {
        log.info("记忆模块: 注册 HybridRetriever, intentMatcher={}, reranker={}, provenance={}",
                intentMatcher != null ? "enabled" : "disabled",
                rerankRouter != null ? "enabled" : "disabled",
                provenanceRepository != null ? "enabled" : "disabled");
        var retriever = new HybridRetriever(vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, intentMatcher, properties, jdbcTemplate, rerankRouter,
                provenanceRepository);
        return retriever;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "lifepilot.memory.retrieval-orchestrator.enabled", havingValue = "true")
    public RetrievalOrchestrator retrievalOrchestrator(
            QueryPlanner planner,
            MemoryRetrievalProperties properties) {
        return new RetrievalOrchestrator(planner, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "lifepilot.memory.retrieval-orchestrator.enabled", havingValue = "true")
    public QueryPlanner retrievalQueryPlanner(
            @Nullable HybridRetrievalSource hybridSource,
            @Nullable ExperienceRetrievalSource experienceSource,
            @Nullable KnowledgeBaseSource knowledgeBaseSource) {
        return new QueryPlanner(hybridSource, experienceSource, knowledgeBaseSource);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "lifepilot.memory.retrieval-orchestrator.enabled", havingValue = "true")
    public HybridRetrievalSource hybridRetrievalSource(
            @Nullable HybridRetriever hybridRetriever) {
        return new HybridRetrievalSource(hybridRetriever);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "lifepilot.memory.retrieval-orchestrator.enabled", havingValue = "true")
    public ExperienceRetrievalSource experienceRetrievalSource(
            @Nullable SemanticMemory semanticMemory) {
        return new ExperienceRetrievalSource(semanticMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "lifepilot.memory.retrieval-orchestrator.enabled", havingValue = "true")
    public KnowledgeBaseSource knowledgeBaseSource(
            @Nullable com.lifepilot.knowledge.retrieve.DocumentRetriever documentRetriever,
            @Nullable com.lifepilot.knowledge.repository.KnowledgeBaseRepository kbRepository) {
        return new KnowledgeBaseSource(documentRetriever, kbRepository);
    }
}

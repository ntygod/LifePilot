package com.lifepilot.knowledge.config;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.knowledge.chunking.RecursiveChunker;
import com.lifepilot.knowledge.chunking.SemanticChunker;
import com.lifepilot.knowledge.enricher.ChunkContextEnricher;
import com.lifepilot.knowledge.util.TokenCounter;
import com.lifepilot.knowledge.extract.KnowledgeExtractionPipeline;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.retrieve.QueryEnhancer;
import com.lifepilot.knowledge.retrieve.RetrievalQualityEvaluator;
import com.lifepilot.memory.config.MemoryAutoConfiguration;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.modelservice.config.ModelRoutingAutoConfiguration;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.prompt.config.PromptAutoConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 知识库增强能力自动配置。
 *
 * <p>该配置只承载跨模块增强能力，例如向量索引、LLM 上下文增强、查询增强与知识提取。
 * 通过显式 {@code after} 约束依赖的 Prompt / 路由 / 记忆配置，避免条件判断过早执行。</p>
 *
 * @author zsg
 * @since 2026-03-27
 */
@AutoConfiguration(after = {
        KnowledgeAutoConfiguration.class,
        PromptAutoConfiguration.class,
        ModelRoutingAutoConfiguration.class,
        MemoryAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "lifepilot.knowledge", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KnowledgeEnhancementAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.knowledge.chunking.semantic-chunking", name = "enabled",
            havingValue = "true")
    @ConditionalOnBean(EmbeddingRouter.class)
    public SemanticChunker semanticChunker(EmbeddingRouter embeddingRouter,
                                           RecursiveChunker recursiveChunker,
                                           KnowledgeBaseProperties props,
                                           TokenCounter tokenCounter) {
        return new SemanticChunker(embeddingRouter, recursiveChunker, props.chunking().semanticChunking(), tokenCounter);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EmbeddingRouter.class)
    @ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public VectorIndexer vectorIndexer(EmbeddingRouter embeddingRouter,
                                       @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
                                       JdbcTemplate jdbcTemplate,
                                       KnowledgeBaseProperties props) {
        return new VectorIndexer(embeddingRouter, vectorJdbcTemplate, jdbcTemplate, props.vectorIndexer());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(GenerationRouter.class)
    public ChunkContextEnricher chunkContextEnricher(GenerationRouter generationRouter,
                                                     KnowledgeBaseProperties props,
                                                     PromptRegistry promptRegistry,
                                                     TokenCounter tokenCounter) {
        return new ChunkContextEnricher(generationRouter, props.contextEnricher(), promptRegistry, tokenCounter);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.knowledge.retrieval", name = "correction-enabled",
            havingValue = "true")
    @ConditionalOnBean(GenerationRouter.class)
    public RetrievalQualityEvaluator retrievalQualityEvaluator(GenerationRouter generationRouter,
                                                                PromptRegistry promptRegistry,
                                                                KnowledgeBaseProperties props) {
        return new RetrievalQualityEvaluator(generationRouter, promptRegistry,
                props.retrieval().correctionHighThreshold(),
                props.retrieval().correctionTimeoutMs());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.knowledge.query-enhancer", name = "mode",
            matchIfMissing = false)
    @ConditionalOnBean(GenerationRouter.class)
    public QueryEnhancer queryEnhancer(GenerationRouter generationRouter,
                                       EmbeddingRouter embeddingRouter,
                                       KnowledgeBaseProperties props,
                                       PromptRegistry promptRegistry) {
        if ("none".equals(props.queryEnhancer().mode())) {
            return null;
        }
        return new QueryEnhancer(generationRouter, embeddingRouter, props.queryEnhancer(), promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(GenerationRouter.class)
    @ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public KnowledgeExtractionPipeline knowledgeExtractionPipeline(GenerationRouter generationRouter,
                                                                   SemanticMemory semanticMemory,
                                                                   KnowledgeBaseProperties props,
                                                                   PromptRegistry promptRegistry,
                                                                   MemorySpaceRepository memorySpaceRepository) {
        return new KnowledgeExtractionPipeline(
                generationRouter,
                semanticMemory,
                props.extraction(),
                promptRegistry,
                memorySpaceRepository
        );
    }
}

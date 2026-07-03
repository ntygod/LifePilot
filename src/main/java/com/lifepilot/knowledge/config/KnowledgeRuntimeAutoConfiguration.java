package com.lifepilot.knowledge.config;

import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.chunking.*;
import com.lifepilot.knowledge.detect.DuplicateDetector;
import com.lifepilot.knowledge.enricher.ChunkContextEnricher;
import com.lifepilot.knowledge.extract.KnowledgeExtractionPipeline;
import com.lifepilot.knowledge.index.FtsIndexer;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.knowledge.parser.FormatDetector;
import com.lifepilot.knowledge.repository.*;
import com.lifepilot.knowledge.retrieve.*;
import com.lifepilot.knowledge.util.TokenCounter;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.rerank.router.RerankRouter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 知识库运行时编排自动配置。
 *
 * <p>负责组装运行期 Bean，例如 SmartChunker、DocumentIngester、DocumentRetriever、
 * 管理服务与同步 Worker。该配置显式晚于核心层与增强层，确保可选增强 Bean
 * 在编排阶段已经可见。</p>
 *
 * @author zsg
 * @since 2026-03-27
 */
@AutoConfiguration(after = {
        KnowledgeAutoConfiguration.class,
        KnowledgeEnhancementAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "lifepilot.knowledge", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KnowledgeRuntimeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRuntimeAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SmartChunker smartChunker(RecursiveChunker recursiveChunker,
                                     DocumentStructureAnalyzer documentStructureAnalyzer,
                                     RegionChunkingRouter regionChunkingRouter,
                                     ChunkMerger chunkMerger) {
        return new SmartChunker(
                recursiveChunker,
                documentStructureAnalyzer,
                regionChunkingRouter,
                chunkMerger
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ParentChildChunker parentChildChunker(RecursiveChunker recursiveChunker,
                                                  DocumentStructureAnalyzer documentStructureAnalyzer,
                                                  RegionChunkingRouter regionChunkingRouter,
                                                  KnowledgeBaseProperties props,
                                                  TokenCounter tokenCounter) {
        // Parent 分块器：独立的 SmartChunker，不应用 overlap
        // parent-child 模式通过层级关系提供上下文，父块间重叠会导致子块跨章节重复
        var parentMerger = new ChunkMerger(
                props.chunking().recursive().maxChunkSize(),
                props.chunking().recursive().minChunkSize(),
                0,  // 父块不需要 overlap
                tokenCounter);
        var parentChunker = new SmartChunker(
                recursiveChunker, documentStructureAnalyzer, regionChunkingRouter, parentMerger);

        // Child 分块器：按段落→句子自然边界切分
        // 中文约 1.5 字符/token，英文约 4 字符/token，取折中值 2 适配中英混合文本
        int childMaxChunkSize = props.chunking().parentChild().childMaxTokens() * 2;
        var childRecursiveConfig = new KnowledgeBaseProperties.Chunking.Recursive(
                props.chunking().recursive().separators(),
                childMaxChunkSize,
                80,     // 子块最小字符数，低于此值与相邻块合并
                0       // 子块不需要 overlap — 精确匹配后返回父块提供完整上下文
        );
        var childChunker = new RecursiveChunker(childRecursiveConfig, tokenCounter);
        return new ParentChildChunker(parentChunker, childChunker);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, MemorySpaceRepository.class})
    public GraphKnowledgeSearcher graphKnowledgeSearcher(SemanticMemory semanticMemory,
                                                          DocumentChunkRepository chunkRepository,
                                                          MemorySpaceRepository memorySpaceRepository) {
        return new GraphKnowledgeSearcher(semanticMemory, chunkRepository, memorySpaceRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChunkDeduplicator chunkDeduplicator(KnowledgeBaseProperties props) {
        return new ChunkDeduplicator(props.retrieval().deduplicationThreshold());
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentRetriever documentRetriever(@Nullable VectorIndexer vectorIndexer,
                                               FtsIndexer ftsIndexer,
                                               @Nullable RerankRouter rerankRouter,
                                               @Nullable QueryEnhancer queryEnhancer,
                                               @Nullable GraphKnowledgeSearcher graphSearcher,
                                               @Nullable RetrievalQualityEvaluator qualityEvaluator,
                                               DocumentChunkRepository chunkRepository,
                                               KnowledgeBaseRepository kbRepository,
                                               KnowledgeBaseProperties props,
                                               ChunkDeduplicator chunkDeduplicator,
                                               MeterRegistry meterRegistry,
                                               ApplicationEventPublisher eventPublisher) {
        return new DocumentRetriever(
                vectorIndexer,
                ftsIndexer,
                rerankRouter,
                queryEnhancer,
                graphSearcher,
                qualityEvaluator,
                chunkRepository,
                kbRepository,
                props.retrieval(),
                chunkDeduplicator,
                meterRegistry,
                eventPublisher
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentIngester documentIngester(FormatDetector formatDetector,
                                             SmartChunker smartChunker,
                                             ParentChildChunker parentChildChunker,
                                             FixedSizeChunker fixedSizeChunker,
                                             RecursiveChunker recursiveChunker,
                                             HeadingChunker headingChunker,
                                             @Nullable SemanticChunker semanticChunker,
                                             @Nullable ChunkContextEnricher contextEnricher,
                                             @Nullable VectorIndexer vectorIndexer,
                                             FtsIndexer ftsIndexer,
                                             DuplicateDetector duplicateDetector,
                                             @Nullable KnowledgeExtractionPipeline extractionPipeline,
                                             DocumentRepository documentRepository,
                                             DocumentChunkRepository chunkRepository,
                                             KnowledgeBaseRepository kbRepository,
                                             ApplicationEventPublisher eventPublisher,
                                             KnowledgeBaseProperties props,
                                             ChunkingConfig chunkingConfig,
                                             TokenCounter tokenCounter) {
        // Parent-Child 启用时，默认分块器使用 ParentChildChunker
        ChunkingStrategy defaultChunker = props.chunking().parentChild().enabled()
                ? parentChildChunker : smartChunker;
        var registry = new HashMap<String, ChunkingStrategy>();
        registry.put(fixedSizeChunker.strategyName(), fixedSizeChunker);
        registry.put(recursiveChunker.strategyName(), recursiveChunker);
        registry.put(headingChunker.strategyName(), headingChunker);
        registry.put(smartChunker.strategyName(), smartChunker);
        registry.put(parentChildChunker.strategyName(), parentChildChunker);
        if (semanticChunker != null) {
            registry.put(semanticChunker.strategyName(), semanticChunker);
        }
        log.info("分块器注册表: {}, 默认策略={}", registry.keySet(), defaultChunker.strategyName());
        return new DocumentIngester(
                formatDetector,
                defaultChunker,
                Map.copyOf(registry),
                contextEnricher,
                vectorIndexer,
                ftsIndexer,
                duplicateDetector,
                extractionPipeline,
                documentRepository,
                chunkRepository,
                kbRepository,
                eventPublisher,
                props,
                chunkingConfig,
                tokenCounter
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBaseManager knowledgeBaseManager(KnowledgeBaseRepository kbRepository,
                                                     DocumentRepository documentRepository,
                                                     DocumentChunkRepository chunkRepository,
                                                     @Nullable VectorIndexer vectorIndexer,
                                                     ApplicationEventPublisher eventPublisher) {
        log.info("知识库模块初始化完成");
        return new KnowledgeBaseManager(
                kbRepository,
                documentRepository,
                chunkRepository,
                vectorIndexer,
                eventPublisher
        );
    }
}

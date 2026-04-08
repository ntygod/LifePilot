package com.lifepilot.knowledge.config;

import com.lifepilot.datastore.config.DataStoreAutoConfiguration;
import com.lifepilot.datastore.sync.DataStoreKnowledgeSyncPublisher;
import com.lifepilot.datastore.sync.DatastoreKnowledgeBaseProvisioner;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.chunking.*;
import com.lifepilot.knowledge.detect.DuplicateDetector;
import com.lifepilot.knowledge.enricher.ChunkContextEnricher;
import com.lifepilot.knowledge.eval.RetrievalEvaluator;
import com.lifepilot.knowledge.extract.KnowledgeExtractionPipeline;
import com.lifepilot.knowledge.index.FtsIndexer;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.knowledge.parser.FormatDetector;
import com.lifepilot.knowledge.repository.*;
import com.lifepilot.knowledge.retrieve.*;
import com.lifepilot.knowledge.sync.DataStoreKnowledgeSyncJobPublisher;
import com.lifepilot.knowledge.sync.DatastoreDocumentProjector;
import com.lifepilot.knowledge.sync.DefaultDatastoreKnowledgeBaseProvisioner;
import com.lifepilot.knowledge.sync.KnowledgeSyncWorker;
import com.lifepilot.knowledge.util.TokenCounter;
import com.lifepilot.memory.semantic.SemanticMemory;
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
        KnowledgeEnhancementAutoConfiguration.class,
        DataStoreAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "lifepilot.knowledge", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KnowledgeRuntimeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRuntimeAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SmartChunker smartChunker(FixedSizeChunker fixedSizeChunker,
                                     RecursiveChunker recursiveChunker,
                                     HeadingChunker headingChunker,
                                     @Nullable SemanticChunker semanticChunker,
                                     KnowledgeBaseProperties props) {
        return new SmartChunker(
                fixedSizeChunker,
                recursiveChunker,
                headingChunker,
                semanticChunker,
                props.chunking().smartChunker()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ParentChildChunker parentChildChunker(SmartChunker smartChunker,
                                                  FixedSizeChunker fixedSizeChunker,
                                                  KnowledgeBaseProperties props,
                                                  TokenCounter tokenCounter) {
        // parent 使用 SmartChunker（大块），child 使用独立的小块 FixedSizeChunker
        var childConfig = new ChunkingConfig(
                props.chunking().parentChild().childMaxTokens() * 4,  // token→字符粗略转换
                50,
                props.chunking().parentChild().childOverlap(),
                props.chunking().parentChild().childMaxTokens(),
                true, true, true);
        var childChunker = new FixedSizeChunker(childConfig, tokenCounter);
        return new ParentChildChunker(smartChunker, childChunker);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SemanticMemory.class)
    public GraphKnowledgeSearcher graphKnowledgeSearcher(SemanticMemory semanticMemory,
                                                          DocumentChunkRepository chunkRepository,
                                                          DocumentRepository docRepository) {
        return new GraphKnowledgeSearcher(semanticMemory, chunkRepository, docRepository);
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
    public RetrievalEvaluator retrievalEvaluator(DocumentRetriever documentRetriever) {
        return new RetrievalEvaluator(documentRetriever);
    }

    @Bean
    @ConditionalOnMissingBean(DataStoreKnowledgeSyncPublisher.class)
    @ConditionalOnBean({
            KnowledgeBaseDatastoreRepository.class,
            KnowledgeSyncJobRepository.class,
            com.lifepilot.datastore.repository.CollectionRepository.class,
            com.lifepilot.datastore.repository.DocumentRepository.class
    })
    public DataStoreKnowledgeSyncPublisher dataStoreKnowledgeSyncPublisher(
            KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository,
            KnowledgeSyncJobRepository knowledgeSyncJobRepository,
            com.lifepilot.datastore.repository.CollectionRepository datastoreCollectionRepository,
            DatastoreDocumentProjector datastoreDocumentProjector) {
        return new DataStoreKnowledgeSyncJobPublisher(
                knowledgeBaseDatastoreRepository,
                knowledgeSyncJobRepository,
                datastoreCollectionRepository,
                datastoreDocumentProjector
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
                                                     KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository,
                                                     @Nullable VectorIndexer vectorIndexer) {
        log.info("知识库模块初始化完成");
        return new KnowledgeBaseManager(
                kbRepository,
                documentRepository,
                chunkRepository,
                knowledgeBaseDatastoreRepository,
                vectorIndexer
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner(
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeBaseManager knowledgeBaseManager) {
        return new DefaultDatastoreKnowledgeBaseProvisioner(knowledgeBaseRepository, knowledgeBaseManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
            KnowledgeSyncJobRepository.class,
            KnowledgeBaseDatastoreRepository.class,
            com.lifepilot.datastore.repository.CollectionRepository.class,
            com.lifepilot.datastore.repository.DocumentRepository.class,
            DocumentRepository.class,
            KnowledgeBaseManager.class,
            DocumentIngester.class
    })
    public KnowledgeSyncWorker knowledgeSyncWorker(
            KnowledgeSyncJobRepository knowledgeSyncJobRepository,
            KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository,
            com.lifepilot.datastore.repository.CollectionRepository datastoreCollectionRepository,
            com.lifepilot.datastore.repository.DocumentRepository datastoreDocumentRepository,
            DocumentRepository knowledgeDocumentRepository,
            KnowledgeBaseManager knowledgeBaseManager,
            DocumentIngester documentIngester,
            DatastoreDocumentProjector datastoreDocumentProjector) {
        return new KnowledgeSyncWorker(
                knowledgeSyncJobRepository,
                knowledgeBaseDatastoreRepository,
                datastoreCollectionRepository,
                datastoreDocumentRepository,
                knowledgeDocumentRepository,
                knowledgeBaseManager,
                documentIngester,
                datastoreDocumentProjector
        );
    }
}

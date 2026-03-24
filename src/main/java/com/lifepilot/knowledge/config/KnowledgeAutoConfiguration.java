package com.lifepilot.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.chunking.ChunkingConfig;
import com.lifepilot.knowledge.chunking.ChunkingStrategy;
import com.lifepilot.knowledge.chunking.FixedSizeChunker;
import com.lifepilot.knowledge.chunking.HeadingChunker;
import com.lifepilot.knowledge.chunking.RecursiveChunker;
import com.lifepilot.knowledge.chunking.SemanticChunker;
import com.lifepilot.knowledge.chunking.SmartChunker;
import com.lifepilot.knowledge.detect.DuplicateDetector;
import com.lifepilot.knowledge.enricher.ChunkContextEnricher;
import com.lifepilot.knowledge.extract.KnowledgeExtractionPipeline;
import com.lifepilot.knowledge.index.FtsIndexer;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.knowledge.parser.FormatDetector;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.knowledge.parser.PdfParser;
import com.lifepilot.knowledge.parser.PlainTextParser;
import com.lifepilot.knowledge.parser.WordParser;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.knowledge.retrieve.QueryEnhancer;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.rerank.router.RerankRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库模块自动配置。
 *
 * <p>统一注册解析、分块、索引、检索、导入与管理相关 Bean。
 *
 * @author zsg
 * @since 2026-02-25
 */
@AutoConfiguration
@EnableConfigurationProperties(KnowledgeBaseProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.knowledge", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KnowledgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAutoConfiguration.class);

    // ---- 解析器 ----

    @Bean
    @ConditionalOnMissingBean
    public MarkdownParser markdownParser() {
        return new MarkdownParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlainTextParser plainTextParser() {
        return new PlainTextParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public PdfParser pdfParser() {
        return new PdfParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public WordParser wordParser() {
        return new WordParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public FormatDetector formatDetector(MarkdownParser markdownParser,
                                         PlainTextParser plainTextParser,
                                         PdfParser pdfParser,
                                         WordParser wordParser) {
        return new FormatDetector(List.of(markdownParser, plainTextParser, pdfParser, wordParser));
    }

    // ---- 分块器 ----

    @Bean
    @ConditionalOnMissingBean
    public ChunkingConfig chunkingConfig(KnowledgeBaseProperties props) {
        var fixedSize = props.chunking().fixedSize();
        return new ChunkingConfig(
                fixedSize.chunkSize(),
                fixedSize.minChunkSize(),
                fixedSize.overlapSize(),
                fixedSize.maxChunkTokens(),
                fixedSize.respectSentences(),
                true,
                true
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public FixedSizeChunker fixedSizeChunker(ChunkingConfig chunkingConfig) {
        return new FixedSizeChunker(chunkingConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public RecursiveChunker recursiveChunker(ChunkingConfig chunkingConfig,
                                             KnowledgeBaseProperties props) {
        return new RecursiveChunker(chunkingConfig, props.chunking().recursive());
    }

    @Bean
    @ConditionalOnMissingBean
    public HeadingChunker headingChunker(ChunkingConfig chunkingConfig,
                                         RecursiveChunker recursiveChunker,
                                         KnowledgeBaseProperties props) {
        return new HeadingChunker(chunkingConfig, recursiveChunker, props.chunking().heading());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.knowledge.chunking.semantic-chunking", name = "enabled",
            havingValue = "true")
    public SemanticChunker semanticChunker(EmbeddingRouter embeddingRouter,
                                           RecursiveChunker recursiveChunker,
                                           KnowledgeBaseProperties props) {
        return new SemanticChunker(embeddingRouter, recursiveChunker, props.chunking().semanticChunking());
    }

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

    // ---- Repository ----

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBaseRepository knowledgeBaseRepository(JdbcTemplate jdbcTemplate,
                                                           ObjectMapper objectMapper) {
        return new KnowledgeBaseRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentRepository documentRepository(JdbcTemplate jdbcTemplate,
                                                 ObjectMapper objectMapper) {
        return new DocumentRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentChunkRepository documentChunkRepository(JdbcTemplate jdbcTemplate,
                                                           ObjectMapper objectMapper) {
        return new DocumentChunkRepository(jdbcTemplate, objectMapper);
    }

    // ---- 索引服务 ----

    @Bean
    @ConditionalOnMissingBean
    public VectorIndexer vectorIndexer(EmbeddingRouter embeddingRouter,
                                       @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
                                       JdbcTemplate jdbcTemplate,
                                       KnowledgeBaseProperties props) {
        return new VectorIndexer(embeddingRouter, vectorJdbcTemplate, jdbcTemplate, props.vectorIndexer());
    }

    @Bean
    @ConditionalOnMissingBean
    public FtsIndexer ftsIndexer(JdbcTemplate jdbcTemplate) {
        return new FtsIndexer(jdbcTemplate);
    }

    // ---- 重复检测 ----

    @Bean
    @ConditionalOnMissingBean
    public DuplicateDetector duplicateDetector(DocumentRepository documentRepository) {
        return new DuplicateDetector(documentRepository);
    }

    // ---- 上下文增强 ----

    @Bean
    @ConditionalOnMissingBean
    public ChunkContextEnricher chunkContextEnricher(GenerationRouter generationRouter,
                                                     KnowledgeBaseProperties props,
                                                     PromptRegistry promptRegistry) {
        return new ChunkContextEnricher(generationRouter, props.contextEnricher(), promptRegistry);
    }

    // ---- 知识提取 ----

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeExtractionPipeline knowledgeExtractionPipeline(GenerationRouter generationRouter,
                                                                   SemanticMemory semanticMemory,
                                                                   KnowledgeBaseProperties props,
                                                                   PromptRegistry promptRegistry) {
        return new KnowledgeExtractionPipeline(generationRouter, semanticMemory, props.extraction(), promptRegistry);
    }

    // ---- 查询增强 ----

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.knowledge.query-enhancer", name = "mode",
            matchIfMissing = false)
    public QueryEnhancer queryEnhancer(GenerationRouter generationRouter,
                                       EmbeddingRouter embeddingRouter,
                                       KnowledgeBaseProperties props,
                                       PromptRegistry promptRegistry) {
        if ("none".equals(props.queryEnhancer().mode())) {
            return null;
        }
        return new QueryEnhancer(generationRouter, embeddingRouter, props.queryEnhancer(), promptRegistry);
    }

    // ---- 检索服务 ----

    @Bean
    @ConditionalOnMissingBean
    public DocumentRetriever documentRetriever(@Nullable VectorIndexer vectorIndexer,
                                               FtsIndexer ftsIndexer,
                                               @Nullable RerankRouter rerankRouter,
                                               @Nullable QueryEnhancer queryEnhancer,
                                               DocumentChunkRepository chunkRepository,
                                               KnowledgeBaseRepository kbRepository,
                                               KnowledgeBaseProperties props) {
        return new DocumentRetriever(
                vectorIndexer,
                ftsIndexer,
                rerankRouter,
                queryEnhancer,
                chunkRepository,
                kbRepository,
                props.retrieval()
        );
    }

    // ---- 导入管线 ----

    @Bean
    @ConditionalOnMissingBean
    public DocumentIngester documentIngester(FormatDetector formatDetector,
                                             SmartChunker smartChunker,
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
                                             ChunkingConfig chunkingConfig) {
        var registry = new HashMap<String, ChunkingStrategy>();
        registry.put(fixedSizeChunker.strategyName(), fixedSizeChunker);
        registry.put(recursiveChunker.strategyName(), recursiveChunker);
        registry.put(headingChunker.strategyName(), headingChunker);
        registry.put(smartChunker.strategyName(), smartChunker);
        if (semanticChunker != null) {
            registry.put(semanticChunker.strategyName(), semanticChunker);
        }
        log.info("分块器注册表: {}", registry.keySet());
        return new DocumentIngester(
                formatDetector,
                smartChunker,
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
                chunkingConfig
        );
    }

    // ---- 管理服务 ----

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBaseManager knowledgeBaseManager(KnowledgeBaseRepository kbRepository,
                                                     DocumentRepository documentRepository,
                                                     DocumentChunkRepository chunkRepository,
                                                     @Nullable VectorIndexer vectorIndexer,
                                                     FtsIndexer ftsIndexer) {
        log.info("知识库模块初始化完成");
        return new KnowledgeBaseManager(
                kbRepository,
                documentRepository,
                chunkRepository,
                vectorIndexer,
                ftsIndexer
        );
    }
}

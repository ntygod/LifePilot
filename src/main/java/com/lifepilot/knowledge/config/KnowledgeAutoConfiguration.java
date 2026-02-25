package com.lifepilot.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.chunking.*;
import com.lifepilot.knowledge.detect.DuplicateDetector;
import com.lifepilot.knowledge.enricher.ChunkContextEnricher;
import com.lifepilot.knowledge.extract.KnowledgeExtractionPipeline;
import com.lifepilot.knowledge.index.FtsIndexer;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.knowledge.parser.*;
import com.lifepilot.knowledge.rerank.ApiReranker;
import com.lifepilot.knowledge.rerank.LlmReranker;
import com.lifepilot.knowledge.rerank.Reranker;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.semantic.SemanticMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 知识库模块 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.knowledge.enabled=true}（默认）激活，
 * 注册解析器、分块器、索引器、检索器、导入管线等全部 Bean。
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
    public FormatDetector formatDetector(MarkdownParser markdownParser, PlainTextParser plainTextParser,
                                         PdfParser pdfParser, WordParser wordParser) {
        return new FormatDetector(List.of(markdownParser, plainTextParser, pdfParser, wordParser));
    }

    // ---- 分块器 ----

    @Bean
    @ConditionalOnMissingBean
    public ChunkingConfig chunkingConfig(KnowledgeBaseProperties props) {
        var fs = props.chunking().fixedSize();
        return new ChunkingConfig(
                fs.chunkSize(), fs.minChunkSize(), fs.overlapSize(),
                fs.maxChunkTokens(), fs.respectSentences(),
                true,   // respectParagraphs
                true    // enableContextPrefix
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public FixedSizeChunker fixedSizeChunker(ChunkingConfig chunkingConfig) {
        return new FixedSizeChunker(chunkingConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public RecursiveChunker recursiveChunker(ChunkingConfig chunkingConfig, KnowledgeBaseProperties props) {
        return new RecursiveChunker(chunkingConfig, props.chunking().recursive());
    }

    @Bean
    @ConditionalOnMissingBean
    public HeadingChunker headingChunker(ChunkingConfig chunkingConfig, RecursiveChunker recursiveChunker,
                                         KnowledgeBaseProperties props) {
        return new HeadingChunker(chunkingConfig, recursiveChunker, props.chunking().heading());
    }

    @Bean
    @ConditionalOnMissingBean
    public SmartChunker smartChunker(FixedSizeChunker fixedSizeChunker, RecursiveChunker recursiveChunker,
                                     HeadingChunker headingChunker, KnowledgeBaseProperties props) {
        return new SmartChunker(fixedSizeChunker, recursiveChunker, headingChunker,
                props.chunking().smartChunker());
    }

    // ---- Repository ----

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBaseRepository knowledgeBaseRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new KnowledgeBaseRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentRepository documentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new DocumentRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentChunkRepository documentChunkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new DocumentChunkRepository(jdbcTemplate, objectMapper);
    }

    // ---- 索引服务 ----

    @Bean
    @ConditionalOnMissingBean
    public VectorIndexer vectorIndexer(LlmRouter llmRouter, JdbcTemplate jdbcTemplate,
                                       KnowledgeBaseProperties props) {
        return new VectorIndexer(llmRouter, jdbcTemplate, props.vectorIndexer());
    }

    @Bean
    @ConditionalOnMissingBean
    public FtsIndexer ftsIndexer(JdbcTemplate jdbcTemplate) {
        return new FtsIndexer(jdbcTemplate);
    }

    // ---- 重复检测 ----

    @Bean
    @ConditionalOnMissingBean
    public DuplicateDetector duplicateDetector(DocumentRepository docRepository) {
        return new DuplicateDetector(docRepository);
    }

    // ---- 上下文增强 ----

    @Bean
    @ConditionalOnMissingBean
    public ChunkContextEnricher chunkContextEnricher(LlmRouter llmRouter, KnowledgeBaseProperties props) {
        return new ChunkContextEnricher(llmRouter, props.contextEnricher());
    }

    // ---- 知识提取 ----

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeExtractionPipeline knowledgeExtractionPipeline(LlmRouter llmRouter,
                                                                    SemanticMemory semanticMemory,
                                                                    KnowledgeBaseProperties props) {
        return new KnowledgeExtractionPipeline(llmRouter, semanticMemory, props.extraction());
    }

    // ---- Reranker（可选） ----

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.knowledge.reranker", name = "enabled",
            havingValue = "true")
    public Reranker reranker(LlmRouter llmRouter, KnowledgeBaseProperties props) {
        var rerankerConfig = props.reranker();
        return switch (rerankerConfig.type()) {
            case "api" -> new ApiReranker(rerankerConfig);
            default -> new LlmReranker(llmRouter);
        };
    }

    // ---- 检索服务 ----

    @Bean
    @ConditionalOnMissingBean
    public DocumentRetriever documentRetriever(VectorIndexer vectorIndexer, FtsIndexer ftsIndexer,
                                                Optional<Reranker> reranker,
                                                KnowledgeBaseProperties props) {
        return new DocumentRetriever(vectorIndexer, ftsIndexer, reranker, props.retrieval());
    }

    // ---- 导入管线 ----

    @Bean
    @ConditionalOnMissingBean
    public DocumentIngester documentIngester(FormatDetector formatDetector, SmartChunker smartChunker,
                                              ChunkContextEnricher contextEnricher,
                                              VectorIndexer vectorIndexer, FtsIndexer ftsIndexer,
                                              DuplicateDetector duplicateDetector,
                                              KnowledgeExtractionPipeline extractionPipeline,
                                              DocumentRepository docRepository,
                                              DocumentChunkRepository chunkRepository,
                                              ApplicationEventPublisher eventPublisher,
                                              KnowledgeBaseProperties props) {
        return new DocumentIngester(formatDetector, smartChunker, contextEnricher,
                vectorIndexer, ftsIndexer, duplicateDetector, extractionPipeline,
                docRepository, chunkRepository, eventPublisher, props);
    }

    // ---- 管理服务 ----

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBaseManager knowledgeBaseManager(KnowledgeBaseRepository kbRepository,
                                                     DocumentRepository docRepository,
                                                     DocumentChunkRepository chunkRepository) {
        log.info("知识库模块初始化完成");
        return new KnowledgeBaseManager(kbRepository, docRepository, chunkRepository);
    }
}

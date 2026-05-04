package com.lifepilot.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.chunking.ChunkingConfig;
import com.lifepilot.knowledge.chunking.ChunkMerger;
import com.lifepilot.knowledge.chunking.DocumentStructureAnalyzer;
import com.lifepilot.knowledge.chunking.FixedSizeChunker;
import com.lifepilot.knowledge.chunking.HeadingChunker;
import com.lifepilot.knowledge.chunking.RecursiveChunker;
import com.lifepilot.knowledge.chunking.RegionChunkingRouter;
import com.lifepilot.knowledge.detect.DuplicateDetector;
import com.lifepilot.knowledge.util.TokenCounter;
import com.lifepilot.knowledge.index.FtsIndexer;
import com.lifepilot.knowledge.parser.FormatDetector;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.knowledge.parser.PdfParser;
import com.lifepilot.knowledge.parser.PlainTextParser;
import com.lifepilot.knowledge.parser.WordParser;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.SessionKnowledgeScopeResolver;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 知识库核心自动配置。
 *
 * <p>仅注册不依赖外部模块装配顺序的核心 Bean：解析器、基础分块器、
 * Repository、FTS 索引与基础领域服务。跨模块增强能力和运行时编排
 * 由独立的后置自动配置负责，避免 {@code @ConditionalOnBean} 在错误时机失效。
 *
 * @author zsg
 * @since 2026-02-25
 */
@AutoConfiguration
@EnableConfigurationProperties(KnowledgeBaseProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.knowledge", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KnowledgeAutoConfiguration {

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
    public FixedSizeChunker fixedSizeChunker(ChunkingConfig chunkingConfig,
                                                TokenCounter tokenCounter) {
        return new FixedSizeChunker(chunkingConfig, tokenCounter);
    }

    @Bean
    @ConditionalOnMissingBean
    public RecursiveChunker recursiveChunker(KnowledgeBaseProperties props,
                                                TokenCounter tokenCounter) {
        return new RecursiveChunker(props.chunking().recursive(), tokenCounter);
    }

    @Bean
    @ConditionalOnMissingBean
    public HeadingChunker headingChunker(RecursiveChunker recursiveChunker,
                                         KnowledgeBaseProperties props,
                                         TokenCounter tokenCounter) {
        return new HeadingChunker(recursiveChunker, props.chunking().heading(), tokenCounter);
    }

    // ---- 三层分块架构 ----

    @Bean
    @ConditionalOnMissingBean
    public DocumentStructureAnalyzer documentStructureAnalyzer(KnowledgeBaseProperties props) {
        var config = props.chunking().structureAnalysis();
        return new DocumentStructureAnalyzer(config.maxHeadingLength(), config.minCodeIndent(), config.minTableColumns());
    }

    @Bean
    @ConditionalOnMissingBean
    public RegionChunkingRouter regionChunkingRouter(
            RecursiveChunker recursiveChunker,
            FixedSizeChunker fixedSizeChunker,
            KnowledgeBaseProperties props,
            TokenCounter tokenCounter) {
        var config = props.chunking().regionRouting();
        return new RegionChunkingRouter(recursiveChunker, fixedSizeChunker, tokenCounter,
                config.maxIntactCodeSize(), config.maxIntactTableSize(), config.maxIntactListSize(),
                config.paragraphMinForRecursive(), props.chunking().recursive().maxChunkSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public ChunkMerger chunkMerger(KnowledgeBaseProperties props, TokenCounter tokenCounter) {
        var r = props.chunking().recursive();
        return new ChunkMerger(r.maxChunkSize(), r.minChunkSize(), r.overlapSize(), tokenCounter);
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
    public FtsIndexer ftsIndexer(JdbcTemplate jdbcTemplate) {
        return new FtsIndexer(jdbcTemplate);
    }

    // ---- 重复检测 ----

    @Bean
    @ConditionalOnMissingBean
    public DuplicateDetector duplicateDetector(DocumentRepository documentRepository) {
        return new DuplicateDetector(documentRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionKnowledgeScopeResolver sessionKnowledgeScopeResolver(
            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository) {
        return new SessionKnowledgeScopeResolver(sessionKnowledgeBaseRepository);
    }

}

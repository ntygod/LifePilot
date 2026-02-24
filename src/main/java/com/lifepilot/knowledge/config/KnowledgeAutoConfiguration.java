package com.lifepilot.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.chunking.ChunkingConfig;
import com.lifepilot.knowledge.chunking.FixedSizeChunker;
import com.lifepilot.knowledge.parser.FormatDetector;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.knowledge.parser.PlainTextParser;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 知识库模块 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.knowledge.enabled=true}（默认）激活，
 * 注册解析器、分块器、Repository 和管理服务等 Bean。
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
    public FormatDetector formatDetector(MarkdownParser markdownParser, PlainTextParser plainTextParser) {
        return new FormatDetector(List.of(markdownParser, plainTextParser));
    }

    @Bean
    @ConditionalOnMissingBean
    public ChunkingConfig chunkingConfig(KnowledgeBaseProperties props) {
        var fs = props.chunking().fixedSize();
        return new ChunkingConfig(
                fs.chunkSize(),
                fs.minChunkSize(),
                fs.overlapSize(),
                fs.maxChunkTokens(),
                fs.respectSentences(),
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

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBaseManager knowledgeBaseManager(KnowledgeBaseRepository kbRepository,
                                                     DocumentRepository docRepository,
                                                     DocumentChunkRepository chunkRepository) {
        log.info("知识库模块初始化完成");
        return new KnowledgeBaseManager(kbRepository, docRepository, chunkRepository);
    }
}

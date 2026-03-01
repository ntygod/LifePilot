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
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * KnowledgeAutoConfiguration 条件化 Bean 注册测试。
 *
 * <p>使用 {@link ApplicationContextRunner} 验证知识库模块在不同配置下的
 * Bean 注册行为：默认启用、显式禁用、自定义属性覆盖。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
class KnowledgeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KnowledgeAutoConfiguration.class))
            .withUserConfiguration(InfraBeansConfig.class);

    @Test
    void 默认配置下注册所有Bean() {
        contextRunner.run(context -> {
            // 解析器
            assertThat(context).hasSingleBean(MarkdownParser.class);
            assertThat(context).hasSingleBean(PlainTextParser.class);
            assertThat(context).hasSingleBean(FormatDetector.class);
            // 分块
            assertThat(context).hasSingleBean(ChunkingConfig.class);
            assertThat(context).hasSingleBean(FixedSizeChunker.class);
            // Repository
            assertThat(context).hasSingleBean(KnowledgeBaseRepository.class);
            assertThat(context).hasSingleBean(DocumentRepository.class);
            assertThat(context).hasSingleBean(DocumentChunkRepository.class);
            // 管理服务
            assertThat(context).hasSingleBean(KnowledgeBaseManager.class);
        });
    }

    @Test
    void 显式禁用时不注册任何Bean() {
        contextRunner
                .withPropertyValues("lifepilot.knowledge.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MarkdownParser.class);
                    assertThat(context).doesNotHaveBean(PlainTextParser.class);
                    assertThat(context).doesNotHaveBean(FormatDetector.class);
                    assertThat(context).doesNotHaveBean(ChunkingConfig.class);
                    assertThat(context).doesNotHaveBean(FixedSizeChunker.class);
                    assertThat(context).doesNotHaveBean(KnowledgeBaseRepository.class);
                    assertThat(context).doesNotHaveBean(DocumentRepository.class);
                    assertThat(context).doesNotHaveBean(DocumentChunkRepository.class);
                    assertThat(context).doesNotHaveBean(KnowledgeBaseManager.class);
                });
    }

    @Test
    void ChunkingConfig_从配置属性正确构建() {
        contextRunner
                .withPropertyValues(
                        "lifepilot.knowledge.chunking.fixed-size.chunk-size=2048",
                        "lifepilot.knowledge.chunking.fixed-size.min-chunk-size=200",
                        "lifepilot.knowledge.chunking.fixed-size.overlap-size=256",
                        "lifepilot.knowledge.chunking.fixed-size.max-chunk-tokens=1024",
                        "lifepilot.knowledge.chunking.fixed-size.respect-sentences=false"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ChunkingConfig.class);
                    var config = context.getBean(ChunkingConfig.class);
                    assertThat(config.maxChunkSize()).isEqualTo(2048);
                    assertThat(config.minChunkSize()).isEqualTo(200);
                    assertThat(config.overlapSize()).isEqualTo(256);
                    assertThat(config.maxChunkTokens()).isEqualTo(1024);
                    assertThat(config.respectSentences()).isFalse();
                });
    }

    @Test
    void ChunkingConfig_默认值正确() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChunkingConfig.class);
            var config = context.getBean(ChunkingConfig.class);
            assertThat(config.maxChunkSize()).isEqualTo(1024);
            assertThat(config.minChunkSize()).isEqualTo(100);
            assertThat(config.overlapSize()).isEqualTo(128);
            assertThat(config.maxChunkTokens()).isEqualTo(512);
            assertThat(config.respectSentences()).isTrue();
        });
    }

    @Test
    void 自定义Bean覆盖默认实现() {
        contextRunner
                .withUserConfiguration(CustomKnowledgeBeansConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(KnowledgeBaseManager.class);
                    assertThat(context).hasBean("customKnowledgeBaseManager");
                });
    }

    /**
     * 基础设施 Mock Bean 配置。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class InfraBeansConfig {
        @Bean(name = "knowledgeTestJdbcTemplate")
        JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
        @Bean(name = "knowledgeTestObjectMapper")
        ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    /**
     * 用户自定义 KnowledgeBaseManager Bean。
     */
    @Configuration
    static class CustomKnowledgeBeansConfig {
        @Bean
        KnowledgeBaseManager customKnowledgeBaseManager() {
            return mock(KnowledgeBaseManager.class);
        }
    }
}

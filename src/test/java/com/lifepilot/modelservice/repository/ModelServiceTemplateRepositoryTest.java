package com.lifepilot.modelservice.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型服务模板仓储集成测试。
 *
 * @author zsg
 * @since 2026-03-30
 */
@SpringBootTest(classes = ModelServiceTemplateRepositoryTest.TestApp.class)
@ActiveProfiles("test")
class ModelServiceTemplateRepositoryTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {
            org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class
    })
    @Import(com.lifepilot.config.DataSourceConfig.class)
    static class TestApp {
    }

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-model-template-test-" + DB_ID).toString().replace("\\", "/") + ".db";
        var vecDbPath = Path.of(tmpDir, "lifepilot-model-template-vec-test-" + DB_ID).toString().replace("\\", "/") + ".db";
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private ModelServiceTemplateRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ModelServiceTemplateRepository(jdbcTemplate, objectMapper);
    }

    @Test
    void findAll_返回数据库中的厂商模板与模型预设() {
        var templates = repository.findAll();

        assertThat(templates)
                .extracting(template -> template.vendorKey())
                .contains("openai", "anthropic", "deepseek", "qwen", "custom-openai", "ollama", "tei");
        assertThat(templates)
                .anySatisfy(template -> {
                    assertThat(template.vendorKey()).isEqualTo("openai");
                    assertThat(template.providerType()).isEqualTo("OPENAI_COMPATIBLE");
                    assertThat(template.modelOptions())
                            .extracting(option -> option.value())
                            .contains("gpt-5.4", "text-embedding-3-large");
                });
        assertThat(repository.existsByVendorKey("qwen")).isTrue();
        assertThat(repository.existsByVendorKey("missing-vendor")).isFalse();
    }
}

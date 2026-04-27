package com.lifepilot.modelservice.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.thinking.ThinkingMode;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型服务仓储集成测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
@SpringBootTest(classes = ModelServiceRepositoryTest.TestApp.class)
@ActiveProfiles("test")
class ModelServiceRepositoryTest {

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
        var dbPath = Path.of(tmpDir, "lifepilot-model-service-test-" + DB_ID).toString().replace("\\", "/") + ".db";
        var vecDbPath = Path.of(tmpDir, "lifepilot-model-service-vec-test-" + DB_ID).toString().replace("\\", "/") + ".db";
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private ModelServiceRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM model_services");
        repository = new ModelServiceRepository(jdbcTemplate, objectMapper);
    }

    @Test
    void findById_初始化时不再注入预置模型服务() {
        assertThat(repository.findAll()).isEmpty();
        assertThat(repository.findById("ollama-qwen2.5")).isEmpty();
    }

    @Test
    void findByKind_能区分生成与向量服务() {
        repository.save(new ModelServiceEntity(
                "generation-main",
                ModelServiceKind.GENERATION,
                "openai-official",
                "https://api.openai.com/v1",
                null,
                "gpt-5.4",
                60,
                0,
                true,
                false,
                ThinkingMode.AUTO,
                java.util.List.of("chat"),
                Set.of(GenerationCapability.CHAT, GenerationCapability.STREAMING),
                Map.of("vendorKey", "openai"),
                "主生成服务",
                "测试生成服务"));
        repository.save(new ModelServiceEntity(
                "embedding-main",
                ModelServiceKind.EMBEDDING,
                "tei-local",
                "http://localhost:8080/v1",
                null,
                "text-embedding-v4",
                30,
                0,
                true,
                false,
                ThinkingMode.AUTO,
                java.util.List.of(),
                Set.of(),
                Map.of("embeddingDimension", 1024),
                "主向量服务",
                "测试向量服务"));

        var generation = repository.findByKind(ModelServiceKind.GENERATION);
        var embedding = repository.findByKind(ModelServiceKind.EMBEDDING);

        assertThat(generation).hasSize(1);
        assertThat(embedding).hasSize(1);
        assertThat(generation).allMatch(service -> service.kind() == ModelServiceKind.GENERATION);
        assertThat(embedding).allMatch(service -> service.kind() == ModelServiceKind.EMBEDDING);
    }

    @Test
    void save_roundTrip_可写入自定义精排服务() {
        String id = "custom-rerank-" + UUID.randomUUID();
        var entity = new ModelServiceEntity(
                id,
                ModelServiceKind.RERANK,
                "tei-local",
                "http://localhost:8082",
                null,
                "bge-reranker-v2-m3",
                20,
                0,
                true,
                false,
                ThinkingMode.AUTO,
                java.util.List.of(),
                Set.of(),
                Map.of("path", "/rerank"),
                "本地 BGE 精排",
                "测试写入的精排服务");

        repository.save(entity);

        var found = repository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().kind()).isEqualTo(ModelServiceKind.RERANK);
        assertThat(found.get().profileId()).isEqualTo("tei-local");
        assertThat(found.get().metadata()).containsEntry("path", "/rerank");
        assertThat(repository.findEnabledByKind(ModelServiceKind.RERANK))
                .extracting(ModelServiceEntity::id)
                .contains(id);
    }
}

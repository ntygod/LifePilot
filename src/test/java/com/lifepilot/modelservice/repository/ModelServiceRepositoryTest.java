package com.lifepilot.modelservice.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.config.ProviderType;
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
        repository = new ModelServiceRepository(jdbcTemplate, objectMapper);
    }

    @Test
    void findById_能读取内置生成服务() {
        var found = repository.findById("ollama-qwen2.5");

        assertThat(found).isPresent();
        assertThat(found.get().kind()).isEqualTo(ModelServiceKind.GENERATION);
        assertThat(found.get().modelName()).isEqualTo("qwen3:8b");
        assertThat(found.get().generationCapabilities()).contains(GenerationCapability.CHAT);
        assertThat(found.get().supportedScenes()).contains("chat");
    }

    @Test
    void findByKind_能区分生成与向量服务() {
        var generation = repository.findByKind(ModelServiceKind.GENERATION);
        var embedding = repository.findByKind(ModelServiceKind.EMBEDDING);

        assertThat(generation).isNotEmpty();
        assertThat(embedding).isNotEmpty();
        assertThat(generation).allMatch(service -> service.kind() == ModelServiceKind.GENERATION);
        assertThat(embedding).allMatch(service -> service.kind() == ModelServiceKind.EMBEDDING);
    }

    @Test
    void save_roundTrip_可写入自定义精排服务() {
        String id = "custom-rerank-" + UUID.randomUUID();
        var entity = new ModelServiceEntity(
                id,
                ModelServiceKind.RERANK,
                ProviderType.TEI,
                "http://localhost:8082",
                null,
                "bge-reranker-v2-m3",
                20,
                0,
                true,
                java.util.List.of(),
                Set.of(),
                Map.of("path", "/rerank"),
                "本地 BGE 精排",
                "测试写入的精排服务");

        repository.save(entity);

        var found = repository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().kind()).isEqualTo(ModelServiceKind.RERANK);
        assertThat(found.get().providerType()).isEqualTo(ProviderType.TEI);
        assertThat(found.get().metadata()).containsEntry("path", "/rerank");
        assertThat(repository.findEnabledByKind(ModelServiceKind.RERANK))
                .extracting(ModelServiceEntity::id)
                .contains(id);
    }
}

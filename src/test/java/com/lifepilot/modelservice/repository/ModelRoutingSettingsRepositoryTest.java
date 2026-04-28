package com.lifepilot.modelservice.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.modelservice.model.EmbeddingSettingsEntity;
import com.lifepilot.modelservice.model.GenerationSettingsEntity;
import com.lifepilot.modelservice.model.RerankExecutionMode;
import com.lifepilot.modelservice.model.RerankSettingsEntity;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型路由设置仓储集成测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
@SpringBootTest(classes = ModelRoutingSettingsRepositoryTest.TestApp.class)
@ActiveProfiles("test")
class ModelRoutingSettingsRepositoryTest {

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
        var dbPath = Path.of(tmpDir, "lifepilot-model-routing-settings-test-" + DB_ID)
                .toString().replace("\\", "/") + ".db";
        var vecDbPath = Path.of(tmpDir, "lifepilot-model-routing-settings-vec-test-" + DB_ID)
                .toString().replace("\\", "/") + ".db";
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private GenerationSettingsRepository generationSettingsRepository;
    private EmbeddingSettingsRepository embeddingSettingsRepository;
    private RerankSettingsRepository rerankSettingsRepository;

    @BeforeEach
    void setUp() {
        generationSettingsRepository = new GenerationSettingsRepository(jdbcTemplate, objectMapper);
        embeddingSettingsRepository = new EmbeddingSettingsRepository(jdbcTemplate);
        rerankSettingsRepository = new RerankSettingsRepository(jdbcTemplate);

        upsertService("gen-main", "GENERATION", "openai-official", "https://api.openai.com/v1", "gpt-5.4");
        upsertService("gen-agent", "GENERATION", "deepseek-official", "https://api.deepseek.com/v1", "deepseek-chat");
        upsertService("embed-main", "EMBEDDING", "tei-local", "http://localhost:8080/v1", "text-embedding-v4");
        upsertService("embed-memory", "EMBEDDING", "ollama-local", "http://localhost:11434", "nomic-embed-text");

        jdbcTemplate.update("""
                INSERT INTO model_services (
                    id, kind, profile_id, api_url, api_key, model_name, timeout_seconds, priority, enabled,
                    is_reasoning, thinking_mode,
                    supported_scenes_json, generation_capabilities_json, metadata_json,
                    display_name, description, created_at, updated_at
                ) VALUES (?, 'RERANK', 'tei-local', ?, NULL, ?, 20, 0, 1, 0, 'AUTO', '[]', '[]', ?, ?, ?, datetime('now'), datetime('now'))
                ON CONFLICT(id) DO UPDATE SET
                    profile_id = excluded.profile_id,
                    api_url = excluded.api_url,
                    model_name = excluded.model_name,
                    enabled = excluded.enabled,
                    metadata_json = excluded.metadata_json,
                    display_name = excluded.display_name,
                    description = excluded.description,
                    updated_at = excluded.updated_at
                """,
                "local-bge-reranker",
                "http://localhost:8082",
                "bge-reranker-v2-m3",
                "{\"path\":\"/rerank\"}",
                "本地 BGE 精排",
                "测试精排服务");

        jdbcTemplate.update("""
                UPDATE generation_settings
                SET default_service_id = ?, scene_service_bindings_json = ?, updated_at = datetime('now')
                WHERE id = ?
                """,
                "gen-main",
                "{}",
                GenerationSettingsRepository.DEFAULT_ID);
        jdbcTemplate.update("""
                UPDATE embedding_settings
                SET default_service_id = ?, knowledge_base_service_id = ?, memory_service_id = ?, updated_at = datetime('now')
                WHERE id = ?
                """,
                "embed-main",
                "embed-main",
                "embed-memory",
                EmbeddingSettingsRepository.DEFAULT_ID);
        jdbcTemplate.update("""
                UPDATE rerank_settings
                SET enabled = 0, mode = 'DISABLED', native_service_id = NULL, llm_service_id = ?,
                    knowledge_top_k = 5, memory_enabled = 1, memory_top_k = 10, updated_at = datetime('now')
                WHERE id = ?
                """,
                "gen-main",
                RerankSettingsRepository.DEFAULT_ID);
    }

    @Test
    void generationSettings_支持默认服务与场景绑定() {
        generationSettingsRepository.save(new GenerationSettingsEntity(
                GenerationSettingsRepository.DEFAULT_ID,
                "gen-agent",
                Map.of("chat", "gen-agent", "agent_react", "gen-main")));

        var found = generationSettingsRepository.findDefault();
        assertThat(found).isPresent();
        assertThat(found.get().defaultServiceId()).isEqualTo("gen-agent");
        assertThat(found.get().sceneServiceBindings())
                .containsEntry("chat", "gen-agent")
                .containsEntry("agent_react", "gen-main");
    }

    @Test
    void embeddingSettings_支持知识库与记忆独立绑定() {
        embeddingSettingsRepository.save(new EmbeddingSettingsEntity(
                EmbeddingSettingsRepository.DEFAULT_ID,
                "embed-main",
                "embed-main",
                "embed-memory"));

        var found = embeddingSettingsRepository.findDefault();
        assertThat(found).isPresent();
        assertThat(found.get().defaultServiceId()).isEqualTo("embed-main");
        assertThat(found.get().knowledgeBaseServiceId()).isEqualTo("embed-main");
        assertThat(found.get().memoryServiceId()).isEqualTo("embed-memory");
    }

    @Test
    void rerankSettings_支持模式化配置() {
        rerankSettingsRepository.save(new RerankSettingsEntity(
                RerankSettingsRepository.DEFAULT_ID,
                true,
                RerankExecutionMode.NATIVE,
                "local-bge-reranker",
                null,
                8,
                true,
                12));

        var found = rerankSettingsRepository.findDefault();
        assertThat(found).isPresent();
        assertThat(found.get().enabled()).isTrue();
        assertThat(found.get().mode()).isEqualTo(RerankExecutionMode.NATIVE);
        assertThat(found.get().nativeServiceId()).isEqualTo("local-bge-reranker");
        assertThat(found.get().knowledgeTopK()).isEqualTo(8);
        assertThat(found.get().memoryTopK()).isEqualTo(12);
    }

    private void upsertService(String id, String kind, String profileId, String apiUrl, String modelName) {
        jdbcTemplate.update("""
                INSERT INTO model_services (
                    id, kind, profile_id, api_url, api_key, model_name, timeout_seconds, priority, enabled,
                    is_reasoning, thinking_mode,
                    supported_scenes_json, generation_capabilities_json, metadata_json,
                    display_name, description, created_at, updated_at
                ) VALUES (?, ?, ?, ?, NULL, ?, 30, 0, 1, 0, 'AUTO', '[]', '[]', '{}', ?, ?, datetime('now'), datetime('now'))
                ON CONFLICT(id) DO UPDATE SET
                    kind = excluded.kind,
                    profile_id = excluded.profile_id,
                    api_url = excluded.api_url,
                    model_name = excluded.model_name,
                    display_name = excluded.display_name,
                    description = excluded.description,
                    updated_at = excluded.updated_at
                """,
                id,
                kind,
                profileId,
                apiUrl,
                modelName,
                id,
                "测试模型服务");
    }
}

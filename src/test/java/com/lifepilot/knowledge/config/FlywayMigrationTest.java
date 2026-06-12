package com.lifepilot.knowledge.config;

import com.lifepilot.tool.config.ToolConfigProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V15 迁移脚本集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 验证迁移脚本执行成功，
 * 表和索引正确创建。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableConfigurationProperties(ToolConfigProperties.class)
class FlywayMigrationTest {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-flyway-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-flyway-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.store.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void V15迁移_knowledge_bases表存在() {
        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='knowledge_bases'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void V15迁移_documents表存在() {
        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='documents'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void V15迁移_document_chunks表存在() {
        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='document_chunks'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void V15迁移_proactive_reminder_replay_reports表存在() {
        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='proactive_reminder_replay_reports'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void V15迁移_proactive_reminder_topic_aliases表存在() {
        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='proactive_reminder_topic_aliases'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void V15迁移_proactive_reminder_inferred_outcomes包含attribution字段() {
        var columns = jdbcTemplate.queryForList("PRAGMA table_info('proactive_reminder_inferred_outcomes')");
        assertThat(columns).anyMatch(column -> "attribution_score".equals(column.get("name")));
    }

    @Test
    void V15迁移_所有索引存在() {
        var expectedIndexes = List.of(
                "idx_knowledge_bases_name",
                "idx_documents_kb_id",
                "idx_documents_status",
                "idx_documents_content_hash",
                "idx_document_chunks_doc_id",
                "idx_document_chunks_kb_id",
                "idx_document_chunks_hash",
                "idx_proactive_reminder_decisions_action_next_eval",
                "idx_proactive_reminder_decisions_policy_version",
                "idx_proactive_reminder_policy_traces_final_action",
                "idx_proactive_reminder_policy_versions_user_version",
                "idx_proactive_reminder_policy_versions_signature",
                "idx_proactive_reminder_inferred_outcomes_topic",
                "idx_proactive_reminder_inferred_outcomes_decision",
                "idx_proactive_reminder_replay_reports_user_generated_at",
                "idx_proactive_reminder_topic_aliases_canonical"
        );

        var actualIndexes = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'idx_%'",
                String.class);

        assertThat(actualIndexes).containsAll(expectedIndexes);
    }

    @Test
    void V15迁移_knowledge_bases表结构正确() {
        // 验证可以执行 INSERT + SELECT，确认列存在
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases (id, name, description, embedding_model, reranker_model,
                    chunking_strategy, chunking_config_json, document_count, total_chunks, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "test-id", "测试", "描述", "model", null, "smart", "{}", 0, 0,
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");

        var name = jdbcTemplate.queryForObject(
                "SELECT name FROM knowledge_bases WHERE id = ?", String.class, "test-id");
        assertThat(name).isEqualTo("测试");

        // 清理
        jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", "test-id");
    }

    @Test
    void V15迁移_documents外键级联删除() {
        // 插入知识库
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases (id, name, description, embedding_model, chunking_strategy,
                    chunking_config_json, document_count, total_chunks, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "kb-fk-test", "FK测试", "描述", "model", "smart", "{}", 0, 0,
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");

        // 插入文档
        jdbcTemplate.update("""
                INSERT INTO documents (id, knowledge_base_id, file_name, file_path, file_size, mime_type,
                    content_hash, status, chunk_count, entity_count, metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "doc-fk-test", "kb-fk-test", "test.md", "/path", 100, "text/markdown",
                "hash", "READY", 0, 0, "{}", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");

        // 删除知识库，验证文档级联删除
        jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", "kb-fk-test");

        var docCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE id = ?", Integer.class, "doc-fk-test");
        assertThat(docCount).isZero();
    }
}

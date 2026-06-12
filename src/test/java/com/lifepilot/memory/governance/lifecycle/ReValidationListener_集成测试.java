package com.lifepilot.memory.governance.lifecycle;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.governance.lifecycle.events.SourceInvalidated;
import com.lifepilot.memory.governance.lifecycle.feedback.RevalidationQueueRepository;
import com.lifepilot.memory.governance.lifecycle.listeners.ReValidationListener;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReValidationListener} 集成测试 —— 用 Flyway 真跑一次 V15 迁移，插入 provenance
 * 样本，触发 listener，断言 {@code memory_revalidation_queue} 行数符合预期。
 *
 * <p>简化方案：绕过 Spring 容器，测试内部用 JdbcTemplate 直接 INSERT provenance 行
 * （及其前置的 memory_spaces / memory_entities / memory_entity_versions 最小骨架），
 * 然后手动实例化 listener + 真实 Repository。这样验证"SQL 对得上 V15 schema"的同时
 * 避开了完整 @SpringBootTest 的启动开销。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("ReValidationListener 集成测试")
class ReValidationListener_集成测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final String SPACE_ID = "space-测试";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private MemoryProvenanceRepository provenanceRepo;
    private RevalidationQueueRepository queueRepo;
    private ReValidationListener listener;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-revalidation-" + dbId + ".db");
        Files.deleteIfExists(dbPath);
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");

        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new SingleConnectionDataSource(jdbcUrl, true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        provenanceRepo = new MemoryProvenanceRepository(jdbcTemplate);
        queueRepo = new RevalidationQueueRepository(jdbcTemplate);
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        listener = new ReValidationListener(provenanceRepo, queueRepo, clock);

        插入记忆空间(SPACE_ID);
    }

    @AfterEach
    void 清理() throws Exception {
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (dbPath != null) {
            Files.deleteIfExists(dbPath);
        }
    }

    @Test
    void DOCUMENT失效应为每个引用实体入队() {
        插入实体("entity-1");
        插入实体("entity-2");
        插入Provenance("entity-1", "doc-1");
        插入Provenance("entity-2", "doc-1");
        // 干扰项：一个引用其他文档的 provenance，不应进入 doc-1 的队列
        插入实体("entity-3");
        插入Provenance("entity-3", "doc-2");

        listener.onSourceInvalidated(new SourceInvalidated(
                SourceType.DOCUMENT, "doc-1", InvalidationKind.DELETED));

        assertThat(queueRepo.countPendingBySource(SourceType.DOCUMENT, "doc-1"))
                .as("doc-1 下应为 entity-1 / entity-2 各入一条 PENDING")
                .isEqualTo(2);
        assertThat(queueRepo.countPendingBySource(SourceType.DOCUMENT, "doc-2"))
                .as("doc-2 未失效，不应产生队列条目")
                .isZero();

        // 额外断言：入队的 entity_id 与插入的 provenance 一致
        var ids = jdbcTemplate.queryForList(
                """
                SELECT entity_id FROM memory_revalidation_queue
                WHERE source_type = 'DOCUMENT' AND source_id = 'doc-1'
                ORDER BY entity_id
                """,
                String.class);
        assertThat(ids).containsExactly("entity-1", "entity-2");
    }

    @Test
    void 无引用实体时不入队() {
        listener.onSourceInvalidated(new SourceInvalidated(
                SourceType.DOCUMENT, "doc-empty", InvalidationKind.DELETED));

        assertThat(queueRepo.countPendingBySource(SourceType.DOCUMENT, "doc-empty"))
                .isZero();
    }

    @Test
    void 同一实体多个provenance记录只入队两条() {
        插入实体("entity-多引用");
        // 同一实体对同一文档有两条 provenance（不同 origin_type 或 version），
        // 期望 findEntityIdsBySource 返回 DISTINCT —— 入队一条即可
        插入Provenance("entity-多引用", "doc-5");
        插入Provenance("entity-多引用", "doc-5");

        listener.onSourceInvalidated(new SourceInvalidated(
                SourceType.DOCUMENT, "doc-5", InvalidationKind.CONTENT_CHANGED));

        assertThat(queueRepo.countPendingBySource(SourceType.DOCUMENT, "doc-5"))
                .as("DISTINCT entity_id 应保证同一实体只入队一条")
                .isEqualTo(1);
    }

    @Test
    void KNOWLEDGE_BASE失效应按知识库维度入队() {
        插入实体("entity-kb-1");
        插入实体("entity-kb-2");
        插入ProvenanceForKb("entity-kb-1", "kb-42");
        插入ProvenanceForKb("entity-kb-2", "kb-42");

        listener.onSourceInvalidated(new SourceInvalidated(
                SourceType.KNOWLEDGE_BASE, "kb-42", InvalidationKind.ARCHIVED));

        assertThat(queueRepo.countPendingBySource(SourceType.KNOWLEDGE_BASE, "kb-42"))
                .isEqualTo(2);
    }

    // ---------- 测试夹具 ----------

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private void 插入实体(String entityId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status,
                    first_seen_at, last_seen_at, created_at, updated_at)
                VALUES (?, ?, 'PRIVATE', 'GOAL', ?, ?, 'UNKNOWN', 'ACTIVE', ?, ?, ?, ?)
                """,
                entityId, SPACE_ID, entityId, entityId,
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private void 插入Provenance(String entityId, String documentId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_provenances(
                    id, entity_id, origin_type, source_document_id, confidence, created_at)
                VALUES (?, ?, 'DOCUMENT', ?, 0.9, ?)
                """,
                UUID.randomUUID().toString(), entityId, documentId, FIXED_NOW.toString());
    }

    private void 插入ProvenanceForKb(String entityId, String kbId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_provenances(
                    id, entity_id, origin_type, source_knowledge_base_id, confidence, created_at)
                VALUES (?, ?, 'KNOWLEDGE_BASE', ?, 0.9, ?)
                """,
                UUID.randomUUID().toString(), entityId, kbId, FIXED_NOW.toString());
    }
}

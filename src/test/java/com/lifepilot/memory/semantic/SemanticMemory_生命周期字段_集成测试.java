package com.lifepilot.memory.semantic;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.SourceType;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.*;
import com.lifepilot.memory.support.MemoryProjectionTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link SemanticMemory} 生命周期字段集成测试（Task B3 重构版）。
 *
 * <p>验证写入入口统一走 {@link SemanticMemory#upsertWithConflictDetection} 与
 * {@link SemanticMemory#updateLifecycleState}，读回通过 {@link TemporalEntity} 承载
 * V15 新增的 7 个生命周期字段；同时覆盖 {@link MemoryProvenanceRepository#markStale}
 * 与 {@code findEntityIdsBySource} 的语义。</p>
 *
 * <p>使用文件 SQLite + 真跑 Flyway 迁移（含 V1–V16），手动装配 SemanticMemory
 * 以规避 {@code @SpringBootTest} 被 {@code SkillTestSupport} mock 污染的问题。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticMemory 生命周期字段集成测试")
class SemanticMemory_生命周期字段_集成测试 {

    @Mock
    private VectorSearcher vectorSearcher;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private MemoryProvenanceRepository provenanceRepository;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-sm-lifecycle-" + dbId + ".db");
        Files.deleteIfExists(dbPath);
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");

        // 直接跑 Flyway 建表，覆盖 V15 新列与 V16 视图
        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new SingleConnectionDataSource(jdbcUrl, true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        // 向量检索 mock — 冲突检测阶段无命中即可
        when(vectorSearcher.searchEntities(any(), any(Integer.class), any(Float.class)))
                .thenReturn(List.of());

        var conflictDetector = new ConflictDetector(jdbcTemplate, vectorSearcher, null, 0.92f, null);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher);
        MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);
        provenanceRepository = new MemoryProvenanceRepository(jdbcTemplate);
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
    void upsert写入带新字段的实体应能读回字段一致() {
        var entity = 构造活跃实体("test-新字段-1", EntityType.PREFERENCE);

        var persisted = semanticMemory.upsertWithConflictDetection(entity, null);

        var loaded = semanticMemory.findById(persisted.id()).orElseThrow();
        assertThat(loaded.lifecycleState()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(loaded.temporality()).isEqualTo(Temporality.PERSISTENT);
        assertThat(loaded.isDerived()).isFalse();
        assertThat(loaded.derivationSources()).isEmpty();
        assertThat(loaded.expiresAt()).isNull();
        assertThat(loaded.succeededBy()).isNull();
        assertThat(loaded.lifecycleReason()).isNull();
        assertThat(loaded.description()).isEqualTo("测试描述");
    }

    @Test
    void updateLifecycleState应能改写生命周期并保留其他字段() {
        var entity = 构造活跃实体("test-新字段-2", EntityType.GOAL);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null);

        semanticMemory.updateLifecycleState(persisted.id(), LifecycleState.CANCELLED, "user-cancel");

        var reloaded = semanticMemory.findById(persisted.id()).orElseThrow();
        assertThat(reloaded.lifecycleState()).isEqualTo(LifecycleState.CANCELLED);
        assertThat(reloaded.lifecycleReason()).isEqualTo("user-cancel");
        // 其他字段保持不变
        assertThat(reloaded.description()).isEqualTo("测试描述");
        assertThat(reloaded.temporality()).isEqualTo(Temporality.PERSISTENT);
        assertThat(reloaded.name()).isEqualTo("测试实体-test-新字段-2");
    }

    @Test
    void 派生实体的derivation_sources能正常读写() {
        var derived = new TemporalEntity(
                "test-派生-1",
                EntityType.PREFERENCE,
                "派生实体",
                "由其他实体合并得出",
                Map.of(),
                1,
                true,
                Instant.now(),
                null,
                null,
                1.0f,
                0.6f,
                0,
                null,
                Instant.now(),
                Instant.now(),
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                true,
                List.of("src-1", "src-2")
        );

        semanticMemory.upsertWithConflictDetection(derived, null);

        var loaded = semanticMemory.findById("test-派生-1").orElseThrow();
        assertThat(loaded.isDerived()).isTrue();
        assertThat(loaded.derivationSources()).containsExactly("src-1", "src-2");
    }

    @Test
    void markStale应标记provenance并能回查实体ID() {
        var entity = 构造活跃实体("test-溯源-1", EntityType.CUSTOM);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null);
        插入provenance("prov-1", persisted.id(), "doc-100");
        插入provenance("prov-2", persisted.id(), "doc-100");

        var now = Instant.parse("2026-04-23T10:00:00Z");
        provenanceRepository.markStale(SourceType.DOCUMENT, "doc-100", now);

        // 两条 provenance 都被标为 STALE
        var statuses = jdbcTemplate.queryForList(
                "SELECT status FROM memory_entity_provenances WHERE source_document_id = ?",
                String.class, "doc-100");
        assertThat(statuses).containsOnly("STALE");

        // findEntityIdsBySource 去重后返回单个实体 ID
        var entityIds = provenanceRepository.findEntityIdsBySource(SourceType.DOCUMENT, "doc-100");
        assertThat(entityIds).containsExactly(persisted.id());
    }

    // ========== helpers ==========

    /** 构造默认 ACTIVE + PERSISTENT 的基础实体（走 16 参兼容构造器补默认值）。 */
    private TemporalEntity 构造活跃实体(String id, EntityType type) {
        var now = Instant.now();
        return new TemporalEntity(
                id,
                type,
                "测试实体-" + id,
                "测试描述",
                Map.of(),
                1,
                true,
                now,
                null,
                null,
                1.0f,
                0.5f,
                0,
                null,
                now,
                now
                // 16 参构造器 → 生命周期字段默认 ACTIVE / PERSISTENT / 非派生
        );
    }

    /** 直接插入一条 provenance 用于测试 markStale。 */
    private void 插入provenance(String provenanceId, String entityId, String documentId) {
        var now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO memory_entity_provenances(
                    id, entity_id, version_id, origin_type, source_reference,
                    source_conversation_id, source_session_id, source_turn_id,
                    source_entry_id, source_document_id, source_knowledge_base_id,
                    confidence, created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                provenanceId, entityId, null, "UNKNOWN", null,
                null, null, null,
                null, documentId, null,
                0.5d, now);
    }
}

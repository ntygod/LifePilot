package com.lifepilot.memory.repository;

import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.lifecycle.SourceType;
import com.lifepilot.memory.semantic.MemoryEntity;
import com.lifepilot.tool.config.ToolConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MemoryEntityRepository} / {@link MemoryEntityProvenanceRepository} V15 新字段读写集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 覆盖：</p>
 * <ul>
 *   <li>基表新列（lifecycle / temporality / derivation_sources）的 save → findById round-trip；</li>
 *   <li>{@link MemoryEntityRepository#updateLifecycleState} 只改状态不 touch 其他字段；</li>
 *   <li>派生实体 {@code derivation_sources} JSON 数组读写；</li>
 *   <li>{@link MemoryEntityProvenanceRepository#markStale} / {@code findEntityIdsBySource}
 *       基于 V15 失效标记列的语义。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableConfigurationProperties(ToolConfigProperties.class)
class MemoryEntityRepository_新字段读写_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-mem-entity-repo-" + DB_ID + ".db")
                .toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-mem-entity-repo-vec-" + DB_ID + ".db")
                .toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private MemoryEntityRepository repo;

    @Autowired
    private MemoryEntityProvenanceRepository provenanceRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 清理前置数据() {
        jdbcTemplate.execute("DELETE FROM memory_entity_provenances");
        jdbcTemplate.execute("DELETE FROM memory_entity_versions");
        jdbcTemplate.execute("DELETE FROM memory_entities WHERE id LIKE 'test-%'");
    }

    @Test
    void 保存并读回带新字段的实体() {
        var entity = buildEntity("test-新字段-1", "PREFERENCE",
                LifecycleState.ACTIVE, Temporality.PERSISTENT);
        repo.save(entity);

        var loaded = repo.findById("test-新字段-1").orElseThrow();

        assertThat(loaded.id()).isEqualTo("test-新字段-1");
        assertThat(loaded.type()).isEqualTo("PREFERENCE");
        assertThat(loaded.name()).isEqualTo("测试实体-test-新字段-1");
        assertThat(loaded.description()).isEqualTo("测试描述");
        assertThat(loaded.importanceScore()).isEqualTo(0.5d);
        assertThat(loaded.lifecycleState()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(loaded.temporality()).isEqualTo(Temporality.PERSISTENT);
        assertThat(loaded.isDerived()).isFalse();
        assertThat(loaded.derivationSources()).isEmpty();
        assertThat(loaded.expiresAt()).isNull();
        assertThat(loaded.succeededBy()).isNull();
    }

    @Test
    void updateLifecycleState应转换成功并保留原有字段() {
        var entity = buildEntity("test-新字段-2", "GOAL",
                LifecycleState.ACTIVE, Temporality.PERSISTENT);
        repo.save(entity);

        repo.updateLifecycleState("test-新字段-2", LifecycleState.CANCELLED, "user-cancelled");

        var reloaded = repo.findById("test-新字段-2").orElseThrow();
        assertThat(reloaded.lifecycleState()).isEqualTo(LifecycleState.CANCELLED);
        assertThat(reloaded.lifecycleReason()).isEqualTo("user-cancelled");
        // 其他字段保持不变
        assertThat(reloaded.description()).isEqualTo("测试描述");
        assertThat(reloaded.importanceScore()).isEqualTo(0.5d);
        assertThat(reloaded.temporality()).isEqualTo(Temporality.PERSISTENT);
    }

    @Test
    void 派生实体的derivation_sources能读写() {
        var entity = buildDerivedEntity("test-派生-1", List.of("src-1", "src-2"));
        repo.save(entity);

        var loaded = repo.findById("test-派生-1").orElseThrow();
        assertThat(loaded.isDerived()).isTrue();
        assertThat(loaded.derivationSources()).containsExactly("src-1", "src-2");
    }

    @Test
    void markStale应更新provenance状态且findEntityIdsBySource能回查实体() {
        var entity = buildEntity("test-溯源-1", "FACT",
                LifecycleState.ACTIVE, Temporality.PERSISTENT);
        repo.save(entity);
        insertProvenance("prov-1", "test-溯源-1", "doc-100");
        insertProvenance("prov-2", "test-溯源-1", "doc-100");

        var now = Instant.parse("2026-04-23T10:00:00Z");
        provenanceRepo.markStale(SourceType.DOCUMENT, "doc-100", now);

        // 两条 provenance 都被标为 STALE
        var statuses = jdbcTemplate.queryForList(
                "SELECT status FROM memory_entity_provenances WHERE source_document_id = ?",
                String.class, "doc-100");
        assertThat(statuses).containsOnly("STALE");

        // findEntityIdsBySource 去重后返回单个实体 ID
        var entityIds = provenanceRepo.findEntityIdsBySource(SourceType.DOCUMENT, "doc-100");
        assertThat(entityIds).containsExactly("test-溯源-1");
    }

    // ========== helpers ==========

    /** 构造基础实体（{@code importanceScore} 默认 0.5，derived 标志为 false）。 */
    private MemoryEntity buildEntity(String id, String type,
                                     LifecycleState state, Temporality temporality) {
        return new MemoryEntity(
                id,
                type,
                "测试实体-" + id,
                "测试描述",
                0.5d,
                state,
                null,
                null,
                temporality,
                null,
                false,
                List.of()
        );
    }

    /** 构造派生实体（携带 {@code derivationSources}）。 */
    private MemoryEntity buildDerivedEntity(String id, List<String> sources) {
        return new MemoryEntity(
                id,
                "PREFERENCE",
                "派生实体-" + id,
                "由其他实体合并得出",
                0.6d,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                true,
                sources
        );
    }

    /** 直接插入一条 provenance 记录用于测试 markStale。 */
    private void insertProvenance(String provenanceId, String entityId, String documentId) {
        var now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO memory_entity_provenances(
                    id, entity_id, version_id, origin_type, source_reference,
                    source_conversation_id, source_session_id, source_turn_id,
                    source_entry_id, source_document_id, source_knowledge_base_id,
                    source_datastore_id, source_collection_id, confidence, created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                provenanceId, entityId, null, "UNKNOWN", null,
                null, null, null,
                null, documentId, null,
                null, null, 0.5d, now);
    }
}

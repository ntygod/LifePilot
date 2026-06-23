package com.lifepilot.memory.semantic;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.query.MemoryQueryApi;
import com.lifepilot.memory.governance.lifecycle.SourceType;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.*;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
import com.lifepilot.prompt.PromptRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link SemanticMemory} 生命周期字段集成测试（Task B3 重构版）。
 *
 * <p>验证写入入口统一走 {@link SemanticMemory#upsertWithConflictDetection} 与
 * {@link SemanticMemory#updateLifecycleState}，读回通过 {@link TemporalEntity} 承载
 * 生命周期字段；同时覆盖 {@link MemoryProvenanceRepository#markStale}
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
    private MemoryQueryApi queryApi;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-sm-lifecycle-" + dbId + ".db");
        Files.deleteIfExists(dbPath);
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");

        // 直接跑 Flyway 建表，覆盖生命周期字段与实体视图
        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new SingleConnectionDataSource(jdbcUrl, true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        var conflictDetector = new ConflictDetector(
                jdbcTemplate, vectorSearcher, mock(GenerationRouter.class), 0.92f, mock(PromptRegistry.class));
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher);
        MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);
        provenanceRepository = new MemoryProvenanceRepository(jdbcTemplate);
        queryApi = new MemoryQueryApi(semanticMemory, provenanceRepository, jdbcTemplate);
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

        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.unknown(null));

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
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.unknown(null));

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

        semanticMemory.upsertWithConflictDetection(derived, null, MemoryWriteContext.unknown(null));

        var loaded = semanticMemory.findById("test-派生-1").orElseThrow();
        assertThat(loaded.isDerived()).isTrue();
        assertThat(loaded.derivationSources()).containsExactly("src-1", "src-2");
    }

    @ParameterizedTest(name = "{0} 被污染时读取应失败")
    @CsvSource({
            "lifecycle_state, BROKEN_STATE",
            "temporality, BROKEN_TEMPORALITY",
            "evidence_kind, BROKEN_EVIDENCE",
            "trust_level, BROKEN_TRUST"
    })
    void 枚举字段被污染时读取应失败(String columnName, String invalidValue) {
        var entity = 构造活跃实体("test-非法枚举-" + columnName, EntityType.PREFERENCE);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.unknown(null));
        jdbcTemplate.update("UPDATE memory_entities SET " + columnName + " = ? WHERE id = ?",
                invalidValue, persisted.id());

        assertThatThrownBy(() -> semanticMemory.findById(persisted.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(columnName)
                .hasMessageContaining(invalidValue);
    }

    @Test
    void derivationSources被污染时读取应失败() {
        var derived = new TemporalEntity(
                "test-非法血缘",
                EntityType.PREFERENCE,
                "非法血缘实体",
                "用于验证派生来源读取严格性",
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
                List.of("src-1")
        );
        semanticMemory.upsertWithConflictDetection(derived, null, MemoryWriteContext.unknown(null));
        jdbcTemplate.update("UPDATE memory_entities SET derivation_sources = ? WHERE id = ?",
                "{不是合法JSON", derived.id());

        assertThatThrownBy(() -> semanticMemory.findById(derived.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("derivation_sources");
    }

    @Test
    void propertiesJson被污染时读取应失败() {
        var entity = new TemporalEntity(
                "test-非法属性",
                EntityType.CUSTOM,
                "非法属性实体",
                "用于验证属性读取严格性",
                Map.of("key", "value"),
                1,
                true,
                Instant.now(),
                null,
                null,
                1.0f,
                0.5f,
                0,
                null,
                Instant.now(),
                Instant.now()
        );
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.unknown(null));
        jdbcTemplate.update("""
                UPDATE memory_entity_versions
                SET properties_json = ?
                WHERE entity_id = ? AND is_current = 1
                """, "{不是合法JSON", persisted.id());

        assertThatThrownBy(() -> semanticMemory.findById(persisted.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("properties_json")
                .hasMessageContaining(persisted.id());
    }

    @ParameterizedTest(name = "MemoryQueryApi {0} 被污染时读取版本应失败")
    @CsvSource({
            "lifecycle_state, BROKEN_STATE",
            "temporality, BROKEN_TEMPORALITY"
    })
    void MemoryQueryApi枚举字段被污染时读取版本应失败(String columnName, String invalidValue) {
        var entity = 构造活跃实体("test-query-api-非法枚举-" + columnName, EntityType.PREFERENCE);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.unknown(null));
        jdbcTemplate.update("UPDATE memory_entities SET " + columnName + " = ? WHERE id = ?",
                invalidValue, persisted.id());

        assertThatThrownBy(() -> queryApi.findAllVersions(persisted.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MemoryQueryApi")
                .hasMessageContaining(columnName)
                .hasMessageContaining(invalidValue);
    }

    @Test
    void MemoryQueryApi的derivationSources被污染时读取版本应失败() {
        var derived = new TemporalEntity(
                "test-query-api-非法血缘",
                EntityType.PREFERENCE,
                "查询门面非法血缘实体",
                "用于验证查询门面派生来源读取严格性",
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
                List.of("src-1")
        );
        semanticMemory.upsertWithConflictDetection(derived, null, MemoryWriteContext.unknown(null));
        jdbcTemplate.update("UPDATE memory_entities SET derivation_sources = ? WHERE id = ?",
                "{不是合法JSON", derived.id());

        assertThatThrownBy(() -> queryApi.findAllVersions(derived.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MemoryQueryApi")
                .hasMessageContaining("derivation_sources")
                .hasMessageContaining(derived.id());
    }

    @Test
    void MemoryQueryApi的propertiesJson被污染时读取版本应失败() {
        var entity = new TemporalEntity(
                "test-query-api-非法属性",
                EntityType.CUSTOM,
                "查询门面非法属性实体",
                "用于验证查询门面属性读取严格性",
                Map.of("key", "value"),
                1,
                true,
                Instant.now(),
                null,
                null,
                1.0f,
                0.5f,
                0,
                null,
                Instant.now(),
                Instant.now()
        );
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.unknown(null));
        jdbcTemplate.update("""
                UPDATE memory_entity_versions
                SET properties_json = ?
                WHERE entity_id = ? AND is_current = 1
                """, "{不是合法JSON", persisted.id());

        assertThatThrownBy(() -> queryApi.findAllVersions(persisted.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MemoryQueryApi")
                .hasMessageContaining("properties_json")
                .hasMessageContaining(persisted.id());
    }

    @Test
    void 冲突检测候选propertiesJson被污染时写入应失败() {
        var existing = new TemporalEntity(
                "test-conflict-非法属性",
                EntityType.PERSON,
                "冲突检测候选",
                "已有实体",
                Map.of("key", "value"),
                1,
                true,
                Instant.now(),
                null,
                null,
                1.0f,
                0.5f,
                0,
                null,
                Instant.now(),
                Instant.now()
        );
        var persisted = semanticMemory.upsertWithConflictDetection(existing, null, MemoryWriteContext.unknown(null));
        jdbcTemplate.update("""
                UPDATE memory_entity_versions
                SET properties_json = ?
                WHERE entity_id = ? AND is_current = 1
                """, "{不是合法JSON", persisted.id());
        var incoming = new TemporalEntity(
                null,
                EntityType.PERSON,
                "冲突检测候选",
                "新实体",
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
                Instant.now()
        );

        assertThatThrownBy(() -> semanticMemory.upsertWithConflictDetection(
                incoming, null, MemoryWriteContext.unknown(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冲突检测")
                .hasMessageContaining("properties_json")
                .hasMessageContaining(persisted.id());
    }

    @ParameterizedTest(name = "MemoryQueryApi 模板 {0} 被污染时读取应失败")
    @CsvSource({
            "steps_json, {不是合法JSON",
            "variables_json, {不是合法JSON",
            "source_trace_ids_json, {不是合法JSON"
    })
    void MemoryQueryApi模板Json字段被污染时读取应失败(String columnName, String invalidValue) {
        String sourceEntityId = "source-query-api-template-" + columnName;
        String templateId = "tpl-query-api-broken-" + columnName;
        插入procedureTemplate(templateId, sourceEntityId);
        jdbcTemplate.update("UPDATE procedure_templates SET " + columnName + " = ? WHERE template_id = ?",
                invalidValue, templateId);

        assertThatThrownBy(() -> queryApi.findProcedureBySourceEntity(sourceEntityId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MemoryQueryApi")
                .hasMessageContaining(columnName)
                .hasMessageContaining(templateId);
    }

    @Test
    void markStale应标记provenance并能回查实体ID() {
        var entity = 构造活跃实体("test-溯源-1", EntityType.CUSTOM);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.unknown(null));
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

    /** 构造默认 ACTIVE + PERSISTENT 的基础实体。 */
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
                // 基础构造器默认 ACTIVE / PERSISTENT / 非派生
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

    private void 插入procedureTemplate(String templateId, String sourceEntityId) {
        var now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO procedure_templates(
                    template_id, name, description, trigger_intent,
                    steps_json, variables_json, success_rate, use_count,
                    last_used_at, source_trace_ids_json, source_entity_id,
                    deactivated_reason, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                templateId,
                "测试模板",
                "用于验证 L4 模板读取严格性",
                "测试触发意图",
                "[]",
                "{}",
                0.8f,
                1,
                null,
                "[]",
                sourceEntityId,
                null,
                now,
                now);
    }
}

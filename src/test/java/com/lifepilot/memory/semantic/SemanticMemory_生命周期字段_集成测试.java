package com.lifepilot.memory.semantic;

import com.lifepilot.agent.learning.conflict.ConflictResolutionService;
import com.lifepilot.agent.learning.staleness.StalenessCoordinator;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.store.support.SemanticMemoryTestSupport;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.query.MemoryQueryApi;
import com.lifepilot.memory.governance.lifecycle.SourceType;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.*;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

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
        var projectionService = MemoryProjectionTestSupport.create(jdbcTemplate, vectorSearcher);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher, SemanticMemoryTestSupport.memorySpaceRepository(jdbcTemplate), projectionService);
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

        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.manual("test"));

        var loaded = semanticMemory.findById(persisted.id()).orElseThrow();
        assertThat(loaded.lifecycleState()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(loaded.temporality()).isEqualTo(Temporality.PERSISTENT);
        assertThat(loaded.isDerived()).isFalse();
        assertThat(loaded.derivationSources()).isEmpty();
        assertThat(loaded.expiresAt()).isNull();
        assertThat(loaded.succeededBy()).isNull();
        assertThat(loaded.lifecycleReason()).isNull();
        assertThat(loaded.description()).isEqualTo("测试描述");

        var versions = queryApi.findAllVersions(persisted.id());
        assertThat(versions).hasSize(1);
        assertThat(versions.getFirst().evidenceKind()).isEqualTo(MemoryEvidenceKind.USER_CONFIRMED);
        assertThat(versions.getFirst().trustLevel()).isEqualTo(MemoryTrustLevel.EXPLICIT);
        assertThat(versions.getFirst().trustScore()).isGreaterThan(0.0f);
    }

    @Test
    void 注入冲突裁决服务后向量检索返回null应让upsert失败() {
        semanticMemory.setConflictResolutionService(mock(ConflictResolutionService.class));
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.<VectorSearchResult>of())
                .thenAnswer(invocation -> null);

        assertThatThrownBy(() -> semanticMemory.upsertWithConflictDetection(
                构造活跃实体("test-conflict-null-vector", EntityType.PREFERENCE),
                null,
                MemoryWriteContext.manual("test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冲突裁决向量检索结果不能为空");
    }

    @Test
    void 注入冲突裁决服务后向量候选实体不存在应让upsert失败() {
        semanticMemory.setConflictResolutionService(mock(ConflictResolutionService.class));
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.<VectorSearchResult>of())
                .thenReturn(List.of(new VectorSearchResult("missing-conflict-candidate", 0.98f)));

        assertThatThrownBy(() -> semanticMemory.upsertWithConflictDetection(
                构造活跃实体("test-conflict-missing-candidate", EntityType.PREFERENCE),
                null,
                MemoryWriteContext.manual("test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冲突裁决向量候选实体不存在")
                .hasMessageContaining("missing-conflict-candidate");
    }

    @Test
    void 注入冲突裁决服务后提交裁决失败应让upsert失败() {
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.<VectorSearchResult>of());
        var candidate = semanticMemory.upsertWithConflictDetection(
                构造活跃实体("test-conflict-candidate", EntityType.PREFERENCE),
                null,
                MemoryWriteContext.manual("test"));
        reset(vectorSearcher);

        var resolutionService = mock(ConflictResolutionService.class);
        semanticMemory.setConflictResolutionService(resolutionService);
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.<VectorSearchResult>of())
                .thenReturn(List.of(new VectorSearchResult(candidate.id(), 0.98f)));
        var failed = new CompletableFuture<Void>();
        failed.completeExceptionally(new IllegalStateException("提交冲突裁决失败"));
        when(resolutionService.resolveAsync(any(TemporalEntity.class), anyList()))
                .thenReturn(failed);

        assertThatThrownBy(() -> semanticMemory.upsertWithConflictDetection(
                构造活跃实体("test-conflict-submit-failure", EntityType.PREFERENCE),
                null,
                MemoryWriteContext.manual("test")))
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasRootCauseMessage("提交冲突裁决失败");
    }

    @Test
    void 注入冲突裁决服务后提交返回null应让upsert失败() {
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.<VectorSearchResult>of());
        var candidate = semanticMemory.upsertWithConflictDetection(
                构造活跃实体("test-conflict-null-future-candidate", EntityType.PREFERENCE),
                null,
                MemoryWriteContext.manual("test"));
        reset(vectorSearcher);

        var resolutionService = mock(ConflictResolutionService.class);
        semanticMemory.setConflictResolutionService(resolutionService);
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.<VectorSearchResult>of())
                .thenReturn(List.of(new VectorSearchResult(candidate.id(), 0.98f)));
        when(resolutionService.resolveAsync(any(TemporalEntity.class), anyList()))
                .thenReturn(null);

        assertThatThrownBy(() -> semanticMemory.upsertWithConflictDetection(
                构造活跃实体("test-conflict-submit-null-future", EntityType.PREFERENCE),
                null,
                MemoryWriteContext.manual("test")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("冲突裁决提交结果不能为空");
    }

    @Test
    void 注入staleness协调器后触发失败应让upsert失败() {
        var coordinator = mock(StalenessCoordinator.class);
        doThrow(new IllegalStateException("staleness 触发失败"))
                .when(coordinator)
                .process(any(TemporalEntity.class));
        semanticMemory.setStalenessCoordinator(coordinator);
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.<VectorSearchResult>of());

        assertThatThrownBy(() -> semanticMemory.upsertWithConflictDetection(
                构造活跃实体("test-staleness-failure", EntityType.PREFERENCE),
                null,
                MemoryWriteContext.manual("test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staleness 触发失败");
    }

    @Test
    void updateLifecycleState应能改写生命周期并保留其他字段() {
        var entity = 构造活跃实体("test-新字段-2", EntityType.GOAL);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.manual("test"));

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
    void findEligibleEntityIds_大结果集也应返回真实集合而非null降级() {
        for (int i = 0; i < 1001; i++) {
            插入当前实体("eligible-" + i);
        }

        var ids = semanticMemory.findEligibleEntityIds(MemoryReadFilter.all());

        assertThat(ids).hasSize(1001);
        assertThat(ids).contains("eligible-0", "eligible-1000");
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
        ,
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                Instant.now());

        semanticMemory.upsertWithConflictDetection(derived, null, MemoryWriteContext.manual("test"));

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
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.manual("test"));
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
        ,
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                Instant.now());
        semanticMemory.upsertWithConflictDetection(derived, null, MemoryWriteContext.manual("test"));
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
        ,
                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                null,
                null,
                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                null,
                false,
                java.util.List.of(),
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                Instant.now());
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.manual("test"));
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
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.manual("test"));
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
        ,
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                Instant.now());
        semanticMemory.upsertWithConflictDetection(derived, null, MemoryWriteContext.manual("test"));
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
        ,
                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                null,
                null,
                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                null,
                false,
                java.util.List.of(),
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                Instant.now());
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.manual("test"));
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
        ,
                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                null,
                null,
                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                null,
                false,
                java.util.List.of(),
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                Instant.now());
        var persisted = semanticMemory.upsertWithConflictDetection(existing, null, MemoryWriteContext.manual("test"));
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
        ,
                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                null,
                null,
                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                null,
                false,
                java.util.List.of(),
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                Instant.now());

        assertThatThrownBy(() -> semanticMemory.upsertWithConflictDetection(
                incoming, null, MemoryWriteContext.manual("test")))
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
    void MemoryQueryApi查不到L4结果时返回OptionalEmpty() {
        assertThat(queryApi.findRuleBySourceEntity("missing-rule-source")).isEmpty();
        assertThat(queryApi.findProcedureBySourceEntity("missing-template-source")).isEmpty();
    }

    @Test
    void MemoryQueryApi空白参数应直接失败() {
        assertThatThrownBy(() -> queryApi.findRuleBySourceEntity(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L4 偏好源实体 ID 不能为空");
        assertThatThrownBy(() -> queryApi.findProcedureBySourceEntity(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L4 程序源实体 ID 不能为空");
        assertThatThrownBy(() -> queryApi.findLatestByType("UNKNOWN_TYPE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知实体类型: UNKNOWN_TYPE");
    }

    @Test
    void markStale应标记provenance并能回查实体ID() {
        var entity = 构造活跃实体("test-溯源-1", EntityType.CUSTOM);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.manual("test"));
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

    @Test
    void provenance批量来源查询遇到空白实体ID应直接失败() {
        assertThatThrownBy(() -> semanticMemory.findSourceEntryIdsByEntityIds(List.of("entity-1", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provenance 查询实体 ID 不能为空");
        assertThatThrownBy(() -> semanticMemory.findSourceDocumentIdsByEntityIds(null, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("provenance 查询实体 ID 集合不能为空");
    }

    // ========== helpers ==========

    /** 构造显式 ACTIVE + PERSISTENT 的测试实体。 */
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
                now,
                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                null,
                null,
                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                null,
                false,
                java.util.List.of(),
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                now
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

    private void 插入当前实体(String id) {
        var now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count, first_seen_at, last_seen_at,
                    created_at, updated_at, lifecycle_state, temporality,
                    evidence_kind, trust_level, trust_score, evidence_count
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id,
                "memory-space-personal-default",
                "USER_FACT",
                EntityType.TOPIC.name(),
                id,
                id,
                "UNKNOWN",
                "ACTIVE",
                0,
                now,
                now,
                now,
                now,
                LifecycleState.ACTIVE.name(),
                Temporality.PERSISTENT.name(),
                MemoryEvidenceKind.USER_CONFIRMED.name(),
                MemoryTrustLevel.EXPLICIT.name(),
                0.9f,
                1);
    }
}

package com.lifepilot.memory.retrieval;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.VersionMerger;
import com.lifepilot.memory.support.MemoryProjectionTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HybridRetriever 生命周期过滤集成测试 —— Flyway 真跑迁移 + 真 SemanticMemory +
 * 真 MemoryProvenanceRepository，仅 mock VectorSearcher（避开向量 embedding 依赖）。
 *
 * <p>覆盖 Task 30 完成标准：
 * <ul>
 *   <li>EXPIRED / SUPERSEDED / ARCHIVED / CANCELLED 实体默认不召回</li>
 *   <li>COMPLETED 召回应带 {@code isHistorical=true}</li>
 *   <li>REGENERATION_NEEDED 召回应带 {@code isStale=true}</li>
 *   <li>STALE provenance 命中应带 {@code needsRevalidation=true}</li>
 *   <li>批量 STALE 查询正确性</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("HybridRetriever 生命周期过滤集成测试")
class HybridRetriever_生命周期过滤_集成测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final String SPACE_ID = "space-lifecycle-retrieval";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private MemoryProvenanceRepository provenanceRepository;
    private VectorSearcher vectorSearcher;
    private FtsSearcher ftsSearcher;
    private GraphTraverser graphTraverser;
    private HybridRetriever retriever;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-retrieval-lc-" + dbId + ".db");
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

        vectorSearcher = mock(VectorSearcher.class);
        // convertVectorResults 走 searchEntities(query, topK, threshold, eligibleIds) 4-arg 重载，
        // 默认回空列表，按测试需要再 stub 命中实体。
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat(),
                any())).thenReturn(List.of());

        var conflictDetector = mock(ConflictDetector.class);
        var versionMerger = mock(VersionMerger.class);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, versionMerger, vectorSearcher);
        MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);

        ftsSearcher = new FtsSearcher(jdbcTemplate);
        graphTraverser = new GraphTraverser(jdbcTemplate);
        provenanceRepository = new MemoryProvenanceRepository(jdbcTemplate);

        var properties = new MemoryProperties();
        properties.getRetrieval().setMinVectorSimilarity(0.0f);
        properties.getRetrieval().setMinFusedScore(0.0f);

        retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate, null,
                provenanceRepository);

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
    void EXPIRED实体不应被召回() {
        插入实体("e-active", "ACTIVE关键词", LifecycleState.ACTIVE);
        插入实体("e-expired", "EXPIRED关键词", LifecycleState.EXPIRED);
        // 向量路径命中全部三个，验证 SQL/应用层过滤
        给向量搜索打桩("e-active", "e-expired");

        var results = retriever.retrieve("ACTIVE关键词", 10, RetrievalWeights.DEFAULT);

        assertThat(results)
                .as("EXPIRED 实体不应被召回")
                .extracting(RetrievalResult::entityId)
                .containsExactly("e-active")
                .doesNotContain("e-expired");
    }

    @Test
    void SUPERSEDED和ARCHIVED和CANCELLED实体不应被召回() {
        插入实体("e-active", "通用关键词 alpha", LifecycleState.ACTIVE);
        插入实体("e-superseded", "通用关键词 beta", LifecycleState.SUPERSEDED);
        插入实体("e-archived", "通用关键词 gamma", LifecycleState.ARCHIVED);
        插入实体("e-cancelled", "通用关键词 delta", LifecycleState.CANCELLED);
        给向量搜索打桩("e-active", "e-superseded", "e-archived", "e-cancelled");

        var results = retriever.retrieve("通用关键词", 10, RetrievalWeights.DEFAULT);

        assertThat(results)
                .extracting(RetrievalResult::entityId)
                .as("只有 ACTIVE 实体应被召回")
                .containsExactly("e-active")
                .doesNotContain("e-superseded", "e-archived", "e-cancelled");
    }

    @Test
    void COMPLETED召回应带isHistorical_true() {
        插入实体("e-completed", "历史任务关键词", LifecycleState.COMPLETED);
        给向量搜索打桩("e-completed");

        var results = retriever.retrieve("历史任务关键词", 10, RetrievalWeights.DEFAULT);

        assertThat(results).hasSize(1);
        var result = results.getFirst();
        assertThat(result.entityId()).isEqualTo("e-completed");
        assertThat(result.isHistorical())
                .as("COMPLETED 应带 isHistorical=true")
                .isTrue();
        assertThat(result.isStale()).isFalse();
        assertThat(result.needsRevalidation()).isFalse();
    }

    @Test
    void REGENERATION_NEEDED召回应带isStale_true() {
        插入实体("e-regen", "派生关键词", LifecycleState.REGENERATION_NEEDED);
        给向量搜索打桩("e-regen");

        var results = retriever.retrieve("派生关键词", 10, RetrievalWeights.DEFAULT);

        assertThat(results).hasSize(1);
        var result = results.getFirst();
        assertThat(result.entityId()).isEqualTo("e-regen");
        assertThat(result.isStale())
                .as("REGENERATION_NEEDED 应带 isStale=true")
                .isTrue();
        assertThat(result.isHistorical()).isFalse();
        assertThat(result.needsRevalidation()).isFalse();
    }

    @Test
    void STALE_provenance命中应带needsRevalidation_true() {
        插入实体("e-stale-prov", "复核关键词", LifecycleState.ACTIVE);
        插入Provenance("e-stale-prov", "STALE");
        给向量搜索打桩("e-stale-prov");

        var results = retriever.retrieve("复核关键词", 10, RetrievalWeights.DEFAULT);

        assertThat(results).hasSize(1);
        var result = results.getFirst();
        assertThat(result.entityId()).isEqualTo("e-stale-prov");
        assertThat(result.needsRevalidation())
                .as("存在 STALE provenance 应带 needsRevalidation=true")
                .isTrue();
        assertThat(result.isHistorical()).isFalse();
        assertThat(result.isStale()).isFalse();
    }

    @Test
    void VALID_provenance命中needsRevalidation_false() {
        插入实体("e-valid-prov", "VALID关键词", LifecycleState.ACTIVE);
        插入Provenance("e-valid-prov", "VALID");
        给向量搜索打桩("e-valid-prov");

        var results = retriever.retrieve("VALID关键词", 10, RetrievalWeights.DEFAULT);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().needsRevalidation())
                .as("仅存在 VALID provenance 时 needsRevalidation 应为 false")
                .isFalse();
    }

    @Test
    void 批量STALE查询正确性() {
        // 三条实体：两条带 STALE，一条只带 VALID；一条完全无 provenance。
        插入实体("e-stale-a", "批量测试A", LifecycleState.ACTIVE);
        插入Provenance("e-stale-a", "STALE");
        插入实体("e-stale-b", "批量测试B", LifecycleState.ACTIVE);
        插入Provenance("e-stale-b", "STALE");
        插入Provenance("e-stale-b", "VALID"); // 同实体既有 STALE 也有 VALID → 依然算命中
        插入实体("e-valid-c", "批量测试C", LifecycleState.ACTIVE);
        插入Provenance("e-valid-c", "VALID");
        插入实体("e-no-prov", "批量测试D", LifecycleState.ACTIVE);

        Set<String> staleIds = provenanceRepository.findStaleEntityIds(
                List.of("e-stale-a", "e-stale-b", "e-valid-c", "e-no-prov", "e-unknown"));

        assertThat(staleIds)
                .as("批量查询应只返回存在 STALE provenance 的实体 ID")
                .containsExactlyInAnyOrder("e-stale-a", "e-stale-b")
                .doesNotContain("e-valid-c", "e-no-prov", "e-unknown");
    }

    @Test
    void 空集合或null输入应返回空Set() {
        assertThat(provenanceRepository.findStaleEntityIds(List.of()))
                .as("空集合输入应返回空 Set")
                .isEmpty();
        assertThat(provenanceRepository.findStaleEntityIds(null))
                .as("null 输入应返回空 Set")
                .isEmpty();
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

    /**
     * 插入一条实体（root + 当前 version）。entity_type 固定 GOAL，reality_type 固定 UNKNOWN。
     * description 字段被 FtsSearcher 的 LIKE 匹配命中以进入 FTS 路径。
     */
    private void 插入实体(String entityId, String description, LifecycleState state) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, temporality, is_derived)
                VALUES (?, ?, 'PRIVATE', 'GOAL', ?, ?, 'UNKNOWN', 'ACTIVE', 0,
                        ?, ?, ?, ?, ?, 'PERSISTENT', 0)
                """,
                entityId, SPACE_ID, entityId, entityId,
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                state.name());
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_versions(
                    id, entity_id, version_no, description,
                    extraction_confidence, importance_score,
                    is_current, valid_from, created_at, updated_at)
                VALUES (?, ?, 1, ?, 0.9, 0.5, 1, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entityId, description,
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private void 插入Provenance(String entityId, String status) {
        String versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM memory_entity_versions WHERE entity_id = ? AND is_current = 1",
                String.class, entityId);
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_provenances(
                    id, entity_id, version_id, origin_type, source_reference, confidence,
                    status, created_at)
                VALUES (?, ?, ?, 'UNKNOWN', 'test', 1.0, ?, ?)
                """,
                UUID.randomUUID().toString(), entityId, versionId,
                status, FIXED_NOW.toString());
    }

    /**
     * 让 {@link VectorSearcher#searchEntities(String, int, float, Set)} 对任意查询
     * 返回指定 ID 列表（均以高相似度 0.9f 命中）。HybridRetriever 会走 4-arg 重载
     * （带 eligibleIds），过滤集为 null 时 eligibleIds 也传 null。
     */
    private void 给向量搜索打桩(String... entityIds) {
        List<VectorSearchResult> hits = java.util.Arrays.stream(entityIds)
                .map(id -> new VectorSearchResult(id, 0.9f))
                .toList();
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat(),
                any())).thenReturn(hits);
    }
}

package com.lifepilot.memory.lifecycle.scanner;

import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.lifecycle.feedback.RegenerationQueueRepository;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.VersionMerger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link DerivationRegenerator} 集成测试 —— 轻量 Flyway + {@link SingleConnectionDataSource}
 * 真跑 V15 迁移 + 真 SemanticMemory + 真 Queue Repository，mock 掉 UserProfileConsolidator
 * 验证编排正确性。
 *
 * <p>覆盖用例：
 * <ul>
 *   <li>画像队列项应调 UserProfileConsolidator 并收尾 SUPERSEDED（若 consolidator
 *       未真正转状态）</li>
 *   <li>非画像派生实体无重算 API 应降级为直接 SUPERSEDED</li>
 *   <li>派生实体已非 REGENERATION_NEEDED 应跳过并 markDone</li>
 *   <li>派生实体已被硬删除应 markDone 不抛异常</li>
 *   <li>单条失败不中断整批</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("DerivationRegenerator 集成测试")
class DerivationRegenerator_集成测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T06:00:00Z");
    private static final String SPACE_ID = "space-regen";
    private static final String PROFILE_NAME = "__consolidated_profile";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private RegenerationQueueRepository queueRepo;
    private UserProfileConsolidator profileConsolidator;
    private DerivationRegenerator regenerator;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-regen-" + dbId + ".db");
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

        var vectorSearcher = mock(VectorSearcher.class);
        var conflictDetector = mock(ConflictDetector.class);
        var versionMerger = mock(VersionMerger.class);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, versionMerger, vectorSearcher);
        MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);

        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = publishedEvents::add;
        semanticMemory.setEventPublisher(publisher);

        queueRepo = new RegenerationQueueRepository(jdbcTemplate);
        profileConsolidator = mock(UserProfileConsolidator.class);
        regenerator = new DerivationRegenerator(queueRepo, semanticMemory, profileConsolidator);

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
    void 画像队列项应调UserProfileConsolidator_并收尾SUPERSEDED() {
        List<String> sourceIds = List.of("src-1", "src-2");
        插入实体("src-1", "PREFERENCE", "偏好1", false, false, List.of(), LifecycleState.SUPERSEDED);
        插入实体("src-2", "PREFERENCE", "偏好2", false, false, List.of(), LifecycleState.ACTIVE);
        插入实体("profile-q1", "CUSTOM", PROFILE_NAME, true, true, sourceIds,
                LifecycleState.REGENERATION_NEEDED);
        String qId = 入队("profile-q1", "src-1");

        regenerator.processQueueNow();

        verify(profileConsolidator, times(1)).consolidate();
        assertThat(读取LifecycleState("profile-q1"))
                .as("consolidator 未真正改状态时收尾补 SUPERSEDED")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(读取QueueStatus(qId)).isEqualTo("DONE");

        var lifecycleEvent = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .filter(e -> e.entityId().equals("profile-q1"))
                .findFirst()
                .orElseThrow();
        assertThat(lifecycleEvent.source()).isEqualTo(ChangeSource.DERIVATION_TRIGGER);
        assertThat(lifecycleEvent.newState()).isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(lifecycleEvent.reason()).isEqualTo("regenerated");
    }

    @Test
    void 非画像派生实体无重算API应降级为直接SUPERSEDED() {
        插入实体("exp-A", "EXPERIENCE", "经验A", false, false, List.of(), LifecycleState.SUPERSEDED);
        插入实体("insight-q2", "CUSTOM", "contrastive-insight-ab", true, true,
                List.of("exp-A"), LifecycleState.REGENERATION_NEEDED);
        String qId = 入队("insight-q2", "exp-A");

        regenerator.processQueueNow();

        verify(profileConsolidator, never()).consolidate();
        assertThat(读取LifecycleState("insight-q2"))
                .as("非画像派生实体直接 SUPERSEDED（降级）")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(读取QueueStatus(qId)).isEqualTo("DONE");

        var lifecycleEvent = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .filter(e -> e.entityId().equals("insight-q2"))
                .findFirst()
                .orElseThrow();
        assertThat(lifecycleEvent.source()).isEqualTo(ChangeSource.DERIVATION_TRIGGER);
        assertThat(lifecycleEvent.reason()).isEqualTo("no-regenerate-api");
    }

    @Test
    void 派生实体已非REGENERATION_NEEDED应跳过且markDone() {
        插入实体("src-3", "PREFERENCE", "偏好3", false, false, List.of(), LifecycleState.SUPERSEDED);
        // 派生实体已被其他路径改成 ARCHIVED，不应再重算
        插入实体("insight-q3", "CUSTOM", "insight-3", true, true,
                List.of("src-3"), LifecycleState.ARCHIVED);
        String qId = 入队("insight-q3", "src-3");

        regenerator.processQueueNow();

        verify(profileConsolidator, never()).consolidate();
        assertThat(读取LifecycleState("insight-q3")).isEqualTo(LifecycleState.ARCHIVED);
        assertThat(读取QueueStatus(qId))
                .as("非 REGENERATION_NEEDED 应 markDone 不 markFailed")
                .isEqualTo("DONE");
        assertThat(publishedEvents)
                .filteredOn(e -> e instanceof EntityLifecycleChanged)
                .as("无状态变化，无事件")
                .isEmpty();
    }

    @Test
    void 派生实体已硬删除应markDone不抛异常() {
        插入实体("src-4", "PREFERENCE", "偏好4", false, false, List.of(), LifecycleState.SUPERSEDED);
        // 注意：这里只入队但不插入派生实体本身，模拟硬删除
        String qId = 手工入队("ghost-derived", "src-4");

        regenerator.processQueueNow();

        assertThat(读取QueueStatus(qId))
                .as("派生实体不存在也应 markDone")
                .isEqualTo("DONE");
        verify(profileConsolidator, never()).consolidate();
    }

    @Test
    void 单条失败不中断整批_后续项仍处理() {
        // bad：画像类型 —— 让 consolidator 抛异常 → markFailed
        插入实体("src-bad", "PREFERENCE", "偏好失败源", false, false, List.of(), LifecycleState.SUPERSEDED);
        插入实体("profile-bad", "CUSTOM", PROFILE_NAME, true, true,
                List.of("src-bad"), LifecycleState.REGENERATION_NEEDED);
        String qIdBad = 入队("profile-bad", "src-bad");

        // good：非画像派生 —— 走降级 SUPERSEDED 路径，不调 consolidator，应 markDone
        插入实体("src-good", "EXPERIENCE", "经验良好", false, false, List.of(), LifecycleState.SUPERSEDED);
        插入实体("insight-good", "CUSTOM", "insight-good", true, true,
                List.of("src-good"), LifecycleState.REGENERATION_NEEDED);
        String qIdGood = 入队("insight-good", "src-good");

        // 让 consolidator 抛异常
        org.mockito.Mockito.doThrow(new RuntimeException("模拟 consolidator 故障"))
                .when(profileConsolidator).consolidate();

        // 不抛异常即代表整批未中断
        regenerator.processQueueNow();

        // bad 项（画像）触发 consolidator 异常 → markFailed
        assertThat(读取QueueStatus(qIdBad)).isEqualTo("FAILED");
        // good 项（非画像）走降级路径 → markDone
        assertThat(读取QueueStatus(qIdGood)).isEqualTo("DONE");
        // good 项经过收尾应 SUPERSEDED；bad 项因异常未收尾，仍 REGENERATION_NEEDED
        assertThat(读取LifecycleState("insight-good")).isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(读取LifecycleState("profile-bad")).isEqualTo(LifecycleState.REGENERATION_NEEDED);
        // consolidator 只应被 bad（画像）调一次
        verify(profileConsolidator, times(1)).consolidate();
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

    private void 插入实体(String entityId,
                         String entityType,
                         String name,
                         boolean isDerivedUnusedLabel,
                         boolean isDerived,
                         List<String> derivationSources,
                         LifecycleState state) {
        String sourcesJson = derivationSources.isEmpty()
                ? null
                : "[" + derivationSources.stream()
                    .map(s -> "\"" + s + "\"")
                    .reduce((a, b) -> a + "," + b).orElse("") + "]";
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, temporality, is_derived, derivation_sources)
                VALUES (?, ?, 'PRIVATE', ?, ?, ?, 'UNKNOWN', 'ACTIVE', 0,
                        ?, ?, ?, ?, ?, 'PERSISTENT', ?, ?)
                """,
                entityId, SPACE_ID, entityType, name, name.toLowerCase(),
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                state.name(),
                isDerived ? 1 : 0, sourcesJson);
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_versions(
                    id, entity_id, version_no, description,
                    extraction_confidence, importance_score,
                    is_current, valid_from, created_at, updated_at)
                VALUES (?, ?, 1, ?, 0.9, 0.5, 1, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entityId, "desc-" + entityId,
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    /** 通过 Repository 正常入队 —— 需要派生实体与触发源都已存在（FK 约束）。 */
    private String 入队(String derivedId, String triggerId) {
        queueRepo.enqueue(derivedId, triggerId, FIXED_NOW);
        // enqueue 未返回 id，这里按唯一 (derived_entity_id, status=PENDING) 取回
        return jdbcTemplate.queryForObject(
                """
                SELECT id FROM derivation_regeneration_queue
                WHERE derived_entity_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC
                LIMIT 1
                """,
                String.class, derivedId);
    }

    /** 直接 SQL 入队 —— 绕开 FK（用于硬删除派生实体的测试场景，先入队再"删"）。 */
    private String 手工入队(String derivedId, String triggerId) {
        // 先用 triggerId 的 FK 约束生成骨架，再用 jdbc 直接插，disable FK 片刻
        jdbcTemplate.execute("PRAGMA foreign_keys = OFF");
        String qid = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO derivation_regeneration_queue
                    (id, derived_entity_id, trigger_source_entity_id, status, created_at)
                VALUES (?, ?, ?, 'PENDING', ?)
                """,
                qid, derivedId, triggerId, FIXED_NOW.toString());
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");
        return qid;
    }

    private LifecycleState 读取LifecycleState(String entityId) {
        String raw = jdbcTemplate.queryForObject(
                "SELECT lifecycle_state FROM memory_entities WHERE id = ?",
                String.class, entityId);
        return LifecycleState.valueOf(raw);
    }

    private String 读取QueueStatus(String queueId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM derivation_regeneration_queue WHERE id = ?",
                String.class, queueId);
    }
}

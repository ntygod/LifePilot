package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.lifecycle.feedback.RegenerationQueueRepository;
import com.lifepilot.memory.lifecycle.listeners.DerivedEntityListener;
import com.lifepilot.memory.lifecycle.scanner.DerivationRegenerator;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.VersionMerger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 场景 S11：对比洞察源失效触发重算 — 派生实体（CUSTOM 类型 + is_derived=1 +
 * derivation_sources 指两个 EXPERIENCE 源）的其中一个源 SUPERSEDED 后：
 *
 * <ol>
 *   <li>{@link DerivedEntityListener} 把派生实体标 {@link LifecycleState#REGENERATION_NEEDED}
 *       并写入 {@code derivation_regeneration_queue} 的 PENDING 行；</li>
 *   <li>{@link DerivationRegenerator#processQueueNow()} 处理队列：因
 *       {@link com.lifepilot.memory.consolidation.ContrastiveLearner} 没有双源重算 API
 *       （Phase 0 Task 14 文档化漂移 #3），也不走画像分支，降级为直接把派生实体转
 *       {@link LifecycleState#SUPERSEDED}，{@code lifecycleReason="no-regenerate-api"}；</li>
 *   <li>队列行状态由 {@code PENDING} → {@code DONE}。</li>
 * </ol>
 *
 * <p><b>EntityType 漂移处理</b>：真实 {@link EntityType} 枚举
 * 不存在 {@code CONTRASTIVE_INSIGHT} 项（Phase 0 Task 14 记录），本场景用
 * {@code CUSTOM + name="contrastive-insight-*"} 近似，与 {@link DerivationRegenerator} 的
 * 真实分派路径（{@code PROFILE_ENTITY_NAME.equals(name)} 不命中 → {@code isDerived} 命中
 * 非画像派生分支 → 降级 SUPERSEDED）一致。</p>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>不走 @SpringBootTest（同 B16/B17 降级原因）；</li>
 *   <li>{@link DerivedEntityListener} 是 {@code @TransactionalEventListener(AFTER_COMMIT)}，
 *       单测无事务管理器，改由 lambda publisher 同步转发
 *       {@link EntityLifecycleChanged} → {@code onLifecycleChanged}；</li>
 *   <li>{@link SemanticMemory#updateLifecycleState} 自己代发
 *       {@link EntityLifecycleChanged}，本测试关心的是
 *       <i>源失效（{@code CONFLICT_RESOLVE}）→ 监听器响应</i> 一跳，而由监听器
 *       / regenerator 触发的 {@code DERIVATION_TRIGGER} 源二次事件已被 listener 入口短路，
 *       无需额外防递归。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-24
 */
@DisplayName("场景 S11 对比洞察源失效触发重算")
class 对比洞察源失效触发重算_场景测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-24T10:00:00Z");
    private static final String SPACE_ID = "space-s11";
    private static final String SOURCE_A = "exp-A";
    private static final String SOURCE_B = "exp-B";
    private static final String INSIGHT_ID = "contrastive-insight-1";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private DerivedEntityListener derivedEntityListener;
    private DerivationRegenerator regenerator;
    private RegenerationQueueRepository queueRepo;
    private UserProfileConsolidator profileConsolidator;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-scenario-s11-" + dbId + ".db");
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

        queueRepo = new RegenerationQueueRepository(jdbcTemplate);
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        derivedEntityListener = new DerivedEntityListener(semanticMemory, queueRepo, clock);

        // UserProfileConsolidator mock —— 非画像分支不该被调用，用 verify(never()) 护栏
        profileConsolidator = mock(UserProfileConsolidator.class);
        regenerator = new DerivationRegenerator(queueRepo, semanticMemory, profileConsolidator);

        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher forwardingPublisher = event -> {
            publishedEvents.add(event);
            // 模拟 @TransactionalEventListener(AFTER_COMMIT)：同步转发给 DerivedEntityListener
            if (event instanceof EntityLifecycleChanged lifecycleChanged) {
                derivedEntityListener.onLifecycleChanged(lifecycleChanged);
            }
        };
        semanticMemory.setEventPublisher(forwardingPublisher);

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
    @DisplayName("源 SUPERSEDED → 派生实体入 REGENERATION_NEEDED 队列；regenerator 降级为 SUPERSEDED")
    void 源失效应级联派生实体走_降级SUPERSEDED收尾() {
        // 1. 前置：两条 EXPERIENCE 源 + 一条依赖它们的派生实体（非画像、is_derived=1）
        插入EXPERIENCE(SOURCE_A);
        插入EXPERIENCE(SOURCE_B);
        插入派生实体(INSIGHT_ID, List.of(SOURCE_A, SOURCE_B));

        assertThat(读取LifecycleState(INSIGHT_ID))
                .as("派生实体初始 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(queueRepo.countPendingByDerived(INSIGHT_ID))
                .as("初始无队列项")
                .isZero();

        // 2. 动作 1：将 exp-A 转 SUPERSEDED（source=CONFLICT_RESOLVE 不被防递归跳过）
        semanticMemory.updateLifecycleState(
                SOURCE_A, LifecycleState.SUPERSEDED,
                "被-exp-A2-替代", ChangeSource.CONFLICT_RESOLVE);

        // 3. 断言：派生实体转 REGENERATION_NEEDED + 队列入 PENDING
        assertThat(读取LifecycleState(INSIGHT_ID))
                .as("源失效级联后派生实体应 REGENERATION_NEEDED")
                .isEqualTo(LifecycleState.REGENERATION_NEEDED);
        assertThat(读取LifecycleReason(INSIGHT_ID))
                .as("lifecycleReason 以 source-invalidated: 开头并携带源 ID")
                .startsWith("source-invalidated:")
                .contains(SOURCE_A);
        assertThat(queueRepo.countPendingByDerived(INSIGHT_ID))
                .as("队列应写入一条 PENDING")
                .isEqualTo(1);

        // 4. 动作 2：regenerator 处理队列 —— 非画像派生降级为直接 SUPERSEDED
        regenerator.processQueueNow();

        // 5. 断言：派生实体最终 SUPERSEDED + reason=no-regenerate-api
        var after = semanticMemory.findById(INSIGHT_ID).orElseThrow();
        assertThat(after.lifecycleState())
                .as("regenerator 降级应将非画像派生实体转 SUPERSEDED")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(after.lifecycleReason())
                .as("lifecycleReason 标记无重算 API 的降级路径")
                .isEqualTo("no-regenerate-api");

        // 6. 队列行 PENDING → DONE
        assertThat(queueRepo.countPendingByDerived(INSIGHT_ID))
                .as("处理后 PENDING 清零")
                .isZero();
        assertThat(读取队列Status(INSIGHT_ID))
                .as("队列行状态推进为 DONE")
                .isEqualTo("DONE");

        // 7. 画像 consolidator 未被误调（非画像分支）
        verify(profileConsolidator, never()).consolidate();
    }

    @Test
    @DisplayName("DERIVATION_TRIGGER 源不应二次级联（入口短路防递归）")
    void DERIVATION_TRIGGER源不二次级联() {
        插入EXPERIENCE(SOURCE_A);
        插入EXPERIENCE(SOURCE_B);
        插入派生实体(INSIGHT_ID, List.of(SOURCE_A, SOURCE_B));

        // source=DERIVATION_TRIGGER 模拟 regenerator 自身收尾调用 —— 监听器应短路
        semanticMemory.updateLifecycleState(
                SOURCE_A, LifecycleState.SUPERSEDED,
                "derivation-trigger-source", ChangeSource.DERIVATION_TRIGGER);

        assertThat(读取LifecycleState(INSIGHT_ID))
                .as("DERIVATION_TRIGGER 源短路，派生实体不应被级联")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(queueRepo.countPendingByDerived(INSIGHT_ID))
                .as("未进队列")
                .isZero();
    }

    // ---------- 测试夹具 ----------

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "S11 测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    /** 插入 ACTIVE + PERSISTENT EXPERIENCE 实体 + 当前版本（供 findDerivedBySourceEntity 过滤 is_current=1 需要）。 */
    private void 插入EXPERIENCE(String entityId) {
        插入实体行(entityId, "EXPERIENCE", /*isDerived*/ 0, /*derivationSourcesJson*/ null);
    }

    /**
     * 插入派生实体（非画像）—— type=CUSTOM，is_derived=1，derivation_sources 以 JSON 字符串持久化，
     * 与 {@code SemanticMemory#serializeDerivationSources} 的 Jackson 格式（双引号 + 方括号）对齐。
     */
    private void 插入派生实体(String entityId, List<String> sources) {
        String json = sources.stream()
                .map(s -> "\"" + s + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]")
                .orElse("[]");
        插入实体行(entityId, "CUSTOM", /*isDerived*/ 1, json);
    }

    private void 插入实体行(String entityId, String entityType, int isDerived, String derivationSourcesJson) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, temporality, is_derived, derivation_sources)
                VALUES (?, ?, 'PRIVATE', ?, ?, ?, 'UNKNOWN', 'ACTIVE', 0,
                        ?, ?, ?, ?, 'ACTIVE', 'PERSISTENT', ?, ?)
                """,
                entityId, SPACE_ID, entityType, entityId, entityId,
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                isDerived, derivationSourcesJson);
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

    private LifecycleState 读取LifecycleState(String entityId) {
        String raw = jdbcTemplate.queryForObject(
                "SELECT lifecycle_state FROM memory_entities WHERE id = ?",
                String.class, entityId);
        return LifecycleState.valueOf(raw);
    }

    private String 读取LifecycleReason(String entityId) {
        return jdbcTemplate.queryForObject(
                "SELECT lifecycle_reason FROM memory_entities WHERE id = ?",
                String.class, entityId);
    }

    private String 读取队列Status(String derivedEntityId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT status FROM derivation_regeneration_queue
                 WHERE derived_entity_id = ?
                 ORDER BY created_at ASC LIMIT 1
                """,
                String.class, derivedEntityId);
    }
}

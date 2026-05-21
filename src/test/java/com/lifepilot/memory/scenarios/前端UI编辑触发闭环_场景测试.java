package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lifepilot.agent.learning.consolidation.PreferenceConsolidator;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.lifecycle.listeners.L4SyncListener;
import com.lifepilot.memory.store.procedural.PreferenceRuleRepository;
import com.lifepilot.memory.store.procedural.ProceduralMemoryRepository;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.VersionMerger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
 * 场景 S15：前端 UI 编辑触发闭环 — 用户在记忆管理页面手动删除一条 PREFERENCE，
 * {@link SemanticMemory} 沿 {@link LifecycleState#ACTIVE} →
 * {@link LifecycleState#ARCHIVED} 转换 + 发 {@link EntityLifecycleChanged}
 * ({@link ChangeSource#UI_EDIT})；{@link L4SyncListener} 订阅后将关联的
 * {@code preference_rules.deactivated_reason} 写入失活原因。
 *
 * <p>验证目标：记忆管理页（Vue 3 SPA）删除一条偏好，应：
 * <ol>
 *   <li>L3 实体沿 ACTIVE → ARCHIVED（V15 lifecycle_state 列）；</li>
 *   <li>L3 事件 source = {@link ChangeSource#UI_EDIT}（表示用户 UI 动作）；</li>
 *   <li>L4 关联的 {@code preference_rules.deactivated_reason} 非空（失活）；</li>
 *   <li>L4 {@code deactivated_reason} 携带 UI 侧给出的理由。</li>
 * </ol>
 *
 * <p><b>实施路径</b>：
 * <ol>
 *   <li>SQLite + Flyway 真跑 V1-V18；</li>
 *   <li>真 SemanticMemory + L4SyncListener + PreferenceRuleRepository；</li>
 *   <li>前置：手动 INSERT PREFERENCE "pref-1" ACTIVE + 当前版本 +
 *       preference_rules 一行 {@code source_entity_id='pref-1'}
 *       {@code deactivated_reason=NULL}（参考 S6 path-B：绕过
 *       {@link PreferenceConsolidator}
 *       当前不填 source_entity_id 的漂移）；</li>
 *   <li>动作：{@code semanticMemory.updateLifecycleState("pref-1",
 *       LifecycleState.ARCHIVED, "user-ui-delete", ChangeSource.UI_EDIT)}
 *       —— 等价 Controller 路径 {@code DELETE /api/memories/entities/pref-1}
 *       的 L3 状态变化部分，同时指定前端 UI 传入的 reason；</li>
 *   <li>事件总线：lambda 转发 EntityLifecycleChanged →
 *       {@link L4SyncListener#onLifecycleChanged}；</li>
 *   <li>断言：pref-1 转 ARCHIVED + lifecycleReason="user-ui-delete" +
 *       L4 rule.deactivated_reason="user-ui-delete"。</li>
 * </ol>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>未走 HTTP 层（需 @SpringBootTest 会撞 meta.enabled=false 问题）；
 *       直接调 {@link SemanticMemory#updateLifecycleState} 模拟前端
 *       {@code DELETE /api/memories/entities/{id}} 的效果 —— Controller
 *       内部走的是 {@link SemanticMemory#archive(TemporalEntity)}
 *       但该重载默认 reason=null（取自 entity.lifecycleReason()）；为验证
 *       "UI 传入 reason 逐跳透传到 L4"，本测试使用
 *       {@code updateLifecycleState(id, ARCHIVED, reason, UI_EDIT)} 的
 *       直接路径（同属 L3 → L4 联动闭环，仅省略版本关闭与向量清理副作用）。
 *       完整 archive 副作用（版本关闭 / 关系归档 / 向量清理）由
 *       {@code SemanticMemory_archive_单元测试} 覆盖；</li>
 *   <li>{@link L4SyncListener} 是 {@code @TransactionalEventListener(AFTER_COMMIT)}，
 *       单测无事务管理器，改由 lambda publisher 同步转发。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-24
 */
@DisplayName("场景 S15 前端 UI 编辑触发闭环")
class 前端UI编辑触发闭环_场景测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-24T10:00:00Z");
    private static final String SPACE_ID = "space-s15";
    private static final String PREF_ID = "pref-1";
    private static final String CATEGORY = "user-preference";
    private static final String UI_REASON = "user-ui-delete";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private PreferenceRuleRepository ruleRepo;
    private L4SyncListener l4SyncListener;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-scenario-s15-" + dbId + ".db");
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

        ruleRepo = new PreferenceRuleRepository(jdbcTemplate);
        var procedureRepo = new ProceduralMemoryRepository(jdbcTemplate);
        l4SyncListener = new L4SyncListener(ruleRepo, procedureRepo);

        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher forwardingPublisher = event -> {
            publishedEvents.add(event);
            if (event instanceof EntityLifecycleChanged lifecycle) {
                l4SyncListener.onLifecycleChanged(lifecycle);
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
    @DisplayName("UI 删除 PREFERENCE → L3 ARCHIVED + L4 规则 deactivated_reason 写入 UI 理由")
    void 前端UI编辑应级联L3归档与L4失活() {
        // 1. 前置：PREFERENCE ACTIVE + L4 规则活跃（deactivated_reason=NULL，source_entity_id 挂钩）
        插入ACTIVE_PREFERENCE(PREF_ID);
        String ruleId = 插入活跃L4规则(PREF_ID);

        assertThat(ruleRepo.findRuleIdsBySourceEntity(PREF_ID))
                .as("初始 L4 规则挂钩到 pref-1")
                .containsExactly(ruleId);
        String reasonBefore = 读取L4规则失活理由(ruleId);
        assertThat(reasonBefore).as("活跃规则 deactivated_reason 应为 NULL").isNull();

        // 2. 动作：前端 DELETE /api/memories/entities/pref-1 → archive + UI 理由
        //    降级为直接 updateLifecycleState 指定 reason（原因见类级 Javadoc）
        semanticMemory.updateLifecycleState(
                PREF_ID, LifecycleState.ARCHIVED, UI_REASON, ChangeSource.UI_EDIT);

        // 3. 断言：L3 转 ARCHIVED + reason="user-ui-delete"
        var after = semanticMemory.findById(PREF_ID).orElseThrow();
        assertThat(after.lifecycleState())
                .as("L3 实体应转 ARCHIVED")
                .isEqualTo(LifecycleState.ARCHIVED);
        assertThat(after.lifecycleReason())
                .as("L3 lifecycleReason 应为前端 UI 传入的 user-ui-delete")
                .isEqualTo(UI_REASON);

        // 4. 事件链路：一条 LifecycleChanged(ARCHIVED, UI_EDIT, reason=UI_REASON)
        var lifecycleEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents).hasSize(1);
        var event = lifecycleEvents.getFirst();
        assertThat(event.source()).isEqualTo(ChangeSource.UI_EDIT);
        assertThat(event.newState()).isEqualTo(LifecycleState.ARCHIVED);
        assertThat(event.oldState()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(event.reason()).isEqualTo(UI_REASON);

        // 5. L4 联动：rule.deactivated_reason 应写入 UI 理由
        String reasonAfter = 读取L4规则失活理由(ruleId);
        assertThat(reasonAfter)
                .as("L4 preference_rules.deactivated_reason 应非空（已失活）")
                .isNotNull()
                .isNotBlank();
        assertThat(reasonAfter)
                .as("L4 失活理由应携带 UI 传入的 user-ui-delete（L4SyncListener 透传 event.reason）")
                .isEqualTo(UI_REASON);
    }

    @Test
    @DisplayName("UI 理由为空时 L4 fallback 到 newState 名称（ARCHIVED）")
    void UI理由为空时L4使用newState名称作为失活理由() {
        插入ACTIVE_PREFERENCE(PREF_ID);
        String ruleId = 插入活跃L4规则(PREF_ID);

        // reason=null：等价于 Controller 走 archive(entity) 默认路径（entity.lifecycleReason()=null）
        semanticMemory.updateLifecycleState(
                PREF_ID, LifecycleState.ARCHIVED, null, ChangeSource.UI_EDIT);

        String reasonAfter = 读取L4规则失活理由(ruleId);
        assertThat(reasonAfter)
                .as("L4SyncListener 对 null reason 兜底使用 newState.name()")
                .isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("重复 UI 删除 idempotent：第二次不再改变 L4 规则理由")
    void 重复UI删除应幂等不覆盖首次失活理由() {
        插入ACTIVE_PREFERENCE(PREF_ID);
        String ruleId = 插入活跃L4规则(PREF_ID);

        semanticMemory.updateLifecycleState(
                PREF_ID, LifecycleState.ARCHIVED, UI_REASON, ChangeSource.UI_EDIT);
        String firstReason = 读取L4规则失活理由(ruleId);

        // 第二次 UI 删除（模拟用户重复点击）—— 事件再发一次，但 L4 规则的
        // deactivated_reason 已非空，不应被覆盖
        semanticMemory.updateLifecycleState(
                PREF_ID, LifecycleState.ARCHIVED, "second-click", ChangeSource.UI_EDIT);
        String secondReason = 读取L4规则失活理由(ruleId);

        assertThat(secondReason)
                .as("第二次 UI 事件不应改写 L4 失活理由（PreferenceRuleRepository 的 IS NULL 过滤）")
                .isEqualTo(firstReason)
                .isEqualTo(UI_REASON);
    }

    // ---------- 测试夹具 ----------

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "S15 测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    /** 插入一条 ACTIVE + PERSISTENT PREFERENCE 实体 + 当前版本行。 */
    private void 插入ACTIVE_PREFERENCE(String entityId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, temporality, is_derived)
                VALUES (?, ?, 'PRIVATE', 'PREFERENCE', ?, ?, 'UNKNOWN', 'ACTIVE', 0,
                        ?, ?, ?, ?, 'ACTIVE', 'PERSISTENT', 0)
                """,
                entityId, SPACE_ID, entityId, entityId,
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                FIXED_NOW.toString(), FIXED_NOW.toString());
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_versions(
                    id, entity_id, version_no, description,
                    extraction_confidence, importance_score,
                    is_current, valid_from, created_at, updated_at)
                VALUES (?, ?, 1, ?, 0.9, 0.5, 1, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entityId, "偏好描述-" + entityId,
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    /**
     * 插入一条挂钩到 sourceEntityId 的活跃 L4 规则（绕过 PreferenceConsolidator 当前
     * 不填 source_entity_id 的漂移 —— 参考 S6 path-B）。
     *
     * @return 规则 rule_id
     */
    private String 插入活跃L4规则(String sourceEntityId) {
        String ruleId = "rule-" + UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO preference_rules(
                    rule_id, category, key, value, confidence,
                    learned_from_json, observation_count, created_at, updated_at,
                    source_entity_id, deactivated_reason
                ) VALUES(?,?,?,?,?,?,?,?,?,?,NULL)
                """,
                ruleId, CATEGORY, "key-" + sourceEntityId, "value", 0.8f,
                "[\"manual-seed\"]", 1,
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                sourceEntityId);
        return ruleId;
    }

    private String 读取L4规则失活理由(String ruleId) {
        return jdbcTemplate.queryForObject(
                "SELECT deactivated_reason FROM preference_rules WHERE rule_id = ?",
                String.class, ruleId);
    }
}

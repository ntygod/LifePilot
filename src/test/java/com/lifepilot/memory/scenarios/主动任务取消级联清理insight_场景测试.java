package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lifepilot.agent.task.proactive.GoalTrackingRepository;
import com.lifepilot.agent.task.proactive.ProactiveMemoryBridge;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.governance.lifecycle.events.ProactiveTaskCancelled;
import com.lifepilot.memory.governance.lifecycle.listeners.ProactiveTaskCancelListener;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
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
 * 场景 S10：主动任务取消级联清理 insight —
 * {@link ProactiveMemoryBridge#markGoalFulfilled(String)} 发
 * {@link ProactiveTaskCancelled} → {@link ProactiveTaskCancelListener}
 * 将所有关联的 L3 insight 从 {@link LifecycleState#ACTIVE} 转
 * {@link LifecycleState#CANCELLED}。
 *
 * <p>验证目标：当用户明确说"那个定期提醒的事儿别做了"（或主动引擎内部判断
 * 目标已达成），{@code markGoalFulfilled(taskId)} 应触发级联，将该 task 产出的
 * 所有 insight PREFERENCE 实体置为 CANCELLED —— 这批 insight 只在
 * 任务上下文内有意义，任务终结后应避免被 HybridRetriever 误召回。</p>
 *
 * <p><b>实施路径</b>：
 * <ol>
 *   <li>SQLite + Flyway 真跑 V1-V18（含 V17 {@code proactive_task_insight_links}）；</li>
 *   <li>{@link ProactiveMemoryBridge} 直接构造（EpisodicMemory / ProceduralMemory 均传 null，
 *       对 S10 路径无影响）；</li>
 *   <li>用 {@link ProactiveMemoryBridge#linkInsightToTask(String, String)} 写 V17 关联表；</li>
 *   <li>事件总线：lambda 捕获 {@link ProactiveTaskCancelled} →
 *       {@link ProactiveTaskCancelListener#onCancelled}（模拟
 *       {@code @TransactionalEventListener(AFTER_COMMIT)}）；</li>
 *   <li>调 {@link ProactiveMemoryBridge#markGoalFulfilled(String)} 触发发事件；</li>
 *   <li>断言：ins-1 / ins-2 转 CANCELLED + lifecycleReason 含 taskId。</li>
 * </ol>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>未走 @SpringBootTest；</li>
 *   <li>{@link ProactiveTaskCancelListener} 与 {@link SemanticMemory} 发的
 *       {@link EntityLifecycleChanged} 都是 {@code @TransactionalEventListener(AFTER_COMMIT)} —
 *       单测无事务管理器，改由 lambda publisher 同步转发；</li>
 *   <li>{@link SemanticMemory#findById} 会被 listener 多次调用；因未 mock
 *       ConflictDetector/VersionMerger 的写路径（本测试不走 upsertWithConflictDetection），
 *       只需保证 memory_entities + memory_entity_versions 有 is_current=1 即可 findById 成功。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-24
 */
@DisplayName("场景 S10 主动任务取消级联 L3 insight")
class 主动任务取消级联清理insight_场景测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-24T10:00:00Z");
    private static final String SPACE_ID = "space-s10";
    private static final String TASK_ID = "task-每日清单";
    private static final String INSIGHT_1 = "insight-1";
    private static final String INSIGHT_2 = "insight-2";
    /** 与 task 无关的 insight，验证只级联关联条目，不误伤。 */
    private static final String INSIGHT_UNRELATED = "insight-unrelated";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private ProactiveMemoryBridge bridge;
    private ProactiveTaskCancelListener listener;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-scenario-s10-" + dbId + ".db");
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
        listener = new ProactiveTaskCancelListener(semanticMemory);

        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher forwardingPublisher = event -> {
            publishedEvents.add(event);
            if (event instanceof ProactiveTaskCancelled cancelled) {
                listener.onCancelled(cancelled);
            }
        };
        semanticMemory.setEventPublisher(forwardingPublisher);

        var goalTrackingRepo = new GoalTrackingRepository(jdbcTemplate);
        bridge = new ProactiveMemoryBridge(
                semanticMemory, null, null, goalTrackingRepo, jdbcTemplate,
                forwardingPublisher, null);

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
    @DisplayName("markGoalFulfilled → 关联 insight 全部 CANCELLED，不关联的 insight 保持 ACTIVE")
    void 主动任务取消应级联关联insight转CANCELLED_不影响其他insight() {
        // 1. 前置：两条属于 task 的 insight + 一条无关联 insight + task 本身（GOAL）
        插入GOAL(TASK_ID);
        插入ACTIVE_INSIGHT(INSIGHT_1);
        插入ACTIVE_INSIGHT(INSIGHT_2);
        插入ACTIVE_INSIGHT(INSIGHT_UNRELATED);

        bridge.linkInsightToTask(TASK_ID, INSIGHT_1);
        bridge.linkInsightToTask(TASK_ID, INSIGHT_2);
        // INSIGHT_UNRELATED 故意不挂钩

        assertThat(bridge.findInsightEntityIdsByTask(TASK_ID))
                .as("V17 关联表应登记 2 条")
                .containsExactlyInAnyOrder(INSIGHT_1, INSIGHT_2);

        // 2. 动作：markGoalFulfilled → 发 ProactiveTaskCancelled → listener 级联
        bridge.markGoalFulfilled(TASK_ID);

        // 3. 断言：两条关联 insight 转 CANCELLED
        var ins1After = semanticMemory.findById(INSIGHT_1).orElseThrow();
        var ins2After = semanticMemory.findById(INSIGHT_2).orElseThrow();
        assertThat(ins1After.lifecycleState())
                .as("关联 insight-1 应转 CANCELLED")
                .isEqualTo(LifecycleState.CANCELLED);
        assertThat(ins2After.lifecycleState())
                .as("关联 insight-2 应转 CANCELLED")
                .isEqualTo(LifecycleState.CANCELLED);
        assertThat(ins1After.lifecycleReason())
                .as("lifecycleReason 含 task id 前缀")
                .contains(TASK_ID)
                .startsWith("proactive-task-cancelled:");
        assertThat(ins2After.lifecycleReason())
                .contains(TASK_ID);

        // 4. 未关联 insight 保持 ACTIVE
        var unrelatedAfter = semanticMemory.findById(INSIGHT_UNRELATED).orElseThrow();
        assertThat(unrelatedAfter.lifecycleState())
                .as("未挂钩该 task 的 insight 不被误伤")
                .isEqualTo(LifecycleState.ACTIVE);

        // 5. 事件链路：一条 ProactiveTaskCancelled + 三条 EntityLifecycleChanged
        //    （task 本身 ACTIVE → ARCHIVED，外加两条 insight ACTIVE → CANCELLED）
        assertThat(publishedEvents.stream()
                .filter(e -> e instanceof ProactiveTaskCancelled).count())
                .as("markGoalFulfilled 发一条 ProactiveTaskCancelled")
                .isEqualTo(1);
        var cancelEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .filter(e -> e.source() == ChangeSource.PROACTIVE_CANCEL)
                .filter(e -> e.newState() == LifecycleState.CANCELLED)
                .toList();
        assertThat(cancelEvents)
                .as("两条关联 insight 各发一条 source=PROACTIVE_CANCEL 的 LifecycleChanged")
                .hasSize(2);
        assertThat(cancelEvents)
                .allMatch(e -> e.newState() == LifecycleState.CANCELLED);
        // task 本身（GOAL）被 archive —— markGoalFulfilled 路径的副作用，source=PROACTIVE_CANCEL
        var archiveEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .filter(e -> e.newState() == LifecycleState.ARCHIVED)
                .filter(e -> e.source() == ChangeSource.PROACTIVE_CANCEL)
                .toList();
        assertThat(archiveEvents)
                .as("markGoalFulfilled 还归档 task 自身一条")
                .hasSize(1);
        assertThat(archiveEvents.getFirst().entityId()).isEqualTo(TASK_ID);
    }

    @Test
    @DisplayName("task 无关联 insight 时 listener 安全跳过，不抛异常")
    void 无关联insight时markGoalFulfilled不抛异常() {
        插入GOAL(TASK_ID);
        // 不挂钩任何 insight

        bridge.markGoalFulfilled(TASK_ID);

        assertThat(publishedEvents.stream()
                .filter(e -> e instanceof ProactiveTaskCancelled).count())
                .as("即使无关联 insight，事件仍应发布（relatedInsightEntityIds=[]）")
                .isEqualTo(1);
        // task 自身 archive 仍会发一条 LifecycleChanged，但无关联 insight
        var archiveEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .filter(e -> e.newState() == LifecycleState.ARCHIVED)
                .filter(e -> e.source() == ChangeSource.PROACTIVE_CANCEL)
                .toList();
        assertThat(archiveEvents)
                .as("markGoalFulfilled 仍归档 task 自身")
                .hasSize(1);
        assertThat(archiveEvents.getFirst().entityId()).isEqualTo(TASK_ID);

        // 不应有 insight CANCELLED 事件
        assertThat(publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .filter(e -> e.source() == ChangeSource.PROACTIVE_CANCEL)
                .filter(e -> e.newState() == LifecycleState.CANCELLED)
                .count())
                .as("无关联 insight，listener 不触发 PROACTIVE_CANCEL 级联")
                .isZero();
    }

    // ---------- 测试夹具 ----------

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "S10 测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    /** 插入一条 GOAL 实体作为 task 本身，markGoalFulfilled 会 archive 它。 */
    private void 插入GOAL(String entityId) {
        插入实体行(entityId, "GOAL");
    }

    /** 插入一条 PREFERENCE 实体作为 insight。 */
    private void 插入ACTIVE_INSIGHT(String entityId) {
        插入实体行(entityId, "PREFERENCE");
    }

    private void 插入实体行(String entityId, String entityType) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, temporality, is_derived)
                VALUES (?, ?, 'PRIVATE', ?, ?, ?, 'UNKNOWN', 'ACTIVE', 0,
                        ?, ?, ?, ?, 'ACTIVE', 'PERSISTENT', 0)
                """,
                entityId, SPACE_ID, entityType, entityId, entityId,
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
                UUID.randomUUID().toString(), entityId, "desc-" + entityId,
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }
}

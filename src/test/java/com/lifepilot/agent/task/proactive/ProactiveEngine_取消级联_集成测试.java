package com.lifepilot.agent.task.proactive;

import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.lifecycle.events.ProactiveTaskCancelled;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.VersionMerger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ProactiveEngine#markGoalFulfilled(String)} 取消级联集成测试（Task 13）。
 *
 * <p>验证：
 * <ul>
 *   <li>通过 {@link ProactiveMemoryBridge#linkInsightToTask(String, String)} 建立关联后，
 *       {@code markGoalFulfilled(taskId)} 应发布 {@link ProactiveTaskCancelled} 事件，
 *       payload 的 {@code relatedInsightEntityIds} 覆盖所有关联的 L3 insight 实体 id</li>
 *   <li>无关联时事件 {@code relatedInsightEntityIds} 为空列表（不抛 NPE）</li>
 *   <li>归档前查询 relatedIds，避免外键级联清理掉关联行后导致查询为空</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ProactiveEngine_取消级联_集成测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private GoalTrackingRepository goalTrackingRepository;
    private ProactiveMemoryBridge bridge;
    private ProactiveEngine engine;
    private List<Object> captured;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-proactive-cancel-" + dbId + ".db");
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

        // Mock 向量检索（测试不关心向量）
        VectorSearcher vectorSearcher = mock(VectorSearcher.class);
        lenient().when(vectorSearcher.searchEntities(any(), any(Integer.class), any(Float.class)))
                .thenReturn(List.of());

        var conflictDetector = new ConflictDetector(jdbcTemplate, vectorSearcher, null, 0.92f, null);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher);

        goalTrackingRepository = new GoalTrackingRepository(jdbcTemplate);
        bridge = new ProactiveMemoryBridge(
                semanticMemory, null, null, goalTrackingRepository, jdbcTemplate);

        captured = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> captured.add(event);
        bridge.setEventPublisher(publisher);

        // 走 ProactiveEngine 作为对外入口，behaviors / gate / delivery 用空/mock
        engine = new ProactiveEngine(
                List.of(), mock(DecisionGate.class), mock(DeliveryEngine.class),
                null, null, null, null, bridge, null, null);
    }

    @AfterEach
    void 清理() throws Exception {
        if (dataSource != null) dataSource.destroy();
        if (dbPath != null) Files.deleteIfExists(dbPath);
    }

    @Test
    void markGoalFulfilled应发布ProactiveTaskCancelled含相关insight() {
        // 前置 1：写一个 GOAL 实体作为 taskId
        var goal = 构造实体(EntityType.GOAL, "goal-学习rust", "想学 rust");
        var persistedGoal = semanticMemory.upsertWithConflictDetection(goal, "test-proactive");
        String taskId = persistedGoal.id();

        // 前置 2：写两个 PREFERENCE 实体作为 insight，并挂钩到 task
        var insight1 = 构造实体(EntityType.PREFERENCE, "proactive_insight_a", "早晨偏好");
        var insight2 = 构造实体(EntityType.PREFERENCE, "proactive_insight_b", "咖啡偏好");
        var p1 = semanticMemory.upsertWithConflictDetection(insight1, "proactive-engine");
        var p2 = semanticMemory.upsertWithConflictDetection(insight2, "proactive-engine");
        bridge.linkInsightToTask(taskId, p1.id());
        bridge.linkInsightToTask(taskId, p2.id());

        captured.clear();   // 忽略 upsert 产生的 LifecycleChanged 事件

        // 执行：ProactiveEngine 的统一入口
        engine.markGoalFulfilled(taskId);

        // 断言：事件发了一次，payload 含两个 insight id
        var cancelEvents = captured.stream()
                .filter(e -> e instanceof ProactiveTaskCancelled)
                .map(e -> (ProactiveTaskCancelled) e)
                .toList();
        assertThat(cancelEvents).hasSize(1);
        var event = cancelEvents.getFirst();
        assertThat(event.taskId()).isEqualTo(taskId);
        assertThat(event.relatedInsightEntityIds())
                .containsExactlyInAnyOrder(p1.id(), p2.id());
    }

    @Test
    void markGoalFulfilled无关联insight时应发空列表事件() {
        var goal = 构造实体(EntityType.GOAL, "goal-无关联", "没有 insight 挂钩");
        var persistedGoal = semanticMemory.upsertWithConflictDetection(goal, "test-proactive");
        captured.clear();

        engine.markGoalFulfilled(persistedGoal.id());

        var cancelEvents = captured.stream()
                .filter(e -> e instanceof ProactiveTaskCancelled)
                .map(e -> (ProactiveTaskCancelled) e)
                .toList();
        assertThat(cancelEvents).hasSize(1);
        assertThat(cancelEvents.getFirst().relatedInsightEntityIds()).isEmpty();
    }

    @Test
    void linkInsightToTask幂等_重复挂钩不影响事件payload() {
        var goal = 构造实体(EntityType.GOAL, "goal-幂等", "幂等测试");
        var persistedGoal = semanticMemory.upsertWithConflictDetection(goal, "test-proactive");
        var insight = 构造实体(EntityType.PREFERENCE, "proactive_insight_c", "某偏好");
        var p = semanticMemory.upsertWithConflictDetection(insight, "proactive-engine");

        // 重复挂钩同一对 (task, insight)
        bridge.linkInsightToTask(persistedGoal.id(), p.id());
        bridge.linkInsightToTask(persistedGoal.id(), p.id());
        bridge.linkInsightToTask(persistedGoal.id(), p.id());

        captured.clear();
        engine.markGoalFulfilled(persistedGoal.id());

        var cancelEvents = captured.stream()
                .filter(e -> e instanceof ProactiveTaskCancelled)
                .map(e -> (ProactiveTaskCancelled) e)
                .toList();
        assertThat(cancelEvents).hasSize(1);
        // 幂等主键 → 只有一条关联记录
        assertThat(cancelEvents.getFirst().relatedInsightEntityIds()).containsExactly(p.id());
    }

    /** 构造一个 ACTIVE 状态、importance=0.5 的当前版本实体。 */
    private TemporalEntity 构造实体(EntityType type, String name, String description) {
        var now = Instant.now();
        return new TemporalEntity(
                null,
                type,
                name,
                description,
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
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                false,
                List.of()
        );
    }
}

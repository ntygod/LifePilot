package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lifepilot.agent.task.proactive.AutonomyRepository;
import com.lifepilot.agent.task.proactive.TrustUpgradeService;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRecord;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRepository;
import com.lifepilot.agent.task.reminder.ReminderFeedbackType;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.lifecycle.events.EntityWeightChanged;
import com.lifepilot.memory.lifecycle.feedback.FeedbackLedgerRepository;
import com.lifepilot.memory.lifecycle.feedback.FeedbackThresholdConfig;
import com.lifepilot.memory.lifecycle.listeners.NegativeFeedbackListener;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.VersionMerger;
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
 * 场景 S14：提醒反馈溯源到 insight — 同一 {@code insight_entity_id} 的 3 个提醒被标
 * "无用" 时，{@link TrustUpgradeService#recordNegativeFeedback(String, String, String)}
 * 3-arg 版查 {@code proactive_reminder_feedback.insight_entity_id} 反查 insight 实体，
 * 对其施加 {@value TrustUpgradeService#NEGATIVE_FEEDBACK_DELTA} 的 importanceScore
 * 惩罚（实际 -0.5），累计 3 次产生 3 条 {@link EntityWeightChanged}，
 * {@link NegativeFeedbackListener} 达到 count=3 阈值把 insight 转
 * {@link LifecycleState#SUPERSEDED}，{@code lifecycleReason="NEGATIVE_FEEDBACK_THRESHOLD"}。
 *
 * <p>验证目标：<b>真实走 TrustUpgradeService 3-arg 路径</b>，不降级到 SemanticMemory
 * 直调。关键点：
 * <ul>
 *   <li>初始 insight importanceScore=0.5，每次 -0.5 = {0.0, -0.5, -1.0}；</li>
 *   <li>第 3 次 cumulativeScore=-1.0，不满足 {@code score < -1.0} 严格阈值，靠 count=3 触发；</li>
 *   <li>第 3 次 {@link NegativeFeedbackListener} 转 SUPERSEDED —— source=NEGATIVE_FEEDBACK。</li>
 * </ul>
 *
 * <p><b>实施路径</b>：
 * <ol>
 *   <li>Flyway V1-V18 真跑（V18 给 {@code proactive_reminder_feedback} 加 insight_entity_id 列）；</li>
 *   <li>插入 1 条 L3 PREFERENCE 实体（insight）+ 3 条 {@code notification_history} 行
 *       + 3 条 {@code proactive_reminder_feedback} 关联行（各自 insight_entity_id=ins-x）；</li>
 *   <li>事件总线 lambda：{@link EntityWeightChanged} → {@link NegativeFeedbackListener#onWeightChanged}，
 *       模拟 {@code @TransactionalEventListener(AFTER_COMMIT)}；</li>
 *   <li>3 次 {@link TrustUpgradeService#recordNegativeFeedback(String, String, String)}；</li>
 *   <li>断言：insight SUPERSEDED + reason=NEGATIVE_FEEDBACK_THRESHOLD + 3 条 weight 事件 +
 *       账本 3 行 delta&lt;0 + cumulative 序列 {0.0, -0.5, -1.0}。</li>
 * </ol>
 *
 * <p><b>降级说明</b>：不走 @SpringBootTest（同 B16/B17）；
 * {@link NegativeFeedbackListener} 是 {@code @TransactionalEventListener(AFTER_COMMIT)}，
 * 单测以 lambda publisher 同步转发。
 *
 * @author zsg
 * @since 2026-04-24
 */
@DisplayName("场景 S14 提醒负反馈溯源到 insight")
class 提醒反馈溯源到insight_场景测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-24T10:00:00Z");
    private static final String SPACE_ID = "space-s14";
    private static final String USER_ID = "user-x";
    private static final String BEHAVIOR = "send-reminder";
    private static final String INSIGHT_ID = "ins-x";
    private static final String INSIGHT_NAME = "proactive_insight_prefer_evening";
    private static final String TOPIC_KEY = "daily-recap";
    private static final List<String> NOTIFICATION_IDS = List.of("n-1", "n-2", "n-3");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private NegativeFeedbackListener negativeFeedbackListener;
    private TrustUpgradeService trustUpgradeService;
    private ReminderFeedbackRepository reminderFeedbackRepo;
    private FeedbackLedgerRepository ledger;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-scenario-s14-" + dbId + ".db");
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

        ledger = new FeedbackLedgerRepository(jdbcTemplate);
        var cfg = new FeedbackThresholdConfig();
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        negativeFeedbackListener = new NegativeFeedbackListener(ledger, semanticMemory, cfg, clock);

        reminderFeedbackRepo = new ReminderFeedbackRepository(jdbcTemplate);
        var autonomyRepo = new AutonomyRepository(jdbcTemplate);
        // TrustUpgradeService 完整 4-arg 构造 —— 启用 3-arg recordNegativeFeedback 溯源路径
        trustUpgradeService = new TrustUpgradeService(
                autonomyRepo, /*config*/ null, reminderFeedbackRepo, semanticMemory);

        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher forwardingPublisher = event -> {
            publishedEvents.add(event);
            if (event instanceof EntityWeightChanged weightChanged) {
                negativeFeedbackListener.onWeightChanged(weightChanged);
            }
            // SemanticMemory 自发的 EntityLifecycleChanged 只计数，不二次转发
        };
        semanticMemory.setEventPublisher(forwardingPublisher);

        插入记忆空间(SPACE_ID);
        插入INSIGHT(INSIGHT_ID, INSIGHT_NAME, 0.5f);
        for (var nid : NOTIFICATION_IDS) {
            插入Notification(nid, USER_ID);
            插入ReminderFeedback(nid, USER_ID, TOPIC_KEY, INSIGHT_ID);
        }
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
    @DisplayName("3 次 recordNegativeFeedback → insight 累计 3 条负反馈 → 转 SUPERSEDED")
    void 三次提醒负反馈应将关联insight转SUPERSEDED() {
        // 1. 初始：insight ACTIVE，importanceScore=0.5
        var before = semanticMemory.findById(INSIGHT_ID).orElseThrow();
        assertThat(before.lifecycleState()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(before.importanceScore()).isEqualTo(0.5f);

        // 2. 动作：3 次 recordNegativeFeedback（每次关联不同 notification，但同 insight）
        //    预期 importanceScore 序列：0.5 → 0.0 → -0.5 → -1.0
        //    cumulativeScore（事件携带的就是新 score）：0.0 / -0.5 / -1.0
        //    delta 均为 -0.5（负），count 递增到 3 → 触发 SUPERSEDED
        for (var nid : NOTIFICATION_IDS) {
            trustUpgradeService.recordNegativeFeedback(USER_ID, BEHAVIOR, nid);
        }

        // 3. 断言：insight 最终 SUPERSEDED
        var after = semanticMemory.findById(INSIGHT_ID).orElseThrow();
        assertThat(after.lifecycleState())
                .as("3 次负反馈累计达 count 阈值（默认 3），insight 应转 SUPERSEDED")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(after.lifecycleReason())
                .as("NegativeFeedbackListener 写入固定 reason")
                .isEqualTo("NEGATIVE_FEEDBACK_THRESHOLD");
        assertThat(after.importanceScore())
                .as("importanceScore 最终应为 -1.0（未夹紧下界，用于累计分阈值判定）")
                .isEqualTo(-1.0f);

        // 4. 事件链路：3 条 EntityWeightChanged + 1 条 EntityLifecycleChanged
        var weightEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityWeightChanged)
                .map(e -> (EntityWeightChanged) e)
                .toList();
        assertThat(weightEvents)
                .as("3 次 recordNegativeFeedback → 3 条 WeightChanged")
                .hasSize(3);
        assertThat(weightEvents)
                .extracting(EntityWeightChanged::entityId)
                .as("全部事件目标实体都是同一 insight")
                .containsOnly(INSIGHT_ID);
        assertThat(weightEvents)
                .extracting(EntityWeightChanged::cumulativeScore)
                .as("cumulativeScore 随每次扣减：0.0 → -0.5 → -1.0（与 importanceScore 同值）")
                .containsExactly(0.0, -0.5, -1.0);
        assertThat(weightEvents)
                .allSatisfy(evt -> {
                    assertThat(evt.delta()).isEqualTo(-0.5);
                    assertThat(evt.source().name()).isEqualTo("USER_FEEDBACK");
                });

        var lifecycleEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents)
                .as("最后一次扣减达阈值后 NegativeFeedbackListener 代调 updateLifecycleState 发 1 条 LifecycleChanged")
                .hasSize(1);
        assertThat(lifecycleEvents.getFirst().source())
                .as("source=NEGATIVE_FEEDBACK（与 NegativeFeedbackListener 的常量对齐）")
                .isEqualTo(ChangeSource.NEGATIVE_FEEDBACK);
        assertThat(lifecycleEvents.getFirst().newState()).isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(lifecycleEvents.getFirst().entityId()).isEqualTo(INSIGHT_ID);

        // 5. 账本审计：3 条 delta<0（每次反馈都入账）
        int totalRows = 读取账本行数(INSIGHT_ID);
        int negativeRows = 读取负反馈账本行数(INSIGHT_ID);
        assertThat(totalRows)
                .as("每次 updateImportanceScore 都会追加账本行")
                .isEqualTo(3);
        assertThat(negativeRows)
                .as("全部 3 行 delta < 0")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("notification 未关联 insight 时溯源跳过，不影响 autonomy 主流程")
    void 无insight关联的提醒反馈_溯源路径应静默跳过() {
        // 插一条不关联 insight 的 notification + feedback
        String orphanNid = "n-orphan";
        插入Notification(orphanNid, USER_ID);
        插入ReminderFeedback(orphanNid, USER_ID, TOPIC_KEY, /*insight*/ null);

        trustUpgradeService.recordNegativeFeedback(USER_ID, BEHAVIOR, orphanNid);

        // insight 未受影响
        var after = semanticMemory.findById(INSIGHT_ID).orElseThrow();
        assertThat(after.lifecycleState())
                .as("无关联 insight 的反馈不应影响任何 insight")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(after.importanceScore()).isEqualTo(0.5f);

        // autonomy 主流程仍走到 upsert —— 验证 recordNegativeFeedback 不因溯源跳过而抛错
        assertThat(publishedEvents.stream()
                .filter(e -> e instanceof EntityWeightChanged).count())
                .as("无关联 insight 时不触发 importanceScore 惩罚 → 无 WeightChanged")
                .isZero();
    }

    // ---------- 测试夹具 ----------

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "S14 测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private void 插入INSIGHT(String entityId, String name, float initialScore) {
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
                entityId, SPACE_ID, name, name,
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                FIXED_NOW.toString(), FIXED_NOW.toString());
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_versions(
                    id, entity_id, version_no, description,
                    extraction_confidence, importance_score,
                    is_current, valid_from, created_at, updated_at)
                VALUES (?, ?, 1, ?, 0.9, ?, 1, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entityId, "desc-" + entityId,
                initialScore,
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private void 插入Notification(String notificationId, String userId) {
        jdbcTemplate.update(
                """
                INSERT INTO notification_history(
                    id, user_id, content_json, channel, sent_at, created_at, updated_at)
                VALUES (?, ?, ?, 'WEB', ?, ?, ?)
                """,
                notificationId, userId, "{}",
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    /** 插入一条 reminder feedback 行，{@code insightEntityId} 可为 null 模拟未关联 insight 的场景。 */
    private void 插入ReminderFeedback(String notificationId, String userId,
                                          String topicKey, String insightEntityId) {
        var record = new ReminderFeedbackRecord(
                UUID.randomUUID().toString(),
                notificationId,
                userId,
                topicKey,
                ReminderFeedbackType.NOT_RELEVANT,
                /*comment*/ null,
                insightEntityId,
                FIXED_NOW,
                FIXED_NOW);
        reminderFeedbackRepo.saveFeedback(record);
    }

    private int 读取账本行数(String entityId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_feedback_ledger WHERE entity_id = ?",
                Integer.class, entityId);
        return n == null ? 0 : n;
    }

    private int 读取负反馈账本行数(String entityId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_feedback_ledger WHERE entity_id = ? AND delta < 0",
                Integer.class, entityId);
        return n == null ? 0 : n;
    }
}

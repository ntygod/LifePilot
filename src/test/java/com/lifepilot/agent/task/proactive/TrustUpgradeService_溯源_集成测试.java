package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.reminder.ReminderFeedbackRepository;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.feedback.FeedbackLedgerRepository;
import com.lifepilot.memory.lifecycle.feedback.FeedbackThresholdConfig;
import com.lifepilot.memory.lifecycle.listeners.NegativeFeedbackListener;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.VersionMerger;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Task 25 集成测试：{@link TrustUpgradeService#recordNegativeFeedback(String, String, String)}
 * 的 notification → insight 溯源链路 —— 从"用户标 notification 无用"到"insight 实体转
 * SUPERSEDED"的端到端验证。
 *
 * <p>构造路径与 {@code NegativeFeedbackListener_集成测试} 一致：
 * <ul>
 *   <li>Flyway 真跑全部迁移（V1 - V18）</li>
 *   <li>真 {@link SemanticMemory}（向量/冲突/合并 mock）+ 真 {@link FeedbackLedgerRepository}
 *       + 真 {@link NegativeFeedbackListener}（订阅 publishedEvents）</li>
 *   <li>通过 JdbcTemplate 直接 INSERT memory_spaces / notification_history /
 *       memory_entities / memory_entity_versions / proactive_reminder_feedback 骨架行</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("TrustUpgradeService 溯源集成测试")
class TrustUpgradeService_溯源_集成测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final String SPACE_ID = "space-溯源";
    private static final String USER_ID = "user-1";
    private static final String BEHAVIOR = "reminder";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private ReminderFeedbackRepository feedbackRepo;
    private TrustUpgradeService trustService;
    private FeedbackLedgerRepository ledger;
    private NegativeFeedbackListener listener;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-trust-溯源-" + dbId + ".db");
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

        // 捕获事件并转发给 listener —— 还原"AFTER_COMMIT 代发 → NegativeFeedbackListener 消费"
        publishedEvents = new ArrayList<>();
        ledger = new FeedbackLedgerRepository(jdbcTemplate);
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        listener = new NegativeFeedbackListener(ledger, semanticMemory, new FeedbackThresholdConfig(), clock);
        ApplicationEventPublisher publisher = event -> {
            publishedEvents.add(event);
            if (event instanceof com.lifepilot.memory.lifecycle.events.EntityWeightChanged ew) {
                // 同步触发 listener —— 避免依赖 Spring 事务事件机制
                listener.onWeightChanged(ew);
            }
        };
        semanticMemory.setEventPublisher(publisher);

        feedbackRepo = new ReminderFeedbackRepository(jdbcTemplate);
        var autonomyRepo = new AutonomyRepository(jdbcTemplate);
        trustService = new TrustUpgradeService(autonomyRepo, null, feedbackRepo, semanticMemory);

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
    void 连续3次无用反馈应使insightSUPERSEDED() {
        // 安排：插 insight 实体 ins-1（初始 importance=0.5），并为其建 3 条 notification + feedback 关联
        插入ACTIVE实体("ins-1", 0.5f);
        String n1 = 插入Notification();
        String n2 = 插入Notification();
        String n3 = 插入Notification();
        插入反馈关联(n1, "ins-1");
        插入反馈关联(n2, "ins-1");
        插入反馈关联(n3, "ins-1");

        // 执行：3 次无用反馈
        trustService.recordNegativeFeedback(USER_ID, BEHAVIOR, n1);
        trustService.recordNegativeFeedback(USER_ID, BEHAVIOR, n2);
        trustService.recordNegativeFeedback(USER_ID, BEHAVIOR, n3);

        // 断言：insight 转 SUPERSEDED（第 3 次累计分 -1.5 < -1.0 立即触发，也满足 count=3）
        assertThat(读取LifecycleState("ins-1"))
                .as("3 次无用反馈累计后应触发 SUPERSEDED")
                .isEqualTo(LifecycleState.SUPERSEDED);

        // 断言：账本记录 3 行 delta=-0.5
        assertThat(读取账本行数("ins-1"))
                .as("每次溯源惩罚都写账本")
                .isEqualTo(3);

        // 断言：恰一次 EntityLifecycleChanged 事件，source=NEGATIVE_FEEDBACK
        var lifecycleEvents = publishedEvents.stream()
                .filter(e -> e instanceof com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged)
                .map(e -> (com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents).hasSize(1);
        assertThat(lifecycleEvents.getFirst().source()).isEqualTo(ChangeSource.NEGATIVE_FEEDBACK);
        assertThat(lifecycleEvents.getFirst().newState()).isEqualTo(LifecycleState.SUPERSEDED);
    }

    @Test
    void 单次无用反馈insight仍ACTIVE但importanceScore下降() {
        插入ACTIVE实体("ins-2", 0.7f);
        String n1 = 插入Notification();
        插入反馈关联(n1, "ins-2");

        trustService.recordNegativeFeedback(USER_ID, BEHAVIOR, n1);

        // 1 次不够触发阈值（count=1 < 3，cumulative=0.2 > -1.0）
        assertThat(读取LifecycleState("ins-2"))
                .as("1 次反馈不应触发 SUPERSEDED")
                .isEqualTo(LifecycleState.ACTIVE);

        // importance 由 0.7 → 0.2（-0.5）
        assertThat(读取Importance("ins-2"))
                .as("importance 下降 NEGATIVE_FEEDBACK_DELTA=-0.5")
                .isCloseTo(0.2f, org.assertj.core.data.Offset.offset(0.001f));

        assertThat(读取账本行数("ins-2")).isEqualTo(1);
    }

    @Test
    void notification无insight关联应跳过不抛() {
        // 没有 feedback 行 —— notification 不属于 insight 来源
        String orphanNotification = 插入Notification();

        // 期望：不抛异常
        trustService.recordNegativeFeedback(USER_ID, BEHAVIOR, orphanNotification);

        // 断言：没有任何账本写入，也没有事件
        assertThat(publishedEvents)
                .filteredOn(e -> e instanceof com.lifepilot.memory.lifecycle.events.EntityWeightChanged)
                .as("无关联时不触发 importanceScore 更新")
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

    private void 插入ACTIVE实体(String entityId, float importance) {
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
                VALUES (?, ?, 1, ?, 0.9, ?, 1, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entityId, "desc-" + entityId, importance,
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private String 插入Notification() {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO notification_history(
                    id, user_id, type_id, content_json, channel, read_status, status,
                    sent_at, created_at, updated_at)
                VALUES (?, ?, 'proactive_reminder', '{}', 'web', 'UNREAD', 'SENT', ?, ?, ?)
                """,
                id, USER_ID, FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
        return id;
    }

    private void 插入反馈关联(String notificationId, String insightEntityId) {
        jdbcTemplate.update(
                """
                INSERT INTO proactive_reminder_feedback(
                    id, notification_id, user_id, topic_key, feedback_type,
                    insight_entity_id, created_at, updated_at)
                VALUES (?, ?, ?, 'topic-test', 'NOT_RELEVANT', ?, ?, ?)
                """,
                UUID.randomUUID().toString(), notificationId, USER_ID, insightEntityId,
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private LifecycleState 读取LifecycleState(String entityId) {
        String raw = jdbcTemplate.queryForObject(
                "SELECT lifecycle_state FROM memory_entities WHERE id = ?",
                String.class, entityId);
        return LifecycleState.valueOf(raw);
    }

    private float 读取Importance(String entityId) {
        Float v = jdbcTemplate.queryForObject(
                "SELECT importance_score FROM memory_entity_versions WHERE entity_id = ? AND is_current = 1",
                Float.class, entityId);
        return v == null ? 0f : v;
    }

    private int 读取账本行数(String entityId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_feedback_ledger WHERE entity_id = ?",
                Integer.class, entityId);
        return n == null ? 0 : n;
    }
}

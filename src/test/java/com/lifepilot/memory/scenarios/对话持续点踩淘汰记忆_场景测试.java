package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.WeightSource;
import com.lifepilot.memory.governance.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.governance.lifecycle.events.EntityWeightChanged;
import com.lifepilot.memory.governance.lifecycle.feedback.FeedbackLedgerRepository;
import com.lifepilot.memory.governance.lifecycle.feedback.FeedbackThresholdConfig;
import com.lifepilot.memory.governance.lifecycle.listeners.NegativeFeedbackListener;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
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
 * 场景 S13：对话持续点踩淘汰记忆 — 用户对同一对话轮产生的注入记忆多次点踩，
 * 累计达到阈值后对应 PREFERENCE 实体沿
 * {@link LifecycleState#ACTIVE} → {@link LifecycleState#SUPERSEDED} 转换，
 * {@code lifecycleReason} 为 {@code NEGATIVE_FEEDBACK_THRESHOLD}。
 *
 * <p>验证目标：用户连续点踩同一条助手回答 3 次（或跨多条指向同一 PREFERENCE
 * 的注入），应推进该 PREFERENCE 累计负反馈 count 达阈值，引发 SUPERSEDED；
 * 后续 HybridRetriever 召回应排除该实体，不再污染新对话。</p>
 *
 * <p><b>实施路径</b>（降级版）：
 * <ol>
 *   <li>SQLite + Flyway 真跑 V1-V18；</li>
 *   <li>真 SemanticMemory + NegativeFeedbackListener + FeedbackLedgerRepository；</li>
 *   <li>前置：手动插 PREFERENCE "pref-x" ACTIVE 实体 + 当前版本（importance=0.5）；</li>
 *   <li>动作：3 次 {@code semanticMemory.updateImportanceScore("pref-x", 递减 newScore, WeightSource.USER_FEEDBACK)}
 *       产生 3 条 {@link EntityWeightChanged} 负 delta；</li>
 *   <li>事件总线：lambda 转发 EntityWeightChanged → listener.onWeightChanged，
 *       模拟 {@code @TransactionalEventListener(AFTER_COMMIT)} 语义；</li>
 *   <li>断言：PREFERENCE 转 SUPERSEDED + reason=NEGATIVE_FEEDBACK_THRESHOLD。</li>
 * </ol>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>未走 @SpringBootTest；</li>
 *   <li><b>未走 FeedbackProcessor + InjectionRecord 溯源层</b>：
 *       {@code FeedbackProcessor.processFeedbackForEntry} 依赖 {@code MessageFeedbackRepository
 *       .findByEntryId} 查询既往反馈并做"同类型重复跳过"逻辑 —— 同一条
 *       {@code entry_id} 连续 3 次 dislike 只会触发第 1 次的 importance 降权，
 *       第 2/3 次被 processor 的"previousType == currentType → skip"分支
 *       拦截。真实 E2E 的 3 次点踩通常对应不同 entry / 不同轮，
 *       或 like→dislike→like→dislike 的切换；</li>
 *   <li>本场景保留 S13 的语义核心——"USER_FEEDBACK 累计 3 次 count 阈值 → SUPERSEDED"，
 *       直接调 {@code updateImportanceScore} 跳过"条目→注入实体"溯源；
 *       完整溯源由 {@code FeedbackProcessor_集成测试} 覆盖（不在本类内重复）；</li>
 *   <li>{@link NegativeFeedbackListener} 是 {@code @TransactionalEventListener(AFTER_COMMIT)}，
 *       单测无事务管理器，改由 lambda publisher 同步转发。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-24
 */
@DisplayName("场景 S13 持续点踩淘汰记忆")
class 对话持续点踩淘汰记忆_场景测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-24T10:00:00Z");
    private static final String SPACE_ID = "space-s13";
    private static final String PREF_ID = "pref-咖啡偏好";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private NegativeFeedbackListener listener;
    private FeedbackLedgerRepository ledger;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-scenario-s13-" + dbId + ".db");
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

        ledger = new FeedbackLedgerRepository(jdbcTemplate);
        var vectorSearcher = mock(VectorSearcher.class);
        var conflictDetector = mock(ConflictDetector.class);
        var versionMerger = mock(VersionMerger.class);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, versionMerger, vectorSearcher);
        MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);

        var cfg = new FeedbackThresholdConfig();
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        listener = new NegativeFeedbackListener(ledger, semanticMemory, cfg, clock);

        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher forwardingPublisher = event -> {
            publishedEvents.add(event);
            if (event instanceof EntityWeightChanged weightChanged) {
                listener.onWeightChanged(weightChanged);
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
    @DisplayName("3 次 USER_FEEDBACK 降权 → PREFERENCE 转 SUPERSEDED + reason=NEGATIVE_FEEDBACK_THRESHOLD")
    void 连续3次dislike应使PREFERENCE转SUPERSEDED() {
        // 1. 前置：PREFERENCE ACTIVE 初始 importance=0.5
        插入ACTIVE_PREFERENCE(PREF_ID, 0.5f);
        assertThat(读取LifecycleState(PREF_ID)).isEqualTo(LifecycleState.ACTIVE);

        // 2. 3 次 USER_FEEDBACK 降权（MemoryProperties.Feedback.dislikePenalty=0.05 默认），
        //    这里每次按 -0.1 递减方便复现阈值触发；实际 penalty 取 application.yml
        //    配置值，不影响 count 触发逻辑
        semanticMemory.updateImportanceScore(PREF_ID, 0.4f, WeightSource.USER_FEEDBACK);
        semanticMemory.updateImportanceScore(PREF_ID, 0.3f, WeightSource.USER_FEEDBACK);
        semanticMemory.updateImportanceScore(PREF_ID, 0.2f, WeightSource.USER_FEEDBACK);

        // 3. 断言：3 次达 count 阈值 → SUPERSEDED
        var after = semanticMemory.findById(PREF_ID).orElseThrow();
        assertThat(after.lifecycleState())
                .as("3 次 USER_FEEDBACK 达阈值应转 SUPERSEDED")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(after.lifecycleReason())
                .as("reason=NEGATIVE_FEEDBACK_THRESHOLD")
                .isEqualTo("NEGATIVE_FEEDBACK_THRESHOLD");

        // 4. 账本审计：3 条 delta<0 入账（源=USER_FEEDBACK）
        assertThat(读取账本行数(PREF_ID)).isEqualTo(3);
        assertThat(读取USER_FEEDBACK账本行数(PREF_ID))
                .as("3 条全部标记为 USER_FEEDBACK 来源（可与 QUALITY_REJECT/EFFECTIVENESS 区分）")
                .isEqualTo(3);

        // 5. 事件链路：3 条 WeightChanged + 1 条 LifecycleChanged(SUPERSEDED, NEGATIVE_FEEDBACK)
        assertThat(publishedEvents.stream()
                .filter(e -> e instanceof EntityWeightChanged).count())
                .isEqualTo(3);
        var lifecycleEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents)
                .as("仅阈值达到时发一条 LifecycleChanged")
                .hasSize(1);
        assertThat(lifecycleEvents.getFirst().source())
                .isEqualTo(ChangeSource.NEGATIVE_FEEDBACK);
        assertThat(lifecycleEvents.getFirst().newState())
                .isEqualTo(LifecycleState.SUPERSEDED);
    }

    @Test
    @DisplayName("单次极端点踩导致 cumulativeScore 击穿阈值 → 立即 SUPERSEDED")
    void 单次极端点踩cumulativeScore击穿阈值应立即SUPERSEDED() {
        插入ACTIVE_PREFERENCE(PREF_ID, 0.5f);

        // 单次 newScore=-0.6，delta=-1.1 超过默认阈值 -1.0 的绝对值，cumulative=-0.6 < -1.0 ? 否
        // 改为 newScore=-1.2：delta=-1.7，cumulative=-1.2 < -1.0 → 触发
        semanticMemory.updateImportanceScore(PREF_ID, -1.2f, WeightSource.USER_FEEDBACK);

        assertThat(读取LifecycleState(PREF_ID))
                .as("cumulativeScore 击穿 -1.0 阈值，count=1 亦不重要")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(读取账本行数(PREF_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("点赞抵消点踩：delta>=0 的反馈不判定阈值")
    void 点赞不推进SUPERSEDED() {
        插入ACTIVE_PREFERENCE(PREF_ID, 0.5f);

        // 两次正反馈（点赞），delta 为正
        semanticMemory.updateImportanceScore(PREF_ID, 0.6f, WeightSource.USER_FEEDBACK);
        semanticMemory.updateImportanceScore(PREF_ID, 0.7f, WeightSource.USER_FEEDBACK);

        assertThat(读取LifecycleState(PREF_ID))
                .as("全正反馈不转 SUPERSEDED")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(读取账本行数(PREF_ID))
                .as("账本仍记录正反馈用于审计")
                .isEqualTo(2);
        assertThat(publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged).count())
                .as("正反馈不推进 SUPERSEDED")
                .isZero();
    }

    // ---------- 测试夹具 ----------

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "S13 测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    /** 插入一条 ACTIVE + PERSISTENT PREFERENCE 实体 + 当前版本行。 */
    private void 插入ACTIVE_PREFERENCE(String entityId, float initialScore) {
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
                UUID.randomUUID().toString(), entityId, "偏好描述-" + entityId,
                initialScore,
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private LifecycleState 读取LifecycleState(String entityId) {
        String raw = jdbcTemplate.queryForObject(
                "SELECT lifecycle_state FROM memory_entities WHERE id = ?",
                String.class, entityId);
        return LifecycleState.valueOf(raw);
    }

    private int 读取账本行数(String entityId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_feedback_ledger WHERE entity_id = ?",
                Integer.class, entityId);
        return n == null ? 0 : n;
    }

    private int 读取USER_FEEDBACK账本行数(String entityId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_feedback_ledger WHERE entity_id = ? AND source = 'USER_FEEDBACK'",
                Integer.class, entityId);
        return n == null ? 0 : n;
    }
}

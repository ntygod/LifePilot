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
 * 场景 S9：垃圾经验被累计负反馈淘汰 — EXPERIENCE 实体连续 3 次
 * {@link WeightSource#QUALITY_REJECT} 反馈后自动沿
 * {@link LifecycleState#ACTIVE} → {@link LifecycleState#SUPERSEDED} 转换，
 * {@code lifecycleReason} 记录 {@code NEGATIVE_FEEDBACK_THRESHOLD}。
 *
 * <p>验证目标：LLM 工具链失败产出的垃圾 EXPERIENCE（如格式错误、结论错误的经验）
 * 通过质量巡检（或人类审阅）被 3 次打上 QUALITY_REJECT 后，累计次数达到
 * {@code memory.feedback.negative-threshold-count=3} 默认阈值，应自动
 * SUPERSEDED，不再进入后续 HybridRetriever 召回污染新任务。</p>
 *
 * <p><b>实施路径</b>：
 * <ol>
 *   <li>SQLite 临时文件 + Flyway 真跑 V1-V18；</li>
 *   <li>SemanticMemory 真实实例（依赖 ConflictDetector / VersionMerger / VectorSearcher 全 mock —
 *       本场景只 touch {@link SemanticMemory#updateImportanceScore}
 *       + {@link SemanticMemory#findById} + {@link SemanticMemory#updateLifecycleState}）；</li>
 *   <li>手动构造 ACTIVE EXPERIENCE 实体行；</li>
 *   <li>事件总线：lambda 转发 {@link EntityWeightChanged} →
 *       {@link NegativeFeedbackListener#onWeightChanged}（模拟
 *       {@code @TransactionalEventListener(AFTER_COMMIT)} 的语义）；</li>
 *   <li>连续 3 次 {@code semanticMemory.updateImportanceScore("exp-x", 递减 newScore, QUALITY_REJECT)}
 *       产出 3 条负 delta 事件；</li>
 *   <li>断言：实体转 SUPERSEDED + lifecycleReason=NEGATIVE_FEEDBACK_THRESHOLD +
 *       账本 3 行 delta&lt;0。</li>
 * </ol>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>未走 @SpringBootTest 完整上下文（同 B16 降级原因）；</li>
 *   <li>{@link NegativeFeedbackListener} 是 {@code @TransactionalEventListener(AFTER_COMMIT)} —
 *       单测无 Spring 事务管理器，改由 lambda publisher 同步转发模拟 AFTER_COMMIT；</li>
 *   <li>{@link SemanticMemory#updateLifecycleState} 内部发
 *       {@link EntityLifecycleChanged}，在测试里该事件被同一 lambda 捕获用作断言计数，
 *       不再下游转发（避免误入 NegativeFeedbackListener —— 本类只订阅
 *       {@link EntityWeightChanged}）。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-24
 */
@DisplayName("场景 S9 垃圾 EXPERIENCE 累计否定淘汰")
class 垃圾经验被累计负反馈淘汰_场景测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-24T10:00:00Z");
    private static final String SPACE_ID = "space-s9";
    private static final String ENTITY_ID = "exp-垃圾";

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
                "lifepilot-scenario-s9-" + dbId + ".db");
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

        // 事件总线：EntityWeightChanged → listener.onWeightChanged
        // 同时捕获 EntityLifecycleChanged 用于断言 SemanticMemory 的代发行为
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
    @DisplayName("3 次 QUALITY_REJECT 累计 → EXPERIENCE 转 SUPERSEDED + reason=NEGATIVE_FEEDBACK_THRESHOLD")
    void 连续3次QUALITY_REJECT应将EXPERIENCE转SUPERSEDED() {
        // 1. 前置：垃圾 EXPERIENCE 初始 importance=0.5 ACTIVE
        插入ACTIVE_EXPERIENCE(ENTITY_ID, 0.5f);
        assertThat(读取LifecycleState(ENTITY_ID))
                .as("初始状态应为 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);

        // 2. 3 次 QUALITY_REJECT —— 每次 newScore 在旧值基础上递减 0.4
        //    delta: 0.1-0.5=-0.4, (-0.3)-0.1=-0.4, (-0.7)-(-0.3)=-0.4
        //    cumulativeScore: 0.1, -0.3, -0.7（均 > -1.0 阈值，靠 count 触发）
        semanticMemory.updateImportanceScore(ENTITY_ID, 0.1f, WeightSource.QUALITY_REJECT);
        semanticMemory.updateImportanceScore(ENTITY_ID, -0.3f, WeightSource.QUALITY_REJECT);
        semanticMemory.updateImportanceScore(ENTITY_ID, -0.7f, WeightSource.QUALITY_REJECT);

        // 3. 断言：3 次后转 SUPERSEDED + 记录 NEGATIVE_FEEDBACK_THRESHOLD 理由
        var after = semanticMemory.findById(ENTITY_ID).orElseThrow();
        assertThat(after.lifecycleState())
                .as("3 次 QUALITY_REJECT 达 count 阈值应转 SUPERSEDED")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(after.lifecycleReason())
                .as("理由应为 NEGATIVE_FEEDBACK_THRESHOLD（NegativeFeedbackListener 的常量）")
                .isEqualTo("NEGATIVE_FEEDBACK_THRESHOLD");

        // 4. 账本审计：3 条 delta<0 入账
        assertThat(读取账本行数(ENTITY_ID))
                .as("每次反馈都追加一行账本（审计需求）")
                .isEqualTo(3);
        assertThat(读取负反馈账本行数(ENTITY_ID))
                .as("3 条全为负 delta")
                .isEqualTo(3);

        // 5. 事件链路：3 条 EntityWeightChanged + 1 条 EntityLifecycleChanged（阈值触发时代发）
        assertThat(publishedEvents.stream()
                .filter(e -> e instanceof EntityWeightChanged).count())
                .as("3 次 updateImportanceScore 产生 3 条 WeightChanged")
                .isEqualTo(3);
        var lifecycleEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents)
                .as("仅最后一次负反馈达阈值时发一条 LifecycleChanged")
                .hasSize(1);
        assertThat(lifecycleEvents.getFirst().source())
                .as("source=NEGATIVE_FEEDBACK（NegativeFeedbackListener 代调 updateLifecycleState 时注入）")
                .isEqualTo(ChangeSource.NEGATIVE_FEEDBACK);
        assertThat(lifecycleEvents.getFirst().newState())
                .isEqualTo(LifecycleState.SUPERSEDED);
    }

    @Test
    @DisplayName("2 次 QUALITY_REJECT 未达阈值 → EXPERIENCE 保持 ACTIVE")
    void 仅2次QUALITY_REJECT不应触发SUPERSEDED() {
        插入ACTIVE_EXPERIENCE(ENTITY_ID, 0.5f);

        // 2 次 —— count=2 < 3，cumulativeScore=0.1/-0.3 都 > -1.0
        semanticMemory.updateImportanceScore(ENTITY_ID, 0.1f, WeightSource.QUALITY_REJECT);
        semanticMemory.updateImportanceScore(ENTITY_ID, -0.3f, WeightSource.QUALITY_REJECT);

        assertThat(读取LifecycleState(ENTITY_ID))
                .as("未达阈值保持 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(读取账本行数(ENTITY_ID))
                .as("账本仍追加 2 行用于审计")
                .isEqualTo(2);
    }

    // ---------- 测试夹具 ----------

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "S9 测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    /**
     * 插入一条 ACTIVE + PERSISTENT EXPERIENCE 实体 + 当前版本行（带 importance_score）。
     * {@link SemanticMemory#updateImportanceScore} 只更新 {@code memory_entity_versions.importance_score}
     * 列，所以必须先有 {@code is_current=1} 版本。
     */
    private void 插入ACTIVE_EXPERIENCE(String entityId, float initialScore) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, temporality, is_derived)
                VALUES (?, ?, 'PRIVATE', 'EXPERIENCE', ?, ?, 'UNKNOWN', 'ACTIVE', 0,
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
                UUID.randomUUID().toString(), entityId, "垃圾经验描述-" + entityId,
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

    private int 读取负反馈账本行数(String entityId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_feedback_ledger WHERE entity_id = ? AND delta < 0",
                Integer.class, entityId);
        return n == null ? 0 : n;
    }
}

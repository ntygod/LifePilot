package com.lifepilot.memory.governance.lifecycle;

import com.lifepilot.memory.governance.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.store.support.SemanticMemoryTestSupport;
import com.lifepilot.memory.governance.lifecycle.events.EntityWeightChanged;
import com.lifepilot.memory.governance.lifecycle.feedback.FeedbackLedgerRepository;
import com.lifepilot.memory.governance.lifecycle.feedback.FeedbackThresholdConfig;
import com.lifepilot.memory.governance.lifecycle.listeners.NegativeFeedbackListener;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * {@link NegativeFeedbackListener} 集成测试 —— Flyway 真跑 + 真 SemanticMemory + 真
 * {@link FeedbackLedgerRepository}，验证账本入账与阈值驱动的 SUPERSEDED 转换。
 *
 * <p>构造路径：
 * <ul>
 *   <li>SQLite 临时文件 + {@link SingleConnectionDataSource} + Flyway classpath 迁移</li>
 *   <li>SemanticMemory 真实实例（依赖的 VectorSearcher / ConflictDetector / VersionMerger 全 mock），
 *       挂上 in-memory {@link ApplicationEventPublisher} 用于断言仅发一次
 *       {@link EntityLifecycleChanged}</li>
 *   <li>手动构造实体行：直接 INSERT memory_entities + memory_entity_versions 最小骨架</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("NegativeFeedbackListener 集成测试")
class NegativeFeedbackListener_集成测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final String SPACE_ID = "space-负反馈";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private FeedbackLedgerRepository ledger;
    private SemanticMemory semanticMemory;
    private NegativeFeedbackListener listener;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-negfeedback-" + dbId + ".db");
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

        // SemanticMemory 真实实例，但向量/冲突/合并全 mock —— 本测试只 touch findById + updateLifecycleState
        var vectorSearcher = mock(VectorSearcher.class);
        var conflictDetector = mock(ConflictDetector.class);
        var versionMerger = mock(VersionMerger.class);
        var projectionService = MemoryProjectionTestSupport.create(jdbcTemplate, vectorSearcher);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, versionMerger, vectorSearcher, SemanticMemoryTestSupport.memorySpaceRepository(jdbcTemplate), projectionService);

        // 事件捕获器：断言只发一次 EntityLifecycleChanged（没有双发）
        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = publishedEvents::add;
        semanticMemory.setEventPublisher(publisher);

        var cfg = new FeedbackThresholdConfig();
        // 默认值 count=3, score=-1.0 —— 测试用例按需覆盖

        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        listener = new NegativeFeedbackListener(ledger, semanticMemory, cfg, clock);

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
    void 连续3次负反馈应触发SUPERSEDED() {
        插入ACTIVE实体("e-1");

        // 三次负反馈：每次都未达累计分阈值 -1.0，但第三次次数到 3
        listener.onWeightChanged(new EntityWeightChanged("e-1", -0.3, -0.3, WeightSource.USER_FEEDBACK));
        listener.onWeightChanged(new EntityWeightChanged("e-1", -0.3, -0.6, WeightSource.USER_FEEDBACK));
        listener.onWeightChanged(new EntityWeightChanged("e-1", -0.3, -0.9, WeightSource.USER_FEEDBACK));

        assertThat(读取LifecycleState("e-1"))
                .as("连续 3 次负反馈达 count 阈值应转 SUPERSEDED")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(读取账本行数("e-1"))
                .as("每次反馈都写账本")
                .isEqualTo(3);

        // 仅最后一次转换触发了 EntityLifecycleChanged
        var lifecycleEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents)
                .as("只应在阈值达到时发一次事件，由 SemanticMemory 代发")
                .hasSize(1);
        assertThat(lifecycleEvents.getFirst().source()).isEqualTo(ChangeSource.NEGATIVE_FEEDBACK);
        assertThat(lifecycleEvents.getFirst().newState()).isEqualTo(LifecycleState.SUPERSEDED);
    }

    @Test
    void 单次累计分低于阈值也触发SUPERSEDED() {
        插入ACTIVE实体("e-2");

        // 单次 cumulativeScore=-1.5 < -1.0，count=1 < 3 —— 走 score 分支
        listener.onWeightChanged(new EntityWeightChanged("e-2", -1.5, -1.5, WeightSource.USER_FEEDBACK));

        assertThat(读取LifecycleState("e-2"))
                .as("cumulativeScore 低于阈值应直接触发")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(读取账本行数("e-2")).isEqualTo(1);
    }

    @Test
    void 正反馈写账本但不触发SUPERSEDED() {
        插入ACTIVE实体("e-3");

        listener.onWeightChanged(new EntityWeightChanged("e-3", 0.5, 0.5, WeightSource.USER_FEEDBACK));

        assertThat(读取LifecycleState("e-3"))
                .as("正反馈不应改变状态")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(读取账本行数("e-3"))
                .as("正反馈也写账本用于审计")
                .isEqualTo(1);
        assertThat(publishedEvents)
                .filteredOn(e -> e instanceof EntityLifecycleChanged)
                .isEmpty();
    }

    @Test
    void 已SUPERSEDED实体幂等不重转() {
        插入SUPERSEDED实体("e-4");

        listener.onWeightChanged(new EntityWeightChanged("e-4", -0.8, -0.8, WeightSource.USER_FEEDBACK));

        assertThat(读取LifecycleState("e-4"))
                .as("非 ACTIVE 实体幂等跳过")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(读取账本行数("e-4"))
                .as("账本仍追加一行，用于审计持续失败")
                .isEqualTo(1);
        assertThat(publishedEvents)
                .filteredOn(e -> e instanceof EntityLifecycleChanged)
                .as("非 ACTIVE 不触发代发事件")
                .isEmpty();
    }

    @Test
    void 两次负反馈但未达count阈值不触发() {
        插入ACTIVE实体("e-5");

        listener.onWeightChanged(new EntityWeightChanged("e-5", -0.3, -0.3, WeightSource.USER_FEEDBACK));
        listener.onWeightChanged(new EntityWeightChanged("e-5", -0.3, -0.6, WeightSource.USER_FEEDBACK));

        assertThat(读取LifecycleState("e-5"))
                .as("count=2 <3 且 cumulativeScore=-0.6 >-1.0，两条件都不满足")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(读取账本行数("e-5")).isEqualTo(2);
        assertThat(publishedEvents)
                .filteredOn(e -> e instanceof EntityLifecycleChanged)
                .isEmpty();
    }

    @Test
    void SUPERSEDED状态更新失败应直接暴露() {
        插入ACTIVE实体("e-fail");
        SemanticMemory spyMemory = spy(semanticMemory);
        doThrow(new RuntimeException("生命周期更新失败"))
                .when(spyMemory).updateLifecycleState(
                        org.mockito.ArgumentMatchers.eq("e-fail"),
                        any(LifecycleState.class),
                        anyString(),
                        any(ChangeSource.class));
        var failingListener = new NegativeFeedbackListener(
                ledger,
                spyMemory,
                new FeedbackThresholdConfig(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> failingListener.onWeightChanged(
                new EntityWeightChanged("e-fail", -1.5, -1.5, WeightSource.USER_FEEDBACK)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("生命周期更新失败");

        assertThat(读取LifecycleState("e-fail")).isEqualTo(LifecycleState.ACTIVE);
        assertThat(读取账本行数("e-fail")).isEqualTo(1);
        assertThat(publishedEvents)
                .filteredOn(e -> e instanceof EntityLifecycleChanged)
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

    private void 插入ACTIVE实体(String entityId) {
        插入实体行(entityId, LifecycleState.ACTIVE);
    }

    private void 插入SUPERSEDED实体(String entityId) {
        插入实体行(entityId, LifecycleState.SUPERSEDED);
    }

    private void 插入实体行(String entityId, LifecycleState state) {
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
                UUID.randomUUID().toString(), entityId, "desc-" + entityId,
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
}

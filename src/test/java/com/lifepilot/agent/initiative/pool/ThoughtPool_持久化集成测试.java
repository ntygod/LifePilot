package com.lifepilot.agent.initiative.pool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtKind;
import com.lifepilot.agent.initiative.model.ThoughtState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 想法池持久化集成测试 —— 验证 submit 落库、状态转换持久化、重启从库恢复活跃想法。
 *
 * <p>内存 SQLite + 直建 initiative_thoughts 表（schema 对照 V3__create_initiative_tables.sql）。</p>
 *
 * @author zsg
 * @since 2026-06-07
 */
class ThoughtPool_持久化集成测试 {

    private SingleConnectionDataSource dataSource;
    private ThoughtRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE initiative_thoughts (
                    id              TEXT PRIMARY KEY,
                    intent_key      TEXT NOT NULL,
                    kind            TEXT NOT NULL,
                    summary         TEXT NOT NULL,
                    evidence_json   TEXT NOT NULL DEFAULT '[]',
                    confidence      REAL NOT NULL DEFAULT 0.0,
                    maturity        REAL NOT NULL DEFAULT 0.0,
                    state           TEXT NOT NULL DEFAULT 'BREWING',
                    action_pattern  TEXT,
                    conversation_id TEXT,
                    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
                    mature_at       TEXT,
                    expressed_at    TEXT,
                    resolved_at     TEXT,
                    last_reinforced_at TEXT
                )
                """);
        repository = new ThoughtRepository(jdbcTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private Thought brewing(String id, String intentKey, ThoughtKind kind, float maturity) {
        var created = Instant.parse("2026-06-07T00:00:00Z");
        return new Thought(id, intentKey, kind, "概要-" + id, List.of(),
                0.7f, maturity, created, null,
                ThoughtState.BREWING, null, created);
    }

    private ThoughtPool newPool() {
        return new ThoughtPool(10, Duration.ofHours(6), Duration.ofHours(24), repository,
                new com.lifepilot.agent.initiative.maturity.MaturityModel(
                        new com.lifepilot.agent.initiative.maturity.MaturityModel.Config(
                                0.6f, 0.5f, 0.15f, 0.15f, 48.0, 24.0, 72.0)));
    }

    @Test
    void submit应落库() {
        var pool = newPool();

        pool.submit(brewing("t1", "intent-a", ThoughtKind.REMINDER, 0.3f));

        var loaded = repository.findById("t1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().intentKey()).isEqualTo("intent-a");
        assertThat(loaded.get().state()).isEqualTo(ThoughtState.BREWING);
    }

    @Test
    void 新建ThoughtPool应从库恢复活跃想法() {
        var pool1 = newPool();
        pool1.submit(brewing("t1", "intent-a", ThoughtKind.REMINDER, 0.3f));
        pool1.submit(brewing("t2", "intent-b", ThoughtKind.FOLLOW_UP, 0.8f));  // maturity≥0.6 → READY

        // 模拟重启：用同一仓库新建想法池
        var pool2 = newPool();

        assertThat(pool2.findById("t1")).isPresent();
        assertThat(pool2.findById("t2")).isPresent();
        assertThat(pool2.activeCount()).isEqualTo(2);
    }

    @Test
    void transition应持久化状态() {
        var pool = newPool();
        pool.submit(brewing("t1", "intent-a", ThoughtKind.REMINDER, 0.3f));

        pool.transition("t1", ThoughtState.DISMISSED);

        var loaded = repository.findById("t1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().state()).isEqualTo(ThoughtState.DISMISSED);
    }

    @Test
    void 终态想法不应被新池恢复为活跃() {
        var pool1 = newPool();
        pool1.submit(brewing("t1", "intent-a", ThoughtKind.REMINDER, 0.3f));
        pool1.transition("t1", ThoughtState.ABSORBED);

        var pool2 = newPool();

        // 终态想法不在 BREWING/READY 恢复集合内
        assertThat(pool2.findById("t1")).isEmpty();
        assertThat(pool2.activeCount()).isZero();
    }

    @Test
    void evolve改变成熟度后应持久化且新池恢复连续值() {
        var pool1 = newPool();
        // lastReinforcedAt = created(2026-06-07)，maturity 0.8
        pool1.submit(brewing("t1", "intent-a", ThoughtKind.FOLLOW_UP, 0.8f));

        // 演化到 created + 120h：超宽限期(24h) + 2 个半衰期(48h) → 0.8 * 0.25 = 0.2
        Instant now = Instant.parse("2026-06-07T00:00:00Z").plus(Duration.ofHours(120));
        int changed = pool1.evolve(now);
        assertThat(changed).isEqualTo(1);

        // 模拟重启：新池从库恢复演化后的成熟度
        var pool2 = newPool();
        var recovered = pool2.findById("t1").orElseThrow();
        assertThat(recovered.maturity()).isCloseTo(0.2f, org.assertj.core.data.Offset.offset(1e-3f));
        assertThat(recovered.state()).isEqualTo(ThoughtState.BREWING);
    }
}

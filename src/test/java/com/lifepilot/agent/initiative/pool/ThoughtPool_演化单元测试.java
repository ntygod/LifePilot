package com.lifepilot.agent.initiative.pool;

import com.lifepilot.agent.initiative.maturity.MaturityModel;
import com.lifepilot.agent.initiative.model.Evidence;
import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtKind;
import com.lifepilot.agent.initiative.model.ThoughtState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ThoughtPool 成熟度演化单元测试（纯内存，repository=null）。
 *
 * @author zsg
 * @since 2026-06-07
 */
class ThoughtPool_演化单元测试 {

    private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");
    private static final MaturityModel MODEL = new MaturityModel(
            new MaturityModel.Config(0.6f, 0.5f, 0.15f, 0.15f, 48.0, 24.0, 72.0));

    private ThoughtPool pool() {
        return new ThoughtPool(20, Duration.ofHours(72), Duration.ofHours(48), null, MODEL);
    }

    private Thought thought(String id, String intentKey, float maturity, Instant matureAt,
                            Instant lastReinforcedAt, ThoughtState state, float evidenceRelevance) {
        var ev = new Evidence("memory_entity", "e-" + id, null, "摘要", "hint", NOW, evidenceRelevance);
        return new Thought(id, intentKey, ThoughtKind.FOLLOW_UP, "概要", List.of(ev),
                0.6f, maturity, NOW, matureAt, state, null, lastReinforcedAt);
    }

    @Test
    void submit去重走reinforce而非固定加0_1() {
        var p = pool();
        p.submit(thought("t1", "intent-a", 0.30f, null, NOW, ThoughtState.BREWING, 1.0f));
        // 同 intentKey 再次提交，证据权重 1.0 → reinforce(0.30, 1.0) = 0.30 + 0.15*1.0*0.70 = 0.405
        var merged = p.submit(thought("t2", "intent-a", 0.30f, null, NOW, ThoughtState.BREWING, 1.0f));

        assertThat(merged.maturity()).isCloseTo(0.405f, within(1e-4f));
        assertThat(merged.maturity()).isNotEqualTo(0.40f);  // 非固定 +0.1
    }

    @Test
    void submit去重增量随证据权重变化() {
        var pHigh = pool();
        pHigh.submit(thought("a1", "k", 0.30f, null, NOW, ThoughtState.BREWING, 1.0f));
        float high = pHigh.submit(thought("a2", "k", 0.30f, null, NOW, ThoughtState.BREWING, 1.0f)).maturity();

        var pLow = pool();
        pLow.submit(thought("b1", "k", 0.30f, null, NOW, ThoughtState.BREWING, 0.3f));
        float low = pLow.submit(thought("b2", "k", 0.30f, null, NOW, ThoughtState.BREWING, 0.3f)).maturity();

        assertThat(high).isGreaterThan(low);  // 证据越强，强化越多
    }

    @Test
    void evolve_超宽限期未强化成熟度下降() {
        var p = pool();
        // lastReinforcedAt = NOW - 100h（远超 grace 24h）
        p.submit(thought("t1", "intent-a", 0.80f, null, NOW.minus(Duration.ofHours(100)),
                ThoughtState.READY, 0.5f));

        p.evolve(NOW);

        float m = p.findById("t1").orElseThrow().maturity();
        assertThat(m).isLessThan(0.80f);
    }

    @Test
    void evolve_跌破下限迁移DISMISSED() {
        var p = pool();
        // maturity 0.20，停滞 500h → 衰减远低于 0.15 下限
        p.submit(thought("t1", "intent-a", 0.20f, null, NOW.minus(Duration.ofHours(500)),
                ThoughtState.BREWING, 0.5f));

        p.evolve(NOW);

        assertThat(p.findById("t1").orElseThrow().state()).isEqualTo(ThoughtState.DISMISSED);
        assertThat(p.activeCount()).isZero();
    }

    @Test
    void evolve_截止临近升温至READY() {
        var p = pool();
        // BREWING maturity 0.30，截止 1 小时后（窗口内），lastReinforcedAt=NOW（无衰减）
        p.submit(thought("t1", "intent-a", 0.30f, NOW.plus(Duration.ofHours(1)), NOW,
                ThoughtState.BREWING, 0.5f));

        p.evolve(NOW);

        var t = p.findById("t1").orElseThrow();
        assertThat(t.maturity()).isGreaterThanOrEqualTo(0.6f);
        assertThat(t.state()).isEqualTo(ThoughtState.READY);
    }

    @Test
    void evolve_终态想法不被重新激活() {
        var p = pool();
        // 已表达（终态前的 EXPRESSED 非终态？EXPRESSED 不是 isTerminal）——用 DISMISSED 验证终态
        p.submit(thought("t1", "intent-a", 0.05f, null, NOW, ThoughtState.DISMISSED, 0.5f));

        p.evolve(NOW);

        assertThat(p.findById("t1").orElseThrow().state()).isEqualTo(ThoughtState.DISMISSED);
    }

    @Test
    void cleanup_READY无成熟时间时按创建时间过期() {
        var p = new ThoughtPool(20, Duration.ofHours(72), Duration.ofHours(1), null, MODEL);
        Instant createdAt = Instant.now().minus(Duration.ofHours(2));
        var ev = new Evidence("memory_entity", "e-ready", null, "摘要", "hint", createdAt, 0.8f);
        p.submit(new Thought("t1", "intent-ready", ThoughtKind.FOLLOW_UP, "概要", List.of(ev),
                0.8f, 0.8f, createdAt, null, ThoughtState.READY, null, createdAt));

        int cleaned = p.cleanup();

        assertThat(cleaned).isEqualTo(1);
        assertThat(p.findById("t1").orElseThrow().state()).isEqualTo(ThoughtState.DISMISSED);
    }
}

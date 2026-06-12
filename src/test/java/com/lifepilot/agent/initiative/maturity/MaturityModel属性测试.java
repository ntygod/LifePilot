package com.lifepilot.agent.initiative.maturity;

import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtKind;
import com.lifepilot.agent.initiative.model.ThoughtState;
import net.jqwik.api.*;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.FloatRange;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MaturityModel 属性测试 —— 验证成熟度演化的确定性数学性质（thought-maturity-evolution）。
 *
 * @author zsg
 * @since 2026-06-07
 */
class MaturityModel属性测试 {

    private static final Instant BASE = Instant.parse("2026-06-07T00:00:00Z");
    private static final MaturityModel.Config CFG =
            new MaturityModel.Config(0.6f, 0.5f, 0.15f, 0.15f, 48.0, 24.0, 72.0);
    private final MaturityModel model = new MaturityModel(CFG);

    private Thought thought(float maturity, Instant matureAt, Instant lastReinforcedAt,
                            ThoughtState state) {
        return new Thought("id", "intent", ThoughtKind.REMINDER, "概要", List.of(),
                0.7f, maturity, BASE, matureAt, state, null, lastReinforcedAt);
    }

    // Property 1: reinforce 与 computeMaturity 输出恒在 [0,1]
    @Property
    void 成熟度有界(@ForAll @FloatRange(min = 0f, max = 1f) float maturity,
                @ForAll @FloatRange(min = 0f, max = 1f) float weight,
                @ForAll @DoubleRange(min = 0, max = 2000) double hoursElapsed) {
        float r = model.reinforce(maturity, weight);
        assertThat(r).isBetween(0f, 1f);

        var t = thought(maturity, null, BASE, ThoughtState.BREWING);
        float c = model.computeMaturity(t, BASE.plusSeconds((long) (hoursElapsed * 3600)));
        assertThat(c).isBetween(0f, 1f);
    }

    // Property 2: 强化单调不减且边际递减
    @Property
    void 强化单调不减且边际递减(@ForAll @FloatRange(min = 0f, max = 0.8f) float m,
                       @ForAll @FloatRange(min = 0.01f, max = 1f) float weight) {
        float reinforced = model.reinforce(m, weight);
        assertThat(reinforced).isGreaterThanOrEqualTo(m);

        // 边际递减：更高起点的增量不超过更低起点的增量
        float lowGain = model.reinforce(m, weight) - m;
        float higher = Math.min(0.95f, m + 0.1f);
        float highGain = model.reinforce(higher, weight) - higher;
        assertThat(highGain).isLessThanOrEqualTo(lowGain + 1e-6f);
    }

    // Property 3: 无截止 + 宽限期内 → maturity 不变
    @Property
    void 无截止且宽限期内成熟度不变(@ForAll @FloatRange(min = 0f, max = 1f) float maturity,
                          @ForAll @DoubleRange(min = 0, max = 24) double hoursInGrace) {
        var t = thought(maturity, null, BASE, ThoughtState.BREWING);
        Instant now = BASE.plusSeconds((long) (hoursInGrace * 3600));
        assertThat(model.computeMaturity(t, now)).isEqualTo(maturity);
    }

    // Property 4: 无截止时衰减单调（越晚越不增）
    @Property
    void 衰减单调(@ForAll @FloatRange(min = 0.2f, max = 1f) float maturity,
             @ForAll @DoubleRange(min = 25, max = 500) double h1,
             @ForAll @DoubleRange(min = 25, max = 500) double h2) {
        var t = thought(maturity, null, BASE, ThoughtState.BREWING);
        double early = Math.min(h1, h2);
        double late = Math.max(h1, h2);
        float mEarly = model.computeMaturity(t, BASE.plusSeconds((long) (early * 3600)));
        float mLate = model.computeMaturity(t, BASE.plusSeconds((long) (late * 3600)));
        assertThat(mLate).isLessThanOrEqualTo(mEarly + 1e-6f);
    }

    // Property 5: 截止越近 maturity 越高（隔离 pull：maturity=0 且 lastReinforcedAt=now）
    @Property
    void 截止越近成熟度越高(@ForAll @DoubleRange(min = 1, max = 70) double remain1,
                    @ForAll @DoubleRange(min = 1, max = 70) double remain2) {
        Instant matureAt = BASE.plusSeconds((long) (100 * 3600));
        double closer = Math.min(remain1, remain2);   // 剩余更少 = 更近
        double farther = Math.max(remain1, remain2);
        Instant nowCloser = matureAt.minusSeconds((long) (closer * 3600));
        Instant nowFarther = matureAt.minusSeconds((long) (farther * 3600));
        float mCloser = model.computeMaturity(thought(0f, matureAt, nowCloser, ThoughtState.BREWING), nowCloser);
        float mFarther = model.computeMaturity(thought(0f, matureAt, nowFarther, ThoughtState.BREWING), nowFarther);
        assertThat(mCloser).isGreaterThanOrEqualTo(mFarther - 1e-6f);
    }

    // Property 6: 迟滞带内状态不变
    @Property
    void 迟滞带内状态不变(@ForAll @FloatRange(min = 0.5f, max = 0.59f) float maturity,
                  @ForAll boolean ready) {
        ThoughtState current = ready ? ThoughtState.READY : ThoughtState.BREWING;
        assertThat(model.resolveState(maturity, current)).isEqualTo(current);
    }

    // Property 7: 确定性 —— 相同输入多次 evolve 结果一致
    @Property
    void 演化确定性(@ForAll @FloatRange(min = 0f, max = 1f) float maturity,
              @ForAll @DoubleRange(min = 0, max = 500) double hours) {
        var t = thought(maturity, BASE.plusSeconds(200 * 3600), BASE, ThoughtState.BREWING);
        Instant now = BASE.plusSeconds((long) (hours * 3600));
        var r1 = model.evolve(t, now);
        var r2 = model.evolve(t, now);
        assertThat(r1).isEqualTo(r2);
    }

    // 点值校验：半衰期处衰减为一半
    @Example
    void 半衰期点衰减为一半() {
        var t = thought(0.8f, null, BASE, ThoughtState.BREWING);
        // grace 24h + halfLife 48h = 72h 处应为 0.8 * 0.5 = 0.4
        float m = model.computeMaturity(t, BASE.plusSeconds(72 * 3600));
        assertThat(m).isCloseTo(0.4f, org.assertj.core.data.Offset.offset(1e-3f));
    }

    // 点值校验：逾期截止 pull = 最大
    @Example
    void 逾期截止成熟度拉满() {
        var t = thought(0.1f, BASE.minusSeconds(3600), BASE, ThoughtState.BREWING);
        assertThat(model.computeMaturity(t, BASE)).isEqualTo(1f);
    }

    // 点值校验：跌破下限 → DISMISSED
    @Example
    void 跌破下限淘汰() {
        assertThat(model.resolveState(0.1f, ThoughtState.BREWING)).isEqualTo(ThoughtState.DISMISSED);
        assertThat(model.resolveState(0.1f, ThoughtState.READY)).isEqualTo(ThoughtState.DISMISSED);
    }

    // 点值校验：终态不被改写
    @Example
    void 终态不被改写() {
        assertThat(model.resolveState(0.05f, ThoughtState.EXPRESSED)).isEqualTo(ThoughtState.EXPRESSED);
        assertThat(model.resolveState(0.9f, ThoughtState.ABSORBED)).isEqualTo(ThoughtState.ABSORBED);
    }
}

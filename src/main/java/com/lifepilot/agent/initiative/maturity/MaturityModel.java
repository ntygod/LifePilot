package com.lifepilot.agent.initiative.maturity;

import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtState;

import java.time.Duration;
import java.time.Instant;

/**
 * 想法成熟度演化模型 — 确定性纯函数（thought-maturity-evolution）。
 *
 * <p>三种力驱动 maturity 演化：</p>
 * <ul>
 *   <li><b>reinforce 强化</b>（离散）：新证据到达时按证据权重提升 maturity，接近 1.0 时边际递减。</li>
 *   <li><b>deadline pull 截止升温</b>（连续）：带未来截止锚点（{@code matureAt}）的想法随临近升温，逾期取最大。</li>
 *   <li><b>staleness decay 停滞衰减</b>（连续）：超过宽限期后按半衰期对 maturity 指数衰减。</li>
 * </ul>
 *
 * <p>maturity 驱动 BREWING/READY/DISMISSED 状态迁移（带迟滞，避免阈值附近抖动）。
 * 全程不调用 LLM、不依赖随机数、不内部读时钟（{@code now} 由调用方传入），保证可复现。</p>
 *
 * @author zsg
 * @since 2026-06-07
 */
public final class MaturityModel {

    /**
     * 成熟度演化配置。
     *
     * @param readyThreshold        就绪阈值（≥ 则 BREWING→READY）
     * @param demoteThreshold       降级阈值（READY 且 &lt; 则回退 BREWING；与 readyThreshold 构成迟滞带）
     * @param dismissFloor          淘汰下限（≤ 则 DISMISSED）
     * @param reinforceBaseGain     强化基础增益（再乘证据权重与边际递减系数）
     * @param decayHalfLifeHours    停滞衰减半衰期（小时）
     * @param decayGraceHours       衰减宽限期（小时，期内不衰减）
     * @param deadlinePullWindowHours 截止升温窗口（小时，截止前此窗口内 pull 从 0 升至 1）
     */
    public record Config(float readyThreshold, float demoteThreshold, float dismissFloor,
                         float reinforceBaseGain, double decayHalfLifeHours,
                         double decayGraceHours, double deadlinePullWindowHours) {}

    /**
     * 单次演化结果。
     *
     * @param maturity 演化后成熟度
     * @param state    演化后状态
     * @param changed  相对入参是否发生变化（maturity 或 state）
     */
    public record EvolveResult(float maturity, ThoughtState state, boolean changed) {}

    private final Config config;

    public MaturityModel(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    /**
     * 证据强化：按证据权重提升 maturity，接近 1.0 时边际递减。
     *
     * @param currentMaturity 当前成熟度 [0,1]
     * @param evidenceWeight  新证据权重（取证据 relevance，[0,1]）
     * @return 强化后的成熟度（不超过 1.0，单调不减）
     */
    public float reinforce(float currentMaturity, float evidenceWeight) {
        float m = clamp01(currentMaturity);
        float w = clamp01(evidenceWeight);
        float gain = config.reinforceBaseGain() * w * (1f - m);
        return clamp01(m + gain);
    }

    /**
     * 演化计算：deadline pull（下托底）与 staleness decay（作用于自然 maturity）合成。
     *
     * @param thought 想法（读取 maturity / matureAt / lastReinforcedAt / createdAt）
     * @param now     当前时间
     * @return 演化后的成熟度
     */
    public float computeMaturity(Thought thought, Instant now) {
        float base = clamp01(thought.maturity());

        // staleness decay：超过宽限期后指数半衰期衰减
        Instant since = thought.lastReinforcedAt() != null
                ? thought.lastReinforcedAt() : thought.createdAt();
        double hoursSince = hoursBetween(since, now);
        double decayFactor = 1.0;
        if (hoursSince > config.decayGraceHours()) {
            double elapsed = hoursSince - config.decayGraceHours();
            decayFactor = Math.pow(0.5, elapsed / config.decayHalfLifeHours());
        }
        float decayed = clamp01((float) (base * decayFactor));

        // deadline pull：截止临近升温，逾期取最大
        float pull = deadlinePull(thought.matureAt(), now);

        return clamp01(Math.max(decayed, pull));
    }

    /** 截止升温贡献：无锚点为 0，逾期为 1，否则随剩余时间在窗口内线性升至 1。 */
    private float deadlinePull(Instant matureAt, Instant now) {
        if (matureAt == null) return 0f;
        if (!matureAt.isAfter(now)) return 1f;  // 逾期（含相等）
        double remainingHours = hoursBetween(now, matureAt);
        double window = config.deadlinePullWindowHours();
        if (remainingHours >= window) return 0f;  // 窗口外
        return clamp01((float) (1.0 - remainingHours / window));
    }

    /**
     * maturity 驱动状态迁移（带迟滞 + 淘汰下限）。终态原样返回。
     */
    public ThoughtState resolveState(float maturity, ThoughtState current) {
        if (current == null || !current.isActive()) {
            return current;  // 仅演化活跃想法（BREWING/READY）；EXPRESSED/终态不改写
        }
        if (maturity <= config.dismissFloor()) {
            return ThoughtState.DISMISSED;
        }
        if (current == ThoughtState.BREWING && maturity >= config.readyThreshold()) {
            return ThoughtState.READY;
        }
        if (current == ThoughtState.READY && maturity < config.demoteThreshold()) {
            return ThoughtState.BREWING;
        }
        return current;  // 迟滞带内保持
    }

    /**
     * 组合演化：computeMaturity + resolveState。
     */
    public EvolveResult evolve(Thought thought, Instant now) {
        float newMaturity = computeMaturity(thought, now);
        ThoughtState newState = resolveState(newMaturity, thought.state());
        boolean changed = Float.compare(newMaturity, thought.maturity()) != 0
                || newState != thought.state();
        return new EvolveResult(newMaturity, newState, changed);
    }

    private static double hoursBetween(Instant a, Instant b) {
        return Duration.between(a, b).toMillis() / 3_600_000.0;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }
}

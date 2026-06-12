package com.lifepilot.agent.learning.forgetting;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.store.entity.TemporalEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Priority Decay 遗忘策略 — 指数衰减公式淘汰低优先级实体。
 *
 * <p>公式：effectivePriority = importanceScore × exp(-λ × daysSinceLastAccess)
 * 淘汰衰减后优先级低于 {@code priorityDecayThreshold} 的实体，
 * 按 effectivePriority 升序排列（最低优先级优先遗忘）。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class PriorityDecayPolicy implements ForgettingPolicy {

    private final AgentLearningProperties.Forgetting config;

    public PriorityDecayPolicy(AgentLearningProperties.Forgetting config) {
        this.config = config;
    }

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        if (budget <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        var now = Instant.now();
        var decayRate = config.getPriorityDecayRate();
        var threshold = config.getPriorityDecayThreshold();

        return candidates.stream()
                // 过滤衰减后优先级低于阈值的实体
                .filter(entity -> computeEffectivePriority(entity, now, decayRate) < threshold)
                // 按 effectivePriority 升序排序（最低优先级优先遗忘）
                .sorted(Comparator.comparingDouble(entity -> computeEffectivePriority(entity, now, decayRate)))
                // 最多返回 budget 个
                .limit(budget)
                .toList();
    }

    @Override
    public String name() {
        return "PriorityDecay";
    }

    /**
     * 计算实体的有效优先级。
     *
     * <p>公式：effectivePriority = importanceScore × exp(-λ × daysSinceLastAccess)
     * 若 {@code lastAccessedAt} 为 null，则使用 {@code createdAt} 作为基准时间。</p>
     *
     * @param entity    目标实体
     * @param now       当前时间
     * @param decayRate 衰减率 λ
     * @return 衰减后的有效优先级，≥ 0
     */
    private static double computeEffectivePriority(TemporalEntity entity, Instant now, float decayRate) {
        var baseTime = entity.lastAccessedAt() != null ? entity.lastAccessedAt() : entity.createdAt();
        var daysSinceLastAccess = Duration.between(baseTime, now).toDays();
        return entity.importanceScore() * Math.exp(-decayRate * daysSinceLastAccess);
    }
}

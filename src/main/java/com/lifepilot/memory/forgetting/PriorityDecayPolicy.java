package com.lifepilot.memory.forgetting;

import com.lifepilot.memory.semantic.TemporalEntity;

import java.util.List;

/**
 * Priority Decay 遗忘策略 — 指数衰减公式淘汰低优先级实体。
 *
 * <p>公式：effectivePriority = importanceScore × exp(-λ × daysSinceLastAccess)
 * 淘汰衰减后优先级低于阈值的实体。
 * 具体实现将在后续任务（11.5）中完成。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class PriorityDecayPolicy implements ForgettingPolicy {

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        // TODO: 任务 11.5 实现
        return List.of();
    }

    @Override
    public String name() {
        return "PriorityDecay";
    }
}

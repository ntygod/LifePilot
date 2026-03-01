package com.lifepilot.memory.forgetting;

import com.lifepilot.memory.semantic.TemporalEntity;

import java.util.List;

/**
 * Hybrid 遗忘策略 — 四阶段顺序执行 FIFO → LRU → PriorityDecay → ReflectionSummary。
 *
 * <p>每阶段独立预算限制（总预算 / 4），防止单阶段遗忘过多实体。
 * 具体实现将在后续任务（11.9）中完成。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class HybridPolicy implements ForgettingPolicy {

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        // TODO: 任务 11.9 实现
        return List.of();
    }

    @Override
    public String name() {
        return "Hybrid";
    }
}

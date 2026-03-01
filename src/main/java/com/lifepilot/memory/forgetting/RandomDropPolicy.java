package com.lifepilot.memory.forgetting;

import com.lifepilot.memory.semantic.TemporalEntity;

import java.util.List;

/**
 * Random Drop 遗忘策略 — 随机选择候选实体进行遗忘。
 *
 * <p>从候选列表中随机选择不超过预算数量的实体。
 * 具体实现将在后续任务（11.9）中完成。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class RandomDropPolicy implements ForgettingPolicy {

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        // TODO: 任务 11.9 实现
        return List.of();
    }

    @Override
    public String name() {
        return "RandomDrop";
    }
}

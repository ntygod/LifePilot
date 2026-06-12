package com.lifepilot.agent.learning.forgetting;

import com.lifepilot.memory.store.entity.TemporalEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Random Drop 遗忘策略 — 随机选择候选实体进行遗忘。
 *
 * <p>从候选列表中随机打乱后选择不超过预算数量的实体。
 * 无状态策略，不依赖任何配置参数。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class RandomDropPolicy implements ForgettingPolicy {

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        if (budget <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 复制一份再打乱，避免修改原始列表（可能是不可变列表）
        var shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled);

        return List.copyOf(shuffled.subList(0, Math.min(budget, shuffled.size())));
    }

    @Override
    public String name() {
        return "RandomDrop";
    }
}

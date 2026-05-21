package com.lifepilot.agent.learning.forgetting;

import com.lifepilot.memory.store.entity.TemporalEntity;

import java.util.List;

/**
 * 遗忘策略 sealed interface — 定义 6 种认知遗忘策略。
 *
 * <p>基于 MaRS 论文的 Hybrid 四阶段遗忘模型，每种策略从候选实体中
 * 选择应被遗忘的实体，返回数量不超过预算限制。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public sealed interface ForgettingPolicy
        permits FifoPolicy, LruPolicy, PriorityDecayPolicy,
                ReflectionSummaryPolicy, RandomDropPolicy, HybridPolicy {

    /**
     * 从候选实体中选择应被遗忘的实体。
     *
     * @param candidates 候选实体列表
     * @param budget     最大遗忘数量
     * @return 应被遗忘的实体列表，数量不超过 budget
     */
    List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget);

    /**
     * 策略名称。
     *
     * @return 策略标识名称
     */
    String name();
}

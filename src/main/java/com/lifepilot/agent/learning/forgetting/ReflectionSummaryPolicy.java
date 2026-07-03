package com.lifepilot.agent.learning.forgetting;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.store.entity.TemporalEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reflection-Summary 遗忘策略 — 选择中等重要度实体用于 LLM 摘要压缩。
 *
 * <p>仅选择 importanceScore 在 [minImportance, maxImportance) 范围内的实体，
 * 按 importanceScore 升序排列（最低重要度优先），限制返回数量不超过预算。
 * 实际的 LLM 摘要生成由 {@code ForgettingEngine} 执行，本策略仅负责候选选择。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class ReflectionSummaryPolicy implements ForgettingPolicy {

    private final AgentLearningProperties.Forgetting config;

    public ReflectionSummaryPolicy(AgentLearningProperties.Forgetting config) {
        this.config = Objects.requireNonNull(config, "config 不能为空");
    }

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        if (budget <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        var minImportance = config.getReflectionSummaryMinImportance();
        var maxImportance = config.getReflectionSummaryMaxImportance();

        return candidates.stream()
                // 过滤：importanceScore 在 [minImportance, maxImportance) 范围内
                .filter(entity -> entity.importanceScore() >= minImportance
                        && entity.importanceScore() < maxImportance)
                // 按 importanceScore 升序排序（最低重要度优先）
                .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore))
                // 最多返回 budget 个
                .limit(budget)
                .toList();
    }

    @Override
    public String name() {
        return "ReflectionSummary";
    }
}

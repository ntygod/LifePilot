package com.lifepilot.memory.forgetting;

import com.lifepilot.memory.semantic.TemporalEntity;

import java.util.List;

/**
 * Reflection-Summary 遗忘策略 — 对中等重要度实体生成 LLM 摘要压缩。
 *
 * <p>仅选择 importanceScore 在 [minImportance, maxImportance) 范围内的实体，
 * 使用 LLM 生成摘要替换原始内容。LLM 不可用时跳过。
 * 具体实现将在后续任务（11.7）中完成。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class ReflectionSummaryPolicy implements ForgettingPolicy {

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        // TODO: 任务 11.7 实现
        return List.of();
    }

    @Override
    public String name() {
        return "ReflectionSummary";
    }
}

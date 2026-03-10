package com.lifepilot.observability.evaluation;

import java.util.List;

/**
 * 评估配置参数 — 允许 eval 和 observability 模块分别传入各自的阈值配置。
 *
 * @param toolSelectionWeight      工具选择正确性权重
 * @param parameterValidityWeight  参数合法性权重
 * @param stepEfficiencyWeight     步骤效率权重
 * @param policyComplianceWeight   策略合规性权重
 * @param tokenEfficiencyWeight    Token 效率权重
 * @param expectedStepCount        期望步骤数（0 表示不限制，使用内置启发式）
 * @param expectedTokenBudget      期望 Token 预算（0 表示不限制，使用内置启发式）
 * @param expectedToolCalls        期望工具调用序列（空列表表示不校验工具选择顺序）
 * @author zsg
 * @since 2026-03-10
 */
public record EvaluationConfig(
        double toolSelectionWeight,
        double parameterValidityWeight,
        double stepEfficiencyWeight,
        double policyComplianceWeight,
        double tokenEfficiencyWeight,
        int expectedStepCount,
        int expectedTokenBudget,
        List<String> expectedToolCalls
) {

    /**
     * 紧凑构造函数 — 保证 expectedToolCalls 不可变。
     */
    public EvaluationConfig {
        expectedToolCalls = expectedToolCalls != null ? List.copyOf(expectedToolCalls) : List.of();
    }

    /**
     * 创建默认配置（等权重，无期望值约束）。
     */
    public static EvaluationConfig defaults() {
        return new EvaluationConfig(0.20, 0.20, 0.20, 0.20, 0.20, 0, 0, List.of());
    }
}

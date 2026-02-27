package com.lifepilot.observability.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 评估步骤 — 记录轨迹评估的五维评分结果。
 *
 * @param stepIndex              步骤序号
 * @param timestamp              发生时间
 * @param duration               耗时
 * @param toolSelectionScore     工具选择正确性评分
 * @param parameterValidityScore 参数合法性评分
 * @param stepEfficiencyScore    步骤效率评分
 * @param policyComplianceScore  策略合规性评分
 * @param tokenEfficiencyScore   Token 效率评分
 * @param overallScore           综合评分
 * @param violations             违规项列表
 * @param suggestions            建议列表
 * @author zsg
 * @since 2026-02-27
 */
public record EvaluationStep(
        int stepIndex,
        Instant timestamp,
        Duration duration,
        double toolSelectionScore,
        double parameterValidityScore,
        double stepEfficiencyScore,
        double policyComplianceScore,
        double tokenEfficiencyScore,
        double overallScore,
        List<String> violations,
        List<String> suggestions
) implements TraceStep {

    /**
     * 紧凑构造函数 — 使用 List.copyOf() 保证集合字段不可变性。
     */
    public EvaluationStep {
        violations = List.copyOf(violations);
        suggestions = List.copyOf(suggestions);
    }

    @Override
    public String typeName() {
        return "evaluation";
    }
}

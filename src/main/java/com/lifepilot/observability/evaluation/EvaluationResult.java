package com.lifepilot.observability.evaluation;

import java.time.Instant;
import java.util.List;

/**
 * 轨迹评估结果 — 记录一次轨迹评估的五维评分和综合评分。
 *
 * @param traceId              追踪 ID
 * @param evaluatedAt          评估时间
 * @param toolSelectionScore   工具选择正确性评分 [0.0, 1.0]
 * @param parameterValidityScore 参数合法性评分 [0.0, 1.0]
 * @param stepEfficiencyScore  步骤效率评分 [0.0, 1.0]
 * @param policyComplianceScore 策略合规性评分 [0.0, 1.0]
 * @param tokenEfficiencyScore Token 效率评分 [0.0, 1.0]
 * @param overallScore         综合评分 [0.0, 1.0]
 * @param actualSteps          实际步骤数
 * @param actualTokens         实际 Token 消耗
 * @param violations           违规项列表
 * @param suggestions          建议列表
 * @author zsg
 * @since 2026-02-27
 */
public record EvaluationResult(
        String traceId,
        Instant evaluatedAt,
        double toolSelectionScore,
        double parameterValidityScore,
        double stepEfficiencyScore,
        double policyComplianceScore,
        double tokenEfficiencyScore,
        double overallScore,
        int actualSteps,
        int actualTokens,
        List<String> violations,
        List<String> suggestions
) {

    /**
     * 紧凑构造函数 — 使用 List.copyOf() 保证集合字段不可变性。
     */
    public EvaluationResult {
        violations = List.copyOf(violations);
        suggestions = List.copyOf(suggestions);
    }
}

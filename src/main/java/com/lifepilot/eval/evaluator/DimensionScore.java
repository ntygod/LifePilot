package com.lifepilot.eval.evaluator;

import java.util.List;

/**
 * 维度评分结果。
 *
 * @param dimensionName 维度名称
 * @param score         评分（0.0 ~ 1.0）
 * @param violations    违规项
 * @param suggestions   改进建议
 * @author zsg
 * @since 2026-08-01
 */
public record DimensionScore(
        String dimensionName,
        double score,
        List<String> violations,
        List<String> suggestions
) {
    public DimensionScore {
        violations = violations != null ? List.copyOf(violations) : List.of();
        suggestions = suggestions != null ? List.copyOf(suggestions) : List.of();
    }
}

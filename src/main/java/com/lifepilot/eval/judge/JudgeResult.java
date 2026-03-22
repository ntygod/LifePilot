package com.lifepilot.eval.judge;

import java.util.List;
import java.util.Map;

/**
 * LLM 评判结果。
 *
 * @param score           评分（0.0 ~ 1.0）
 * @param justification   评判理由
 * @param tokensUsed      评估消耗的 Token 数
 * @param fallback        是否为降级结果
 * @param dimensionScores 子维度评分（accuracy、completeness、safety、style）
 * @param suggestions     改进建议
 * @author zsg
 * @since 2026-08-01
 */
public record JudgeResult(
        double score,
        String justification,
        int tokensUsed,
        boolean fallback,
        Map<String, Double> dimensionScores,
        List<String> suggestions
) {
    public JudgeResult {
        dimensionScores = dimensionScores != null ? Map.copyOf(dimensionScores) : Map.of();
        suggestions = suggestions != null ? List.copyOf(suggestions) : List.of();
    }

    /**
     * 向后兼容的构造方法（无子维度和建议）。
     */
    public JudgeResult(double score, String justification, int tokensUsed, boolean fallback) {
        this(score, justification, tokensUsed, fallback, Map.of(), List.of());
    }
}

package com.lifepilot.eval.judge;

/**
 * LLM 评判结果。
 *
 * @param score         评分（0.0 ~ 1.0）
 * @param justification 评判理由
 * @param tokensUsed    评估消耗的 Token 数
 * @param fallback      是否为降级结果
 * @author zsg
 * @since 2026-08-01
 */
public record JudgeResult(
        double score,
        String justification,
        int tokensUsed,
        boolean fallback
) {
}

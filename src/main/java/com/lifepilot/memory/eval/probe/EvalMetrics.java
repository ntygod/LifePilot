package com.lifepilot.memory.eval.probe;

/**
 * 评估跑批的汇总指标。
 *
 * <p>字段取自 {@link LatencyProbe}、{@link TokenProbe}、{@link RetrievalProbe}
 * 和逐题 Judge 结果。值面向报告和基线对比。</p>
 *
 * @param questionCount     参与评估的题目总数
 * @param llmScore          所有题目 Judge 得分的平均（排除 skipped）
 * @param f1Score           F1Judge 得分平均（若使用）
 * @param exactMatch        ExactMatchJudge 得分平均（若使用）
 * @param hitRate           召回命中率（有 ground truth 的比例）
 * @param p50LatencyMs      p50 检索延迟
 * @param p95LatencyMs      p95 检索延迟
 * @param p99LatencyMs      p99 检索延迟
 * @param avgTokensPerRecall 每次召回平均 token 数
 * @param totalTokens       累计 token
 * @param skippedByJudge    被判定器跳过的题目数（LLM-as-judge 未启用时）
 * @author zsg
 * @since 2026-05-09
 */
public record EvalMetrics(
        int questionCount,
        float llmScore,
        float f1Score,
        float exactMatch,
        float hitRate,
        long p50LatencyMs,
        long p95LatencyMs,
        long p99LatencyMs,
        int avgTokensPerRecall,
        long totalTokens,
        int skippedByJudge
) {
    public static EvalMetrics empty() {
        return new EvalMetrics(0, 0f, 0f, 0f, 0f, 0, 0, 0, 0, 0, 0);
    }
}

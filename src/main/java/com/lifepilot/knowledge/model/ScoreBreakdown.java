package com.lifepilot.knowledge.model;

import java.util.Optional;

/**
 * 检索分数来源明细 — 记录各路检索的原始分数和融合分数。
 *
 * @param vectorScore   向量检索分数
 * @param ftsScore      FTS5 全文检索分数
 * @param rrfFusedScore RRF 融合后分数
 * @param rerankerScore 精排分数（可选）
 * @author zsg
 * @since 2026-03-06
 */
public record ScoreBreakdown(
        double vectorScore,
        double ftsScore,
        double rrfFusedScore,
        Optional<Double> rerankerScore
) {
    public ScoreBreakdown {
        if (rerankerScore == null) rerankerScore = Optional.empty();
    }
}

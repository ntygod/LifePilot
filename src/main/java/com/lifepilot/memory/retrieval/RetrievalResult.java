package com.lifepilot.memory.retrieval;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * 检索结果 — 混合检索引擎返回的单条结果，按 fusedScore 降序排列。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record RetrievalResult(
        String entityId,
        String entityType,
        String name,
        @Nullable String description,
        float fusedScore,
        ScoreBreakdown scoreBreakdown,
        String sourcePath,
        @Nullable Instant lastAccessedAt,
        float importanceScore
) implements Comparable<RetrievalResult> {

    @Override
    public int compareTo(RetrievalResult other) {
        return Float.compare(other.fusedScore, this.fusedScore);
    }

    /**
     * 分数明细 — 记录各路检索的原始分数和加权分数。
     */
    public record ScoreBreakdown(
            float vectorScore, float vectorWeighted,
            float ftsScore, float ftsWeighted,
            float graphScore, float graphWeighted,
            float recencyBoost, float importanceBoost
    ) {}
}

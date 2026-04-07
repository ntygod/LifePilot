package com.lifepilot.knowledge.retrieve;

import java.time.Instant;

/**
 * 检索追踪记录 — 捕获单次检索请求的完整画像。
 *
 * <p>作为 Spring 应用事件发布，供日志、监控、SSE 推送等外部消费。
 *
 * @author zsg
 * @since 2026-04-07
 */
public record RetrievalTrace(
        String traceId,
        String query,
        Instant startTime,
        long totalDurationMs,
        long vectorSearchMs,
        long ftsSearchMs,
        long graphSearchMs,
        long fusionMs,
        long rerankMs,
        int vectorHits,
        int ftsHits,
        int graphHits,
        int deduplicatedCount,
        int fusedCount,
        int finalCount,
        double topScore,
        boolean corrected,
        String correctionReason
) {

    /** 创建基础 trace（无校正信息）。 */
    public static RetrievalTrace of(String traceId, String query, Instant startTime,
                                     long totalDurationMs, long vectorSearchMs,
                                     long ftsSearchMs, long graphSearchMs,
                                     long fusionMs, long rerankMs,
                                     int vectorHits, int ftsHits, int graphHits,
                                     int deduplicatedCount, int fusedCount,
                                     int finalCount, double topScore) {
        return new RetrievalTrace(traceId, query, startTime, totalDurationMs,
                vectorSearchMs, ftsSearchMs, graphSearchMs, fusionMs, rerankMs,
                vectorHits, ftsHits, graphHits, deduplicatedCount, fusedCount,
                finalCount, topScore, false, null);
    }

    /** 标记为已校正的 trace。 */
    public RetrievalTrace withCorrection(String reason) {
        return new RetrievalTrace(traceId, query, startTime, totalDurationMs,
                vectorSearchMs, ftsSearchMs, graphSearchMs, fusionMs, rerankMs,
                vectorHits, ftsHits, graphHits, deduplicatedCount, fusedCount,
                finalCount, topScore, true, reason);
    }
}

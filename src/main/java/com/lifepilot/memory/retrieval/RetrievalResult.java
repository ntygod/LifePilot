package com.lifepilot.memory.retrieval;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * 检索结果 — 混合检索引擎返回的单条结果，按 fusedScore 降序排列。
 *
 * <p>V16 扩展：新增 3 个生命周期标注字段，供下游 LLM 注入时区分"历史/已过期/待复核"语义：
 * <ul>
 *   <li>{@code isHistorical} — 实体处于 {@link com.lifepilot.memory.lifecycle.LifecycleState#COMPLETED}，
 *       仍可召回但应在提示里标"已完成"。</li>
 *   <li>{@code isStale} — 实体处于 {@link com.lifepilot.memory.lifecycle.LifecycleState#REGENERATION_NEEDED}，
 *       降权保留，避免因派生源失效导致上下文空白。</li>
 *   <li>{@code needsRevalidation} — 实体的任一 provenance 记录当前处于 {@code STALE}，
 *       LLM 应主动追问用户以确认其仍然成立。</li>
 * </ul>
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
        float importanceScore,
        @Nullable Instant validTo,
        boolean isHistorical,
        boolean isStale,
        boolean needsRevalidation
) implements Comparable<RetrievalResult> {

    @Override
    public int compareTo(RetrievalResult other) {
        return Float.compare(other.fusedScore, this.fusedScore);
    }

    /**
     * 构造仅变更生命周期标注的副本 — 供 HybridRetriever 在融合后按实际状态回写标注。
     */
    public RetrievalResult withLifecycleAnnotations(boolean isHistorical,
                                                    boolean isStale,
                                                    boolean needsRevalidation) {
        return new RetrievalResult(entityId, entityType, name, description,
                fusedScore, scoreBreakdown, sourcePath, lastAccessedAt,
                importanceScore, validTo,
                isHistorical, isStale, needsRevalidation);
    }

    /**
     * 分数明细 — 记录各路检索的原始分数和加权分数。
     */
    public record ScoreBreakdown(
            float vectorScore, float vectorWeighted,
            float ftsScore, float ftsWeighted,
            float graphScore, float graphWeighted,
            float recencyBoost, float importanceBoost,
            float trustBoost, float lifecycleAdjustment
    ) {
    }
}

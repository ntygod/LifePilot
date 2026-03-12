package com.lifepilot.memory.retrieval;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * 单路检索的排名条目 — VectorSearcher/FtsSearcher/GraphTraverser 的统一输出格式。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record RankedItem(
        String entityId,
        String entityType,
        String name,
        @Nullable String description,
        float score,
        @Nullable Instant lastAccessedAt,
        float importanceScore,
        @Nullable Instant validTo,
        @Nullable Instant updatedAt
) {}

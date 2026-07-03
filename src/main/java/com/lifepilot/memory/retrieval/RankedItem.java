package com.lifepilot.memory.retrieval;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

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
) {
    public RankedItem {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("排名条目 entityId 不能为空");
        }
        if (!entityId.equals(entityId.trim())) {
            throw new IllegalArgumentException("排名条目 entityId 不能包含首尾空白: " + entityId);
        }
        entityType = Objects.requireNonNull(entityType, "排名条目实体类型不能为空");
        if (entityType.isBlank()) {
            throw new IllegalArgumentException("排名条目实体类型不能为空");
        }
        if (!entityType.equals(entityType.trim())) {
            throw new IllegalArgumentException("排名条目实体类型不能包含首尾空白: " + entityType);
        }
        name = Objects.requireNonNull(name, "排名条目名称不能为空");
        if (name.isBlank()) {
            throw new IllegalArgumentException("排名条目名称不能为空");
        }
        if (!name.equals(name.trim())) {
            throw new IllegalArgumentException("排名条目名称不能包含首尾空白: " + name);
        }
        if (description != null && !description.equals(description.trim())) {
            throw new IllegalArgumentException("排名条目描述不能包含首尾空白: " + description);
        }
        if (!Float.isFinite(score) || score < 0.0f) {
            throw new IllegalArgumentException("排名条目分数必须是非负有限数: " + score);
        }
        if (!Float.isFinite(importanceScore) || importanceScore < 0.0f || importanceScore > 1.0f) {
            throw new IllegalArgumentException("排名条目重要度必须在 [0,1] 范围内: " + importanceScore);
        }
    }
}

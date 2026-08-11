package com.lifepilot.memory.retrieval;

/**
 * 向量检索结果 — 包含实体 ID 和相似度得分。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record VectorSearchResult(String entityId, float similarity) {

    public VectorSearchResult {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("向量检索结果 entityId 不能为空");
        }
        if (!entityId.equals(entityId.trim())) {
            throw new IllegalArgumentException("向量检索结果 entityId 不能包含首尾空白: " + entityId);
        }
        if (!Float.isFinite(similarity) || similarity < 0.0f || similarity > 1.0f) {
            throw new IllegalArgumentException("向量检索结果 similarity 必须在 [0,1] 范围内: " + similarity);
        }
    }
}

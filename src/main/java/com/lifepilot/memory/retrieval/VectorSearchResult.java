package com.lifepilot.memory.retrieval;

/**
 * 向量检索结果 — 包含实体 ID 和相似度得分。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record VectorSearchResult(String entityId, float similarity) {}

package com.lifepilot.knowledge.model;

/**
 * 索引结果 — 记录向量索引和 FTS5 索引的执行结果。
 *
 * @param vectorCount 向量索引条目数
 * @param ftsCount    FTS5 索引条目数
 * @param durationMs  索引耗时（毫秒）
 * @author zsg
 * @since 2026-02-25
 */
public record IndexingResult(int vectorCount, int ftsCount, long durationMs) {}

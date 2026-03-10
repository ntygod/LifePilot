package com.lifepilot.datastore.model;

import org.springframework.lang.Nullable;

/**
 * 聚合请求 — 封装时序聚合查询的完整参数。
 *
 * <p>针对 METRIC 类型集合，按指定字段和聚合函数计算统计值，
 * 可选按时间粒度分组和时间范围过滤。</p>
 *
 * @param collectionId 目标集合 ID
 * @param field        聚合字段名称
 * @param func         聚合函数
 * @param groupBy      时间分组粒度（可选）
 * @param startTime    时间范围起始（可选，ISO 8601）
 * @param endTime      时间范围结束（可选，ISO 8601）
 * @author zsg
 * @since 2026-03-10
 */
public record AggregationRequest(
        String collectionId,
        String field,
        AggregateFunction func,
        @Nullable TimeGranularity groupBy,
        @Nullable String startTime,
        @Nullable String endTime
) {
}

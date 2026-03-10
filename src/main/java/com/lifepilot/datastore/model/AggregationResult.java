package com.lifepilot.datastore.model;

/**
 * 聚合结果 — 单个时间桶的聚合计算值。
 *
 * <p>由 AggregationEngine 生成的 SQL 查询返回，
 * 每行对应一个时间桶（如某天、某周、某月）的聚合值。</p>
 *
 * @param timeBucket 时间桶标识（如 "2026-03-10"、"2026-W10"、"2026-03"）
 * @param value      聚合计算值
 * @author zsg
 * @since 2026-03-10
 */
public record AggregationResult(
        String timeBucket,
        double value
) {
}

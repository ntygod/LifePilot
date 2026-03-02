package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * Analytics 用量统计响应。
 *
 * @param totalRequests     总请求数
 * @param totalTokens       总 Token 数
 * @param inputTokens       输入 Token 数（prompt tokens）
 * @param outputTokens      输出 Token 数（completion tokens）
 * @param estimatedCost    估算成本（美元）
 * @param timeRange        时间范围
 * @param dailyStats       每日统计
 * @author zsg
 * @since 2026-02-28
 */
public record UsageStats(
        long totalRequests,
        long totalTokens,
        long inputTokens,
        long outputTokens,
        Double estimatedCost,
        TimeRange timeRange,
        List<DailyStat> dailyStats
) {
    /**
     * 时间范围。
     */
    public record TimeRange(
            String from,
            String to
    ) {
    }

    /**
     * 每日统计。
     */
    public record DailyStat(
            String date,
            long requests,
            long tokens,
            long inputTokens,
            long outputTokens,
            Double cost
    ) {
    }
}

package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * Tool 调用统计响应。
 *
 * @param toolStats  各工具的聚合统计
 * @param dailyTrend 按天聚合的调用趋势
 * @author zsg
 * @since 2026-03-07
 */
public record ToolAnalyticsResponse(
        List<ToolStatItem> toolStats,
        List<DailyTrend> dailyTrend
) {

    /**
     * 单个工具的调用统计项。
     *
     * @param toolId       工具 ID
     * @param toolName     工具显示名称
     * @param callCount    调用总次数
     * @param successCount 成功次数
     * @param failureCount 失败次数
     * @param avgLatencyMs 平均耗时（毫秒）
     */
    public record ToolStatItem(
            String toolId,
            String toolName,
            int callCount,
            int successCount,
            int failureCount,
            long avgLatencyMs
    ) {}

    /**
     * 每日调用趋势数据。
     *
     * @param date         日期（ISO 格式，如 2026-03-01）
     * @param callCount    当日调用总次数
     * @param successCount 当日成功次数
     * @param failureCount 当日失败次数
     */
    public record DailyTrend(
            String date,
            int callCount,
            int successCount,
            int failureCount
    ) {}
}

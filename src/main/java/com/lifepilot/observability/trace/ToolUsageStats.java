package com.lifepilot.observability.trace;

/**
 * 工具使用统计。
 *
 * <p>按工具维度汇总调用次数、成功率和平均耗时，用于前端工具使用分析视图。</p>
 *
 * @param toolId       工具 ID
 * @param callCount    调用次数
 * @param successCount 成功次数
 * @param failureCount 失败次数
 * @param successRate  成功率（0.0-1.0）
 * @param avgDurationMs 平均耗时（毫秒）
 * @author zsg
 * @since 2026-02-28
 */
public record ToolUsageStats(
        String toolId,
        int callCount,
        int successCount,
        int failureCount,
        double successRate,
        double avgDurationMs
) {
}


package com.lifepilot.workflow.model;

import java.util.List;

/**
 * 工作流执行统计。
 *
 * @param workflowId     工作流 ID
 * @param totalInstances 总实例数
 * @param completedCount 完成数
 * @param failedCount    失败数
 * @param cancelledCount 取消数
 * @param runningCount   运行中数
 * @param avgDurationMs  平均执行耗时（毫秒）
 * @param dailyTrends    最近 7 天执行趋势
 * @author zsg
 * @since 2026-03-13
 */
public record WorkflowStats(
        String workflowId,
        int totalInstances, int completedCount, int failedCount,
        int cancelledCount, int runningCount,
        long avgDurationMs,
        List<DailyTrend> dailyTrends
) {
    public WorkflowStats {
        dailyTrends = dailyTrends == null ? List.of() : List.copyOf(dailyTrends);
    }
}

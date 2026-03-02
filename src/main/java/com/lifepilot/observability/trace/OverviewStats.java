package com.lifepilot.observability.trace;

/**
 * 概览统计。
 *
 * <p>用于在指定时间窗口内汇总轨迹数量、成功率、平均步骤数、平均耗时和 Token 消耗等核心指标。</p>
 *
 * @param totalTraces   轨迹总数
 * @param successCount  成功轨迹数量
 * @param failureCount  失败轨迹数量
 * @param successRate   成功率（0.0-1.0）
 * @param avgSteps      平均步骤数
 * @param avgDurationMs 平均耗时（毫秒）
 * @param totalTokens   总 Token 消耗
 * @param avgTokens     平均每条轨迹 Token 消耗
 * @author zsg
 * @since 2026-02-28
 */
public record OverviewStats(
        int totalTraces,
        int successCount,
        int failureCount,
        double successRate,
        double avgSteps,
        double avgDurationMs,
        long totalTokens,
        double avgTokens
) {
}


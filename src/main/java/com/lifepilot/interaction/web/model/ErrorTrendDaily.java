package com.lifepilot.interaction.web.model;

/**
 * 错误趋势每日统计。
 *
 * @param date         日期（yyyy-MM-dd）
 * @param agentErrors  Agent 错误数
 * @param toolErrors   工具错误数
 * @param totalErrors  总错误数
 * @author zsg
 * @since 2026-03-08
 */
public record ErrorTrendDaily(
        String date,
        long agentErrors,
        long toolErrors,
        long totalErrors
) {
}

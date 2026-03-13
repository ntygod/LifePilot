package com.lifepilot.workflow.model;

/**
 * 每日执行趋势数据点。
 *
 * @param date         日期（yyyy-MM-dd 格式）
 * @param totalCount   当日总执行次数
 * @param successCount 当日成功次数
 * @param failedCount  当日失败次数
 * @author zsg
 * @since 2026-03-13
 */
public record DailyTrend(String date, int totalCount, int successCount, int failedCount) {}

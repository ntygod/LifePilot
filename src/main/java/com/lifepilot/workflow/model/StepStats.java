package com.lifepilot.workflow.model;

/**
 * 步骤执行统计。
 *
 * @param stepId        步骤 ID
 * @param stepType      步骤类型
 * @param totalCount    总执行次数
 * @param successCount  成功次数
 * @param failedCount   失败次数
 * @param avgDurationMs 平均耗时（毫秒）
 * @param maxDurationMs 最大耗时（毫秒）
 * @param totalRetries  总重试次数
 * @author zsg
 * @since 2026-03-13
 */
public record StepStats(
        String stepId, String stepType,
        int totalCount, int successCount, int failedCount,
        long avgDurationMs, long maxDurationMs, int totalRetries
) {}

package com.lifepilot.agent.task;

import org.springframework.lang.Nullable;

/**
 * Cron 任务执行日志数据载体。
 *
 * @param id         日志 ID（UUID）
 * @param taskId     关联的任务 ID
 * @param executedAt 执行时间（ISO 8601）
 * @param status     执行状态：success / failed / timeout
 * @param durationMs 执行耗时（毫秒）
 * @param tokensUsed Token 消耗量
 * @param summary    Agent 回复摘要（前 500 字符）
 * @param createdAt  创建时间（ISO 8601）
 * @author zsg
 * @since 2026-03-19
 */
public record CronTaskLog(
        String id,
        String taskId,
        String executedAt,
        String status,
        long durationMs,
        int tokensUsed,
        @Nullable String summary,
        String createdAt
) {}

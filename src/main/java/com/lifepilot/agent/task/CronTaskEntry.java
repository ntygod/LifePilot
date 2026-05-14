package com.lifepilot.agent.task;

/**
 * Cron 定时任务数据载体。
 *
 * @param id          任务 ID（UUID）
 * @param name        任务名称（用户可读）
 * @param schedule    cron 表达式（Spring CronExpression 6 位格式）
 * @param instruction Agent 执行时的 prompt（自然语言）
 * @param status      任务状态：active / paused / completed
 * @param createdAt   创建时间（ISO 8601）
 * @param updatedAt   更新时间（ISO 8601）
 * @param skillIds    创建时已加载的 Skill ID 列表（逗号分隔），执行时自动预加载
 * @param projectId   归属项目 ID（Plan 2 Task A2 新增）；{@code null} 表示归属主账户
 * @author zsg
 * @since 2026-03-19
 */
public record CronTaskEntry(
        String id,
        String name,
        String schedule,
        String instruction,
        String status,
        String createdAt,
        String updatedAt,
        @org.springframework.lang.Nullable String skillIds,
        @org.springframework.lang.Nullable String projectId
) {
}

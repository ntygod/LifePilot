package com.lifepilot.scheduler.model;

import org.springframework.lang.Nullable;

/**
 * 定时任务实体。
 *
 * <p>{@code actionJson} 存储 {@link TaskAction} 的 JSON 序列化形式，
 * 由 {@link TaskActionCodec} 负责序列化/反序列化。
 *
 * @param id              任务唯一标识（UUID）
 * @param name            任务名称
 * @param triggerType     触发类型（ONCE / CRON）
 * @param triggerAt       一次性任务触发时间（ISO 8601），周期性任务为 null
 * @param cronExpr        周期性任务 cron 表达式，一次性任务为 null
 * @param actionJson      TaskAction 序列化 JSON
 * @param status          任务状态
 * @param errorMessage    执行失败时的错误信息
 * @param lastTriggeredAt 上次触发时间（ISO 8601）
 * @param nextTriggerAt   下次触发时间（ISO 8601）
 * @param metadataJson    扩展元数据 JSON（如关联 scheduleId）
 * @param createdAt       创建时间（ISO 8601）
 * @param updatedAt       更新时间（ISO 8601）
 * @author zsg
 * @since 2026-03-16
 */
public record ScheduledTask(
        String id,
        String name,
        TriggerType triggerType,
        @Nullable String triggerAt,
        @Nullable String cronExpr,
        String actionJson,
        TaskStatus status,
        @Nullable String errorMessage,
        @Nullable String lastTriggeredAt,
        @Nullable String nextTriggerAt,
        @Nullable String metadataJson,
        String createdAt,
        String updatedAt
) {}

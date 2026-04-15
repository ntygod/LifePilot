package com.lifepilot.agent.task.proactive.schedule;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 轻量日程事件 — 从对话中提取，替代外部日历集成。
 *
 * @param id           唯一标识
 * @param userId       用户 ID
 * @param title        事件标题
 * @param eventTime    事件时间（可为 null 表示未确定）
 * @param sourceSessionId 来源对话 ID
 * @param createdAt    创建时间
 * @author zsg
 * @since 2026-04-14
 */
public record ScheduleEvent(
        String id,
        String userId,
        String title,
        @Nullable Instant eventTime,
        @Nullable String sourceSessionId,
        Instant createdAt
) {
    public ScheduleEvent {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(title, "title 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }
}

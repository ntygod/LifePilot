package com.lifepilot.skill.builtin.schedule;

import org.springframework.lang.Nullable;

/**
 * 日程。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record ScheduleItem(
        String id,
        String title,
        String startTime,
        String endTime,
        @Nullable String location,
        @Nullable String notes,
        String createdAt,
        String updatedAt
) {}

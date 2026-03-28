package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 提醒运行时上下文。
 *
 * @param now                当前时间
 * @param zoneId             时区
 * @param quietHoursStart    静默开始时间
 * @param quietHoursEnd      静默结束时间
 * @param remindersSentToday 今日全局已发送次数
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderRuntimeContext(
        Instant now,
        ZoneId zoneId,
        @Nullable LocalTime quietHoursStart,
        @Nullable LocalTime quietHoursEnd,
        int remindersSentToday
) {

    public ReminderRuntimeContext {
        now = Objects.requireNonNull(now, "now 不能为空");
        zoneId = Objects.requireNonNull(zoneId, "zoneId 不能为空");
        remindersSentToday = Math.max(0, remindersSentToday);
    }

    public boolean isWithinQuietHours() {
        if (quietHoursStart == null || quietHoursEnd == null) {
            return false;
        }
        LocalTime localNow = LocalTime.ofInstant(now, zoneId);
        if (quietHoursStart.isBefore(quietHoursEnd)) {
            return !localNow.isBefore(quietHoursStart) && localNow.isBefore(quietHoursEnd);
        }
        return !localNow.isBefore(quietHoursStart) || localNow.isBefore(quietHoursEnd);
    }

    public int remainingReminderSlots(int dailyMaxReminders) {
        return Math.max(0, dailyMaxReminders - remindersSentToday);
    }
}

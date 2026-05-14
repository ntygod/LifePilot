package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 提醒运行时上下文。
 *
 * @param now                当前时间
 * @param zoneId             时区
 * @param quietHoursStart    静默开始时间
 * @param quietHoursEnd      静默结束时间
 * @param remindersSentToday 今日全局已发送次数
 * @param focusState         当前桌面焦点状态（可为 null）
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderRuntimeContext(
        Instant now,
        ZoneId zoneId,
        @Nullable LocalTime quietHoursStart,
        @Nullable LocalTime quietHoursEnd,
        int remindersSentToday,
        @Nullable ReminderFocusState focusState
) {

    /** IDE 窗口标题关键词 — 命中即视为"编码中"。 */
    private static final Pattern IDE_TITLE_PATTERN = Pattern.compile(
            "VS Code|Visual Studio Code|IntelliJ|WebStorm|PyCharm|CLion|GoLand|Rider|RustRover|Cursor|Zed|Neovim",
            Pattern.CASE_INSENSITIVE
    );

    /** 空闲判定阈值（分钟）。 */
    private static final int IDLE_THRESHOLD_MINUTES = 5;

    public ReminderRuntimeContext {
        now = Objects.requireNonNull(now, "now 不能为空");
        zoneId = Objects.requireNonNull(zoneId, "zoneId 不能为空");
        remindersSentToday = Math.max(0, remindersSentToday);
    }

    /**
     * 判断用户是否正在 IDE 中编码。
     */
    public boolean isFocusedCoding() {
        if (focusState == null) {
            return false;
        }
        return IDE_TITLE_PATTERN.matcher(focusState.focusTitle()).find();
    }

    /**
     * 判断焦点应用是否为全屏。
     */
    public boolean isFullscreenApp() {
        return focusState != null && focusState.fullscreen();
    }

    /**
     * 判断用户是否处于空闲状态（无操作超过阈值）。
     */
    public boolean isIdle() {
        return focusState != null && focusState.idleMinutes() >= IDLE_THRESHOLD_MINUTES;
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

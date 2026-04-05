package com.lifepilot.agent.task.reminder;

import java.time.Instant;
import java.util.Objects;

/**
 * 焦点应用状态 — 由 Tauri 桌面端定期上报。
 *
 * @param focusApp    当前焦点应用可执行文件名
 * @param focusTitle  焦点窗口标题
 * @param fullscreen  是否全屏
 * @param idleMinutes 用户空闲分钟数
 * @param timestamp   状态时间戳
 * @author zsg
 * @since 2026-04-05
 */
public record ReminderFocusState(
        String focusApp,
        String focusTitle,
        boolean fullscreen,
        int idleMinutes,
        Instant timestamp
) {

    public ReminderFocusState {
        focusApp = Objects.requireNonNullElse(focusApp, "");
        focusTitle = Objects.requireNonNullElse(focusTitle, "");
        idleMinutes = Math.max(0, idleMinutes);
        timestamp = Objects.requireNonNullElse(timestamp, Instant.now());
    }
}

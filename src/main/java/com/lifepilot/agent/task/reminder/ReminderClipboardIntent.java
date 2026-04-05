package com.lifepilot.agent.task.reminder;

import java.time.Instant;
import java.util.Objects;

/**
 * 剪贴板意图 — 由 Tauri 桌面端识别并上报的结构化剪贴板内容。
 *
 * @param intentType 意图类型
 * @param value      原始值
 * @param timestamp  识别时间
 * @author zsg
 * @since 2026-04-05
 */
public record ReminderClipboardIntent(
        ReminderClipboardIntentType intentType,
        String value,
        Instant timestamp
) {

    public ReminderClipboardIntent {
        intentType = Objects.requireNonNull(intentType, "intentType 不能为空");
        value = Objects.requireNonNullElse(value, "");
        timestamp = Objects.requireNonNullElse(timestamp, Instant.now());
    }
}

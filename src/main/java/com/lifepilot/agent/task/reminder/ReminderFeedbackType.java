package com.lifepilot.agent.task.reminder;

import java.util.Locale;

/**
 * 主动提醒反馈类型。
 *
 * @author zsg
 * @since 2026-03-28
 */
public enum ReminderFeedbackType {
    ACTED,
    SNOOZED,
    DISMISSED,
    NOT_RELEVANT;

    public static ReminderFeedbackType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("反馈类型不能为空");
        }
        return ReminderFeedbackType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}

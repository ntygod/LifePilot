package com.lifepilot.agent.task.reminder;

/**
 * 提醒策略动作。
 *
 * @author zsg
 * @since 2026-03-28
 */
public enum ReminderAction {
    SKIP,
    SOFT_PUSH,
    NORMAL_PUSH,
    DEFER_TO_WINDOW,
    PREPARE,
    AUTO_EXECUTE
}

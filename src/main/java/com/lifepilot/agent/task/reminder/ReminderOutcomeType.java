package com.lifepilot.agent.task.reminder;

/**
 * 提醒隐式结果类型。
 *
 * @author zsg
 * @since 2026-03-28
 */
public enum ReminderOutcomeType {
    /** 用户确实完成了提醒的事项。 */
    ACTED,
    /** 反事实推断：事项可能被遗忘（relevantAt 已过但无后续提及）。 */
    MISSED
}

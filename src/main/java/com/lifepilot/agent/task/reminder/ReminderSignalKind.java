package com.lifepilot.agent.task.reminder;

/**
 * 提醒信号类型。
 *
 * <p>表示采集层归一化后的主题信号类别，
 * 供候选检测器判断适用的提醒模式。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public enum ReminderSignalKind {
    DEADLINE,
    COMMITMENT,
    HABIT,
    EVENT,
    ANOMALY
}

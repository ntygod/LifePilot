package com.lifepilot.agent.task.reminder;

/**
 * 主动提醒执行结果。
 *
 * @param topicsCollected 采集到的主题数量
 * @param decisionsEvaluated 完成决策的主题数量
 * @param remindersSent 实际发送的提醒数量
 * @author zsg
 * @since 2026-03-28
 */
public record ProactiveReminderRunResult(
        int topicsCollected,
        int decisionsEvaluated,
        int remindersSent
) {}

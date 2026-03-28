package com.lifepilot.agent.task.reminder;

/**
 * 用户级主动提醒反馈汇总。
 *
 * @param actedCount       已处理次数
 * @param snoozedCount     稍后提醒次数
 * @param dismissedCount   忽略次数
 * @param notRelevantCount 不相关次数
 * @param mutedTopicCount  已静默主题数
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderUserFeedbackSummary(
        int actedCount,
        int snoozedCount,
        int dismissedCount,
        int notRelevantCount,
        int mutedTopicCount
) {

    public static ReminderUserFeedbackSummary empty() {
        return new ReminderUserFeedbackSummary(0, 0, 0, 0, 0);
    }

    public int totalFeedbackCount() {
        return Math.max(0, actedCount) + Math.max(0, snoozedCount)
                + Math.max(0, dismissedCount) + Math.max(0, notRelevantCount);
    }

    public float positiveWeight() {
        return Math.max(0, actedCount) + Math.max(0, snoozedCount) * 0.6f;
    }

    public float negativeWeight() {
        return Math.max(0, dismissedCount) + Math.max(0, notRelevantCount) * 1.4f;
    }
}

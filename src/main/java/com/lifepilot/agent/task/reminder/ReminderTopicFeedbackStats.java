package com.lifepilot.agent.task.reminder;

/**
 * 主动提醒主题反馈统计。
 *
 * @param actedCount30d       30 天内已处理次数
 * @param snoozedCount30d     30 天内稍后提醒次数
 * @param dismissedCount30d   30 天内忽略次数
 * @param notRelevantCount30d 30 天内不相关次数
 * @param muted               是否静默
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderTopicFeedbackStats(
        int actedCount30d,
        int snoozedCount30d,
        int dismissedCount30d,
        int notRelevantCount30d,
        boolean muted
) {

    public static ReminderTopicFeedbackStats empty() {
        return new ReminderTopicFeedbackStats(0, 0, 0, 0, false);
    }
}

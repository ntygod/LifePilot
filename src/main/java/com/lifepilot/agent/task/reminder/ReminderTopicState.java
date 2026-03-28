package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 提醒主题状态。
 *
 * <p>表示某个提醒主题在反馈闭环中的历史状态，
 * 供算法计算用户接受度、疲劳度和冷却控制。</p>
 *
 * @param lastRemindedAt      上次提醒时间
 * @param remindersSentToday  今日已发送次数
 * @param readCount30d        近 30 天已读次数
 * @param actedCount30d       近 30 天处理次数
 * @param snoozedCount30d     近 30 天稍后提醒次数
 * @param dismissedCount30d   近 30 天忽略次数
 * @param notRelevantCount30d 近 30 天不相关次数
 * @param muted               是否静默
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderTopicState(
        @Nullable Instant lastRemindedAt,
        int remindersSentToday,
        int readCount30d,
        int actedCount30d,
        int snoozedCount30d,
        int dismissedCount30d,
        int notRelevantCount30d,
        boolean muted
) {

    public ReminderTopicState {
        remindersSentToday = Math.max(0, remindersSentToday);
        readCount30d = Math.max(0, readCount30d);
        actedCount30d = Math.max(0, actedCount30d);
        snoozedCount30d = Math.max(0, snoozedCount30d);
        dismissedCount30d = Math.max(0, dismissedCount30d);
        notRelevantCount30d = Math.max(0, notRelevantCount30d);
    }

    public static ReminderTopicState empty() {
        return new ReminderTopicState(null, 0, 0, 0, 0, 0, 0, false);
    }

    /**
     * 估算该主题的用户适配度。
     *
     * <p>正反馈权重大于弱反馈，负反馈对得分压制更强。</p>
     */
    public float userFitScore() {
        int positive = actedCount30d * 5 + snoozedCount30d * 2 + readCount30d;
        int negative = dismissedCount30d * 3 + notRelevantCount30d * 5;
        if (positive == 0 && negative == 0) {
            return 0.5f;
        }
        return Math.max(0.0f, Math.min(1.0f, (positive + 1.0f) / (positive + negative + 2.0f)));
    }
}

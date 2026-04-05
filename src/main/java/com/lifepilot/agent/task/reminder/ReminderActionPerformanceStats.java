package com.lifepilot.agent.task.reminder;

/**
 * 主动提醒动作效果统计。
 *
 * @param candidateType    候选类型
 * @param action           决策动作
 * @param sentCount        发送次数
 * @param actedCount       已处理次数
 * @param snoozedCount     稍后提醒次数
 * @param dismissedCount   忽略次数
 * @param notRelevantCount 不相关次数
 * @param readOnlyCount    已读但未给出明确反馈次数
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderActionPerformanceStats(
        String candidateType,
        ReminderAction action,
        int sentCount,
        int actedCount,
        int snoozedCount,
        int dismissedCount,
        int notRelevantCount,
        int readOnlyCount
) {

    public ReminderActionPerformanceStats {
        sentCount = Math.max(0, sentCount);
        actedCount = Math.max(0, actedCount);
        snoozedCount = Math.max(0, snoozedCount);
        dismissedCount = Math.max(0, dismissedCount);
        notRelevantCount = Math.max(0, notRelevantCount);
        readOnlyCount = Math.max(0, readOnlyCount);
    }

    public static ReminderActionPerformanceStats empty(String candidateType, ReminderAction action) {
        return new ReminderActionPerformanceStats(candidateType, action, 0, 0, 0, 0, 0, 0);
    }

    public float rewardMean() {
        if (sentCount <= 0) {
            return 0.5f;
        }
        float reward = actedCount * 1.0f
                + snoozedCount * 0.55f
                + readOnlyCount * 0.20f
                - dismissedCount * 0.65f
                - notRelevantCount * 1.0f;
        return clamp((reward / sentCount + 1.0f) / 2.0f);
    }

    public float confidence() {
        return clamp(sentCount / 8.0f);
    }

    public ReminderActionPerformanceStats merge(ReminderActionPerformanceStats other) {
        if (other == null) {
            return this;
        }
        return new ReminderActionPerformanceStats(
                candidateType != null ? candidateType : other.candidateType,
                action != null ? action : other.action,
                sentCount + other.sentCount,
                actedCount + other.actedCount,
                snoozedCount + other.snoozedCount,
                dismissedCount + other.dismissedCount,
                notRelevantCount + other.notRelevantCount,
                readOnlyCount + other.readOnlyCount
        );
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

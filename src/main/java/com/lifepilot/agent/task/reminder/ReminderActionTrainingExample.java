package com.lifepilot.agent.task.reminder;

/**
 * 动作学习训练样本。
 *
 * <p>从历史提醒决策与反馈中回放得到，用于训练上下文动作策略。</p>
 *
 * @param candidateType            候选类型
 * @param action                   实际执行动作
 * @param finalScore               候选最终分
 * @param evidenceScore            证据分
 * @param timingScore              时机分
 * @param urgencyScore             紧急度
 * @param userFitScore             用户适配分
 * @param actionabilityScore       可执行性分
 * @param duplicatePenalty         重复惩罚
 * @param fatiguePenalty           疲劳惩罚
 * @param topicRemindersSentToday  主题当日已提醒次数
 * @param topicReadCount30d        主题近 30 天已读数
 * @param topicActedCount30d       主题近 30 天处理数
 * @param topicDismissedCount30d   主题近 30 天忽略数
 * @param topicSnoozedCount30d     主题近 30 天稍后提醒数
 * @param topicNotRelevantCount30d 主题近 30 天不相关数
 * @param reward                   样本奖励值（0-1）
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderActionTrainingExample(
        String candidateType,
        ReminderAction action,
        float finalScore,
        float evidenceScore,
        float timingScore,
        float urgencyScore,
        float userFitScore,
        float actionabilityScore,
        float duplicatePenalty,
        float fatiguePenalty,
        int topicRemindersSentToday,
        int topicReadCount30d,
        int topicActedCount30d,
        int topicDismissedCount30d,
        int topicSnoozedCount30d,
        int topicNotRelevantCount30d,
        float reward
) {

    public ReminderActionTrainingExample {
        candidateType = candidateType != null ? candidateType : "";
        action = action != null ? action : ReminderAction.SOFT_PUSH;
        finalScore = clamp(finalScore);
        evidenceScore = clamp(evidenceScore);
        timingScore = clamp(timingScore);
        urgencyScore = clamp(urgencyScore);
        userFitScore = clamp(userFitScore);
        actionabilityScore = clamp(actionabilityScore);
        duplicatePenalty = clamp(duplicatePenalty);
        fatiguePenalty = clamp(fatiguePenalty);
        topicRemindersSentToday = Math.max(0, topicRemindersSentToday);
        topicReadCount30d = Math.max(0, topicReadCount30d);
        topicActedCount30d = Math.max(0, topicActedCount30d);
        topicDismissedCount30d = Math.max(0, topicDismissedCount30d);
        topicSnoozedCount30d = Math.max(0, topicSnoozedCount30d);
        topicNotRelevantCount30d = Math.max(0, topicNotRelevantCount30d);
        reward = clamp(reward);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

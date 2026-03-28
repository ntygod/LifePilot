package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 主动提醒决策记录。
 *
 * @param id                        决策记录 ID
 * @param runId                     所属执行轮次 ID
 * @param topicKey                  主题键
 * @param title                     主题标题
 * @param signalId                  触发信号 ID
 * @param candidateType             候选类型
 * @param action                    决策动作
 * @param decisionReason            决策原因
 * @param rationale                 候选理由
 * @param finalScore                最终得分
 * @param evidenceScore             证据得分
 * @param timingScore               时机得分
 * @param urgencyScore              紧急度得分
 * @param userFitScore              用户适配得分
 * @param actionabilityScore        可执行性得分
 * @param duplicatePenalty          重复惩罚
 * @param fatiguePenalty            疲劳惩罚
 * @param suggestedAt               建议触发时间
 * @param nextEvaluationAt          下次评估时间
 * @param notified                  是否已发送通知
 * @param notificationId            关联通知 ID
 * @param topicLastRemindedAt       主题最近提醒时间
 * @param topicRemindersSentToday   主题今日提醒数
 * @param topicReadCount30d         主题 30 天已读数
 * @param topicActedCount30d        主题 30 天处理数
 * @param topicDismissedCount30d    主题 30 天忽略数
 * @param topicSnoozedCount30d      主题 30 天稍后提醒数
 * @param topicNotRelevantCount30d  主题 30 天不相关数
 * @param topicMuted                主题是否静默
 * @param policyVersionId           策略版本 ID
 * @param policyVersion             策略版本号
 * @param createdAt                 创建时间
 * @param updatedAt                 更新时间
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderDecisionRecord(
        String id,
        String runId,
        String topicKey,
        String title,
        String signalId,
        String candidateType,
        String action,
        String decisionReason,
        String rationale,
        float finalScore,
        float evidenceScore,
        float timingScore,
        float urgencyScore,
        float userFitScore,
        float actionabilityScore,
        float duplicatePenalty,
        float fatiguePenalty,
        @Nullable Instant suggestedAt,
        @Nullable Instant nextEvaluationAt,
        boolean notified,
        @Nullable String notificationId,
        @Nullable Instant topicLastRemindedAt,
        int topicRemindersSentToday,
        int topicReadCount30d,
        int topicActedCount30d,
        int topicDismissedCount30d,
        int topicSnoozedCount30d,
        int topicNotRelevantCount30d,
        boolean topicMuted,
        @Nullable String policyVersionId,
        @Nullable Integer policyVersion,
        Instant createdAt,
        Instant updatedAt
) {

    public ReminderDecisionRecord(String id,
                                  String runId,
                                  String topicKey,
                                  String title,
                                  String signalId,
                                  String candidateType,
                                  String action,
                                  String decisionReason,
                                  String rationale,
                                  float finalScore,
                                  float evidenceScore,
                                  float timingScore,
                                  float urgencyScore,
                                  float userFitScore,
                                  float actionabilityScore,
                                  float duplicatePenalty,
                                  float fatiguePenalty,
                                  @Nullable Instant suggestedAt,
                                  @Nullable Instant nextEvaluationAt,
                                  boolean notified,
                                  @Nullable String notificationId,
                                  @Nullable Instant topicLastRemindedAt,
                                  int topicRemindersSentToday,
                                  int topicReadCount30d,
                                  int topicActedCount30d,
                                  int topicDismissedCount30d,
                                  int topicSnoozedCount30d,
                                  int topicNotRelevantCount30d,
                                  boolean topicMuted,
                                  Instant createdAt,
                                  Instant updatedAt) {
        this(id, runId, topicKey, title, signalId, candidateType, action, decisionReason, rationale,
                finalScore, evidenceScore, timingScore, urgencyScore, userFitScore, actionabilityScore,
                duplicatePenalty, fatiguePenalty, suggestedAt, nextEvaluationAt, notified, notificationId,
                topicLastRemindedAt, topicRemindersSentToday, topicReadCount30d, topicActedCount30d,
                topicDismissedCount30d, topicSnoozedCount30d, topicNotRelevantCount30d, topicMuted,
                null, null, createdAt, updatedAt);
    }
}

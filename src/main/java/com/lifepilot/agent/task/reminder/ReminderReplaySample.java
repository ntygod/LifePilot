package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 提醒策略离线回放样本。
 *
 * <p>按时间顺序回放历史提醒决策，比较历史动作与新策略动作。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderReplaySample(
        String decisionId,
        Instant decidedAt,
        String topicKey,
        String title,
        String signalId,
        ReminderCandidateType candidateType,
        ReminderAction historicalAction,
        ReminderAction baseAction,
        @Nullable Instant nextEvaluationAt,
        String decisionReason,
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
        boolean acted,
        float actedReward,
        boolean snoozed,
        boolean dismissed,
        boolean notRelevant,
        boolean readOnly
) {

    public ReminderCandidate candidate() {
        return new ReminderCandidate(
                topicKey,
                title,
                candidateType,
                signalId,
                evidenceScore,
                timingScore,
                urgencyScore,
                userFitScore,
                actionabilityScore,
                duplicatePenalty,
                fatiguePenalty,
                finalScore,
                null,
                decisionReason
        );
    }

    public ReminderTopicState topicState() {
        return new ReminderTopicState(
                null,
                topicRemindersSentToday,
                topicReadCount30d,
                topicActedCount30d,
                topicSnoozedCount30d,
                topicDismissedCount30d,
                topicNotRelevantCount30d,
                false
        );
    }

    public ReminderDecision baseDecision() {
        return new ReminderDecision(candidate(), baseAction, nextEvaluationAt, normalizeBaseReason());
    }

    public ReminderActionTrainingExample toTrainingExample() {
        return new ReminderActionTrainingExample(
                candidateType.name(),
                historicalAction,
                finalScore,
                evidenceScore,
                timingScore,
                urgencyScore,
                userFitScore,
                actionabilityScore,
                duplicatePenalty,
                fatiguePenalty,
                topicRemindersSentToday,
                topicReadCount30d,
                topicActedCount30d,
                topicDismissedCount30d,
                topicSnoozedCount30d,
                topicNotRelevantCount30d,
                historicalReward()
        );
    }

    public ReminderActionPerformanceStats toPerformanceStats() {
        return new ReminderActionPerformanceStats(
                candidateType.name(),
                historicalAction,
                1,
                acted ? 1 : 0,
                snoozed ? 1 : 0,
                dismissed ? 1 : 0,
                notRelevant ? 1 : 0,
                readOnly ? 1 : 0
        );
    }

    public float historicalReward() {
        if (acted) {
            return actedReward > 0.0f ? actedReward : 1.0f;
        }
        if (snoozed) {
            return 0.72f;
        }
        if (dismissed) {
            return 0.18f;
        }
        if (notRelevant) {
            return 0.0f;
        }
        if (readOnly) {
            return 0.58f;
        }
        return 0.42f;
    }

    private String normalizeBaseReason() {
        if (baseAction == ReminderAction.SKIP) {
            return decisionReason != null && decisionReason.contains("当前没有足够理由打扰用户")
                    ? "当前没有足够理由打扰用户"
                    : "候选得分不足";
        }
        if (baseAction == ReminderAction.DEFER_TO_WINDOW) {
            return "等待更合适时间";
        }
        if (decisionReason == null || decisionReason.isBlank()) {
            return "";
        }
        int idx = decisionReason.indexOf('；');
        return idx > 0 ? decisionReason.substring(0, idx) : decisionReason;
    }
}

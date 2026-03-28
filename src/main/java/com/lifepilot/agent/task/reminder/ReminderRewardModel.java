package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;

/**
 * 主动提醒奖励与去因模型。
 *
 * <p>用于区分“事情后来完成了”和“提醒在多大程度上促成了完成”，
 * 让动作学习和离线回放不再把所有完成都视作同等强正反馈。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public final class ReminderRewardModel {

    private static final float PASSIVE_BASELINE_REWARD = 0.42f;
    private static final float READ_ONLY_REWARD = 0.58f;
    private static final float SNOOZED_REWARD = 0.72f;
    private static final float DISMISSED_REWARD = 0.18f;
    private static final float NOT_RELEVANT_REWARD = 0.0f;
    private static final float EXPLICIT_ACTED_REWARD = 1.0f;

    private ReminderRewardModel() {
    }

    public static float rewardFor(@Nullable String feedbackType,
                                  @Nullable String readStatus,
                                  float inferredActedAttribution) {
        if ("ACTED".equals(feedbackType)) {
            return EXPLICIT_ACTED_REWARD;
        }
        if ("SNOOZED".equals(feedbackType)) {
            return SNOOZED_REWARD;
        }
        if ("DISMISSED".equals(feedbackType)) {
            return DISMISSED_REWARD;
        }
        if ("NOT_RELEVANT".equals(feedbackType)) {
            return NOT_RELEVANT_REWARD;
        }
        if (inferredActedAttribution > 0.0f) {
            return implicitActedReward(inferredActedAttribution);
        }
        if ("READ".equals(readStatus)) {
            return READ_ONLY_REWARD;
        }
        return PASSIVE_BASELINE_REWARD;
    }

    public static float implicitActedReward(float attributionScore) {
        if (attributionScore <= 0.0f) {
            return 0.0f;
        }
        return clamp(Math.max(PASSIVE_BASELINE_REWARD, attributionScore));
    }

    public static float inferAttributionScore(ReminderOutcomeEvidenceSource source,
                                              float confidenceScore,
                                              @Nullable Instant decidedAt,
                                              @Nullable Instant inferredAt,
                                              @Nullable String candidateType) {
        float sourceWeight = switch (source) {
            case WORKSPACE_STATE -> 0.84f;
            case WORKFLOW_INSTANCE -> 0.82f;
            case WORKFLOW_STEP_LOG -> 0.78f;
            case TRACE_TOOL_OUTPUT -> 0.74f;
            case SEMANTIC_STATE -> 0.68f;
            case CONVERSATION_MESSAGE -> 0.62f;
        };
        long minutes = 0L;
        if (decidedAt != null && inferredAt != null && !inferredAt.isBefore(decidedAt)) {
            minutes = Math.max(0L, Duration.between(decidedAt, inferredAt).toMinutes());
        }
        float score = 0.55f * sourceWeight + 0.45f * clamp(confidenceScore);
        score += timingAdjustment(minutes);
        score += typeAdjustment(minutes, candidateType);
        return clamp(score);
    }

    private static float timingAdjustment(long minutes) {
        if (minutes <= 30L) {
            return 0.12f;
        }
        if (minutes <= 120L) {
            return 0.06f;
        }
        if (minutes <= 12L * 60L) {
            return 0.0f;
        }
        if (minutes <= 24L * 60L) {
            return -0.06f;
        }
        if (minutes <= 72L * 60L) {
            return -0.14f;
        }
        return -0.22f;
    }

    private static float typeAdjustment(long minutes, @Nullable String candidateType) {
        if (candidateType == null || candidateType.isBlank()) {
            return 0.0f;
        }
        try {
            return switch (ReminderCandidateType.valueOf(candidateType)) {
                case DUE_SOON -> minutes <= 120L ? 0.04f : -0.02f;
                case COMMITMENT_GAP -> minutes <= 12L * 60L ? 0.02f : -0.04f;
                case HABIT_WINDOW -> minutes <= 4L * 60L ? 0.03f : -0.03f;
                case PREPARATION_WINDOW -> minutes <= 3L * 60L ? 0.04f : -0.02f;
                case BEHAVIOR_ANOMALY -> minutes <= 60L ? 0.03f : -0.01f;
            };
        } catch (IllegalArgumentException ignored) {
            return 0.0f;
        }
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

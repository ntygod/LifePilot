package com.lifepilot.agent.task.reminder;

import java.time.Duration;
import java.time.Instant;

/**
 * 提醒评分模型。
 *
 * <p>负责把检测器产出的候选映射为统一分数，
 * 输出规则化可解释结果，而不是直接调用 LLM 做主决策。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderScoringModel {

    /**
     * 对提醒候选进行统一评分。
     */
    public ReminderCandidate score(ReminderTopicSnapshot snapshot,
                                   ReminderSignal signal,
                                   ReminderCandidateType type,
                                   Instant suggestedAt,
                                   ReminderRuntimeContext context,
                                   ReminderPolicyConfig config,
                                   String rationale) {
        float evidenceScore = calcEvidenceScore(signal);
        float timingScore = calcTimingScore(signal, suggestedAt, context, config);
        float urgencyScore = calcUrgencyScore(signal, type, context, config);
        float userFitScore = snapshot.state().userFitScore();
        float actionabilityScore = signal.actionable() ? 1.0f : 0.45f;
        float duplicatePenalty = calcDuplicatePenalty(snapshot.state(), context, config);
        float fatiguePenalty = calcFatiguePenalty(snapshot.state(), context, config);

        float finalScore = clamp(
                0.30f * evidenceScore
                        + 0.25f * timingScore
                        + 0.20f * urgencyScore
                        + 0.15f * userFitScore
                        + 0.10f * actionabilityScore
                        - duplicatePenalty
                        - fatiguePenalty
        );

        return new ReminderCandidate(
                snapshot.topicKey(),
                snapshot.title(),
                type,
                signal.signalId(),
                evidenceScore,
                timingScore,
                urgencyScore,
                userFitScore,
                actionabilityScore,
                duplicatePenalty,
                fatiguePenalty,
                finalScore,
                suggestedAt,
                rationale
        );
    }

    private float calcEvidenceScore(ReminderSignal signal) {
        float evidenceCountScore = Math.min(1.0f, signal.evidenceCount() / 3.0f);
        return clamp(
                signal.confidenceScore() * 0.60f
                        + signal.importanceScore() * 0.25f
                        + evidenceCountScore * 0.15f
        );
    }

    private float calcTimingScore(ReminderSignal signal,
                                  Instant suggestedAt,
                                  ReminderRuntimeContext context,
                                  ReminderPolicyConfig config) {
        Duration untilSuggested = Duration.between(context.now(), suggestedAt);
        if (!untilSuggested.isNegative() && !untilSuggested.isZero()) {
            float minutes = untilSuggested.toMinutes();
            float ratio = Math.min(1.0f, minutes / config.preferredWindowLookahead().toMinutes());
            return clamp(0.40f + (1.0f - ratio) * 0.35f);
        }

        if (signal.kind() == ReminderSignalKind.HABIT && signal.preferredWindowStartHour() != null) {
            return 0.92f;
        }
        if (signal.kind() == ReminderSignalKind.DEADLINE || signal.kind() == ReminderSignalKind.EVENT) {
            return 0.95f;
        }
        if (signal.kind() == ReminderSignalKind.ANOMALY) {
            return clamp(0.55f + signal.anomalyScore() * 0.30f);
        }
        return 0.78f;
    }

    private float calcUrgencyScore(ReminderSignal signal,
                                   ReminderCandidateType type,
                                   ReminderRuntimeContext context,
                                   ReminderPolicyConfig config) {
        Instant relevantAt = signal.relevantAt();
        if (relevantAt == null) {
            return switch (type) {
                case COMMITMENT_GAP -> 0.65f;
                case HABIT_WINDOW -> 0.45f;
                case BEHAVIOR_ANOMALY -> clamp(0.40f + signal.anomalyScore() * 0.50f);
                default -> 0.50f;
            };
        }

        Duration remaining = Duration.between(context.now(), relevantAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return 1.0f;
        }

        float thresholdHours = switch (type) {
            case PREPARATION_WINDOW -> {
                Duration leadTime = signal.preparationLeadTime() != null
                        ? signal.preparationLeadTime()
                        : Duration.ofHours(2);
                yield Math.max(1.0f, leadTime.toMinutes() / 60.0f);
            }
            default -> (float) config.dueSoonThresholdHours();
        };
        float remainingHours = Math.max(0.0f, remaining.toMinutes() / 60.0f);
        return clamp(1.0f - Math.min(1.0f, remainingHours / thresholdHours));
    }

    private float calcDuplicatePenalty(ReminderTopicState state,
                                       ReminderRuntimeContext context,
                                       ReminderPolicyConfig config) {
        if (state.lastRemindedAt() == null) {
            return 0.0f;
        }
        Duration sinceLast = Duration.between(state.lastRemindedAt(), context.now());
        if (sinceLast.isNegative() || sinceLast.compareTo(config.defaultCooldown()) < 0) {
            return 1.0f;
        }
        if (sinceLast.compareTo(config.defaultCooldown().multipliedBy(2)) < 0) {
            return 0.12f;
        }
        return 0.0f;
    }

    private float calcFatiguePenalty(ReminderTopicState state,
                                     ReminderRuntimeContext context,
                                     ReminderPolicyConfig config) {
        float load = (context.remindersSentToday() + state.remindersSentToday())
                / (float) Math.max(1, config.dailyMaxReminders());
        return clamp(load * 0.15f);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

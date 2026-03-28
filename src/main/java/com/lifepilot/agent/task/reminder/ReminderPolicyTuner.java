package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 主动提醒策略调优器。
 *
 * <p>基于近期反馈自动收紧或放宽提醒策略，
 * 让系统先具备轻量自适应能力，后续再替换为 bandit / RL 策略。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderPolicyTuner {

    /**
     * 基于用户近期反馈调优策略。
     */
    public ReminderPolicyConfig tune(ReminderPolicyConfig baseConfig,
                                     ReminderUserFeedbackSummary summary) {
        return tune(baseConfig, summary, null);
    }

    /**
     * 基于显式反馈和离线回放共同调优策略。
     */
    public ReminderPolicyConfig tune(ReminderPolicyConfig baseConfig,
                                     ReminderUserFeedbackSummary summary,
                                     ReminderReplayReport replayReport) {
        if (baseConfig == null) {
            return null;
        }
        boolean hasFeedbackSignal = summary != null
                && (summary.totalFeedbackCount() > 0 || summary.mutedTopicCount() > 0);
        boolean hasReplaySignal = replayReport != null && replayReport.sampleCount() > 0;
        if (!hasFeedbackSignal && !hasReplaySignal) {
            return baseConfig;
        }

        ReminderUserFeedbackSummary safeSummary = summary != null ? summary : ReminderUserFeedbackSummary.empty();
        int totalFeedback = Math.max(1, safeSummary.totalFeedbackCount());
        float positiveWeight = safeSummary.positiveWeight();
        float negativeWeight = safeSummary.negativeWeight();
        float negativeRatio = clamp(negativeWeight / (positiveWeight + negativeWeight + 1.0f));
        float positiveRatio = clamp(positiveWeight / (positiveWeight + negativeWeight + 1.0f));
        boolean conservativeMode = negativeWeight >= positiveWeight;
        boolean permissiveMode = positiveWeight > negativeWeight * 1.5f && totalFeedback >= 3;

        int dueSoonThresholdHours = baseConfig.dueSoonThresholdHours();
        int commitmentGapThresholdHours = baseConfig.commitmentGapThresholdHours();
        int dailyMaxReminders = baseConfig.dailyMaxReminders();
        int cooldownHours = baseConfig.defaultCooldownHours();
        int lookaheadMinutes = baseConfig.preferredWindowLookaheadMinutes();
        float minFinalScore = baseConfig.minFinalScore();
        float softPushThreshold = baseConfig.softPushThreshold();
        float strongPushThreshold = baseConfig.strongPushThreshold();
        float anomalyThreshold = baseConfig.anomalyThreshold();

        if (conservativeMode) {
            minFinalScore += Math.min(0.12f, 0.05f + negativeRatio * 0.08f);
            softPushThreshold += Math.min(0.10f, 0.04f + negativeRatio * 0.06f);
            strongPushThreshold += Math.min(0.08f, 0.03f + negativeRatio * 0.05f);
            cooldownHours += Math.min(36, 6 + Math.round(negativeRatio * 24));
            commitmentGapThresholdHours += Math.min(18, 4 + Math.round(negativeRatio * 10));
            dailyMaxReminders = Math.max(1, dailyMaxReminders - (negativeRatio >= 0.55f ? 1 : 0));
            anomalyThreshold += Math.min(0.10f, safeSummary.notRelevantCount() * 0.02f);
        }

        if (safeSummary.snoozedCount() > safeSummary.actedCount()) {
            lookaheadMinutes += Math.min(120, 30 + (safeSummary.snoozedCount() - safeSummary.actedCount()) * 15);
            cooldownHours += Math.min(12, 2 + (safeSummary.snoozedCount() - safeSummary.actedCount()) * 2);
        }

        if (safeSummary.mutedTopicCount() > 0) {
            dailyMaxReminders = Math.max(1, dailyMaxReminders - 1);
            minFinalScore += Math.min(0.06f, safeSummary.mutedTopicCount() * 0.02f);
            softPushThreshold += Math.min(0.05f, safeSummary.mutedTopicCount() * 0.015f);
        }

        if (permissiveMode) {
            minFinalScore -= Math.min(0.05f, 0.02f + positiveRatio * 0.03f);
            softPushThreshold -= Math.min(0.05f, 0.02f + positiveRatio * 0.03f);
            strongPushThreshold -= Math.min(0.04f, 0.01f + positiveRatio * 0.02f);
            cooldownHours = Math.max(6, cooldownHours - Math.min(12, 4 + Math.round(positiveRatio * 6)));
            dailyMaxReminders += 1;
            dueSoonThresholdHours = Math.max(6, dueSoonThresholdHours - 2);
        }

        if (hasReplaySignal) {
            float expectedDelta = replayReport.replayedEstimatedPushRewardMean()
                    - replayReport.historicalEstimatedPushRewardMean();
            float shiftRatio = replayReport.sampleCount() > 0
                    ? clamp(replayReport.actionShiftCount() / (float) replayReport.sampleCount())
                    : 0.0f;
            boolean replaySuggestsTighter = replayReport.suppressedCount() > replayReport.promotedCount()
                    && expectedDelta >= -0.02f;
            boolean replaySuggestsLooser = replayReport.promotedCount() > replayReport.suppressedCount()
                    && expectedDelta > 0.02f;

            if (replaySuggestsTighter) {
                minFinalScore += Math.min(0.06f, 0.02f + shiftRatio * 0.05f);
                softPushThreshold += Math.min(0.05f, 0.02f + shiftRatio * 0.04f);
                strongPushThreshold += Math.min(0.04f, 0.01f + shiftRatio * 0.03f);
                cooldownHours += Math.min(12, 2 + Math.round(shiftRatio * 10));
                dailyMaxReminders = Math.max(1, dailyMaxReminders - (expectedDelta >= 0.01f ? 1 : 0));
            }

            if (replaySuggestsLooser) {
                minFinalScore -= Math.min(0.05f, 0.015f + shiftRatio * 0.04f);
                softPushThreshold -= Math.min(0.04f, 0.015f + shiftRatio * 0.03f);
                strongPushThreshold -= Math.min(0.03f, 0.01f + shiftRatio * 0.02f);
                cooldownHours = Math.max(6, cooldownHours - Math.min(8, 2 + Math.round(shiftRatio * 6)));
                dailyMaxReminders += 1;
            }

            if (expectedDelta < -0.05f && replayReport.replayedPushCount() > replayReport.historicalPushCount()) {
                minFinalScore += Math.min(0.04f, 0.02f + Math.abs(expectedDelta) * 0.30f);
                softPushThreshold += Math.min(0.03f, 0.01f + Math.abs(expectedDelta) * 0.20f);
                dailyMaxReminders = Math.max(1, dailyMaxReminders - 1);
            }
        }

        minFinalScore = clamp(minFinalScore);
        softPushThreshold = Math.max(minFinalScore + 0.04f, clamp(softPushThreshold));
        strongPushThreshold = Math.max(softPushThreshold + 0.08f, clamp(strongPushThreshold));
        anomalyThreshold = clamp(anomalyThreshold);

        return new ReminderPolicyConfig(
                dueSoonThresholdHours,
                commitmentGapThresholdHours,
                dailyMaxReminders,
                cooldownHours,
                lookaheadMinutes,
                minFinalScore,
                softPushThreshold,
                strongPushThreshold,
                anomalyThreshold
        );
    }

    /**
     * 对调优结果施加安全护栏，避免单轮噪声直接放大到线上策略。
     */
    public ReminderPolicyGuardrailResult applyGuardrail(ReminderPolicyConfig baseConfig,
                                                       ReminderPolicyConfig tunedConfig,
                                                       @Nullable ReminderPolicyConfig latestConfig,
                                                       @Nullable ReminderPolicyVersionInsight latestInsight,
                                                       ReminderUserFeedbackSummary summary,
                                                       @Nullable ReminderReplayReport replayReport,
                                                       AgentConfigProperties.TaskConfig taskConfig) {
        if (tunedConfig == null) {
            return ReminderPolicyGuardrailResult.passthrough(baseConfig);
        }
        if (taskConfig == null || !taskConfig.isProactiveReminderSafeTuningEnabled()) {
            return ReminderPolicyGuardrailResult.passthrough(tunedConfig);
        }

        ReminderPolicyConfig anchor = latestConfig != null ? latestConfig : baseConfig;
        ReminderPolicyConfig guarded = clampStep(anchor, tunedConfig, taskConfig);
        boolean adjusted = !guarded.equals(tunedConfig);
        boolean rolledBack = false;
        List<String> reasons = new ArrayList<>();
        if (adjusted) {
            reasons.add("超过单轮最大调参步长，已按锚点策略裁剪");
        }

        boolean moreAggressive = isMoreAggressive(anchor, guarded);
        ReminderPolicyAdjustmentDirection replayDirection = resolveReplayDirection(replayReport);
        float replayExpectedDelta = replayReport != null
                ? replayReport.replayedEstimatedPushRewardMean() - replayReport.historicalEstimatedPushRewardMean()
                : 0.0f;
        ReminderUserFeedbackSummary safeSummary = summary != null ? summary : ReminderUserFeedbackSummary.empty();
        boolean hasFeedbackSignal = safeSummary.totalFeedbackCount() > 0 || safeSummary.mutedTopicCount() > 0;
        boolean negativePressure = hasFeedbackSignal
                && safeSummary.negativeWeight() >= safeSummary.positiveWeight();

        if (moreAggressive
                && replayReport != null
                && replayDirection == ReminderPolicyAdjustmentDirection.LOOSER
                && taskConfig.isProactiveReminderTuningRequireConsistentReplayDirection()
                && latestInsight != null
                && latestInsight.hasReplay()
                && latestInsight.direction() != ReminderPolicyAdjustmentDirection.LOOSER) {
            guarded = revertAggressiveFields(anchor, guarded);
            adjusted = true;
            reasons.add("当前回放建议放松，但与上一版回放方向不一致，已拒绝激进调整");
        }

        if (moreAggressive && negativePressure) {
            guarded = revertAggressiveFields(anchor, guarded);
            adjusted = true;
            reasons.add("近期负反馈占优，已拒绝激进调整");
        }

        if (replayReport != null
                && moreAggressive
                && replayExpectedDelta <= -Math.abs(taskConfig.getProactiveReminderTuningReplayRollbackDelta())
                && replayReport.replayedPushCount() >= replayReport.historicalPushCount()) {
            guarded = anchor;
            adjusted = true;
            rolledBack = true;
            reasons.add("回放收益明显恶化且推送更多，已回退到上一版策略");
        }

        if (reasons.isEmpty()) {
            reasons.add("未触发护栏");
        }
        return new ReminderPolicyGuardrailResult(guarded, adjusted, rolledBack, String.join("；", reasons));
    }

    private float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private ReminderPolicyConfig clampStep(ReminderPolicyConfig anchor,
                                           ReminderPolicyConfig candidate,
                                           AgentConfigProperties.TaskConfig taskConfig) {
        int hourDelta = Math.max(1, taskConfig.getProactiveReminderTuningMaxHourDelta());
        int lookaheadDelta = Math.max(1, taskConfig.getProactiveReminderTuningMaxLookaheadMinutesDelta());
        int dailyDelta = Math.max(1, taskConfig.getProactiveReminderTuningMaxDailyReminderDelta());
        float scoreDelta = Math.max(0.005f, taskConfig.getProactiveReminderTuningMaxScoreDelta());

        return new ReminderPolicyConfig(
                clampInt(candidate.dueSoonThresholdHours(), anchor.dueSoonThresholdHours(), hourDelta),
                clampInt(candidate.commitmentGapThresholdHours(), anchor.commitmentGapThresholdHours(), hourDelta),
                clampInt(candidate.dailyMaxReminders(), anchor.dailyMaxReminders(), dailyDelta),
                clampInt(candidate.defaultCooldownHours(), anchor.defaultCooldownHours(), hourDelta),
                clampInt(candidate.preferredWindowLookaheadMinutes(), anchor.preferredWindowLookaheadMinutes(), lookaheadDelta),
                clampFloat(candidate.minFinalScore(), anchor.minFinalScore(), scoreDelta),
                clampFloat(candidate.softPushThreshold(), anchor.softPushThreshold(), scoreDelta),
                clampFloat(candidate.strongPushThreshold(), anchor.strongPushThreshold(), scoreDelta),
                clampFloat(candidate.anomalyThreshold(), anchor.anomalyThreshold(), scoreDelta)
        );
    }

    private ReminderPolicyConfig revertAggressiveFields(ReminderPolicyConfig anchor,
                                                        ReminderPolicyConfig candidate) {
        return new ReminderPolicyConfig(
                Math.min(candidate.dueSoonThresholdHours(), anchor.dueSoonThresholdHours()),
                Math.max(candidate.commitmentGapThresholdHours(), anchor.commitmentGapThresholdHours()),
                Math.min(candidate.dailyMaxReminders(), anchor.dailyMaxReminders()),
                Math.max(candidate.defaultCooldownHours(), anchor.defaultCooldownHours()),
                Math.min(candidate.preferredWindowLookaheadMinutes(), anchor.preferredWindowLookaheadMinutes()),
                Math.max(candidate.minFinalScore(), anchor.minFinalScore()),
                Math.max(candidate.softPushThreshold(), anchor.softPushThreshold()),
                Math.max(candidate.strongPushThreshold(), anchor.strongPushThreshold()),
                Math.max(candidate.anomalyThreshold(), anchor.anomalyThreshold())
        );
    }

    private boolean isMoreAggressive(ReminderPolicyConfig anchor,
                                     ReminderPolicyConfig candidate) {
        return candidate.dueSoonThresholdHours() > anchor.dueSoonThresholdHours()
                || candidate.commitmentGapThresholdHours() < anchor.commitmentGapThresholdHours()
                || candidate.dailyMaxReminders() > anchor.dailyMaxReminders()
                || candidate.defaultCooldownHours() < anchor.defaultCooldownHours()
                || candidate.preferredWindowLookaheadMinutes() > anchor.preferredWindowLookaheadMinutes()
                || candidate.minFinalScore() < anchor.minFinalScore()
                || candidate.softPushThreshold() < anchor.softPushThreshold()
                || candidate.strongPushThreshold() < anchor.strongPushThreshold()
                || candidate.anomalyThreshold() < anchor.anomalyThreshold();
    }

    private ReminderPolicyAdjustmentDirection resolveReplayDirection(@Nullable ReminderReplayReport replayReport) {
        if (replayReport == null || replayReport.sampleCount() <= 0) {
            return ReminderPolicyAdjustmentDirection.NEUTRAL;
        }
        if (replayReport.promotedCount() > replayReport.suppressedCount()) {
            return ReminderPolicyAdjustmentDirection.LOOSER;
        }
        if (replayReport.suppressedCount() > replayReport.promotedCount()) {
            return ReminderPolicyAdjustmentDirection.TIGHTER;
        }
        return ReminderPolicyAdjustmentDirection.NEUTRAL;
    }

    private int clampInt(int value, int anchor, int maxDelta) {
        return Math.max(anchor - maxDelta, Math.min(anchor + maxDelta, value));
    }

    private float clampFloat(float value, float anchor, float maxDelta) {
        return clamp(Math.max(anchor - maxDelta, Math.min(anchor + maxDelta, value)));
    }
}

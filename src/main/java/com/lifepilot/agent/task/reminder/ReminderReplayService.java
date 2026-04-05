package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主动提醒离线回放服务。
 *
 * <p>按历史时间顺序回放样本，使用先前样本模拟在线学习过程，
 * 比较历史动作与当前策略动作的差异。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderReplayService {

    private final ReminderExecutionRepository executionRepository;
    private final ReminderOpportunityPolicySelector opportunityPolicySelector;
    private final ReminderActionPolicySelector actionPolicySelector;
    private final ReminderActionContextualBandit actionBandit;
    private final AgentConfigProperties config;

    public ReminderReplayService(ReminderExecutionRepository executionRepository,
                                 AgentConfigProperties config) {
        this(executionRepository,
                new ReminderOpportunityPolicySelector(config),
                new ReminderActionPolicySelector(config),
                new ReminderActionContextualBandit(
                        config != null ? config.getTask().getProactiveReminderBanditExplorationAlpha() : 0.18f,
                        config != null ? config.getTask().getProactiveReminderBanditMinExamples() : 16,
                        config != null ? config.getTask().getProactiveReminderBanditMinActionSamples() : 3,
                        1.0d
                ),
                config);
    }

    public ReminderReplayService(ReminderExecutionRepository executionRepository,
                                 ReminderOpportunityPolicySelector opportunityPolicySelector,
                                 ReminderActionPolicySelector actionPolicySelector,
                                 ReminderActionContextualBandit actionBandit,
                                 AgentConfigProperties config) {
        this.executionRepository = executionRepository;
        this.opportunityPolicySelector = opportunityPolicySelector != null
                ? opportunityPolicySelector
                : new ReminderOpportunityPolicySelector(config);
        this.actionPolicySelector = actionPolicySelector != null
                ? actionPolicySelector
                : new ReminderActionPolicySelector(config);
        this.actionBandit = actionBandit != null ? actionBandit : new ReminderActionContextualBandit();
        this.config = config != null ? config : new AgentConfigProperties();
    }

    public ReminderReplayReport replay(String userId, Instant since, int limit) {
        List<ReminderReplaySample> samples = executionRepository.findReplaySamplesByUserIdSince(
                userId,
                since,
                Math.max(1, limit)
        );
        if (samples.isEmpty()) {
            return ReminderReplayReport.empty(userId, since);
        }

        ReminderPolicyConfig policyConfig = defaultPolicyConfig();
        List<ReminderActionTrainingExample> trainingExamples = new ArrayList<>();
        StatsAccumulator statsAccumulator = new StatsAccumulator();
        List<ReminderReplayEvaluation> evaluations = new ArrayList<>();
        Map<String, Integer> actionShiftMatrix = new LinkedHashMap<>();

        int replayedPushCount = 0;
        int suppressedCount = 0;
        int promotedCount = 0;
        int actionShiftCount = 0;
        float historicalObservedRewardSum = 0.0f;
        float historicalEstimatedRewardSum = 0.0f;
        float replayedEstimatedRewardSum = 0.0f;

        for (ReminderReplaySample sample : samples) {
            ReminderActionPolicyProfile profile = new ReminderActionPolicyProfile(statsAccumulator.toStats());
            ReminderDecision opportunityDecision = opportunityPolicySelector.refine(
                    sample.baseDecision(),
                    sample.topicState(),
                    policyConfig,
                    trainingExamples
            );
            ReminderDecision replayedDecision = actionPolicySelector.refine(
                    opportunityDecision,
                    sample.topicState(),
                    policyConfig,
                    profile,
                    trainingExamples
            );

            RewardEstimates estimates = estimateRewards(sample, profile, trainingExamples, replayedDecision.action());
            boolean shifted = replayedDecision.action() != sample.historicalAction();
            if (shifted) {
                actionShiftCount++;
                actionShiftMatrix.merge(sample.historicalAction().name() + "->" + replayedDecision.action().name(), 1, Integer::sum);
            }
            if (isPushAction(replayedDecision.action())) {
                replayedPushCount++;
            } else if (isPushAction(sample.historicalAction())) {
                suppressedCount++;
            }
            if (sample.baseAction() == ReminderAction.SKIP && isPushAction(replayedDecision.action())) {
                promotedCount++;
            }

            historicalObservedRewardSum += sample.historicalReward();
            historicalEstimatedRewardSum += estimates.historicalEstimatedReward();
            replayedEstimatedRewardSum += estimates.replayedEstimatedReward();
            evaluations.add(new ReminderReplayEvaluation(
                    sample.decisionId(),
                    sample.candidateType(),
                    sample.historicalAction(),
                    replayedDecision.action(),
                    sample.historicalReward(),
                    estimates.historicalEstimatedReward(),
                    estimates.replayedEstimatedReward(),
                    shifted,
                    estimates.usedBanditEstimate()
            ));

            trainingExamples.add(sample.toTrainingExample());
            statsAccumulator.add(sample.toPerformanceStats());
        }

        int sampleCount = samples.size();
        return new ReminderReplayReport(
                userId,
                since,
                sampleCount,
                sampleCount,
                replayedPushCount,
                suppressedCount,
                promotedCount,
                actionShiftCount,
                divide(historicalObservedRewardSum, sampleCount),
                divide(historicalEstimatedRewardSum, sampleCount),
                divide(replayedEstimatedRewardSum, sampleCount),
                Map.copyOf(actionShiftMatrix),
                List.copyOf(evaluations)
        );
    }

    private RewardEstimates estimateRewards(ReminderReplaySample sample,
                                            ReminderActionPolicyProfile profile,
                                            List<ReminderActionTrainingExample> trainingExamples,
                                            ReminderAction replayedAction) {
        if (trainingExamples == null || trainingExamples.isEmpty()) {
            return new RewardEstimates(
                    fallbackEstimate(profile, sample, sample.historicalAction()),
                    fallbackEstimate(profile, sample, replayedAction),
                    false
            );
        }

        ReminderActionContextualBandit.Policy policy = actionBandit.fit(
                trainingExamples,
                List.of(ReminderAction.SOFT_PUSH, ReminderAction.NORMAL_PUSH)
        );
        if (policy == null) {
            return new RewardEstimates(
                    fallbackEstimate(profile, sample, sample.historicalAction()),
                    fallbackEstimate(profile, sample, replayedAction),
                    false
            );
        }
        return new RewardEstimates(
                estimateWithBandit(policy, sample, sample.historicalAction()),
                estimateWithBandit(policy, sample, replayedAction),
                true
        );
    }

    private float estimateWithBandit(ReminderActionContextualBandit.Policy policy,
                                     ReminderReplaySample sample,
                                     ReminderAction action) {
        if (!isPushAction(action)) {
            return 0.0f;
        }
        return policy.estimate(sample.candidate(), sample.topicState(), action).expectedReward();
    }

    private float fallbackEstimate(ReminderActionPolicyProfile profile,
                                   ReminderReplaySample sample,
                                   ReminderAction action) {
        if (!isPushAction(action)) {
            return 0.0f;
        }
        if (profile == null || !profile.hasLearningSignal()) {
            return sample.historicalReward();
        }
        return profile.statsFor(sample.candidateType(), action).rewardMean();
    }

    private ReminderPolicyConfig defaultPolicyConfig() {
        return new ReminderPolicyConfig(
                24,
                18,
                config.getTask().getProactiveReminderDailyMaxReminders(),
                config.getTask().getProactiveReminderCooldownHours(),
                60,
                0.55f,
                0.63f,
                0.78f,
                0.65f
        );
    }

    private boolean isPushAction(ReminderAction action) {
        return action == ReminderAction.SOFT_PUSH || action == ReminderAction.NORMAL_PUSH
                || action == ReminderAction.PREPARE || action == ReminderAction.AUTO_EXECUTE;
    }

    private float divide(float total, int count) {
        return count > 0 ? total / count : 0.0f;
    }

    private record RewardEstimates(
            float historicalEstimatedReward,
            float replayedEstimatedReward,
            boolean usedBanditEstimate
    ) {
    }

    private static final class StatsAccumulator {

        private final Map<String, Map<ReminderAction, MutableStats>> statsByType = new LinkedHashMap<>();

        private void add(ReminderActionPerformanceStats stats) {
            statsByType
                    .computeIfAbsent(stats.candidateType(), _ -> new EnumMap<>(ReminderAction.class))
                    .computeIfAbsent(stats.action(), _ -> new MutableStats())
                    .add(stats);
        }

        private List<ReminderActionPerformanceStats> toStats() {
            List<ReminderActionPerformanceStats> stats = new ArrayList<>();
            for (Map.Entry<String, Map<ReminderAction, MutableStats>> typeEntry : statsByType.entrySet()) {
                for (Map.Entry<ReminderAction, MutableStats> actionEntry : typeEntry.getValue().entrySet()) {
                    stats.add(actionEntry.getValue().toImmutable(typeEntry.getKey(), actionEntry.getKey()));
                }
            }
            return List.copyOf(stats);
        }
    }

    private static final class MutableStats {

        private int sentCount;
        private int actedCount;
        private int snoozedCount;
        private int dismissedCount;
        private int notRelevantCount;
        private int readOnlyCount;

        private void add(ReminderActionPerformanceStats stats) {
            sentCount += stats.sentCount();
            actedCount += stats.actedCount();
            snoozedCount += stats.snoozedCount();
            dismissedCount += stats.dismissedCount();
            notRelevantCount += stats.notRelevantCount();
            readOnlyCount += stats.readOnlyCount();
        }

        private ReminderActionPerformanceStats toImmutable(String candidateType, ReminderAction action) {
            return new ReminderActionPerformanceStats(
                    candidateType,
                    action,
                    sentCount,
                    actedCount,
                    snoozedCount,
                    dismissedCount,
                    notRelevantCount,
                    readOnlyCount
            );
        }
    }
}

package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;

import java.util.List;

/**
 * 主动提醒动作策略选择器。
 *
 * <p>在规则层已经判定“可以提醒”的前提下，结合近期动作效果，
 * 动态选择 {@code SOFT_PUSH} 或 {@code NORMAL_PUSH}。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderActionPolicySelector {

    private final ReminderActionContextualBandit contextualBandit;

    public ReminderActionPolicySelector() {
        this(new ReminderActionContextualBandit());
    }

    public ReminderActionPolicySelector(AgentConfigProperties config) {
        this(new ReminderActionContextualBandit(
                config != null ? config.getTask().getProactiveReminderBanditExplorationAlpha() : 0.18f,
                config != null ? config.getTask().getProactiveReminderBanditMinExamples() : 16,
                config != null ? config.getTask().getProactiveReminderBanditMinActionSamples() : 3,
                1.0d
        ));
    }

    public ReminderActionPolicySelector(ReminderActionContextualBandit contextualBandit) {
        this.contextualBandit = contextualBandit != null ? contextualBandit : new ReminderActionContextualBandit();
    }

    /**
     * 基于动作画像调整提醒强度。
     */
    public ReminderDecision refine(ReminderDecision baseDecision,
                                   ReminderPolicyConfig config,
                                    ReminderActionPolicyProfile profile) {
        return refine(baseDecision, ReminderTopicState.empty(), config, profile, List.of());
    }

    /**
     * 基于上下文学习样本与汇总画像调整提醒强度。
     */
    public ReminderDecision refine(ReminderDecision baseDecision,
                                   ReminderTopicState topicState,
                                   ReminderPolicyConfig config,
                                   ReminderActionPolicyProfile profile,
                                   List<ReminderActionTrainingExample> examples) {
        if (baseDecision == null || profile == null || !profile.hasLearningSignal()) {
            return refineWithBanditOnly(baseDecision, topicState, config, examples);
        }
        if (baseDecision.action() != ReminderAction.SOFT_PUSH
                && baseDecision.action() != ReminderAction.NORMAL_PUSH) {
            return baseDecision;
        }

        ReminderCandidate candidate = baseDecision.candidate();
        List<ReminderAction> allowedActions = allowedActions(baseDecision, candidate, config);
        ReminderDecision banditDecision = refineWithBandit(baseDecision, topicState, allowedActions, examples);
        if (banditDecision != null) {
            return banditDecision;
        }
        if (allowedActions.size() <= 1) {
            return baseDecision;
        }

        ReminderAction bestAction = baseDecision.action();
        float bestScore = score(candidate, bestAction, profile);
        for (ReminderAction action : allowedActions) {
            float currentScore = score(candidate, action, profile);
            if (currentScore > bestScore) {
                bestAction = action;
                bestScore = currentScore;
            }
        }

        float baseScore = score(candidate, baseDecision.action(), profile);
        if (bestAction == baseDecision.action() || bestScore - baseScore < 0.05f) {
            return baseDecision;
        }

        String adjustedReason = baseDecision.reason() + "；结合近期反馈偏好调整为" + label(bestAction);
        return new ReminderDecision(candidate, bestAction, baseDecision.nextEvaluationAt(), adjustedReason);
    }

    private ReminderDecision refineWithBanditOnly(ReminderDecision baseDecision,
                                                  ReminderTopicState topicState,
                                                  ReminderPolicyConfig config,
                                                  List<ReminderActionTrainingExample> examples) {
        if (baseDecision == null) {
            return null;
        }
        if (baseDecision.action() != ReminderAction.SOFT_PUSH
                && baseDecision.action() != ReminderAction.NORMAL_PUSH) {
            return baseDecision;
        }
        List<ReminderAction> allowedActions = allowedActions(baseDecision, baseDecision.candidate(), config);
        ReminderDecision banditDecision = refineWithBandit(baseDecision, topicState, allowedActions, examples);
        return banditDecision != null ? banditDecision : baseDecision;
    }

    private ReminderDecision refineWithBandit(ReminderDecision baseDecision,
                                              ReminderTopicState topicState,
                                              List<ReminderAction> allowedActions,
                                              List<ReminderActionTrainingExample> examples) {
        if (examples == null || examples.isEmpty() || allowedActions.size() <= 1) {
            return null;
        }
        ReminderActionContextualBandit.Policy banditPolicy = contextualBandit.fit(examples, allowedActions);
        if (banditPolicy == null) {
            return null;
        }
        ReminderAction bestAction = baseDecision.action();
        float bestScore = banditPolicy.score(baseDecision.candidate(), topicState, bestAction);
        for (ReminderAction action : allowedActions) {
            float score = banditPolicy.score(baseDecision.candidate(), topicState, action);
            if (score > bestScore) {
                bestAction = action;
                bestScore = score;
            }
        }
        float baseScore = banditPolicy.score(baseDecision.candidate(), topicState, baseDecision.action());
        if (bestAction == baseDecision.action() || bestScore - baseScore < 0.02f) {
            return null;
        }
        String adjustedReason = baseDecision.reason()
                + "；结合近期上下文反馈策略调整为"
                + label(bestAction);
        return new ReminderDecision(baseDecision.candidate(), bestAction, baseDecision.nextEvaluationAt(), adjustedReason);
    }

    private List<ReminderAction> allowedActions(ReminderDecision baseDecision,
                                                ReminderCandidate candidate,
                                                ReminderPolicyConfig config) {
        if (baseDecision.action() == ReminderAction.NORMAL_PUSH) {
            if (candidate.urgencyScore() >= 0.92f) {
                return List.of(ReminderAction.NORMAL_PUSH);
            }
            return List.of(ReminderAction.NORMAL_PUSH, ReminderAction.SOFT_PUSH);
        }
        if (candidate.finalScore() >= config.strongPushThreshold() - 0.03f
                || candidate.urgencyScore() >= 0.75f) {
            return List.of(ReminderAction.SOFT_PUSH, ReminderAction.NORMAL_PUSH);
        }
        return List.of(ReminderAction.SOFT_PUSH);
    }

    private float score(ReminderCandidate candidate,
                        ReminderAction action,
                        ReminderActionPolicyProfile profile) {
        ReminderActionPerformanceStats stats = profile.statsFor(candidate.type(), action);
        float rewardMean = stats.rewardMean();
        float explorationBonus = (1.0f - stats.confidence()) * 0.08f;
        float actionBias = action == ReminderAction.NORMAL_PUSH
                ? normalPushBias(candidate)
                : softPushBias(candidate);
        return rewardMean * 0.70f + actionBias * 0.22f + explorationBonus;
    }

    private float normalPushBias(ReminderCandidate candidate) {
        float bias = candidate.finalScore() * 0.55f
                + candidate.urgencyScore() * 0.30f
                + candidate.actionabilityScore() * 0.15f;
        if (candidate.duplicatePenalty() > 0.0f || candidate.fatiguePenalty() > 0.10f) {
            bias -= 0.10f;
        }
        return clamp(bias);
    }

    private float softPushBias(ReminderCandidate candidate) {
        float bias = candidate.finalScore() * 0.40f
                + candidate.userFitScore() * 0.30f
                + (1.0f - candidate.duplicatePenalty()) * 0.15f
                + (1.0f - candidate.fatiguePenalty()) * 0.15f;
        return clamp(bias);
    }

    private String label(ReminderAction action) {
        return switch (action) {
            case SOFT_PUSH -> "轻提醒";
            case NORMAL_PUSH -> "标准提醒";
            case DEFER_TO_WINDOW -> "延后提醒";
            case SKIP -> "跳过";
        };
    }

    private float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

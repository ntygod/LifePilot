package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;

import java.util.List;

/**
 * 主动提醒机会策略选择器。
 *
 * <p>在规则层和硬边界之后、动作强度选择之前，
 * 使用上下文 Bandit 判断当前是否值得打扰用户。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderOpportunityPolicySelector {

    private final ReminderActionContextualBandit contextualBandit;
    private final float promoteThreshold;
    private final float suppressThreshold;
    private final float promotionMargin;

    public ReminderOpportunityPolicySelector() {
        this(new ReminderActionContextualBandit(), 0.72f, 0.34f, 0.08f);
    }

    public ReminderOpportunityPolicySelector(AgentConfigProperties config) {
        this(new ReminderActionContextualBandit(
                        config != null ? config.getTask().getProactiveReminderBanditExplorationAlpha() : 0.18f,
                        config != null ? config.getTask().getProactiveReminderBanditMinExamples() : 16,
                        config != null ? config.getTask().getProactiveReminderBanditMinActionSamples() : 3,
                        1.0d
                ),
                config != null ? config.getTask().getProactiveReminderOpportunityPromoteThreshold() : 0.72f,
                config != null ? config.getTask().getProactiveReminderOpportunitySuppressThreshold() : 0.34f,
                config != null ? config.getTask().getProactiveReminderOpportunityPromotionMargin() : 0.08f);
    }

    public ReminderOpportunityPolicySelector(ReminderActionContextualBandit contextualBandit,
                                             float promoteThreshold,
                                             float suppressThreshold,
                                             float promotionMargin) {
        this.contextualBandit = contextualBandit != null ? contextualBandit : new ReminderActionContextualBandit();
        this.promoteThreshold = clamp(promoteThreshold);
        this.suppressThreshold = clamp(suppressThreshold);
        this.promotionMargin = Math.max(0.0f, promotionMargin);
    }

    /**
     * 根据上下文收益估计调整“是否提醒”。
     */
    public ReminderDecision refine(ReminderDecision baseDecision,
                                   ReminderTopicState topicState,
                                   ReminderPolicyConfig config,
                                   List<ReminderActionTrainingExample> examples) {
        if (baseDecision == null
                || topicState == null
                || config == null
                || examples == null
                || examples.isEmpty()) {
            return baseDecision;
        }

        ReminderActionContextualBandit.Policy policy = contextualBandit.fit(
                examples,
                List.of(ReminderAction.SOFT_PUSH, ReminderAction.NORMAL_PUSH)
        );
        if (policy == null) {
            return baseDecision;
        }

        ReminderActionBanditEstimate softEstimate =
                policy.estimate(baseDecision.candidate(), topicState, ReminderAction.SOFT_PUSH);
        ReminderActionBanditEstimate normalEstimate =
                policy.estimate(baseDecision.candidate(), topicState, ReminderAction.NORMAL_PUSH);
        ReminderActionBanditEstimate bestEstimate = betterEstimate(softEstimate, normalEstimate);
        if (bestEstimate == null) {
            return baseDecision;
        }

        if (isPushAction(baseDecision.action())) {
            if (shouldSuppress(baseDecision, bestEstimate)) {
                return new ReminderDecision(
                        baseDecision.candidate(),
                        ReminderAction.SKIP,
                        baseDecision.nextEvaluationAt(),
                        baseDecision.reason() + "；近期类似场景提醒收益偏低，暂缓打扰"
                );
            }
            return baseDecision;
        }

        if (baseDecision.action() == ReminderAction.SKIP && canPromote(baseDecision, config, bestEstimate)) {
            return new ReminderDecision(
                    baseDecision.candidate(),
                    ReminderAction.SOFT_PUSH,
                    baseDecision.nextEvaluationAt(),
                    baseDecision.reason() + "；结合近期类似场景反馈，补发轻提醒"
            );
        }
        return baseDecision;
    }

    private boolean shouldSuppress(ReminderDecision baseDecision, ReminderActionBanditEstimate estimate) {
        ReminderCandidate candidate = baseDecision.candidate();
        if (candidate.urgencyScore() >= 0.78f) {
            return false;
        }
        if (candidate.finalScore() >= 0.88f && candidate.urgencyScore() >= 0.65f) {
            return false;
        }
        return estimate.conservativeReward() < suppressThreshold;
    }

    private boolean canPromote(ReminderDecision baseDecision,
                               ReminderPolicyConfig config,
                               ReminderActionBanditEstimate estimate) {
        ReminderCandidate candidate = baseDecision.candidate();
        if (!isPromotableSkip(baseDecision)) {
            return false;
        }
        if (candidate.urgencyScore() < 0.35f && candidate.timingScore() < 0.70f) {
            return false;
        }
        if (candidate.finalScore() + promotionMargin < config.minFinalScore()) {
            return false;
        }
        return estimate.optimisticReward() >= promoteThreshold;
    }

    private boolean isPromotableSkip(ReminderDecision decision) {
        return decision.skipReason() != null && decision.skipReason().isPromotable();
    }

    private ReminderActionBanditEstimate betterEstimate(ReminderActionBanditEstimate left,
                                                        ReminderActionBanditEstimate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        if (right.optimisticReward() > left.optimisticReward()) {
            return right;
        }
        if (right.optimisticReward() == left.optimisticReward()
                && right.expectedReward() > left.expectedReward()) {
            return right;
        }
        return left;
    }

    private boolean isPushAction(ReminderAction action) {
        return action == ReminderAction.SOFT_PUSH
                || action == ReminderAction.NORMAL_PUSH
                || action == ReminderAction.PREPARE
                || action == ReminderAction.AUTO_EXECUTE;
    }

    private float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

package com.lifepilot.agent.task.reminder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 提醒决策引擎。
 *
 * <p>输入主题快照，输出是否提醒、何时提醒以及提醒强度。
 * 当前版本使用统一评分 + 硬边界控制，后续可替换为 Contextual Bandit 策略。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderDecisionEngine {

    private final ReminderCandidateDetector candidateDetector;

    public ReminderDecisionEngine() {
        this(new ReminderCandidateDetector());
    }

    public ReminderDecisionEngine(ReminderCandidateDetector candidateDetector) {
        this.candidateDetector = candidateDetector;
    }

    /**
     * 评估单个主题。
     */
    public Optional<ReminderDecision> evaluateTopic(ReminderTopicSnapshot snapshot,
                                                    ReminderRuntimeContext context,
                                                    ReminderPolicyConfig config) {
        List<ReminderCandidate> candidates = candidateDetector.detect(snapshot, context, config);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        ReminderCandidate best = candidates.getFirst();
        return Optional.of(decide(best, snapshot.state(), context, config));
    }

    /**
     * 批量评估多个主题，并应用每日上限约束。
     */
    public List<ReminderDecision> evaluate(List<ReminderTopicSnapshot> snapshots,
                                           ReminderRuntimeContext context,
                                           ReminderPolicyConfig config) {
        List<ReminderDecision> decisions = snapshots.stream()
                .map(snapshot -> evaluateTopic(snapshot, context, config))
                .flatMap(Optional::stream)
                .toList();

        if (decisions.isEmpty()) {
            return List.of();
        }

        int remainingSlots = context.remainingReminderSlots(config.dailyMaxReminders());
        List<ReminderDecision> result = new ArrayList<>();
        List<ReminderDecision> pushDecisions = decisions.stream()
                .filter(this::isPushAction)
                .sorted(Comparator.comparing(ReminderDecision::finalScore).reversed())
                .toList();

        for (ReminderDecision decision : decisions) {
            if (!isPushAction(decision)) {
                result.add(decision);
                continue;
            }

            int rank = pushDecisions.indexOf(decision);
            if (rank >= remainingSlots) {
                result.add(new ReminderDecision(
                        decision.candidate(),
                        ReminderAction.SKIP,
                        ReminderSkipReason.DAILY_LIMIT,
                        decision.nextEvaluationAt(),
                        ReminderSkipReason.DAILY_LIMIT.label()
                ));
            } else {
                result.add(decision);
            }
        }

        return result.stream()
                .sorted(Comparator
                        .comparing(this::actionPriority)
                        .thenComparing(ReminderDecision::finalScore, Comparator.reverseOrder()))
                .toList();
    }

    private ReminderDecision decide(ReminderCandidate candidate,
                                    ReminderTopicState state,
                                    ReminderRuntimeContext context,
                                    ReminderPolicyConfig config) {
        if (state.muted()) {
            return skipDecision(candidate, ReminderSkipReason.TOPIC_MUTED);
        }
        if (context.isWithinQuietHours()) {
            return skipDecision(candidate, ReminderSkipReason.QUIET_HOURS);
        }
        if (isWithinCooldown(state, context, config)) {
            return skipDecision(candidate, ReminderSkipReason.COOLDOWN);
        }
        if (candidate.finalScore() < config.minFinalScore()) {
            return skipDecision(candidate, ReminderSkipReason.LOW_SCORE);
        }
        if (candidate.suggestedAt() != null && candidate.suggestedAt().isAfter(context.now())) {
            return new ReminderDecision(candidate, ReminderAction.DEFER_TO_WINDOW,
                    candidate.suggestedAt(), "更适合在预测窗口提醒");
        }
        if (candidate.finalScore() >= config.strongPushThreshold() || candidate.urgencyScore() >= 0.85f) {
            return new ReminderDecision(candidate, ReminderAction.NORMAL_PUSH, null, "命中高优先级提醒条件");
        }
        if (candidate.finalScore() >= config.softPushThreshold()) {
            return new ReminderDecision(candidate, ReminderAction.SOFT_PUSH, null, "适合发送轻提醒");
        }
        return skipDecision(candidate, ReminderSkipReason.INSUFFICIENT_REASON);
    }

    private static ReminderDecision skipDecision(ReminderCandidate candidate, ReminderSkipReason skipReason) {
        return new ReminderDecision(candidate, ReminderAction.SKIP, skipReason, null, skipReason.label());
    }

    private boolean isWithinCooldown(ReminderTopicState state,
                                     ReminderRuntimeContext context,
                                     ReminderPolicyConfig config) {
        if (state.lastRemindedAt() == null) {
            return false;
        }
        Duration sinceLast = Duration.between(state.lastRemindedAt(), context.now());
        return sinceLast.isNegative() || sinceLast.compareTo(config.defaultCooldown()) < 0;
    }

    private boolean isPushAction(ReminderDecision decision) {
        return decision.action() == ReminderAction.SOFT_PUSH
                || decision.action() == ReminderAction.NORMAL_PUSH;
    }

    private int actionPriority(ReminderDecision decision) {
        return switch (decision.action()) {
            case NORMAL_PUSH -> 0;
            case SOFT_PUSH -> 1;
            case DEFER_TO_WINDOW -> 2;
            case SKIP -> 3;
        };
    }
}

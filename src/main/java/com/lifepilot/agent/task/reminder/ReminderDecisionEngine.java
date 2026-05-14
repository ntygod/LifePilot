package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.task.reminder.timing.GoldilocksWindowCalculator;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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
    @Nullable
    private final ReminderTrustGradient trustGradient;
    @Nullable
    private final GoldilocksWindowCalculator goldilocksWindow;
    @Nullable
    private final Supplier<String> userIdSupplier;

    public ReminderDecisionEngine() {
        this(new ReminderCandidateDetector(), null, null, null);
    }

    public ReminderDecisionEngine(ReminderCandidateDetector candidateDetector) {
        this(candidateDetector, null, null, null);
    }

    public ReminderDecisionEngine(ReminderCandidateDetector candidateDetector,
                                  @Nullable ReminderTrustGradient trustGradient) {
        this(candidateDetector, trustGradient, null, null);
    }

    public ReminderDecisionEngine(ReminderCandidateDetector candidateDetector,
                                  @Nullable ReminderTrustGradient trustGradient,
                                  @Nullable GoldilocksWindowCalculator goldilocksWindow,
                                  @Nullable Supplier<String> userIdSupplier) {
        this.candidateDetector = candidateDetector;
        this.trustGradient = trustGradient;
        this.goldilocksWindow = goldilocksWindow;
        this.userIdSupplier = userIdSupplier;
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

        // --- 焦点状态检查（在静默检查之后、冷却检查之前） ---
        if (context.isFullscreenApp()) {
            return skipDecision(candidate, ReminderSkipReason.FULLSCREEN_APP);
        }

        // --- Goldilocks 窗口检查 ---
        if (goldilocksWindow != null) {
            String userId = userIdSupplier != null ? safeGet(userIdSupplier) : null;
            if (goldilocksWindow.isWindowClosed(userId, candidate, context.now())) {
                return skipDecision(candidate, ReminderSkipReason.WINDOW_CLOSED);
            }
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

        // --- 基础动作推断 ---
        ReminderAction baseAction;
        String baseReason;
        if (candidate.finalScore() >= config.strongPushThreshold() || candidate.urgencyScore() >= 0.85f) {
            baseAction = ReminderAction.NORMAL_PUSH;
            baseReason = "命中高优先级提醒条件";
        } else if (candidate.finalScore() >= config.softPushThreshold()) {
            baseAction = ReminderAction.SOFT_PUSH;
            baseReason = "适合发送轻提醒";
        } else {
            return skipDecision(candidate, ReminderSkipReason.INSUFFICIENT_REASON);
        }

        // --- 焦点编码降级：NORMAL_PUSH → SOFT_PUSH ---
        if (context.isFocusedCoding() && baseAction == ReminderAction.NORMAL_PUSH) {
            baseAction = ReminderAction.SOFT_PUSH;
            baseReason = "用户正在编码中，降级为轻提醒";
        }

        // --- 信任等级约束 ---
        baseAction = applyTrustConstraint(candidate, baseAction);

        return new ReminderDecision(candidate, baseAction, null, baseReason);
    }

    /**
     * 根据信任等级约束最大允许动作。
     *
     * <p>如果信任等级不足以执行当前动作则逐级降级。</p>
     */
    private ReminderAction applyTrustConstraint(ReminderCandidate candidate, ReminderAction action) {
        if (trustGradient == null) {
            return action;
        }
        ReminderTrustLevel trust = trustGradient.getTrustLevel("default", candidate.type());
        return switch (action) {
            case AUTO_EXECUTE -> trust.isAtLeast(ReminderTrustLevel.AUTO_EXECUTE)
                    ? action : applyTrustConstraint(candidate, ReminderAction.PREPARE);
            case PREPARE -> trust.isAtLeast(ReminderTrustLevel.PREPARE)
                    ? action : applyTrustConstraint(candidate, ReminderAction.NORMAL_PUSH);
            case NORMAL_PUSH -> trust.isAtLeast(ReminderTrustLevel.NOTIFY)
                    ? action : ReminderAction.SOFT_PUSH;
            case SOFT_PUSH -> trust.isAtLeast(ReminderTrustLevel.OBSERVE)
                    ? action : ReminderAction.SKIP;
            default -> action;
        };
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
        return switch (decision.action()) {
            case SOFT_PUSH, NORMAL_PUSH, PREPARE, AUTO_EXECUTE -> true;
            case SKIP, DEFER_TO_WINDOW -> false;
        };
    }

    private int actionPriority(ReminderDecision decision) {
        return switch (decision.action()) {
            case AUTO_EXECUTE -> 0;
            case PREPARE -> 1;
            case NORMAL_PUSH -> 2;
            case SOFT_PUSH -> 3;
            case DEFER_TO_WINDOW -> 4;
            case SKIP -> 5;
        };
    }

    @Nullable
    private static String safeGet(Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }
}

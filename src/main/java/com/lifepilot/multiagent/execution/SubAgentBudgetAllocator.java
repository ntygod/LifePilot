package com.lifepilot.multiagent.execution;

import com.lifepilot.agent.model.Budget;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Map;

/**
 * 子 Agent 预算分配器。
 *
 * <p>统一处理并行 Worker 的预算裁剪逻辑，
 * 预算基线始终来自父请求剩余额度，而不是父请求总额度。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
final class SubAgentBudgetAllocator {

    private static final String MAX_TOKENS_KEY = "max_tokens";
    private static final String MAX_STEPS_KEY = "max_steps";
    private static final String TIMEOUT_SECONDS_KEY = "timeout_seconds";

    private SubAgentBudgetAllocator() {
    }

    static Budget allocateWorkerPoolBudget(@Nullable Budget parentBudget,
                                           Budget fallbackBudget,
                                           double ratio) {
        Budget base = parentBudget != null ? parentBudget : fallbackBudget;
        return base.allocateForSubAgent(ratio);
    }

    @SuppressWarnings("unchecked")
    static Budget allocateWorkerBudget(Budget poolBudget, int workerCount, Map<String, Object> taskDef) {
        double ratio = workerCount > 0 ? 1.0 / workerCount : 0.0;
        Budget perWorker = poolBudget.allocateForSubAgent(ratio);

        Object budgetOverride = taskDef.get("budget");
        if (!(budgetOverride instanceof Map<?, ?> overrideMap)) {
            return perWorker;
        }

        return capBudget(
                perWorker,
                positiveInteger(((Map<String, Object>) overrideMap).get(MAX_TOKENS_KEY)),
                positiveInteger(((Map<String, Object>) overrideMap).get(MAX_STEPS_KEY)),
                positiveDuration(((Map<String, Object>) overrideMap).get(TIMEOUT_SECONDS_KEY))
        );
    }

    private static Budget capBudget(Budget base,
                                    @Nullable Integer maxTokens,
                                    @Nullable Integer maxSteps,
                                    @Nullable Duration maxDuration) {
        int cappedTokens = maxTokens != null ? Math.min(base.maxTokens(), maxTokens) : base.maxTokens();
        int cappedSteps = maxSteps != null ? Math.min(base.maxSteps(), maxSteps) : base.maxSteps();
        Duration cappedDuration = maxDuration != null
                ? minDuration(base.maxDuration(), maxDuration)
                : base.maxDuration();
        return Budget.builder()
                .maxTokens(cappedTokens)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(cappedSteps)
                .stepsUsed(0)
                .maxDuration(cappedDuration)
                .elapsed(Duration.ZERO)
                .build();
    }

    private static @Nullable Integer positiveInteger(@Nullable Object value) {
        if (value instanceof Number number) {
            int parsed = number.intValue();
            return parsed > 0 ? parsed : null;
        }
        return null;
    }

    private static @Nullable Duration positiveDuration(@Nullable Object value) {
        Integer seconds = positiveInteger(value);
        return seconds != null ? Duration.ofSeconds(seconds) : null;
    }

    private static Duration minDuration(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }
}

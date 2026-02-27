package com.lifepilot.eval.evaluator;

import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.eval.scenario.BenchmarkScenario;

import java.util.ArrayList;
import java.util.List;

/**
 * Token 效率评估器。
 *
 * <p>评分 = min(expectedTokenBudget / actualTokens, 1.0)。
 * 若 actualTokens == 0，评分为 1.0。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public final class TokenEfficiencyEvaluator implements DimensionEvaluator {

    private static final String DIMENSION_NAME = "tokenEfficiency";

    @Override
    public String dimensionName() {
        return DIMENSION_NAME;
    }

    @Override
    public DimensionScore evaluate(List<TraceStep> steps, BenchmarkScenario scenario) {
        // 从 LlmCallStep 子类型累加 Token 消耗
        long actualTokens = steps.stream()
                .filter(s -> s instanceof LlmCallStep)
                .mapToLong(s -> {
                    var llm = (LlmCallStep) s;
                    return llm.inputTokens() + llm.outputTokens();
                })
                .sum();

        if (actualTokens == 0) {
            return new DimensionScore(DIMENSION_NAME, 1.0, List.of(), List.of());
        }

        int expectedTokenBudget = scenario.expectedTokenBudget();
        double score = Math.min((double) expectedTokenBudget / actualTokens, 1.0);

        var violations = new ArrayList<String>();
        var suggestions = new ArrayList<String>();

        if (actualTokens > expectedTokenBudget) {
            violations.add("实际 Token 消耗(%d)超过预算(%d)"
                    .formatted(actualTokens, expectedTokenBudget));
            suggestions.add("优化 Prompt 或减少不必要的工具调用以降低 Token 消耗");
        }

        return new DimensionScore(DIMENSION_NAME, score,
                List.copyOf(violations), List.copyOf(suggestions));
    }
}

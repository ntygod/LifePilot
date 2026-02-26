package com.lifepilot.eval.evaluator;

import com.lifepilot.agent.trace.TraceStep;
import com.lifepilot.eval.scenario.BenchmarkScenario;

import java.util.ArrayList;
import java.util.List;

/**
 * 步骤效率评估器。
 *
 * <p>评分 = min(expectedStepCount / actualStepCount, 1.0)。
 * 若 actualStepCount == 0，评分为 1.0（无步骤意味着无需评估）。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public final class StepEfficiencyEvaluator implements DimensionEvaluator {

    private static final String DIMENSION_NAME = "stepEfficiency";

    @Override
    public String dimensionName() {
        return DIMENSION_NAME;
    }

    @Override
    public DimensionScore evaluate(List<TraceStep> steps, BenchmarkScenario scenario) {
        int actualStepCount = steps.size();
        int expectedStepCount = scenario.expectedStepCount();

        if (actualStepCount == 0) {
            return new DimensionScore(DIMENSION_NAME, 1.0, List.of(), List.of());
        }

        double score = Math.min((double) expectedStepCount / actualStepCount, 1.0);

        var violations = new ArrayList<String>();
        var suggestions = new ArrayList<String>();

        if (actualStepCount > expectedStepCount) {
            violations.add("实际步骤数(%d)超过期望步骤数(%d)"
                    .formatted(actualStepCount, expectedStepCount));
            suggestions.add("优化 Agent 决策路径，减少不必要的中间步骤");
        }

        return new DimensionScore(DIMENSION_NAME, score,
                List.copyOf(violations), List.copyOf(suggestions));
    }
}

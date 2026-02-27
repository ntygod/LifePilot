package com.lifepilot.eval.evaluator;

import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.eval.scenario.BenchmarkScenario;

import java.util.List;

/**
 * 维度评估器 sealed interface — 每个评估维度一个 permit。
 *
 * <p>通过 sealed 修饰符确保评估维度在编译时完全已知，
 * switch 表达式可以穷举匹配所有维度。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public sealed interface DimensionEvaluator permits
        ToolSelectionEvaluator,
        ParameterValidityEvaluator,
        StepEfficiencyEvaluator,
        PolicyComplianceEvaluator,
        TokenEfficiencyEvaluator {

    /** 维度名称。 */
    String dimensionName();

    /**
     * 评估单个维度。
     *
     * @param steps    轨迹步骤列表
     * @param scenario Benchmark 场景
     * @return 维度评估结果
     */
    DimensionScore evaluate(List<TraceStep> steps, BenchmarkScenario scenario);
}

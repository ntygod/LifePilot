package com.lifepilot.eval.evaluator;

import com.lifepilot.observability.evaluation.EvaluationConfig;
import com.lifepilot.observability.evaluation.EvaluationCore;
import com.lifepilot.observability.evaluation.EvaluationResult;
import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 轨迹评估器 — 委托 {@link EvaluationCore} 执行五维评估，构建 {@link EvalResult}。
 *
 * <p>从 {@link BenchmarkScenario} 提取维度权重、期望步骤数、期望 Token 预算和期望工具调用序列，
 * 构建 {@link EvaluationConfig} 后委托给共享评估核心。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class TrajectoryEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryEvaluator.class);

    private final EvaluationCore evaluationCore;

    public TrajectoryEvaluator(EvaluationCore evaluationCore) {
        this.evaluationCore = evaluationCore;
    }

    /**
     * 评估轨迹 — 委托 EvaluationCore 执行五维评估。
     *
     * @param steps    轨迹步骤
     * @param scenario Benchmark 场景
     * @return 评估结果
     */
    public EvalResult evaluate(List<TraceStep> steps, BenchmarkScenario scenario) {
        // 从 BenchmarkScenario 构建 EvaluationConfig
        EvaluationConfig evalConfig = buildEvaluationConfig(scenario);

        // 委托 EvaluationCore 执行五维评估
        EvaluationResult coreResult = evaluationCore.evaluate(steps, evalConfig);

        log.debug("轨迹评估完成: scenarioId={}, overallScore={}", scenario.id(), coreResult.overallScore());

        // TraceStep sealed interface 不携带 traceId，由 EvalEngine 在外层设置
        return EvalResult.builder()
                .evalId(UUID.randomUUID().toString())
                .traceId("")
                .scenarioId(scenario.id())
                .dimensionScores(Map.of(
                        "toolSelection", coreResult.toolSelectionScore(),
                        "parameterValidity", coreResult.parameterValidityScore(),
                        "stepEfficiency", coreResult.stepEfficiencyScore(),
                        "policyCompliance", coreResult.policyComplianceScore(),
                        "tokenEfficiency", coreResult.tokenEfficiencyScore()
                ))
                .overallScore(coreResult.overallScore())
                .violations(coreResult.violations())
                .suggestions(coreResult.suggestions())
                .llmJudgeScore(null)
                .llmJudgeJustification(null)
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .gitCommitHash(null)
                .gitBranch(null)
                .evalRunId(null)
                .build();
    }

    /**
     * 从 BenchmarkScenario 构建 EvaluationConfig。
     *
     * @param scenario 场景定义
     * @return 评估配置
     */
    private EvaluationConfig buildEvaluationConfig(BenchmarkScenario scenario) {
        Map<String, Double> weights = scenario.dimensionWeights();
        return new EvaluationConfig(
                weights.getOrDefault("toolSelection", 0.2),
                weights.getOrDefault("parameterValidity", 0.2),
                weights.getOrDefault("stepEfficiency", 0.2),
                weights.getOrDefault("policyCompliance", 0.2),
                weights.getOrDefault("tokenEfficiency", 0.2),
                scenario.expectedStepCount(),
                scenario.expectedTokenBudget(),
                scenario.expectedToolCalls()
        );
    }
}

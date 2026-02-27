package com.lifepilot.eval.evaluator;

import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 轨迹评估器 — 协调五个维度评估器，计算加权综合评分。
 *
 * <p>遍历所有 {@link DimensionEvaluator}，对每个维度调用 evaluate 获取评分，
 * 再根据 {@link BenchmarkScenario#dimensionWeights()} 计算加权综合评分。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class TrajectoryEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryEvaluator.class);

    private final List<DimensionEvaluator> evaluators;
    private final DynamicToolRegistry toolRegistry;

    public TrajectoryEvaluator(List<DimensionEvaluator> evaluators, DynamicToolRegistry toolRegistry) {
        this.evaluators = List.copyOf(evaluators);
        this.toolRegistry = toolRegistry;
    }

    /**
     * 评估轨迹。
     *
     * <p>对每个维度评估器调用 evaluate，收集维度评分、违规项和建议，
     * 然后根据场景定义的维度权重计算加权综合评分。</p>
     *
     * @param steps    轨迹步骤
     * @param scenario Benchmark 场景
     * @return 评估结果
     */
    public EvalResult evaluate(List<TraceStep> steps, BenchmarkScenario scenario) {
        Map<String, Double> dimensionScores = new LinkedHashMap<>();
        List<String> allViolations = new ArrayList<>();
        List<String> allSuggestions = new ArrayList<>();
        double overallScore = 0.0;

        Map<String, Double> weights = scenario.dimensionWeights();

        for (DimensionEvaluator evaluator : evaluators) {
            DimensionScore score = evaluator.evaluate(steps, scenario);
            dimensionScores.put(score.dimensionName(), score.score());
            allViolations.addAll(score.violations());
            allSuggestions.addAll(score.suggestions());

            // 查找该维度的权重，缺失时默认 0.0
            double weight = weights.getOrDefault(score.dimensionName(), 0.0);
            overallScore += score.score() * weight;
        }

        // TraceStep sealed interface 不携带 traceId，由 EvalEngine 在外层设置
        String traceId = "";

        log.debug("轨迹评估完成: scenarioId={}, overallScore={}", scenario.id(), overallScore);

        return EvalResult.builder()
                .evalId(UUID.randomUUID().toString())
                .traceId(traceId)
                .scenarioId(scenario.id())
                .dimensionScores(dimensionScores)
                .overallScore(overallScore)
                .violations(allViolations)
                .suggestions(allSuggestions)
                .llmJudgeScore(null)
                .llmJudgeJustification(null)
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .gitCommitHash(null)
                .gitBranch(null)
                .evalRunId(null)
                .build();
    }
}

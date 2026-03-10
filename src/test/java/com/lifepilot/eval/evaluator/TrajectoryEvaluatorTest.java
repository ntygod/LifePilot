package com.lifepilot.eval.evaluator;

import com.lifepilot.observability.evaluation.EvaluationCore;
import com.lifepilot.observability.guardrail.ApprovalMode;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.GuardrailStep;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TrajectoryEvaluator 轨迹评估器单元测试。
 *
 * <p>使用真实的 {@link EvaluationCore} 实例，通过精心构造输入来产生可预测的评分。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
class TrajectoryEvaluatorTest {

    private EvaluationCore evaluationCore;

    @BeforeEach
    void setUp() {
        evaluationCore = new EvaluationCore();
    }

    /** 构建 ToolCallStep。 */
    private static ToolCallStep toolStep(int index, String toolId) {
        return new ToolCallStep(index, Instant.now(), Duration.ZERO,
                toolId, "execute", "{}", "output", true, null, RiskLevel.LOW);
    }

    /** 构建 LlmCallStep（用于 Token 和步骤计数）。 */
    private static LlmCallStep llmStep(int index, int inputTokens, int outputTokens) {
        return new LlmCallStep(index, Instant.now(), Duration.ZERO,
                "test", "model", "eval", inputTokens, outputTokens, Duration.ZERO, false, 0.0, null);
    }

    /** 构建被阻止的 GuardrailStep。 */
    private static GuardrailStep blockedStep(int index, String reason) {
        return new GuardrailStep(index, Instant.now(), Duration.ZERO,
                "policy", "tool_call", false, reason, RiskLevel.HIGH, ApprovalMode.USER_CONFIRM);
    }

    /**
     * 五维加权综合评分测试。
     * <p>
     * 构造 4 个 LlmCallStep（无 ToolCall / GuardrailStep），
     * expectedStepCount=2 → stepEfficiency = min(2/4, 1.0) = 0.5，
     * expectedTokenBudget=500, actualTokens=400 → tokenEfficiency = min(500/400, 1.0) = 1.0，
     * 无工具调用 → toolSelection=1.0, parameterValidity=1.0，
     * 无护栏 → policyCompliance=1.0。
     * 权重: toolSelection=0.1, parameterValidity=0.1, stepEfficiency=0.4, policyCompliance=0.1, tokenEfficiency=0.3
     * 期望综合评分: 1.0*0.1 + 1.0*0.1 + 0.5*0.4 + 1.0*0.1 + 1.0*0.3 = 0.1+0.1+0.2+0.1+0.3 = 0.80
     */
    @Test
    void 加权综合评分_正确计算() {
        var evaluator = new TrajectoryEvaluator(evaluationCore);

        var scenario = BenchmarkScenario.builder()
                .id("s-001").name("加权测试").userInput("输入")
                .dimensionWeights(Map.of(
                        "toolSelection", 0.1,
                        "parameterValidity", 0.1,
                        "stepEfficiency", 0.4,
                        "policyCompliance", 0.1,
                        "tokenEfficiency", 0.3))
                .timeoutSeconds(60).expectedTokenBudget(500).expectedStepCount(2)
                .build();

        // 4 个 LlmCallStep，每个 100 tokens (50 input + 50 output) → 总 400 tokens
        var steps = List.<TraceStep>of(
                llmStep(0, 50, 50), llmStep(1, 50, 50),
                llmStep(2, 50, 50), llmStep(3, 50, 50));

        EvalResult result = evaluator.evaluate(steps, scenario);

        assertEquals(0.80, result.overallScore(), 0.01);
        assertEquals(0.5, result.dimensionScores().get("stepEfficiency"), 0.01);
        assertEquals(1.0, result.dimensionScores().get("tokenEfficiency"), 0.01);
        assertEquals("s-001", result.scenarioId());
    }

    @Test
    void 空步骤列表_traceId为空字符串() {
        var evaluator = new TrajectoryEvaluator(evaluationCore);

        var scenario = BenchmarkScenario.builder()
                .id("s-002").name("空步骤").userInput("输入")
                .dimensionWeights(Map.of("stepEfficiency", 1.0))
                .timeoutSeconds(60).expectedTokenBudget(1000).expectedStepCount(0)
                .build();

        EvalResult result = evaluator.evaluate(List.of(), scenario);

        assertEquals("", result.traceId());
        // EvaluationCore 对空步骤返回默认评分 0.5
        assertEquals(0.5, result.overallScore(), 0.01);
    }

    /**
     * 维度权重中缺少某个维度时，buildEvaluationConfig 使用默认值 0.2。
     * 验证 policyCompliance 维度评分正确（无护栏步骤 → 1.0）。
     */
    @Test
    void 全维度权重_policyCompliance满分() {
        var evaluator = new TrajectoryEvaluator(evaluationCore);

        var scenario = BenchmarkScenario.builder()
                .id("s-003").name("全维度权重").userInput("输入")
                .dimensionWeights(Map.of(
                        "toolSelection", 0.2,
                        "parameterValidity", 0.2,
                        "stepEfficiency", 0.2,
                        "policyCompliance", 0.2,
                        "tokenEfficiency", 0.2))
                .timeoutSeconds(60).expectedTokenBudget(1000).expectedStepCount(3)
                .build();

        var steps = List.<TraceStep>of(toolStep(0, "t1"), toolStep(1, "t2"), toolStep(2, "t3"));

        EvalResult result = evaluator.evaluate(steps, scenario);

        // 所有维度均为 1.0，综合评分也为 1.0
        assertEquals(1.0, result.overallScore(), 0.01);
        assertEquals(1.0, result.dimensionScores().get("policyCompliance"), 0.01);
    }

    /**
     * 违规项和建议从所有维度汇总。
     */
    @Test
    void 违规项和建议_从所有维度汇总() {
        var evaluator = new TrajectoryEvaluator(evaluationCore);

        var scenario = BenchmarkScenario.builder()
                .id("s-004").name("汇总测试").userInput("输入")
                .dimensionWeights(Map.of(
                        "toolSelection", 0.1,
                        "parameterValidity", 0.1,
                        "stepEfficiency", 0.3,
                        "policyCompliance", 0.3,
                        "tokenEfficiency", 0.2))
                .timeoutSeconds(60).expectedTokenBudget(1000).expectedStepCount(1)
                .build();

        // 3 个步骤，其中 1 个被阻止
        var steps = List.<TraceStep>of(
                toolStep(0, "t1"),
                blockedStep(1, "危险操作"),
                toolStep(2, "t2"));

        EvalResult result = evaluator.evaluate(steps, scenario);

        // stepEfficiency: min(1/3, 1.0) ≈ 0.33 < 0.5 → 违规
        // policyCompliance: 1 blocked / 1 guardrail → 违规
        assertFalse(result.violations().isEmpty());
        assertTrue(result.violations().size() >= 2);
    }

    @Test
    void evalResult字段_正确填充() {
        var evaluator = new TrajectoryEvaluator(evaluationCore);

        var scenario = BenchmarkScenario.builder()
                .id("s-005").name("字段测试").userInput("输入")
                .dimensionWeights(Map.of("stepEfficiency", 1.0))
                .timeoutSeconds(60).expectedTokenBudget(1000).expectedStepCount(1)
                .build();

        var steps = List.<TraceStep>of(toolStep(0, "t1"));

        EvalResult result = evaluator.evaluate(steps, scenario);

        assertNotNull(result.evalId());
        assertFalse(result.evalId().isEmpty());
        assertNotNull(result.evaluatedAt());
        assertNull(result.llmJudgeScore());
        assertNull(result.llmJudgeJustification());
        assertEquals(0, result.llmJudgeTokensUsed());
        assertNull(result.gitCommitHash());
        assertNull(result.gitBranch());
        assertNull(result.evalRunId());
    }
}

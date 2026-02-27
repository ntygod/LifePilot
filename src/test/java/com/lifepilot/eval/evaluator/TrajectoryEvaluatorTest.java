package com.lifepilot.eval.evaluator;

import com.lifepilot.observability.guardrail.ApprovalMode;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.GuardrailStep;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * TrajectoryEvaluator 轨迹评估器单元测试。
 *
 * <p>使用真实的 DimensionEvaluator 实现，通过精心构造输入来产生可预测的评分。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
class TrajectoryEvaluatorTest {

    private DynamicToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(DynamicToolRegistry.class);
        when(toolRegistry.resolve(anyString())).thenReturn(Optional.empty());
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
     * 使用 StepEfficiencyEvaluator 和 TokenEfficiencyEvaluator 两个维度测试加权综合评分。
     * <p>
     * StepEfficiency: expectedStepCount=2, actualStepCount=4 → score = min(2/4, 1.0) = 0.5
     * TokenEfficiency: expectedTokenBudget=500, actualTokens=4*100=400 → score = min(500/400, 1.0) = 1.0
     * 权重: stepEfficiency=0.6, tokenEfficiency=0.4
     * 期望综合评分: 0.5 * 0.6 + 1.0 * 0.4 = 0.30 + 0.40 = 0.70
     */
    @Test
    void 加权综合评分_正确计算() {
        var stepEval = new StepEfficiencyEvaluator();
        var tokenEval = new TokenEfficiencyEvaluator();
        var evaluator = new TrajectoryEvaluator(List.of(stepEval, tokenEval), toolRegistry);

        var scenario = BenchmarkScenario.builder()
                .id("s-001").name("加权测试").userInput("输入")
                .dimensionWeights(Map.of("stepEfficiency", 0.6, "tokenEfficiency", 0.4))
                .timeoutSeconds(60).expectedTokenBudget(500).expectedStepCount(2)
                .build();

        // 4 个 LlmCallStep，每个 100 tokens (50 input + 50 output) → 总 400 tokens
        var steps = List.<TraceStep>of(
                llmStep(0, 50, 50), llmStep(1, 50, 50),
                llmStep(2, 50, 50), llmStep(3, 50, 50));

        EvalResult result = evaluator.evaluate(steps, scenario);

        assertEquals(0.70, result.overallScore(), 0.0001);
        assertEquals(0.5, result.dimensionScores().get("stepEfficiency"), 0.0001);
        assertEquals(1.0, result.dimensionScores().get("tokenEfficiency"), 0.0001);
        assertEquals("s-001", result.scenarioId());
    }

    @Test
    void 空步骤列表_traceId为空字符串() {
        var stepEval = new StepEfficiencyEvaluator();
        var evaluator = new TrajectoryEvaluator(List.of(stepEval), toolRegistry);

        var scenario = BenchmarkScenario.builder()
                .id("s-002").name("空步骤").userInput("输入")
                .dimensionWeights(Map.of("stepEfficiency", 1.0))
                .timeoutSeconds(60).expectedTokenBudget(1000).expectedStepCount(0)
                .build();

        EvalResult result = evaluator.evaluate(List.of(), scenario);

        assertEquals("", result.traceId());
        assertEquals(1.0, result.overallScore(), 0.0001);
    }

    /**
     * 维度权重中缺少某个维度时，该维度权重默认为 0。
     */
    @Test
    void 缺失维度权重_默认为0() {
        var stepEval = new StepEfficiencyEvaluator();
        var policyEval = new PolicyComplianceEvaluator();
        var evaluator = new TrajectoryEvaluator(List.of(stepEval, policyEval), toolRegistry);

        var scenario = BenchmarkScenario.builder()
                .id("s-003").name("缺失权重").userInput("输入")
                .dimensionWeights(Map.of("stepEfficiency", 1.0))
                .timeoutSeconds(60).expectedTokenBudget(1000).expectedStepCount(3)
                .build();

        var steps = List.<TraceStep>of(toolStep(0, "t1"), toolStep(1, "t2"), toolStep(2, "t3"));

        EvalResult result = evaluator.evaluate(steps, scenario);

        assertEquals(1.0, result.overallScore(), 0.0001);
        assertEquals(1.0, result.dimensionScores().get("policyCompliance"), 0.0001);
    }

    /**
     * 违规项和建议从所有维度汇总。
     */
    @Test
    void 违规项和建议_从所有维度汇总() {
        var stepEval = new StepEfficiencyEvaluator();
        var policyEval = new PolicyComplianceEvaluator();
        var evaluator = new TrajectoryEvaluator(List.of(stepEval, policyEval), toolRegistry);

        var scenario = BenchmarkScenario.builder()
                .id("s-004").name("汇总测试").userInput("输入")
                .dimensionWeights(Map.of("stepEfficiency", 0.5, "policyCompliance", 0.5))
                .timeoutSeconds(60).expectedTokenBudget(1000).expectedStepCount(1)
                .build();

        // 3 个步骤，其中 1 个被阻止
        var steps = List.<TraceStep>of(
                toolStep(0, "t1"),
                blockedStep(1, "危险操作"),
                toolStep(2, "t2"));

        EvalResult result = evaluator.evaluate(steps, scenario);

        assertFalse(result.violations().isEmpty());
        assertFalse(result.suggestions().isEmpty());
        assertTrue(result.violations().size() >= 2);
    }

    @Test
    void evalResult字段_正确填充() {
        var stepEval = new StepEfficiencyEvaluator();
        var evaluator = new TrajectoryEvaluator(List.of(stepEval), toolRegistry);

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

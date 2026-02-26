package com.lifepilot.eval.evaluator;

import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.trace.TraceStep;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        // 默认所有工具解析返回空（ParameterValidityEvaluator 会将未找到的工具计为无效）
        when(toolRegistry.resolve(anyString())).thenReturn(Optional.empty());
    }

    /** 构建测试用 TraceStep。 */
    private static TraceStep buildStep(int index, String toolId, boolean blocked,
                                       String blockReason, int tokensUsed) {
        return TraceStep.builder()
                .traceId("trace-001")
                .stepIndex(index)
                .phaseBefore(AgentPhase.EXECUTING)
                .phaseAfter(AgentPhase.EXECUTING)
                .action(new Action.ToolResult(
                        toolId != null ? toolId : "unknown", true, "ok", tokensUsed, 100L, false))
                .toolId(toolId)
                .toolInput("{}")
                .toolOutput("output")
                .blocked(blocked)
                .blockReason(blockReason)
                .tokensUsed(tokensUsed)
                .latencyMs(100L)
                .timestamp(Instant.now())
                .build();
    }

    /** 构建简单的非阻塞步骤。 */
    private static TraceStep buildStep(int index, String toolId, int tokensUsed) {
        return buildStep(index, toolId, false, null, tokensUsed);
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

        // 4 个步骤，每个 100 tokens → 总 400 tokens
        var steps = List.of(
                buildStep(0, "t1", 100), buildStep(1, "t2", 100),
                buildStep(2, "t3", 100), buildStep(3, "t4", 100));

        EvalResult result = evaluator.evaluate(steps, scenario);

        assertEquals(0.70, result.overallScore(), 0.0001);
        assertEquals(0.5, result.dimensionScores().get("stepEfficiency"), 0.0001);
        assertEquals(1.0, result.dimensionScores().get("tokenEfficiency"), 0.0001);
        assertEquals("s-001", result.scenarioId());
        assertEquals("trace-001", result.traceId());
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
        // StepEfficiencyEvaluator: actualStepCount=0 → 评分 1.0
        assertEquals(1.0, result.overallScore(), 0.0001);
    }

    /**
     * 维度权重中缺少某个维度时，该维度权重默认为 0。
     * <p>
     * StepEfficiency: expectedStepCount=3, actualStepCount=3 → score = 1.0, weight = 1.0
     * PolicyCompliance: 无 blocked → score = 1.0, weight = 0（不在 dimensionWeights 中）
     * 期望综合评分: 1.0 * 1.0 + 1.0 * 0.0 = 1.0
     */
    @Test
    void 缺失维度权重_默认为0() {
        var stepEval = new StepEfficiencyEvaluator();
        var policyEval = new PolicyComplianceEvaluator();
        var evaluator = new TrajectoryEvaluator(List.of(stepEval, policyEval), toolRegistry);

        var scenario = BenchmarkScenario.builder()
                .id("s-003").name("缺失权重").userInput("输入")
                .dimensionWeights(Map.of("stepEfficiency", 1.0))  // policyCompliance 无权重
                .timeoutSeconds(60).expectedTokenBudget(1000).expectedStepCount(3)
                .build();

        var steps = List.of(
                buildStep(0, "t1", 100), buildStep(1, "t2", 100), buildStep(2, "t3", 100));

        EvalResult result = evaluator.evaluate(steps, scenario);

        // stepEfficiency: 3/3 = 1.0 * 1.0 = 1.0; policyCompliance: 1.0 * 0.0 = 0.0
        assertEquals(1.0, result.overallScore(), 0.0001);
        // policyCompliance 评分仍然记录在 dimensionScores 中
        assertEquals(1.0, result.dimensionScores().get("policyCompliance"), 0.0001);
    }

    /**
     * 违规项和建议从所有维度汇总。
     * <p>
     * StepEfficiency: 期望 1 步，实际 3 步 → 产生违规和建议
     * PolicyCompliance: 1 个 blocked 步骤 → 产生违规和建议
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
        var steps = List.of(
                buildStep(0, "t1", false, null, 100),
                buildStep(1, "t2", true, "危险操作", 100),
                buildStep(2, "t3", false, null, 100));

        EvalResult result = evaluator.evaluate(steps, scenario);

        // StepEfficiency 产生违规（实际 3 > 期望 1），PolicyCompliance 产生违规（1 个 blocked）
        assertFalse(result.violations().isEmpty());
        assertFalse(result.suggestions().isEmpty());
        // 至少包含来自两个维度的违规
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

        var steps = List.of(buildStep(0, "t1", 100));

        EvalResult result = evaluator.evaluate(steps, scenario);

        // evalId 为 UUID 格式
        assertNotNull(result.evalId());
        assertFalse(result.evalId().isEmpty());
        // evaluatedAt 已设置
        assertNotNull(result.evaluatedAt());
        // LLM Judge 字段为 null/0（由 EvalEngine 后续填充）
        assertNull(result.llmJudgeScore());
        assertNull(result.llmJudgeJustification());
        assertEquals(0, result.llmJudgeTokensUsed());
        // Git 和 evalRunId 字段为 null（由 EvalEngine 后续填充）
        assertNull(result.gitCommitHash());
        assertNull(result.gitBranch());
        assertNull(result.evalRunId());
    }
}

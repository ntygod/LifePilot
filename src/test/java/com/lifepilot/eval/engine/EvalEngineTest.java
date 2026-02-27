package com.lifepilot.eval.engine;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.eval.evaluator.TrajectoryEvaluator;
import com.lifepilot.eval.judge.JudgeResult;
import com.lifepilot.eval.judge.LlmJudge;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.store.EvalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EvalEngine 协调流程单元测试。
 *
 * <p>Mock AgentLoop、TraceRecorder、LlmJudge 等依赖，
 * 验证 evaluateScenario 和 evaluateBatch 的完整协调流程，
 * 以及 Agent 异常场景的降级处理。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
class EvalEngineTest {

    private ScenarioLoader scenarioLoader;
    private AgentLoop agentLoop;
    private TraceRecorder traceRecorder;
    private TrajectoryEvaluator trajectoryEvaluator;
    private LlmJudge llmJudge;
    private EvalStore evalStore;
    private EvalReport evalReport;
    private EvalConfigProperties config;
    private EvalEngine evalEngine;

    @BeforeEach
    void setUp() {
        scenarioLoader = mock(ScenarioLoader.class);
        agentLoop = mock(AgentLoop.class);
        traceRecorder = mock(TraceRecorder.class);
        trajectoryEvaluator = mock(TrajectoryEvaluator.class);
        llmJudge = mock(LlmJudge.class);
        evalStore = mock(EvalStore.class);
        evalReport = mock(EvalReport.class);
        config = new EvalConfigProperties();

        evalEngine = new EvalEngine(
                scenarioLoader, agentLoop, traceRecorder,
                trajectoryEvaluator, llmJudge, evalStore,
                evalReport, config);
    }

    /** 构建测试用 BenchmarkScenario（无 LLM Judge）。 */
    private static BenchmarkScenario buildScenario(String id) {
        return BenchmarkScenario.builder()
                .id(id)
                .name("测试场景-" + id)
                .userInput("测试输入")
                .expectedToolCalls(List.of("tool.a"))
                .expectedOutputPattern(".*")
                .dimensionWeights(Map.of(
                        "toolSelection", 0.3,
                        "parameterValidity", 0.2,
                        "stepEfficiency", 0.2,
                        "policyCompliance", 0.2,
                        "tokenEfficiency", 0.1))
                .timeoutSeconds(60)
                .tags(List.of("test"))
                .expectedTokenBudget(1000)
                .expectedStepCount(3)
                .build();
    }

    /** 构建测试用 BenchmarkScenario（带 LLM Judge）。 */
    private static BenchmarkScenario buildScenarioWithJudge(String id) {
        return buildScenario(id).toBuilder()
                .llmJudgeCriteria("回答应包含关键信息")
                .build();
    }

    /** 构建 Mock AgentResponse。 */
    private static AgentResponse buildAgentResponse() {
        return new AgentResponse("trace-001", "session-001", "Agent 输出内容", 500, 3, null);
    }

    /** 构建 Mock EvalResult。 */
    private static EvalResult buildEvalResult(String scenarioId, double overallScore) {
        return EvalResult.builder()
                .evalId("eval-001")
                .traceId("trace-001")
                .scenarioId(scenarioId)
                .dimensionScores(Map.of("toolSelection", 0.9, "stepEfficiency", 0.8))
                .overallScore(overallScore)
                .violations(List.of())
                .suggestions(List.of())
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .build();
    }

    // --- 正常评估流程 ---

    @Test
    void 正常评估流程_Agent执行成功_返回评估结果() {
        var scenario = buildScenario("s-001");
        var agentResponse = buildAgentResponse();
        var expectedResult = buildEvalResult("s-001", 0.85);

        when(agentLoop.run(any(AgentRequest.class))).thenReturn(agentResponse);
        when(trajectoryEvaluator.evaluate(anyList(), eq(scenario))).thenReturn(expectedResult);

        var result = evalEngine.evaluateScenario(scenario);

        assertNotNull(result);
        assertEquals("s-001", result.scenarioId());
        assertEquals(0.85, result.overallScore(), 0.0001);
        // 验证 AgentLoop 被调用
        verify(agentLoop).run(any(AgentRequest.class));
        // 验证 TrajectoryEvaluator 被调用
        verify(trajectoryEvaluator).evaluate(anyList(), eq(scenario));
        // 验证异步持久化被调用
        verify(evalStore).persistAsync(any(EvalResult.class));
        // 无 llmJudgeCriteria，LlmJudge 不应被调用
        verify(llmJudge, never()).judge(anyString(), anyString(), anyString());
    }

    // --- Agent 异常降级 ---

    @Test
    void Agent执行异常_返回评分0且violations包含错误信息() {
        var scenario = buildScenario("s-002");
        when(agentLoop.run(any(AgentRequest.class)))
                .thenThrow(new RuntimeException("Agent 执行超时"));

        var result = evalEngine.evaluateScenario(scenario);

        assertNotNull(result);
        assertEquals("s-002", result.scenarioId());
        assertEquals(0.0, result.overallScore(), 0.0001);
        assertFalse(result.violations().isEmpty());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.contains("Agent 执行异常") && v.contains("Agent 执行超时")));
        // 异常场景也应异步持久化
        verify(evalStore).persistAsync(any(EvalResult.class));
        // TrajectoryEvaluator 不应被调用
        verify(trajectoryEvaluator, never()).evaluate(anyList(), any());
    }

    // --- 带 LLM Judge 的评估 ---

    @Test
    void 带LlmJudge的场景_Judge被调用且结果合并() {
        var scenario = buildScenarioWithJudge("s-003");
        var agentResponse = buildAgentResponse();
        var evalResult = buildEvalResult("s-003", 0.80);
        var judgeResult = new JudgeResult(0.9, "输出质量良好", 120, false);

        when(agentLoop.run(any(AgentRequest.class))).thenReturn(agentResponse);
        when(trajectoryEvaluator.evaluate(anyList(), eq(scenario))).thenReturn(evalResult);
        when(llmJudge.judge(eq("Agent 输出内容"), eq(".*"), eq("回答应包含关键信息")))
                .thenReturn(judgeResult);

        var result = evalEngine.evaluateScenario(scenario);

        assertNotNull(result);
        assertEquals("s-003", result.scenarioId());
        // LLM Judge 结果应合并到 EvalResult
        Double judgeScore = result.llmJudgeScore();
        assertNotNull(judgeScore);
        assertEquals(0.9, judgeScore.doubleValue(), 0.0001);
        assertEquals("输出质量良好", result.llmJudgeJustification());
        assertEquals(120, result.llmJudgeTokensUsed());
        verify(llmJudge).judge(anyString(), anyString(), anyString());
    }

    // --- 无 LLM Judge 的评估 ---

    @Test
    void 无LlmJudge的场景_Judge不被调用() {
        var scenario = buildScenario("s-004");
        var agentResponse = buildAgentResponse();
        var evalResult = buildEvalResult("s-004", 0.75);

        when(agentLoop.run(any(AgentRequest.class))).thenReturn(agentResponse);
        when(trajectoryEvaluator.evaluate(anyList(), eq(scenario))).thenReturn(evalResult);

        var result = evalEngine.evaluateScenario(scenario);

        assertNotNull(result);
        assertNull(result.llmJudgeScore());
        verify(llmJudge, never()).judge(anyString(), anyString(), anyString());
    }

    // --- 批量评估 ---

    @Test
    void 批量评估_多个场景逐个评估并生成报告() {
        var scenario1 = buildScenario("s-005");
        var scenario2 = buildScenario("s-006");
        var agentResponse = buildAgentResponse();
        var evalResult1 = buildEvalResult("s-005", 0.85);
        var evalResult2 = buildEvalResult("s-006", 0.70);
        var expectedSummary = ReportSummary.builder()
                .evalRunId("run-001")
                .totalScenarios(2)
                .passCount(2)
                .failCount(0)
                .averageOverallScore(0.775)
                .dimensionAverages(Map.of())
                .degraded(false)
                .evaluatedAt(Instant.now())
                .build();

        when(agentLoop.run(any(AgentRequest.class))).thenReturn(agentResponse);
        when(trajectoryEvaluator.evaluate(anyList(), eq(scenario1))).thenReturn(evalResult1);
        when(trajectoryEvaluator.evaluate(anyList(), eq(scenario2))).thenReturn(evalResult2);
        when(evalReport.generateSummary(anyList(), anyString())).thenReturn(expectedSummary);

        var summary = evalEngine.evaluateBatch(List.of(scenario1, scenario2));

        assertNotNull(summary);
        assertEquals(2, summary.totalScenarios());
        // AgentLoop 应被调用两次（每个场景一次）
        verify(agentLoop, times(2)).run(any(AgentRequest.class));
        // TrajectoryEvaluator 应被调用两次
        verify(trajectoryEvaluator, times(2)).evaluate(anyList(), any(BenchmarkScenario.class));
        // EvalReport.generateSummary 应被调用一次
        verify(evalReport).generateSummary(anyList(), anyString());
        // 异步持久化应被调用两次
        verify(evalStore, times(2)).persistAsync(any(EvalResult.class));
    }

    // --- 异步持久化验证 ---

    @Test
    void 评估完成后_异步持久化被调用() {
        var scenario = buildScenario("s-007");
        var agentResponse = buildAgentResponse();
        var evalResult = buildEvalResult("s-007", 0.90);

        when(agentLoop.run(any(AgentRequest.class))).thenReturn(agentResponse);
        when(trajectoryEvaluator.evaluate(anyList(), eq(scenario))).thenReturn(evalResult);

        evalEngine.evaluateScenario(scenario);

        verify(evalStore).persistAsync(argThat(result ->
                result.scenarioId().equals("s-007")));
    }
}

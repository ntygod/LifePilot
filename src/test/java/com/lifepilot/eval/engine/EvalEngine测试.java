package com.lifepilot.eval.engine;

import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.eval.judge.LlmJudge;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.store.EvalStore;
import com.lifepilot.observability.evaluation.EvaluationConfig;
import com.lifepilot.observability.evaluation.EvaluationCore;
import com.lifepilot.observability.evaluation.EvaluationResult;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EvalEngine 评估引擎单元测试。
 *
 * <p>Mock 所有外部依赖，验证 evaluateScenario 正常流程和超时降级。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@ExtendWith(MockitoExtension.class)
class EvalEngine测试 {

    @Mock private ScenarioLoader scenarioLoader;
    @Mock private AgentOrchestrator agentOrchestrator;
    @Mock private TraceQuery traceQuery;
    @Mock private EvaluationCore evaluationCore;
    @Mock private LlmJudge llmJudge;
    @Mock private EvalStore evalStore;
    @Mock private EvalReport evalReport;
    @Mock private DynamicToolRegistry toolRegistry;

    private EvalConfigProperties config;
    private EvalEngine evalEngine;

    @BeforeEach
    void setUp() {
        config = new EvalConfigProperties();
        config.setDefaultPassThreshold(0.7);
        config.setDegradationThreshold(0.1);
        var execution = new EvalConfigProperties.Execution();
        execution.setDefaultTimeoutSeconds(2);
        config.setExecution(execution);
        var llmJudgeConfig = new EvalConfigProperties.LlmJudge();
        llmJudgeConfig.setFallbackScore(0.5);
        config.setLlmJudge(llmJudgeConfig);

        evalEngine = new EvalEngine(
                scenarioLoader, agentOrchestrator, traceQuery,
                evaluationCore, llmJudge, evalStore,
                evalReport, toolRegistry, config, null
        );
    }

    @Test
    void evaluateScenario_正常流程返回有效结果() {
        var scenario = buildScenario("s1", 30);
        var agentResponse = new AgentResponse("trace-1", "session-1", "回答内容", 100, 3, null);

        when(agentOrchestrator.run(any())).thenReturn(agentResponse);
        when(traceQuery.getSteps("trace-1")).thenReturn(List.of());
        when(evaluationCore.evaluate(anyList(), any(EvaluationConfig.class), eq("trace-1")))
                .thenReturn(new EvaluationResult(
                        "trace-1", Instant.now(),
                        0.9, 0.8, 0.85, 0.95, 0.7,
                        0.84, 3, 100,
                        List.of(), List.of("建议优化步骤")
                ));

        EvalResult result = evalEngine.evaluateScenario(scenario, "run-1");

        assertThat(result).isNotNull();
        assertThat(result.scenarioId()).isEqualTo("s1");
        assertThat(result.evalRunId()).isEqualTo("run-1");
        assertThat(result.traceId()).isEqualTo("trace-1");
        assertThat(result.overallScore()).isEqualTo(0.84);
        assertThat(result.violations()).isEmpty();
        assertThat(result.suggestions()).containsExactly("建议优化步骤");
        // 验证异步持久化被调用
        verify(evalStore).persistAsync(any(EvalResult.class));
    }

    @Test
    void evaluateScenario_Agent异常返回零分结果() {
        var scenario = buildScenario("s-err", 30);

        when(agentOrchestrator.run(any())).thenThrow(new RuntimeException("LLM 不可用"));

        EvalResult result = evalEngine.evaluateScenario(scenario, "run-err");

        assertThat(result.scenarioId()).isEqualTo("s-err");
        assertThat(result.overallScore()).isEqualTo(0.0);
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().getFirst()).contains("Agent 执行异常");
        verify(evalStore).persistAsync(any(EvalResult.class));
    }

    @Test
    void evaluateScenario_traceId为空返回失败结果() {
        var scenario = buildScenario("s-no-trace", 30);
        // traceId 为空
        var agentResponse = new AgentResponse("", "session-1", "回答", 50, 1, null);

        when(agentOrchestrator.run(any())).thenReturn(agentResponse);

        EvalResult result = evalEngine.evaluateScenario(scenario, "run-no-trace");

        assertThat(result.overallScore()).isEqualTo(0.0);
        assertThat(result.violations()).anyMatch(v -> v.contains("traceId 为空"));
    }

    @Test
    void evaluateBatch_返回ReportSummary() {
        var s1 = buildScenario("batch-1", 30);
        var s2 = buildScenario("batch-2", 30);

        // 两个场景都正常执行
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("t1", "sess", "内容1", 80, 2, null))
                .thenReturn(new AgentResponse("t2", "sess", "内容2", 90, 3, null));
        when(traceQuery.getSteps(anyString())).thenReturn(List.of());
        when(evaluationCore.evaluate(anyList(), any(EvaluationConfig.class), anyString()))
                .thenReturn(new EvaluationResult(
                        "t", Instant.now(),
                        0.8, 0.8, 0.8, 0.8, 0.8,
                        0.8, 2, 80,
                        List.of(), List.of()
                ));

        var expectedSummary = ReportSummary.builder()
                .evalRunId("mock-run")
                .totalScenarios(2)
                .passCount(2)
                .failCount(0)
                .averageOverallScore(0.8)
                .dimensionAverages(Map.of())
                .degraded(false)
                .regressedScenarios(List.of())
                .newRegressions(List.of())
                .evaluatedAt(Instant.now())
                .build();

        when(evalReport.generateSummary(anyList(), anyString())).thenReturn(expectedSummary);

        ReportSummary summary = evalEngine.evaluateBatch(List.of(s1, s2));

        assertThat(summary).isNotNull();
        assertThat(summary.totalScenarios()).isEqualTo(2);
        verify(evalReport).generateSummary(argThat(list -> list.size() == 2), anyString());
    }

    // ── 辅助方法 ──

    private BenchmarkScenario buildScenario(String id, int timeout) {
        return BenchmarkScenario.builder()
                .id(id)
                .name("测试场景 " + id)
                .userInput("你好")
                .expectedToolCalls(List.of())
                .dimensionWeights(Map.of(
                        "toolSelection", 0.2,
                        "parameterValidity", 0.2,
                        "stepEfficiency", 0.2,
                        "policyCompliance", 0.2,
                        "tokenEfficiency", 0.2
                ))
                .timeoutSeconds(timeout)
                .tags(List.of("test"))
                .expectedTokenBudget(500)
                .expectedStepCount(3)
                .build();
    }
}

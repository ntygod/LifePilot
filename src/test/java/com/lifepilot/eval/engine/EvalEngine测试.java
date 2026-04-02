package com.lifepilot.eval.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.eval.evaluator.DiagnosticEnricher;
import com.lifepilot.eval.evaluator.DimensionEvaluator;
import com.lifepilot.eval.feedback.FeedbackStore;
import com.lifepilot.eval.judge.LlmJudge;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.eval.scenario.MockToolSpec;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.store.EvalStore;
import com.lifepilot.observability.evaluation.EvaluationConfig;
import com.lifepilot.observability.evaluation.EvaluationCore;
import com.lifepilot.observability.evaluation.EvaluationResult;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.lang.reflect.Method;

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
    @Mock private FeedbackStore feedbackStore;

    private EvalConfigProperties config;
    private EvalEngine evalEngine;

    @BeforeEach
    void setUp() {
        config = new EvalConfigProperties();
        config.setDefaultPassThreshold(0.7);
        config.setDegradationThreshold(0.1);
        var execution = new EvalConfigProperties.Execution();
        execution.setDefaultTimeoutSeconds(2);
        execution.setParallelism(1);
        config.setExecution(execution);
        var llmJudgeConfig = new EvalConfigProperties.LlmJudge();
        llmJudgeConfig.setFallbackScore(0.5);
        config.setLlmJudge(llmJudgeConfig);

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var diagnosticEnricher = new DiagnosticEnricher();
        var objectMapper = new ObjectMapper();

        evalEngine = new EvalEngine(
                scenarioLoader, agentOrchestrator, traceQuery,
                evaluationCore, llmJudge, evalStore,
                evalReport, toolRegistry, config,
                executor, diagnosticEnricher, objectMapper,
                List.of(), feedbackStore, null
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
        // suggestions 现在包含原始建议 + 诊断建议
        assertThat(result.suggestions()).contains("建议优化步骤");
        // 诊断报告应非空
        assertThat(result.diagnosticJson()).isNotNull();
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

    @Test
    @SuppressWarnings("unchecked")
    void registerMockTools_应覆盖同ID真实工具并在清理后恢复() throws Exception {
        DynamicToolRegistry realRegistry = new DynamicToolRegistry(mock(org.springframework.context.ApplicationEventPublisher.class));
        BuiltinTool originalTool = BuiltinTool.builder()
                .id("knowledge.search")
                .name("原始检索资料")
                .description("原始工具")
                .inputSchema(JsonSchema.empty())
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .executor(input -> ToolResult.success(Map.of("results", List.of(), "count", 0)))
                .build();
        realRegistry.registerBuiltinTool(originalTool);

        evalEngine = new EvalEngine(
                scenarioLoader, agentOrchestrator, traceQuery,
                evaluationCore, llmJudge, evalStore,
                evalReport, realRegistry, config,
                Executors.newVirtualThreadPerTaskExecutor(),
                new DiagnosticEnricher(), new ObjectMapper(),
                List.of(), feedbackStore, null
        );

        var scenario = BenchmarkScenario.builder()
                .id("domain-isolation-a")
                .name("领域隔离 A")
                .userInput("主角金手指是什么")
                .expectedToolCalls(List.of("knowledge.search"))
                .dimensionWeights(Map.of(
                        "toolSelection", 0.2,
                        "parameterValidity", 0.2,
                        "stepEfficiency", 0.2,
                        "policyCompliance", 0.2,
                        "tokenEfficiency", 0.2
                ))
                .timeoutSeconds(30)
                .mockTools(List.of(new MockToolSpec(
                        "knowledge.search",
                        List.of(new MockToolSpec.MockBehavior(
                                "主角金手指",
                                "{\"results\":[{\"content\":\"主角金手指设定：时间回溯。\"}],\"count\":1}",
                                false,
                                0
                        )),
                        "{\"results\":[],\"count\":0}"
                )))
                .tags(List.of("domain-isolation"))
                .expectedTokenBudget(500)
                .expectedStepCount(2)
                .build();

        Method registerMethod = EvalEngine.class.getDeclaredMethod("registerMockTools", BenchmarkScenario.class);
        registerMethod.setAccessible(true);
        List<Object> registrations = (List<Object>) registerMethod.invoke(evalEngine, scenario);

        var overridden = realRegistry.resolve("knowledge.search").orElseThrow();
        assertThat(overridden.name()).isEqualTo("mock-knowledge.search");
        var toolResult = overridden.execute(new ToolInput(
                overridden.id(),
                Map.of("query", "主角金手指"),
                JsonSchema.empty(),
                null,
                null
        ));
        assertThat(toolResult.ok()).isTrue();
        assertThat(toolResult.<String>getData("response")).contains("时间回溯");

        Method unregisterMethod = EvalEngine.class.getDeclaredMethod("unregisterMockTools", List.class);
        unregisterMethod.setAccessible(true);
        unregisterMethod.invoke(evalEngine, registrations);

        ToolContract restored = realRegistry.resolve("knowledge.search").orElseThrow();
        assertThat(restored.name()).isEqualTo("原始检索资料");
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

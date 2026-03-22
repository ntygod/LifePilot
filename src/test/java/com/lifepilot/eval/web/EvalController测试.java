package com.lifepilot.eval.web;

import com.lifepilot.eval.engine.EvalEngine;
import com.lifepilot.eval.feedback.FeedbackStore;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.store.EvalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EvalController REST 端点单元测试。
 *
 * <p>直接调用 Controller 方法，Mock 所有依赖，验证返回值和 HTTP 状态码。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@ExtendWith(MockitoExtension.class)
class EvalController测试 {

    @Mock private ScenarioLoader scenarioLoader;
    @Mock private EvalEngine evalEngine;
    @Mock private EvalStore evalStore;
    @Mock private EvalReport evalReport;
    @Mock private FeedbackStore feedbackStore;

    private EvalController controller;

    @BeforeEach
    void setUp() {
        controller = new EvalController(scenarioLoader, evalEngine, evalStore, evalReport, feedbackStore);
    }

    @Test
    void listScenarios_无tag返回全部场景() {
        var s1 = buildScenario("s1");
        var s2 = buildScenario("s2");
        when(scenarioLoader.loadAll()).thenReturn(List.of(s1, s2));

        var result = controller.listScenarios(null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().data()).hasSize(2);
    }

    @Test
    void listScenarios_按tag过滤() {
        var s1 = buildScenario("s1");
        when(scenarioLoader.loadByTags(List.of("basic"))).thenReturn(List.of(s1));

        var result = controller.listScenarios("basic");

        assertThat(result.getBody().data()).hasSize(1);
        assertThat(result.getBody().data().getFirst().id()).isEqualTo("s1");
    }

    @Test
    void getScenario_存在时返回200() {
        var s1 = buildScenario("s1");
        when(scenarioLoader.loadById("s1")).thenReturn(s1);

        var result = controller.getScenario("s1");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().data().id()).isEqualTo("s1");
    }

    @Test
    void triggerRun_全量运行返回报告() {
        var s1 = buildScenario("s1");
        when(scenarioLoader.loadAll()).thenReturn(List.of(s1));

        var summary = buildSummary("run-1");
        when(evalEngine.evaluateBatch(anyList())).thenReturn(summary);

        var request = new EvalRunRequest(null, null, null, null, null, null);
        var result = controller.triggerRun(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().data().evalRunId()).isEqualTo("run-1");
    }

    @Test
    void triggerRun_按scenarioIds过滤() {
        var s1 = buildScenario("s1");
        when(scenarioLoader.loadById("s1")).thenReturn(s1);

        var summary = buildSummary("run-2");
        when(evalEngine.evaluateBatch(argThat(list -> list.size() == 1))).thenReturn(summary);

        var request = new EvalRunRequest(List.of("s1"), null, null, null, null, null);
        var result = controller.triggerRun(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(evalEngine).evaluateBatch(argThat(list -> list.size() == 1));
    }

    @Test
    void triggerRun_按tag过滤() {
        var s1 = buildScenario("s1");
        when(scenarioLoader.loadByTags(List.of("basic"))).thenReturn(List.of(s1));

        var summary = buildSummary("run-3");
        when(evalEngine.evaluateBatch(anyList())).thenReturn(summary);

        var request = new EvalRunRequest(null, "basic", null, null, null, null);
        var result = controller.triggerRun(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(scenarioLoader).loadByTags(List.of("basic"));
    }

    @Test
    void getRunResults_返回结果列表() {
        var evalResult = buildEvalResult("e1", "s1", "run-1");
        when(evalStore.findByRunId("run-1")).thenReturn(List.of(evalResult));

        var result = controller.getRunResults("run-1");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().data()).hasSize(1);
    }

    @Test
    void getRunReport_生成并返回报告() {
        var evalResult = buildEvalResult("e1", "s1", "run-1");
        when(evalStore.findByRunId("run-1")).thenReturn(List.of(evalResult));

        var summary = buildSummary("run-1");
        when(evalReport.generateSummary(anyList(), eq("run-1"))).thenReturn(summary);

        var result = controller.getRunReport("run-1");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().data().evalRunId()).isEqualTo("run-1");
    }

    @Test
    void getResultsByScenario_返回场景历史结果() {
        var r1 = buildEvalResult("e1", "s1", "run-1");
        when(evalStore.findByScenarioId("s1", 5)).thenReturn(List.of(r1));

        var result = controller.getResultsByScenario("s1", 5);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().data()).hasSize(1);
    }

    // ── 辅助方法 ──

    private BenchmarkScenario buildScenario(String id) {
        return BenchmarkScenario.builder()
                .id(id)
                .name("场景 " + id)
                .userInput("你好")
                .expectedToolCalls(List.of())
                .dimensionWeights(Map.of("toolSelection", 1.0))
                .timeoutSeconds(30)
                .tags(List.of("test"))
                .expectedTokenBudget(500)
                .expectedStepCount(3)
                .build();
    }

    private ReportSummary buildSummary(String runId) {
        return ReportSummary.builder()
                .evalRunId(runId)
                .totalScenarios(1)
                .passCount(1)
                .failCount(0)
                .averageOverallScore(0.85)
                .dimensionAverages(Map.of())
                .degraded(false)
                .regressedScenarios(List.of())
                .newRegressions(List.of())
                .evaluatedAt(Instant.now())
                .build();
    }

    private EvalResult buildEvalResult(String evalId, String scenarioId, String runId) {
        return EvalResult.builder()
                .evalId(evalId)
                .traceId("trace-1")
                .scenarioId(scenarioId)
                .dimensionScores(Map.of("toolSelection", 0.9))
                .overallScore(0.9)
                .violations(List.of())
                .suggestions(List.of())
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .evalRunId(runId)
                .build();
    }
}

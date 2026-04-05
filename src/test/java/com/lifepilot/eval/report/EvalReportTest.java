package com.lifepilot.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.store.EvalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * EvalReport 评估报告生成器单元测试。
 *
 * @author zsg
 * @since 2026-08-01
 */
@ExtendWith(MockitoExtension.class)
class EvalReportTest {

    @Mock
    private EvalStore evalStore;

    private EvalConfigProperties config;
    private EvalReport evalReport;

    @BeforeEach
    void setUp() {
        config = new EvalConfigProperties();
        config.setDefaultPassThreshold(0.7);
        config.setDegradationThreshold(0.1);
        var objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        evalReport = new EvalReport(evalStore, config, objectMapper);

        // 默认无基线、无评分历史（lenient 避免未使用 stub 导致失败）
        lenient().when(evalStore.findLatestBaselineRunId()).thenReturn(Optional.empty());
        lenient().when(evalStore.findScoreHistory(anyString(), anyString(), anyInt())).thenReturn(List.of());
    }

    @Test
    void generateSummary_基本聚合正确() {
        var results = List.of(
                buildResult("s1", 0.9, Map.of("toolSelection", 0.8, "stepEfficiency", 1.0)),
                buildResult("s2", 0.6, Map.of("toolSelection", 0.5, "stepEfficiency", 0.7))
        );

        // 无历史数据
        when(evalStore.findByScenarioId(anyString(), anyInt())).thenReturn(List.of());

        var summary = evalReport.generateSummary(results, "run-1");

        assertEquals("run-1", summary.evalRunId());
        assertEquals(2, summary.totalScenarios());
        assertEquals(1, summary.passCount());   // s1 通过 (0.9 >= 0.7)
        assertEquals(1, summary.failCount());   // s2 失败 (0.6 < 0.7)
        assertEquals(0.75, summary.averageOverallScore(), 0.001);
        assertNotNull(summary.evaluatedAt());
    }

    @Test
    void generateSummary_维度平均分计算正确() {
        var results = List.of(
                buildResult("s1", 0.8, Map.of("toolSelection", 0.9, "tokenEfficiency", 0.7)),
                buildResult("s2", 0.7, Map.of("toolSelection", 0.5, "tokenEfficiency", 0.9))
        );

        when(evalStore.findByScenarioId(anyString(), anyInt())).thenReturn(List.of());

        var summary = evalReport.generateSummary(results, "run-1");

        assertEquals(0.7, summary.dimensionAverages().get("toolSelection"), 0.001);
        assertEquals(0.8, summary.dimensionAverages().get("tokenEfficiency"), 0.001);
    }

    @Test
    void generateSummary_空结果列表() {
        var summary = evalReport.generateSummary(List.of(), "run-empty");

        assertEquals(0, summary.totalScenarios());
        assertEquals(0, summary.passCount());
        assertEquals(0, summary.failCount());
        assertEquals(0.0, summary.averageOverallScore(), 0.001);
        assertTrue(summary.dimensionAverages().isEmpty());
        assertFalse(summary.degraded());
        assertTrue(summary.regressedScenarios().isEmpty());
        assertTrue(summary.newRegressions().isEmpty());
    }

    @Test
    void generateSummary_退化检测_平均分下降超过阈值() {
        var currentResults = List.of(
                buildResult("s1", 0.5, Map.of()),
                buildResult("s2", 0.4, Map.of())
        );
        // 当前平均分 = 0.45

        // 上次运行 s1 得分 0.9，s2 得分 0.8 → 上次平均 0.85
        // 差值 0.85 - 0.45 = 0.4 > 0.1 → degraded
        var prevS1 = buildResultWithRunId("s1", 0.9, "prev-run");
        var prevS2 = buildResultWithRunId("s2", 0.8, "prev-run");

        when(evalStore.findByScenarioId("s1", 5)).thenReturn(List.of(prevS1));
        when(evalStore.findByScenarioId("s2", 5)).thenReturn(List.of(prevS2));

        var summary = evalReport.generateSummary(currentResults, "run-2");

        assertTrue(summary.degraded());
    }

    @Test
    void generateSummary_退化检测_平均分下降未超过阈值() {
        var currentResults = List.of(
                buildResult("s1", 0.85, Map.of())
        );

        var prevS1 = buildResultWithRunId("s1", 0.9, "prev-run");
        when(evalStore.findByScenarioId("s1", 5)).thenReturn(List.of(prevS1));

        var summary = evalReport.generateSummary(currentResults, "run-2");

        // 差值 0.9 - 0.85 = 0.05 < 0.1 → 不退化
        assertFalse(summary.degraded());
    }

    @Test
    void generateSummary_新增退化场景检测() {
        var currentResults = List.of(
                buildResult("s1", 0.5, Map.of()),  // 本次失败
                buildResult("s2", 0.8, Map.of())   // 本次通过
        );

        // s1 上次通过 (0.9 >= 0.7)，本次失败 (0.5 < 0.7) → 新增退化
        var prevS1 = buildResultWithRunId("s1", 0.9, "prev-run");
        // s2 上次也通过
        var prevS2 = buildResultWithRunId("s2", 0.85, "prev-run");

        when(evalStore.findByScenarioId("s1", 5)).thenReturn(List.of(prevS1));
        when(evalStore.findByScenarioId("s2", 5)).thenReturn(List.of(prevS2));

        var summary = evalReport.generateSummary(currentResults, "run-2");

        assertEquals(List.of("s1"), summary.newRegressions());
    }

    @Test
    void generateSummary_上次也失败不算新增退化() {
        var currentResults = List.of(
                buildResult("s1", 0.5, Map.of())  // 本次失败
        );

        // s1 上次也失败 (0.3 < 0.7) → 不算新增退化
        var prevS1 = buildResultWithRunId("s1", 0.3, "prev-run");
        when(evalStore.findByScenarioId("s1", 5)).thenReturn(List.of(prevS1));

        var summary = evalReport.generateSummary(currentResults, "run-2");

        assertTrue(summary.newRegressions().isEmpty());
    }

    @Test
    void generateSummary_失败场景列入regressedScenarios() {
        var results = List.of(
                buildResult("s1", 0.9, Map.of()),
                buildResult("s2", 0.5, Map.of()),
                buildResult("s3", 0.3, Map.of())
        );

        when(evalStore.findByScenarioId(anyString(), anyInt())).thenReturn(List.of());

        var summary = evalReport.generateSummary(results, "run-1");

        assertEquals(2, summary.regressedScenarios().size());
        assertTrue(summary.regressedScenarios().contains("s2"));
        assertTrue(summary.regressedScenarios().contains("s3"));
    }

    @Test
    void generateSummary_无历史数据时不退化() {
        var results = List.of(buildResult("s1", 0.3, Map.of()));

        when(evalStore.findByScenarioId(anyString(), anyInt())).thenReturn(List.of());

        var summary = evalReport.generateSummary(results, "run-1");

        assertFalse(summary.degraded());
    }

    @Test
    void printToConsole_不抛异常() {
        var summary = ReportSummary.builder()
                .evalRunId("run-1")
                .totalScenarios(3)
                .passCount(2)
                .failCount(1)
                .averageOverallScore(0.75)
                .dimensionAverages(Map.of("toolSelection", 0.8, "stepEfficiency", 0.7))
                .degraded(true)
                .regressedScenarios(List.of("s3"))
                .newRegressions(List.of("s3"))
                .evaluatedAt(Instant.now())
                .build();

        assertDoesNotThrow(() -> evalReport.printToConsole(summary));
    }

    @Test
    void exportJson_有效JSON输出() {
        var summary = ReportSummary.builder()
                .evalRunId("run-1")
                .totalScenarios(2)
                .passCount(1)
                .failCount(1)
                .averageOverallScore(0.65)
                .dimensionAverages(Map.of("toolSelection", 0.7))
                .degraded(false)
                .regressedScenarios(List.of("s2"))
                .newRegressions(List.of())
                .evaluatedAt(Instant.parse("2026-08-01T10:00:00Z"))
                .build();

        String json = evalReport.exportJson(summary);

        assertNotNull(json);
        assertTrue(json.contains("run-1"));
        assertTrue(json.contains("0.65"));
        assertTrue(json.contains("toolSelection"));
        assertTrue(json.contains("s2"));

        // 验证是合法 JSON
        assertDoesNotThrow(() -> new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .readTree(json));
    }

    @Test
    void exportJson_往返一致性() throws Exception {
        var summary = ReportSummary.builder()
                .evalRunId("run-rt")
                .totalScenarios(5)
                .passCount(3)
                .failCount(2)
                .averageOverallScore(0.72)
                .dimensionAverages(Map.of("toolSelection", 0.8, "tokenEfficiency", 0.6))
                .degraded(true)
                .regressedScenarios(List.of("s4", "s5"))
                .newRegressions(List.of("s4"))
                .evaluatedAt(Instant.parse("2026-08-01T12:00:00Z"))
                .build();

        String json = evalReport.exportJson(summary);

        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        var parsed = mapper.readValue(json, ReportSummary.class);

        assertEquals(summary.evalRunId(), parsed.evalRunId());
        assertEquals(summary.totalScenarios(), parsed.totalScenarios());
        assertEquals(summary.passCount(), parsed.passCount());
        assertEquals(summary.failCount(), parsed.failCount());
        assertEquals(summary.averageOverallScore(), parsed.averageOverallScore(), 0.001);
        assertEquals(summary.degraded(), parsed.degraded());
        assertEquals(summary.evaluatedAt(), parsed.evaluatedAt());
    }

    // ── 辅助方法 ──

    private EvalResult buildResult(String scenarioId, double overallScore, Map<String, Double> dimensionScores) {
        return EvalResult.builder()
                .evalId("e-" + scenarioId)
                .traceId("t-" + scenarioId)
                .scenarioId(scenarioId)
                .dimensionScores(dimensionScores)
                .overallScore(overallScore)
                .violations(List.of())
                .suggestions(List.of())
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .evalRunId("run-1")
                .build();
    }

    private EvalResult buildResultWithRunId(String scenarioId, double overallScore, String runId) {
        return EvalResult.builder()
                .evalId("e-" + scenarioId + "-" + runId)
                .traceId("t-" + scenarioId)
                .scenarioId(scenarioId)
                .dimensionScores(Map.of())
                .overallScore(overallScore)
                .violations(List.of())
                .suggestions(List.of())
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .evalRunId(runId)
                .build();
    }
}

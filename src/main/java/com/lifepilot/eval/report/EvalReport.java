package com.lifepilot.eval.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.store.EvalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 评估报告生成器 — 聚合评估结果、检测退化、输出报告。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>聚合多个 {@link EvalResult} 生成 {@link ReportSummary}</li>
 *   <li>对比上次运行检测退化和新增退化场景</li>
 *   <li>控制台表格格式输出</li>
 *   <li>JSON 导出</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class EvalReport {

    private static final Logger log = LoggerFactory.getLogger(EvalReport.class);

    private final EvalStore evalStore;
    private final EvalConfigProperties config;
    private final ObjectMapper jsonMapper;

    public EvalReport(EvalStore evalStore, EvalConfigProperties config) {
        this.evalStore = evalStore;
        this.config = config;
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.registerModule(new JavaTimeModule());
        this.jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 生成汇总报告。
     *
     * <p>计算通过/失败数、平均分、各维度平均分，并对比上次运行检测退化。</p>
     *
     * @param results   当前运行的评估结果列表
     * @param evalRunId 当前评估运行 ID
     * @return 报告汇总
     */
    public ReportSummary generateSummary(List<EvalResult> results, String evalRunId) {
        int totalScenarios = results.size();
        double passThreshold = config.getDefaultPassThreshold();

        int passCount = (int) results.stream().filter(r -> r.passed(passThreshold)).count();
        int failCount = totalScenarios - passCount;

        // 计算平均综合评分
        double averageOverallScore = results.isEmpty() ? 0.0
                : results.stream().mapToDouble(EvalResult::overallScore).average().orElse(0.0);

        // 计算各维度平均评分
        Map<String, Double> dimensionAverages = computeDimensionAverages(results);

        // 收集当前失败的场景
        List<String> regressedScenarios = results.stream()
                .filter(r -> !r.passed(passThreshold))
                .map(EvalResult::scenarioId)
                .toList();

        // 检测新增退化场景（上次通过本次未通过）和退化标记
        List<String> newRegressions = new ArrayList<>();
        List<Double> previousScores = new ArrayList<>();

        for (EvalResult current : results) {
            List<EvalResult> previousResults = evalStore.findByScenarioId(current.scenarioId(), 1);
            // 过滤掉当前运行的结果，取上一次运行的结果
            Optional<EvalResult> previousResult = previousResults.stream()
                    .filter(r -> !r.evalRunId().equals(evalRunId))
                    .findFirst();

            if (previousResult.isPresent()) {
                EvalResult prev = previousResult.get();
                previousScores.add(prev.overallScore());
                // 上次通过、本次未通过 → 新增退化
                if (prev.passed(passThreshold) && !current.passed(passThreshold)) {
                    newRegressions.add(current.scenarioId());
                }
            }
        }

        // 退化检测：对比上次运行平均分
        boolean degraded = false;
        if (!previousScores.isEmpty()) {
            double previousAvg = previousScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            degraded = (previousAvg - averageOverallScore) > config.getDegradationThreshold();
        }

        ReportSummary summary = ReportSummary.builder()
                .evalRunId(evalRunId)
                .totalScenarios(totalScenarios)
                .passCount(passCount)
                .failCount(failCount)
                .averageOverallScore(averageOverallScore)
                .dimensionAverages(dimensionAverages)
                .degraded(degraded)
                .regressedScenarios(regressedScenarios)
                .newRegressions(newRegressions)
                .evaluatedAt(Instant.now())
                .build();

        log.info("评估报告已生成: runId={}, 总场景={}, 通过={}, 失败={}, 平均分={}, 退化={}",
                evalRunId, totalScenarios, passCount, failCount,
                String.format("%.3f", averageOverallScore), degraded);

        return summary;
    }

    /**
     * 输出报告到控制台（表格格式）。
     *
     * @param summary 报告汇总
     */
    public void printToConsole(ReportSummary summary) {
        String separator = "═".repeat(70);
        String thinSeparator = "─".repeat(70);

        System.out.println();
        System.out.println(separator);
        System.out.printf("  评估报告  |  运行 ID: %s%n", summary.evalRunId());
        System.out.println(separator);
        System.out.printf("  %-20s %s%n", "评估时间:", summary.evaluatedAt());
        System.out.printf("  %-20s %d%n", "总场景数:", summary.totalScenarios());
        System.out.printf("  %-20s %d%n", "通过:", summary.passCount());
        System.out.printf("  %-20s %d%n", "失败:", summary.failCount());
        System.out.printf("  %-20s %.3f%n", "平均综合评分:", summary.averageOverallScore());
        System.out.printf("  %-20s %s%n", "退化:", summary.degraded() ? "⚠ 是" : "✓ 否");
        System.out.println(thinSeparator);

        // 各维度平均评分
        if (!summary.dimensionAverages().isEmpty()) {
            System.out.println("  各维度平均评分:");
            summary.dimensionAverages().forEach((dim, score) ->
                    System.out.printf("    %-25s %.3f%n", dim + ":", score));
            System.out.println(thinSeparator);
        }

        // 退化场景
        if (!summary.regressedScenarios().isEmpty()) {
            System.out.println("  失败场景:");
            summary.regressedScenarios().forEach(s ->
                    System.out.printf("    ✗ %s%n", s));
            System.out.println(thinSeparator);
        }

        // 新增退化场景
        if (!summary.newRegressions().isEmpty()) {
            System.out.println("  ⚠ 新增退化场景（上次通过，本次失败）:");
            summary.newRegressions().forEach(s ->
                    System.out.printf("    ↓ %s%n", s));
            System.out.println(thinSeparator);
        }

        System.out.println(separator);
        System.out.println();
    }

    /**
     * 导出报告为 JSON 字符串。
     *
     * @param summary 报告汇总
     * @return JSON 字符串
     */
    public String exportJson(ReportSummary summary) {
        try {
            return jsonMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            log.error("导出报告 JSON 失败: runId={}", summary.evalRunId(), e);
            throw new RuntimeException("导出报告 JSON 失败", e);
        }
    }

    /**
     * 计算各维度平均评分。
     *
     * @param results 评估结果列表
     * @return 维度名 → 平均评分
     */
    private Map<String, Double> computeDimensionAverages(List<EvalResult> results) {
        if (results.isEmpty()) {
            return Map.of();
        }

        // 收集所有维度名
        Set<String> allDimensions = results.stream()
                .flatMap(r -> r.dimensionScores().keySet().stream())
                .collect(Collectors.toSet());

        Map<String, Double> averages = new LinkedHashMap<>();
        for (String dimension : allDimensions) {
            double avg = results.stream()
                    .filter(r -> r.dimensionScores().containsKey(dimension))
                    .mapToDouble(r -> r.dimensionScores().get(dimension))
                    .average()
                    .orElse(0.0);
            averages.put(dimension, avg);
        }
        return averages;
    }
}

package com.lifepilot.eval.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.ComparisonReport.ScenarioComparison;
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

    public EvalReport(EvalStore evalStore, EvalConfigProperties config, ObjectMapper objectMapper) {
        this.evalStore = evalStore;
        this.config = config;
        this.jsonMapper = objectMapper;
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

        // 优先与基线对比，否则回退到与前一次运行对比
        Optional<String> baselineRunId = evalStore.findLatestBaselineRunId();
        Map<String, Double> baselineScoreMap = new HashMap<>();
        if (baselineRunId.isPresent()) {
            List<EvalResult> baselineResults = evalStore.findByRunId(baselineRunId.get());
            for (EvalResult br : baselineResults) {
                baselineScoreMap.put(br.scenarioId(), br.overallScore());
            }
        }

        // 检测新增退化场景和渐进漂移
        List<String> newRegressions = new ArrayList<>();
        List<Double> comparisonScores = new ArrayList<>();
        List<String> driftingScenarios = new ArrayList<>();

        for (EvalResult current : results) {
            // 退化对比：优先使用基线，回退到前一次运行
            Double comparisonScore = baselineScoreMap.get(current.scenarioId());
            if (comparisonScore == null) {
                // 无基线，回退到前一次运行
                List<EvalResult> previousResults = evalStore.findByScenarioId(current.scenarioId(), 5);
                Optional<EvalResult> previousResult = previousResults.stream()
                        .filter(r -> !r.evalRunId().equals(evalRunId))
                        .findFirst();
                if (previousResult.isPresent()) {
                    comparisonScore = previousResult.get().overallScore();
                }
            }

            if (comparisonScore != null) {
                comparisonScores.add(comparisonScore);
                // 对比方通过、本次未通过 → 新增退化
                if (comparisonScore >= passThreshold && !current.passed(passThreshold)) {
                    newRegressions.add(current.scenarioId());
                }
            }

            // 渐进漂移检测：连续 3 次评分递减标记为 drifting（排除当前运行）
            List<Double> scoreHistory = evalStore.findScoreHistory(current.scenarioId(), evalRunId, 4);
            if (scoreHistory.size() >= 3) {
                boolean drifting = true;
                for (int i = 0; i < scoreHistory.size() - 1; i++) {
                    if (scoreHistory.get(i) >= scoreHistory.get(i + 1)) {
                        drifting = false;
                        break;
                    }
                }
                // scoreHistory 按时间降序，所以最新的在前
                // 连续递减意味着: history[0] < history[1] < history[2]
                if (drifting) {
                    driftingScenarios.add(current.scenarioId());
                    log.warn("检测到渐进漂移: scenarioId={}, 最近评分={}", current.scenarioId(), scoreHistory);
                }
            }
        }

        // 退化检测：对比平均分
        boolean degraded = false;
        if (!comparisonScores.isEmpty()) {
            double comparisonAvg = comparisonScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            degraded = (comparisonAvg - averageOverallScore) > config.getDegradationThreshold();
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
                .baselineRunId(baselineRunId.orElse(null))
                .driftingScenarios(driftingScenarios)
                .evaluatedAt(Instant.now())
                .build();

        log.info("评估报告已生成: runId={}, 总场景={}, 通过={}, 失败={}, 平均分={}, 退化={}, 基线={}, 漂移场景={}",
                evalRunId, totalScenarios, passCount, failCount,
                String.format("%.3f", averageOverallScore), degraded,
                baselineRunId.orElse("无"), driftingScenarios.size());

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

        // 渐进漂移场景
        if (!summary.driftingScenarios().isEmpty()) {
            System.out.println("  ⚠ 渐进漂移场景（连续 3 次评分下降）:");
            summary.driftingScenarios().forEach(s ->
                    System.out.printf("    ~ %s%n", s));
            System.out.println(thinSeparator);
        }

        // 基线信息
        if (summary.baselineRunId() != null) {
            System.out.printf("  %-20s %s%n", "对比基线:", summary.baselineRunId());
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
     * A/B 对比两次评估运行。
     *
     * <p>按 scenarioId 匹配两次运行的结果，计算每个场景的分差和状态。</p>
     *
     * @param currentRunId  当前运行 ID
     * @param baselineRunId 基线运行 ID
     * @return 对比报告
     */
    public ComparisonReport compareRuns(String currentRunId, String baselineRunId) {
        List<EvalResult> currentResults = evalStore.findByRunId(currentRunId);
        List<EvalResult> baselineResults = evalStore.findByRunId(baselineRunId);

        // 按 scenarioId 索引基线结果
        Map<String, EvalResult> baselineMap = baselineResults.stream()
                .collect(Collectors.toMap(EvalResult::scenarioId, r -> r, (a, b) -> a));

        double degradationThreshold = config.getDegradationThreshold();
        List<ScenarioComparison> comparisons = new ArrayList<>();

        for (EvalResult current : currentResults) {
            EvalResult baseline = baselineMap.get(current.scenarioId());
            if (baseline != null) {
                double delta = current.overallScore() - baseline.overallScore();
                String status;
                if (delta > degradationThreshold) {
                    status = "improved";
                } else if (delta < -degradationThreshold) {
                    status = "degraded";
                } else {
                    status = "unchanged";
                }
                comparisons.add(new ScenarioComparison(
                        current.scenarioId(), current.overallScore(),
                        baseline.overallScore(), delta, status));
            } else {
                // 基线中无此场景，标记为新增
                comparisons.add(new ScenarioComparison(
                        current.scenarioId(), current.overallScore(),
                        0.0, current.overallScore(), "new"));
            }
        }

        double currentAvg = currentResults.stream()
                .mapToDouble(EvalResult::overallScore).average().orElse(0.0);
        double baselineAvg = baselineResults.stream()
                .mapToDouble(EvalResult::overallScore).average().orElse(0.0);

        log.info("A/B 对比完成: current={}, baseline={}, currentAvg={}, baselineAvg={}, delta={}",
                currentRunId, baselineRunId,
                String.format("%.3f", currentAvg), String.format("%.3f", baselineAvg),
                String.format("%.3f", currentAvg - baselineAvg));

        return new ComparisonReport(currentRunId, baselineRunId,
                currentAvg, baselineAvg, currentAvg - baselineAvg,
                comparisons, null, null);
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

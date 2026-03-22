package com.lifepilot.eval.report;

import com.lifepilot.eval.model.RunMetadata;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * A/B 对比报告 — 对比两次评估运行的结果差异。
 *
 * @param currentRunId     当前运行 ID
 * @param baselineRunId    基线运行 ID
 * @param currentAvg       当前平均分
 * @param baselineAvg      基线平均分
 * @param delta            分差（当前 - 基线）
 * @param scenarios        场景级对比详情
 * @param currentMetadata  当前运行元数据
 * @param baselineMetadata 基线运行元数据
 * @author zsg
 * @since 2026-03-22
 */
public record ComparisonReport(
        String currentRunId,
        String baselineRunId,
        double currentAvg,
        double baselineAvg,
        double delta,
        List<ScenarioComparison> scenarios,
        @Nullable RunMetadata currentMetadata,
        @Nullable RunMetadata baselineMetadata
) {
    public ComparisonReport {
        scenarios = scenarios != null ? List.copyOf(scenarios) : List.of();
    }

    /**
     * 场景级对比。
     *
     * @param scenarioId    场景 ID
     * @param currentScore  当前评分
     * @param baselineScore 基线评分
     * @param delta         分差
     * @param status        状态：improved / degraded / unchanged
     */
    public record ScenarioComparison(
            String scenarioId,
            double currentScore,
            double baselineScore,
            double delta,
            String status
    ) {}
}

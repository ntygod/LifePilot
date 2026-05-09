package com.lifepilot.memory.eval.report;

import com.lifepilot.memory.eval.probe.EvalMetrics;

import java.util.List;

/**
 * 单个 benchmark 的评估报告段。
 *
 * @param benchmarkName  {@code locomo} / {@code longmemeval}
 * @param caseCount      参与评估的 case 数
 * @param metrics        该 benchmark 的汇总指标
 * @param perCaseDetails 每题的明细（供调试）
 * @author zsg
 * @since 2026-05-09
 */
public record BenchmarkReport(
        String benchmarkName,
        int caseCount,
        EvalMetrics metrics,
        List<CaseDetail> perCaseDetails
) {
    public BenchmarkReport {
        if (benchmarkName == null || benchmarkName.isBlank()) {
            throw new IllegalArgumentException("benchmarkName 不能为空");
        }
        if (metrics == null) metrics = EvalMetrics.empty();
        perCaseDetails = perCaseDetails == null ? List.of() : List.copyOf(perCaseDetails);
    }
}

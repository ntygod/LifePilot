package com.lifepilot.memory.eval.baseline;

import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 退化检测结果。
 *
 * @param status              PASS / FAIL / NO_BASELINE
 * @param diffs               所有对比的指标差值
 * @param baselineTimestamp   基线生成时间（NO_BASELINE 时为 null）
 * @author zsg
 * @since 2026-05-09
 */
public record RegressionResult(
        Status status,
        List<MetricDiff> diffs,
        @Nullable Instant baselineTimestamp
) {
    public RegressionResult {
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
    }

    public boolean isFail() {
        return status == Status.FAIL;
    }

    public boolean isPass() {
        return status == Status.PASS;
    }

    public boolean isNoBaseline() {
        return status == Status.NO_BASELINE;
    }

    public static RegressionResult pass(List<MetricDiff> diffs, Instant baselineTs) {
        return new RegressionResult(Status.PASS, diffs, baselineTs);
    }

    public static RegressionResult fail(List<MetricDiff> diffs, Instant baselineTs) {
        return new RegressionResult(Status.FAIL, diffs, baselineTs);
    }

    public static RegressionResult noBaseline() {
        return new RegressionResult(Status.NO_BASELINE, List.of(), null);
    }

    public enum Status {
        PASS, FAIL, NO_BASELINE
    }
}

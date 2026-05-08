package com.lifepilot.memory.eval.report;

import com.lifepilot.memory.eval.baseline.RegressionResult;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次评估跑批的完整报告。
 *
 * <p>作为 Markdown/JSON 输出和基线对比的输入/输出容器。</p>
 *
 * @param timestamp         跑批时间
 * @param gitSha            当前 git commit，不可用时为 null
 * @param mode              quick / full
 * @param benchmarks        各 benchmark 的子报告
 * @param regressionResult  基线对比结果（跑批后填充）
 * @author zsg
 * @since 2026-05-09
 */
public record EvalReport(
        Instant timestamp,
        @Nullable String gitSha,
        String mode,
        List<BenchmarkReport> benchmarks,
        @Nullable RegressionResult regressionResult
) {
    public EvalReport {
        if (timestamp == null) timestamp = Instant.now();
        if (mode == null || mode.isBlank()) mode = "quick";
        benchmarks = benchmarks == null ? List.of() : List.copyOf(benchmarks);
    }

    public EvalReport withRegression(RegressionResult regression) {
        return new EvalReport(timestamp, gitSha, mode,
                new ArrayList<>(benchmarks), regression);
    }
}

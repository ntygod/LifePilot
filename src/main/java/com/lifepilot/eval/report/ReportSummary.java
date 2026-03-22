package com.lifepilot.eval.report;

import com.lifepilot.eval.model.RunMetadata;
import lombok.Builder;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 报告汇总。
 *
 * @param evalRunId           评估运行 ID
 * @param totalScenarios      总场景数
 * @param passCount           通过数
 * @param failCount           失败数
 * @param averageOverallScore 平均综合评分
 * @param dimensionAverages   各维度平均评分
 * @param degraded            是否退化
 * @param regressedScenarios  退化场景列表
 * @param newRegressions      新增退化场景列表
 * @param evaluatedAt         评估时间
 * @param experienceInjected  是否注入了经验上下文
 * @param metadata            运行元数据
 * @author zsg
 * @since 2026-08-01
 */
@Builder(toBuilder = true)
public record ReportSummary(
        String evalRunId,
        int totalScenarios,
        int passCount,
        int failCount,
        double averageOverallScore,
        Map<String, Double> dimensionAverages,
        boolean degraded,
        List<String> regressedScenarios,
        List<String> newRegressions,
        Instant evaluatedAt,
        boolean experienceInjected,
        @Nullable RunMetadata metadata
) {
    public ReportSummary {
        dimensionAverages = dimensionAverages != null ? Map.copyOf(dimensionAverages) : Map.of();
        regressedScenarios = regressedScenarios != null ? List.copyOf(regressedScenarios) : List.of();
        newRegressions = newRegressions != null ? List.copyOf(newRegressions) : List.of();
    }
}

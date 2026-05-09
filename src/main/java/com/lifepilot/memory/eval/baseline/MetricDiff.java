package com.lifepilot.memory.eval.baseline;

/**
 * 单项指标的基线对比差值。
 *
 * @param metricName   指标名（如 {@code llm_score / p95_latency_ms / avg_tokens}）
 * @param baselineValue 基线值
 * @param currentValue  当前值
 * @param tolerance     允许变化（相对或绝对取决于指标，见 {@code RegressionDetector}）
 * @param violated     是否超出容差
 * @author zsg
 * @since 2026-05-09
 */
public record MetricDiff(
        String metricName,
        float baselineValue,
        float currentValue,
        float tolerance,
        boolean violated
) {}

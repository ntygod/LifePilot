package com.lifepilot.observability.trace;

/**
 * Token 消耗统计 — 时间范围内的 Token 使用汇总。
 *
 * @param traceCount        Trace 数量
 * @param totalTokens       总 Token 数
 * @param totalInputTokens  总输入 Token 数
 * @param totalOutputTokens 总输出 Token 数
 * @param avgTokensPerTrace 每个 Trace 平均 Token 数
 * @param maxTokens         单个 Trace 最大 Token 数
 * @param successCount      成功 Trace 数量
 * @param avgDurationMs     平均耗时（毫秒）
 * @author zsg
 * @since 2026-02-27
 */
public record TokenConsumptionStats(
        int traceCount,
        long totalTokens,
        long totalInputTokens,
        long totalOutputTokens,
        double avgTokensPerTrace,
        int maxTokens,
        int successCount,
        double avgDurationMs
) {
}

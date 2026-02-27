package com.lifepilot.observability.trace;

import jakarta.annotation.Nullable;

import java.time.Instant;

/**
 * 轨迹摘要 — 用于列表展示的精简信息。
 *
 * @param traceId           追踪 ID
 * @param sessionId         会话 ID
 * @param goal              用户目标
 * @param startTime         开始时间
 * @param endTime           结束时间（可为 null）
 * @param totalDurationMs   总耗时（毫秒）
 * @param totalSteps        总步骤数
 * @param totalTokens       总 Token 数
 * @param inputTokens       输入 Token 数
 * @param outputTokens      输出 Token 数
 * @param success           是否成功
 * @param terminationReason 终止原因（可为 null）
 * @param errorMessage      错误信息（可为 null）
 * @author zsg
 * @since 2026-02-27
 */
public record TraceSummary(
        String traceId,
        String sessionId,
        String goal,
        Instant startTime,
        @Nullable Instant endTime,
        long totalDurationMs,
        int totalSteps,
        int totalTokens,
        int inputTokens,
        int outputTokens,
        boolean success,
        @Nullable String terminationReason,
        @Nullable String errorMessage
) {
}

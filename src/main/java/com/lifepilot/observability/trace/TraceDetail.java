package com.lifepilot.observability.trace;

import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 轨迹详情 — 包含完整步骤列表的详细信息。
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
 * @param finalOutput       最终输出（可为 null）
 * @param errorMessage      错误信息（可为 null）
 * @param metadataJson      元数据 JSON（可为 null）
 * @param steps             步骤列表
 * @author zsg
 * @since 2026-02-27
 */
public record TraceDetail(
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
        @Nullable String finalOutput,
        @Nullable String errorMessage,
        @Nullable String metadataJson,
        List<TraceStep> steps
) {

    /**
     * 紧凑构造函数 — 使用 List.copyOf() 保证步骤列表不可变性。
     */
    public TraceDetail {
        steps = List.copyOf(steps);
    }
}

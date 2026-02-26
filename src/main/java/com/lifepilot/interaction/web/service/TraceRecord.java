package com.lifepilot.interaction.web.service;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 轨迹查询结果。
 *
 * @param id                轨迹 ID
 * @param sessionId         会话 ID
 * @param userMessage       用户消息
 * @param finalOutput       最终输出
 * @param success           是否成功
 * @param errorMessage      错误消息
 * @param terminationReason 终止原因
 * @param totalSteps        总步骤数
 * @param totalTokens       总 Token 消耗
 * @param durationMs        执行耗时（毫秒）
 * @param modelId           模型 ID
 * @param parentTraceId     父轨迹 ID
 * @param depth             嵌套深度
 * @param createdAt         创建时间
 * @author zsg
 * @since 2026-02-27
 */
public record TraceRecord(
        String id,
        String sessionId,
        String userMessage,
        @Nullable String finalOutput,
        boolean success,
        @Nullable String errorMessage,
        @Nullable String terminationReason,
        int totalSteps,
        int totalTokens,
        long durationMs,
        @Nullable String modelId,
        @Nullable String parentTraceId,
        int depth,
        Instant createdAt
) {
}

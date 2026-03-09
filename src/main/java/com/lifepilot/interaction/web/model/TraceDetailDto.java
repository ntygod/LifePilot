package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;

/**
 * 前端 Trace 详情 DTO（与 zhiwei-web 的 types/TraceDetail 对齐）。
 *
 * <p>注意：该 DTO 仅包含 Web UI 需要的字段，不暴露后端完整 TraceDetail 的所有字段。</p>
 */
public record TraceDetailDto(
        String id,
        String sessionId,
        String userMessage,
        boolean success,
        int totalSteps,
        int totalTokens,
        long durationMs,
        String createdAt,
        @Nullable String finalOutput,
        @Nullable String errorMessage,
        @Nullable String terminationReason,
        @Nullable String modelId
) {
}


package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;

/**
 * 前端 Trace 步骤 DTO（与 zhiwei-web 的 types/TraceStep 对齐）。
 *
 * @author zsg
 * @since 2026-03-05
 */
public record TraceStepDto(
        String id,
        int stepIndex,
        String phaseBefore,
        String phaseAfter,
        String actionType,
        @Nullable String actionJson,
        @Nullable String toolId,
        @Nullable String toolInputJson,
        @Nullable String toolOutput,
        boolean success,
        boolean blocked,
        @Nullable String blockReason,
        int tokensUsed,
        long latencyMs,
        String createdAt
) {
}


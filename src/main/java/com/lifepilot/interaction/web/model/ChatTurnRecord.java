package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 会话轮次聚合记录。
 *
 * @author zsg
 * @since 2026-03-25
 */
public record ChatTurnRecord(
        String turnId,
        String sessionId,
        ChatTurnAction lastAction,
        ChatTurnStatus status,
        String requestPayloadJson,
        @Nullable String userEntryId,
        @Nullable String assistantEntryId,
        @Nullable String latestTraceId,
        @Nullable String resumedFromTraceId,
        @Nullable String completionMode,
        @Nullable Integer lastErrorCode,
        @Nullable String lastErrorMessage,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt
) {}

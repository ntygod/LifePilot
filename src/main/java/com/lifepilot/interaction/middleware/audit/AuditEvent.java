package com.lifepilot.interaction.middleware.audit;

import java.time.Instant;

import com.lifepilot.interaction.model.TokenUsage;
import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 审计事件 record，记录单次请求的完整审计信息。
 *
 * @author zsg
 * @since 2026-02-25
 */
@Builder(toBuilder = true)
public record AuditEvent(
        String auditId,
        String messageId,
        @Nullable String sessionId,
        String channelType,
        String userId,
        @Nullable String requestContentHash,
        @Nullable String requestSummary,
        int responseStatusCode,
        @Nullable String responseSummary,
        @Nullable String routeType,
        long latencyMs,
        @Nullable TokenUsage tokenUsage,
        @Nullable String middlewareResultsJson,
        Instant createdAt
) {}

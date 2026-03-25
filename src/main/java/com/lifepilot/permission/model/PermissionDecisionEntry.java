package com.lifepilot.permission.model;

import com.lifepilot.observability.guardrail.RiskLevel;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 权限判定审计记录。
 *
 * @author zsg
 * @since 2026-03-25
 */
public record PermissionDecisionEntry(
        String id,
        @Nullable String sessionId,
        @Nullable String traceId,
        @Nullable String workspaceId,
        @Nullable String taskId,
        @Nullable String userId,
        String toolId,
        PermissionActionType actionType,
        RiskLevel riskLevel,
        String channel,
        ExecutionGrantScope resourceScope,
        PermissionDecisionType decisionType,
        @Nullable String matchedGrantId,
        @Nullable PermissionSubjectType matchedSubjectType,
        @Nullable String matchedSubjectId,
        @Nullable String reason,
        Instant createdAt
) {
}

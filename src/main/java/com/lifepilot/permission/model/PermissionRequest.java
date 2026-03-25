package com.lifepilot.permission.model;

import com.lifepilot.observability.guardrail.RiskLevel;
import org.springframework.lang.Nullable;

import java.util.Set;

/**
 * 单次工具调用对应的权限请求。
 *
 * @author zsg
 * @since 2026-03-25
 */
public record PermissionRequest(
        String toolId,
        PermissionActionType actionType,
        RiskLevel riskLevel,
        String channel,
        ExecutionGrantScope resourceScope,
        @Nullable String sessionId,
        @Nullable String workspaceId,
        @Nullable String taskId,
        @Nullable String userId,
        @Nullable String turnId,
        @Nullable String traceId
) {

    private static final Set<String> AUTONOMOUS_CHANNEL_PREFIXES = Set.of("cron", "heartbeat", "workflow");

    public PermissionRequest {
        channel = channel == null || channel.isBlank() ? "unknown" : channel;
        resourceScope = resourceScope != null ? resourceScope : ExecutionGrantScope.EMPTY;
    }

    public boolean isAutonomousChannel() {
        return AUTONOMOUS_CHANNEL_PREFIXES.stream().anyMatch(channel::startsWith);
    }

    @Nullable
    public String subjectId(PermissionSubjectType subjectType) {
        return switch (subjectType) {
            case SESSION -> sessionId;
            case WORKSPACE -> workspaceId;
            case TASK -> taskId != null && !taskId.isBlank()
                    ? taskId
                    : resourceScope.get("taskId") != null ? String.valueOf(resourceScope.get("taskId")) : null;
            case USER -> userId;
        };
    }
}

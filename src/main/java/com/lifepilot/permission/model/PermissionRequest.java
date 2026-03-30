package com.lifepilot.permission.model;

import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.observability.guardrail.RiskLevel;
import org.springframework.lang.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
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
        SourceKind sourceKind,
        String sourceId,
        @Nullable String channelPlatform,
        @Nullable String channelInstanceId,
        ExecutionGrantScope resourceScope,
        @Nullable String sessionId,
        @Nullable String workspaceId,
        @Nullable String taskId,
        @Nullable String userId,
        @Nullable String turnId,
        @Nullable String traceId,
        boolean autonomousTaskGrantRequired
) {

    private static final Set<String> AUTONOMOUS_CHANNEL_PREFIXES = Set.of("cron", "heartbeat", "workflow");

    public PermissionRequest {
        sourceKind = sourceKind != null ? sourceKind : SourceKind.SYSTEM;
        sourceId = sourceId == null || sourceId.isBlank() ? "unknown" : sourceId;
        channelPlatform = normalize(channelPlatform);
        channelInstanceId = normalize(channelInstanceId);
        channel = normalizePrimaryChannel(channel, sourceId, channelPlatform, channelInstanceId);
        resourceScope = resourceScope != null ? resourceScope : ExecutionGrantScope.EMPTY;
    }

    public PermissionRequest(
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
        this(toolId, actionType, riskLevel, channel, resourceScope, sessionId, workspaceId, taskId, userId, turnId, traceId,
                false);
    }

    public PermissionRequest(
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
            @Nullable String traceId,
            boolean autonomousTaskGrantRequired
    ) {
        this(
                toolId,
                actionType,
                riskLevel,
                channel,
                legacySource(channel, sessionId).sourceKind(),
                legacySource(channel, sessionId).sourceId(),
                legacySource(channel, sessionId).channelPlatform(),
                legacySource(channel, sessionId).channelInstanceId(),
                resourceScope,
                sessionId,
                workspaceId,
                taskId,
                userId,
                turnId,
                traceId,
                autonomousTaskGrantRequired
        );
    }

    public PermissionRequest(
            String toolId,
            PermissionActionType actionType,
            RiskLevel riskLevel,
            String channel,
            SourceKind sourceKind,
            String sourceId,
            @Nullable String channelPlatform,
            @Nullable String channelInstanceId,
            ExecutionGrantScope resourceScope,
            @Nullable String sessionId,
            @Nullable String workspaceId,
            @Nullable String taskId,
            @Nullable String userId,
            @Nullable String turnId,
            @Nullable String traceId
    ) {
        this(toolId, actionType, riskLevel, channel, sourceKind, sourceId, channelPlatform, channelInstanceId,
                resourceScope, sessionId, workspaceId,
                taskId, userId, turnId, traceId, false);
    }

    public boolean isAutonomousChannel() {
        return isAutonomousSource();
    }

    public boolean isAutonomousSource() {
        if (sourceKind == SourceKind.CRON
                || sourceKind == SourceKind.HEARTBEAT
                || sourceKind == SourceKind.WORKFLOW) {
            return true;
        }
        return AUTONOMOUS_CHANNEL_PREFIXES.stream().anyMatch(channel::startsWith);
    }

    public List<String> channelAliases() {
        var aliases = new LinkedHashSet<String>();
        aliases.add(channel);
        addAlias(aliases, channelPlatform);
        addAlias(aliases, channelInstanceId);
        addAlias(aliases, sourceId);
        return List.copyOf(aliases);
    }

    public boolean requiresAutonomousPreAuthorization() {
        return actionType == PermissionActionType.CREATE_SCHEDULE && autonomousTaskGrantRequired;
    }

    @Nullable
    public String subjectId(PermissionSubjectType subjectType) {
        return switch (subjectType) {
            case SESSION -> sessionId;
            case WORKSPACE -> workspaceId;
            case TASK -> taskId != null && !taskId.isBlank()
                    ? taskId
                    : resourceScope.firstValue("taskIds", "taskId");
            case USER -> userId;
        };
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizePrimaryChannel(@Nullable String channel,
                                                  String sourceId,
                                                  @Nullable String channelPlatform,
                                                  @Nullable String channelInstanceId) {
        String normalizedChannel = normalize(channel);
        if (normalizedChannel != null) {
            return normalizedChannel;
        }
        if (channelInstanceId != null) {
            return channelInstanceId;
        }
        if (channelPlatform != null) {
            return channelPlatform;
        }
        return sourceId;
    }

    private static void addAlias(LinkedHashSet<String> aliases, @Nullable String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            aliases.add(normalized);
        }
    }

    private static InteractionSource legacySource(String channel, @Nullable String sessionId) {
        return InteractionSource.legacy(channel, sessionId);
    }
}

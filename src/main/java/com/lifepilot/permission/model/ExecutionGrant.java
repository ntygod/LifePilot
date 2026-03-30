package com.lifepilot.permission.model;

import com.lifepilot.observability.guardrail.RiskLevel;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 已批准的执行授权。
 *
 * @author zsg
 * @since 2026-03-25
 */
public record ExecutionGrant(
        String id,
        PermissionSubjectType subjectType,
        String subjectId,
        PermissionActionType actionType,
        RiskLevel riskCeiling,
        ExecutionGrantScope scope,
        List<String> channels,
        boolean autonomousAllowed,
        @Nullable Instant expiresAt,
        @Nullable Instant revokedAt,
        @Nullable String revokedBy,
        @Nullable String revokedReason,
        @Nullable String createdBy,
        @Nullable String sourceEntryId,
        @Nullable String reason,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public ExecutionGrant {
        scope = scope != null ? scope : ExecutionGrantScope.EMPTY;
        channels = channels == null || channels.isEmpty() ? List.of() : List.copyOf(channels);
        metadata = metadata == null || metadata.isEmpty() ? Map.of() : Map.copyOf(metadata);
    }

    public boolean isActiveAt(Instant now) {
        if (revokedAt != null) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(now);
    }

    public boolean supportsRisk(RiskLevel requestedRiskLevel) {
        return riskCeiling.ordinal() >= requestedRiskLevel.ordinal();
    }

    public boolean matchesSubject(PermissionRequest request) {
        String currentSubjectId = request.subjectId(subjectType);
        return currentSubjectId != null && currentSubjectId.equals(subjectId);
    }

    public boolean supportsChannel(PermissionRequest request) {
        if (channels.isEmpty()) {
            return true;
        }
        for (String allowedChannel : channels) {
            if ("*".equals(allowedChannel)) {
                return true;
            }
            for (String requestChannel : request.channelAliases()) {
                if (requestChannel.equals(allowedChannel)
                        || requestChannel.startsWith(allowedChannel + ":")
                        || requestChannel.startsWith(allowedChannel + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean supportsAutonomous(PermissionRequest request) {
        return !request.isAutonomousChannel() || autonomousAllowed;
    }

    public int subjectSpecificity() {
        return switch (subjectType) {
            case TASK -> 4;
            case SESSION -> 3;
            case WORKSPACE -> 2;
            case USER -> 1;
        };
    }
}

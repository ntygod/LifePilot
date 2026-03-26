package com.lifepilot.permission.model;

import org.springframework.lang.Nullable;

/**
 * 权限判定结果。
 *
 * @author zsg
 * @since 2026-03-25
 */
public record PermissionDecision(
        PermissionDecisionType type,
        @Nullable ExecutionGrant matchedGrant,
        String reason
) {

    public static PermissionDecision passed(@Nullable ExecutionGrant grant, String reason) {
        return new PermissionDecision(PermissionDecisionType.PASSED, grant, reason);
    }

    public static PermissionDecision needsApproval(String reason) {
        return new PermissionDecision(PermissionDecisionType.NEEDS_APPROVAL, null, reason);
    }

    public static PermissionDecision blocked(String reason) {
        return new PermissionDecision(PermissionDecisionType.BLOCKED, null, reason);
    }

    public boolean isPassed() {
        return type == PermissionDecisionType.PASSED;
    }

    public boolean needsApproval() {
        return type == PermissionDecisionType.NEEDS_APPROVAL;
    }

    public boolean isBlocked() {
        return type == PermissionDecisionType.BLOCKED;
    }

    @Nullable
    public String matchedGrantId() {
        return matchedGrant != null ? matchedGrant.id() : null;
    }
}

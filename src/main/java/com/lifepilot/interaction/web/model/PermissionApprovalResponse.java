package com.lifepilot.interaction.web.model;

/**
 * 权限审批提交数据载体。
 *
 * @param requestId 请求 ID
 * @param approved 是否批准
 * @param subjectType 用户选择的授权主体范围
 * @param reason 拒绝或补充说明
 * @author zsg
 * @since 2026-03-25
 */
public record PermissionApprovalResponse(
        String requestId,
        boolean approved,
        String subjectType,
        String reason
) {
}

package com.lifepilot.interaction.web.model;

import java.util.List;
import java.util.Map;

/**
 * 权限审批请求载体。
 *
 * <p>通过 SSE 推送到前端，要求用户为当前高风险操作选择授权范围。</p>
 *
 * @param requestId 请求 ID
 * @param toolId 工具 ID
 * @param toolName 工具名称
 * @param actionType 操作类型
 * @param riskLevel 风险等级
 * @param message 审批提示文案
 * @param availableSubjectTypes 可选授权主体范围
 * @param recommendedSubjectType 推荐授权范围
 * @param resourceScope 资源作用域
 * @param streamId SSE 流标识
 * @param timestamp 请求时间
 * @author zsg
 * @since 2026-03-25
 */
public record PermissionApprovalRequest(
        String requestId,
        String toolId,
        String toolName,
        String actionType,
        String riskLevel,
        String message,
        List<String> availableSubjectTypes,
        String recommendedSubjectType,
        Map<String, Object> resourceScope,
        String streamId,
        String timestamp
) {
}

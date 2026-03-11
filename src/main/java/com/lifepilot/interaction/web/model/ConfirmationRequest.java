package com.lifepilot.interaction.web.model;

/**
 * 工具确认请求数据载体。
 *
 * <p>由 WebUserConfirmationService 构建，通过 SSE 推送到前端。
 * 前端根据此数据展示确认对话框。</p>
 *
 * @param requestId    确认请求唯一标识（UUID）
 * @param toolId       工具唯一标识
 * @param toolName     工具显示名称
 * @param riskLevel    风险等级名称（HIGH / CRITICAL）
 * @param approvalMode 审批模式名称（USER_CONFIRM / USER_CONFIRM_WITH_VERIFICATION）
 * @param message      确认消息（由 GuardrailEngine 生成）
 * @param timestamp    请求时间戳（ISO 8601 格式）
 * @author zsg
 * @since 2026-03-11
 */
public record ConfirmationRequest(
        String requestId,
        String toolId,
        String toolName,
        String riskLevel,
        String approvalMode,
        String message,
        String timestamp
) {}

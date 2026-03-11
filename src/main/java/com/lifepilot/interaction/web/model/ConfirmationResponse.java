package com.lifepilot.interaction.web.model;

/**
 * 工具确认响应数据载体。
 *
 * <p>由前端确认对话框提交，通过 REST 端点传递给 WebUserConfirmationService。</p>
 *
 * @param requestId 确认请求唯一标识（UUID，与 ConfirmationRequest.requestId 对应）
 * @param confirmed 用户是否确认执行
 * @param reason    拒绝原因（可选，confirmed 为 false 时可填写）
 * @author zsg
 * @since 2026-03-11
 */
public record ConfirmationResponse(
        String requestId,
        boolean confirmed,
        String reason
) {}

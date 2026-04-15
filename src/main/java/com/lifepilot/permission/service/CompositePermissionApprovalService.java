package com.lifepilot.permission.service;

import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.tool.ToolContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 路由权限审批服务 — 根据 approvalContext 中的渠道标识直接路由到对应实现。
 *
 * <ul>
 *   <li>有 {@code streamId} → Web（SSE 弹窗）</li>
 *   <li>有 {@code channelInstanceId} → Channel（飞书/钉钉/企微卡片）</li>
 *   <li>都没有 → 拒绝</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-15
 */
public class CompositePermissionApprovalService implements PermissionApprovalService {

    private static final Logger log = LoggerFactory.getLogger(CompositePermissionApprovalService.class);

    private final PermissionApprovalService webApproval;
    @Nullable
    private final PermissionApprovalService channelApproval;

    public CompositePermissionApprovalService(PermissionApprovalService webApproval,
                                              @Nullable PermissionApprovalService channelApproval) {
        this.webApproval = webApproval;
        this.channelApproval = channelApproval;
        log.info("权限审批路由初始化: web={}, channel={}",
                webApproval.getClass().getSimpleName(),
                channelApproval != null ? channelApproval.getClass().getSimpleName() : "无");
    }

    @Override
    @Nullable
    public ExecutionGrant requestApproval(ToolContract tool, PermissionRequest request,
                                          @Nullable Map<String, String> approvalContext) {
        if (approvalContext == null || approvalContext.isEmpty()) {
            log.info("审批上下文为空，无法发起审批: toolId={}", tool.id());
            return null;
        }

        // 有 streamId → Web 审批
        String streamId = approvalContext.get("streamId");
        if (streamId != null && !streamId.isBlank()) {
            return webApproval.requestApproval(tool, request, approvalContext);
        }

        // 有 channelInstanceId → 渠道审批
        String channelInstanceId = approvalContext.get("channelInstanceId");
        if (channelInstanceId != null && !channelInstanceId.isBlank() && channelApproval != null) {
            return channelApproval.requestApproval(tool, request, approvalContext);
        }

        log.info("无匹配的审批渠道: toolId={}, contextKeys={}", tool.id(), approvalContext.keySet());
        return null;
    }
}

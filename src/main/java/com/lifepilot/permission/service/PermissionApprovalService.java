package com.lifepilot.permission.service;

import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.tool.ToolContract;
import org.springframework.lang.Nullable;

/**
 * 权限审批服务。
 *
 * <p>用于交互式渠道在高风险操作缺少授权时，向用户申请新的执行授权。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public interface PermissionApprovalService {

    /**
     * 请求用户审批并在批准后创建新的执行授权。
     *
     * @param tool 目标工具
     * @param request 权限请求
     * @param streamId Web 会话的 SSE 流标识
     * @return 创建完成的授权；用户拒绝、超时或渠道不支持时返回 null
     */
    @Nullable
    ExecutionGrant requestApproval(ToolContract tool, PermissionRequest request, @Nullable String streamId);
}

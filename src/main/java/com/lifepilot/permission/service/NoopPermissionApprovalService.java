package com.lifepilot.permission.service;

import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.tool.ToolContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

/**
 * 默认权限审批服务。
 *
 * <p>当当前部署没有注册交互式审批实现时，所有需要审批的请求都会被拒绝。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public class NoopPermissionApprovalService implements PermissionApprovalService {

    private static final Logger log = LoggerFactory.getLogger(NoopPermissionApprovalService.class);

    @Override
    @Nullable
    public ExecutionGrant requestApproval(ToolContract tool, PermissionRequest request, @Nullable String streamId) {
        log.info("当前渠道未提供权限审批实现，拒绝执行: toolId={}, channel={}, actionType={}",
                tool.id(), request.channel(), request.actionType());
        return null;
    }
}

package com.lifepilot.tool.semantics;

import com.lifepilot.permission.model.ExecutionGrantScope;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 工具资源解析结果。
 *
 * <p>一份结果同时服务于权限审批与并行调度，避免两条链路重复推导资源。</p>
 *
 * @param scope 规范化后的权限作用域
 * @param normalizedResources 用于资源冲突判断的稳定资源值
 * @author zsg
 * @since 2026-03-26
 */
public record ToolScopeResolution(
        ExecutionGrantScope scope,
        List<String> normalizedResources
) {

    public static final ToolScopeResolution EMPTY = new ToolScopeResolution(ExecutionGrantScope.EMPTY, List.of());

    public ToolScopeResolution {
        scope = scope != null ? scope : ExecutionGrantScope.EMPTY;
        normalizedResources = normalizedResources == null || normalizedResources.isEmpty()
                ? List.of()
                : List.copyOf(normalizedResources);
    }

    @Nullable
    public String workspaceId() {
        List<String> workspacePaths = scope.stringValues("workspacePaths");
        if (workspacePaths.size() == 1) {
            return workspacePaths.getFirst();
        }
        return null;
    }
}

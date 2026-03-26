package com.lifepilot.tool.semantics;

import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.model.ToolSchedulingMode;

/**
 * 工具执行语义。
 *
 * <p>统一声明动作语义、资源解析和并行调度策略，是权限审批与调度系统的唯一真相源。</p>
 *
 * @param actionType 权限动作类型
 * @param schedulingMode 调度模式
 * @param scopeResolver 资源解析器
 * @author zsg
 * @since 2026-03-26
 */
public record ToolExecutionSemantics(
        PermissionActionType actionType,
        ToolSchedulingMode schedulingMode,
        ToolScopeResolver scopeResolver
) {

    public ToolExecutionSemantics {
        actionType = actionType != null ? actionType : PermissionActionType.GENERIC_TOOL_OPERATION;
        schedulingMode = schedulingMode != null ? schedulingMode : ToolSchedulingMode.SEQUENTIAL;
        scopeResolver = scopeResolver != null ? scopeResolver : ToolScopeResolvers.none();
        if (schedulingMode == ToolSchedulingMode.RESOURCE_SERIALIZED
                && scopeResolver == ToolScopeResolvers.none()) {
            throw new IllegalArgumentException("RESOURCE_SERIALIZED 工具必须声明资源解析器");
        }
    }

    public static ToolExecutionSemantics of(PermissionActionType actionType,
                                            ToolSchedulingMode schedulingMode,
                                            ToolScopeResolver scopeResolver) {
        return new ToolExecutionSemantics(actionType, schedulingMode, scopeResolver);
    }

    public static ToolExecutionSemantics of(PermissionActionType actionType, ToolScopeResolver scopeResolver) {
        return new ToolExecutionSemantics(actionType, ToolSchedulingMode.SEQUENTIAL, scopeResolver);
    }

    public static ToolExecutionSemantics generic() {
        return new ToolExecutionSemantics(
                PermissionActionType.GENERIC_TOOL_OPERATION,
                ToolSchedulingMode.SEQUENTIAL,
                ToolScopeResolvers.none()
        );
    }

    public static ToolExecutionSemantics generic(ToolSchedulingMode schedulingMode) {
        return new ToolExecutionSemantics(
                PermissionActionType.GENERIC_TOOL_OPERATION,
                schedulingMode,
                ToolScopeResolvers.none()
        );
    }
}

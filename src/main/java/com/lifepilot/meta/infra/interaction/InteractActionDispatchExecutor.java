package com.lifepilot.meta.infra.interaction;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

/**
 * 交互工具 action 路由执行器。
 *
 * <p>统一承接 choose / input / notify 三类交互动作。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class InteractActionDispatchExecutor extends ActionDispatchExecutor {

    public InteractActionDispatchExecutor(ChooseToolExecutor chooseExecutor,
                                          InputToolExecutor inputExecutor,
                                          NotifyToolExecutor notifyExecutor) {
        register("choose",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                chooseExecutor::execute);
        register("input",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                inputExecutor::execute);
        register("notify",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.none()
                ),
                notifyExecutor::execute);
    }
}

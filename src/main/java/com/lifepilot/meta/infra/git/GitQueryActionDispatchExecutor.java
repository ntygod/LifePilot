package com.lifepilot.meta.infra.git;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

/**
 * Git 查询 action 路由执行器。
 *
 * <p>统一承接 status / diff / log / blame 四类只读 Git 操作。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitQueryActionDispatchExecutor extends ActionDispatchExecutor {

    public GitQueryActionDispatchExecutor(GitStatusToolExecutor statusExecutor,
                                          GitDiffToolExecutor diffExecutor,
                                          GitLogToolExecutor logExecutor,
                                          GitBlameToolExecutor blameExecutor) {
        register("status",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ),
                statusExecutor::execute);
        register("diff",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ),
                diffExecutor::execute);
        register("log",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ),
                logExecutor::execute);
        register("blame",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ),
                blameExecutor::execute);
    }
}

package com.lifepilot.meta.infra.git;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

/**
 * Git 变更 action 路由执行器。
 *
 * <p>统一承接 commit / stash / branch 三类写操作 Git 能力。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitMutateActionDispatchExecutor extends ActionDispatchExecutor {

    public GitMutateActionDispatchExecutor(GitCommitToolExecutor commitExecutor,
                                           GitStashToolExecutor stashExecutor,
                                           GitBranchToolExecutor branchExecutor) {
        register("commit",
                RiskLevel.HIGH,
                ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees("path")
                ),
                commitExecutor::execute);
        register("stash",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees("path")
                ),
                stashExecutor::execute);
        register("branch",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees("path")
                ),
                branchExecutor::execute);
    }
}

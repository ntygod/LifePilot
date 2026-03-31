package com.lifepilot.meta.infra.file;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

/**
 * 文件管理 action 路由执行器。
 *
 * <p>统一承接 move / copy / delete 三类文件管理动作。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class FileManageActionDispatchExecutor extends ActionDispatchExecutor {

    public FileManageActionDispatchExecutor(FileMoveToolExecutor moveExecutor,
                                            FileCopyToolExecutor copyExecutor,
                                            FileDeleteToolExecutor deleteExecutor) {
        register("move",
                RiskLevel.HIGH,
                ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("source", "destination")
                ),
                moveExecutor::execute);
        register("copy",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("source", "destination")
                ),
                copyExecutor::execute);
        register("delete",
                RiskLevel.HIGH,
                ToolExecutionSemantics.of(
                        PermissionActionType.DELETE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ),
                deleteExecutor::execute);
    }
}

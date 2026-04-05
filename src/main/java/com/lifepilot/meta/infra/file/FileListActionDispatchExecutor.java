package com.lifepilot.meta.infra.file;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

/**
 * 文件列表 action 路由执行器。
 *
 * <p>统一承接 list / search / info 三类文件查询动作。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class FileListActionDispatchExecutor extends ActionDispatchExecutor {

    public FileListActionDispatchExecutor(FileListToolExecutor listExecutor,
                                          FileSearchToolExecutor searchExecutor,
                                          FileInfoToolExecutor infoExecutor) {
        register("list",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ),
                listExecutor::execute);
        register("search",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ),
                searchExecutor::execute);
        register("info",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ),
                infoExecutor::execute);
    }
}

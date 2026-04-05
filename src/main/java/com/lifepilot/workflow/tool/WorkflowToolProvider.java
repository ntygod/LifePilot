package com.lifepilot.workflow.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import com.lifepilot.workflow.engine.WorkflowCommandService;
import com.lifepilot.workflow.registry.WorkflowRegistry;

import java.util.List;
import java.util.Map;

/**
 * 工作流管理工具提供者。
 *
 * <p>集中管理统一的 {@code workflow} 元能力工具，通过 action 参数路由到
 * list / start / status / cancel / resume 五类具体工作流操作。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class WorkflowToolProvider {

    private final WorkflowRegistry registry;
    private final WorkflowCommandService commandService;

    public WorkflowToolProvider(WorkflowRegistry registry, WorkflowCommandService commandService) {
        this.registry = registry;
        this.commandService = commandService;
    }

    /**
     * 构建工作流工具列表（1 个）。
     *
     * @return 工具列表
     */
    public List<BuiltinTool> buildWorkflowTools() {
        var executor = new WorkflowActionDispatchExecutor(registry, commandService);
        return List.of(buildWorkflowTool(executor));
    }

    /** 构建统一工作流工具。 */
    private BuiltinTool buildWorkflowTool(WorkflowActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("workflow")
                .name("工作流管理")
                .description("管理工作流。通过 action 参数支持五类操作：" +
                        "list=列出可用工作流，start=启动工作流实例，" +
                        "status=查询实例状态，cancel=取消实例，resume=恢复暂停实例。")
                .category(ToolCategory.ACTION)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("list", "start", "status", "cancel", "resume"),
                                        "description", "工作流操作类型")),
                                Map.entry("workflowId", Map.of(
                                        "type", "string",
                                        "description", "工作流定义 ID；action=start 时必填")),
                                Map.entry("instanceId", Map.of(
                                        "type", "string",
                                        "description", "工作流实例 ID；action=status/cancel/resume 时必填")),
                                Map.entry("inputs", Map.of(
                                        "type", "object",
                                        "description", "工作流输入参数；action=start 时可选"))
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("workflowIds", false, "workflowId", "instanceId")
                ))
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }
}

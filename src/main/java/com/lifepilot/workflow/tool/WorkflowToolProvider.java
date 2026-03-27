package com.lifepilot.workflow.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import com.lifepilot.workflow.engine.WorkflowCommandService;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowInstance;
import com.lifepilot.workflow.model.WorkflowTrigger;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流管理工具提供者 — 构建 4 个工作流管理 BuiltinTool。
 *
 * <p>遵循 BrowserToolProvider 的委托构建模式，
 * 由 {@link com.lifepilot.meta.infra.InfraToolProvider} 在 registerTools() 中调用。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class WorkflowToolProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkflowToolProvider.class);

    private final WorkflowRegistry registry;
    private final WorkflowCommandService commandService;

    public WorkflowToolProvider(WorkflowRegistry registry, WorkflowCommandService commandService) {
        this.registry = registry;
        this.commandService = commandService;
    }

    /**
     * 构建 4 个工作流管理工具。
     *
     * @return 工具列表
     */
    public List<BuiltinTool> buildWorkflowTools() {
        return List.of(
                buildListTool(),
                buildStartTool(),
                buildStatusTool(),
                buildCancelTool(),
                buildResumeTool()
        );
    }

    /** 构建列出可用工作流工具。 */
    private BuiltinTool buildListTool() {
        return BuiltinTool.builder()
                .id("workflow.list")
                .name("列出可用工作流")
                .description("列出所有可用工作流。如需创建新工作流，请使用 workflow-creator skill")
                .category(ToolCategory.PERCEPTION)
                .inputSchema(JsonSchema.of(Map.of("type", "object", "properties", Map.of())))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .executor(input -> {
                    try {
                        List<WorkflowDefinition> enabled = registry.listEnabled();
                        List<Map<String, Object>> items = enabled.stream()
                                .map(this::definitionToSummary)
                                .toList();
                        return ToolResult.success(Map.of("workflows", items, "count", items.size()));
                    } catch (Exception e) {
                        log.error("列出工作流失败: {}", e.getMessage(), e);
                        return ToolResult.error("列出工作流失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建启动工作流工具 — 异步返回 instanceId，不阻塞等待完成。 */
    private BuiltinTool buildStartTool() {
        return BuiltinTool.builder()
                .id("workflow.start")
                .name("启动工作流")
                .description("启动工作流实例，适用于多步骤自动化任务。简单提醒或单条待办请使用 todo")
                .category(ToolCategory.ACTION)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("workflowId"),
                        "properties", Map.of(
                                "workflowId", Map.of("type", "string", "description", "工作流定义 ID"),
                                "inputs", Map.of("type", "object", "description", "工作流输入参数（可选）")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("workflowIds", false, "workflowId")
                ))
                .executor(input -> {
                    try {
                        String workflowId = input.getParam("workflowId", String.class);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> inputs = input.getOptionalParam("inputs", Map.class).orElse(null);
                        String instanceId = commandService.start(workflowId, inputs);
                        return ToolResult.success(Map.of("instanceId", instanceId, "status", "CREATED"));
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error(e.getMessage());
                    } catch (IllegalStateException e) {
                        return ToolResult.error(e.getMessage());
                    } catch (Exception e) {
                        log.error("启动工作流失败: {}", e.getMessage(), e);
                        return ToolResult.error("启动工作流失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询工作流实例状态工具。 */
    private BuiltinTool buildStatusTool() {
        return BuiltinTool.builder()
                .id("workflow.status")
                .name("查询工作流状态")
                .description("查询工作流实例执行状态")
                .category(ToolCategory.PERCEPTION)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("instanceId"),
                        "properties", Map.of(
                                "instanceId", Map.of("type", "string", "description", "工作流实例 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.exactValues("workflowInstanceIds", "instanceId")
                ))
                .executor(input -> {
                    try {
                        String instanceId = input.getParam("instanceId", String.class);
                        return commandService.getStatus(instanceId)
                                .map(this::instanceToStatusMap)
                                .map(ToolResult::success)
                                .orElse(ToolResult.error("工作流实例不存在: id=" + instanceId));
                    } catch (Exception e) {
                        log.error("查询工作流状态失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询工作流状态失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建取消工作流实例工具。 */
    private BuiltinTool buildCancelTool() {
        return BuiltinTool.builder()
                .id("workflow.cancel")
                .name("取消工作流")
                .description("取消正在执行的工作流实例")
                .category(ToolCategory.ACTION)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("instanceId"),
                        "properties", Map.of(
                                "instanceId", Map.of("type", "string", "description", "工作流实例 ID")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("workflowInstanceIds", "instanceId")
                ))
                .executor(input -> {
                    try {
                        String instanceId = input.getParam("instanceId", String.class);
                        commandService.cancel(instanceId);
                        return ToolResult.success(Map.of("instanceId", instanceId, "status", "CANCELLED"));
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        return ToolResult.error(e.getMessage());
                    } catch (Exception e) {
                        log.error("取消工作流失败: {}", e.getMessage(), e);
                        return ToolResult.error("取消工作流失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建恢复暂停工作流实例工具。 */
    private BuiltinTool buildResumeTool() {
        return BuiltinTool.builder()
                .id("workflow.resume")
                .name("恢复工作流")
                .description("恢复暂停中的工作流实例（如等待审批、等待外部数据的工作流）")
                .category(ToolCategory.ACTION)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("instanceId"),
                        "properties", Map.of(
                                "instanceId", Map.of("type", "string", "description", "工作流实例 ID")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("workflowInstanceIds", "instanceId")
                ))
                .executor(input -> {
                    try {
                        String instanceId = input.getParam("instanceId", String.class);
                        commandService.resume(instanceId);
                        return ToolResult.success(Map.of("instanceId", instanceId, "status", "RESUMED"));
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        return ToolResult.error(e.getMessage());
                    } catch (Exception e) {
                        log.error("恢复工作流失败: {}", e.getMessage(), e);
                        return ToolResult.error("恢复工作流失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // ---- 辅助方法 ----

    /** 将工作流定义映射为摘要 Map。 */
    private Map<String, Object> definitionToSummary(WorkflowDefinition def) {
        var map = new HashMap<String, Object>();
        map.put("id", def.id());
        map.put("name", def.name());
        if (def.description() != null) map.put("description", def.description());
        map.put("triggers", def.triggers().stream().map(this::triggerToString).toList());
        if (!def.inputs().isEmpty()) {
            map.put("inputs", def.inputs().entrySet().stream()
                    .map(e -> {
                        var m = new LinkedHashMap<String, Object>();
                        m.put("name", e.getKey());
                        m.put("type", e.getValue().type());
                        m.put("required", e.getValue().required());
                        if (e.getValue().description() != null) m.put("description", e.getValue().description());
                        return m;
                    })
                    .toList());
        }
        return Map.copyOf(map);
    }

    /** 将触发器转换为可读字符串。 */
    private String triggerToString(WorkflowTrigger trigger) {
        return switch (trigger) {
            case WorkflowTrigger.CronTrigger ct -> "cron:" + ct.cron();
            case WorkflowTrigger.EventTrigger et -> "event:" + et.eventType();
            case WorkflowTrigger.ManualTrigger _ -> "manual";
            case WorkflowTrigger.WebhookTrigger _ -> "webhook";
        };
    }

    /** 将工作流实例映射为状态 Map。 */
    private Map<String, Object> instanceToStatusMap(WorkflowInstance instance) {
        var map = new HashMap<String, Object>();
        map.put("instanceId", instance.id());
        map.put("workflowId", instance.workflowId());
        map.put("state", instance.state().name());
        map.put("completedSteps", instance.completedStepIds().size());
        if (instance.failureReason() != null) map.put("failureReason", instance.failureReason());
        var startedAt = instance.startedAt();
        if (startedAt != null) map.put("startedAt", startedAt.toString());
        var completedAt = instance.completedAt();
        if (completedAt != null) map.put("completedAt", completedAt.toString());
        return Map.copyOf(map);
    }
}

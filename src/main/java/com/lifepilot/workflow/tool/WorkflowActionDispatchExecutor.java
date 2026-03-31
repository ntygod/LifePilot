package com.lifepilot.workflow.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
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
 * 工作流工具 action 路由执行器。
 *
 * <p>统一承接 list / start / status / cancel / resume 五类工作流操作。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class WorkflowActionDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowActionDispatchExecutor.class);

    private final WorkflowRegistry registry;
    private final WorkflowCommandService commandService;

    public WorkflowActionDispatchExecutor(WorkflowRegistry registry, WorkflowCommandService commandService) {
        this.registry = registry;
        this.commandService = commandService;

        register("list",
                RiskLevel.LOW,
                ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE),
                input -> {
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
                });

        register("start",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("workflowIds", false, "workflowId")
                ),
                input -> {
                    try {
                        String workflowId = input.getParam("workflowId", String.class);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> inputs = input.getOptionalParam("inputs", Map.class).orElse(null);
                        String instanceId = commandService.start(workflowId, inputs);
                        return ToolResult.success(Map.of("instanceId", instanceId, "status", "CREATED"));
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        return ToolResult.error(e.getMessage());
                    } catch (Exception e) {
                        log.error("启动工作流失败: {}", e.getMessage(), e);
                        return ToolResult.error("启动工作流失败: " + e.getMessage());
                    }
                });

        register("status",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.exactValues("workflowInstanceIds", "instanceId")
                ),
                input -> {
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
                });

        register("cancel",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("workflowInstanceIds", "instanceId")
                ),
                input -> {
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
                });

        register("resume",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("workflowInstanceIds", "instanceId")
                ),
                input -> {
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
                });
    }

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

    private String triggerToString(WorkflowTrigger trigger) {
        return switch (trigger) {
            case WorkflowTrigger.CronTrigger ct -> "cron:" + ct.cron();
            case WorkflowTrigger.EventTrigger et -> "event:" + et.eventType();
            case WorkflowTrigger.ManualTrigger _ -> "manual";
            case WorkflowTrigger.WebhookTrigger _ -> "webhook";
        };
    }

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

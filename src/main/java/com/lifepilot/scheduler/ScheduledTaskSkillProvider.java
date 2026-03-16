package com.lifepilot.scheduler;

import com.lifepilot.notification.Urgency;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.scheduler.model.ScheduledTask;
import com.lifepilot.scheduler.model.TaskAction;
import com.lifepilot.scheduler.model.TriggerType;
import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务管理内置 Skill 提供者。
 *
 * <p>注册 5 个定时任务 CRUD 工具到 DynamicToolRegistry，
 * 提供定时任务管理 Skill 定义蓝图。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
@BuiltinSkill(id = "scheduled-task", order = 25)
public class ScheduledTaskSkillProvider implements BuiltinSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskSkillProvider.class);

    private final ScheduledTaskService scheduledTaskService;
    private final PromptRegistry promptRegistry;

    public ScheduledTaskSkillProvider(ScheduledTaskService scheduledTaskService,
                                      PromptRegistry promptRegistry) {
        this.scheduledTaskService = scheduledTaskService;
        this.promptRegistry = promptRegistry;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("scheduled-task")
                .name("定时任务管理")
                .description("管理定时任务，支持创建、查询、取消、修改一次性或周期性定时任务")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions("定时任务管理 Skill，支持创建、查询、取消、修改定时任务。")
                .suggestedTools(List.of(
                        "builtin.scheduled-task.create",
                        "builtin.scheduled-task.list",
                        "builtin.scheduled-task.get",
                        "builtin.scheduled-task.cancel",
                        "builtin.scheduled-task.update"
                ))
                .metadata(Map.of())
                .build();
    }

    @Override
    public void registerTools(DynamicToolRegistry toolRegistry) {
        toolRegistry.registerBuiltinTool(buildCreateTool());
        toolRegistry.registerBuiltinTool(buildListTool());
        toolRegistry.registerBuiltinTool(buildGetTool());
        toolRegistry.registerBuiltinTool(buildCancelTool());
        toolRegistry.registerBuiltinTool(buildUpdateTool());
        log.info("定时任务 Skill 工具注册完成: count=5");
    }

    // ---- 工具构建方法 ----

    /** 构建创建定时任务工具。 */
    @SuppressWarnings("unchecked")
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("builtin.scheduled-task.create")
                .name("创建定时任务")
                .description("创建一次性或周期性定时任务，支持发送通知、调用 Agent、执行工具三种动作类型")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("name", "triggerType", "actionType", "actionParams"),
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "任务名称"),
                                "triggerType", Map.of("type", "string", "enum", List.of("ONCE", "CRON"),
                                        "description", "触发类型：ONCE 一次性 / CRON 周期性"),
                                "triggerAt", Map.of("type", "string", "format", "date-time",
                                        "description", "一次性任务触发时间 ISO 8601（triggerType=ONCE 时必填）"),
                                "cronExpr", Map.of("type", "string",
                                        "description", "周期性任务 cron 表达式（triggerType=CRON 时必填）"),
                                "actionType", Map.of("type", "string",
                                        "enum", List.of("SendNotification", "InvokeAgent", "ExecuteTool"),
                                        "description", "动作类型"),
                                "actionParams", Map.of("type", "object",
                                        "description", "动作参数")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String name = input.getParam("name", String.class);
                        String triggerTypeStr = input.getParam("triggerType", String.class);
                        String triggerAt = input.getOptionalParam("triggerAt", String.class).orElse(null);
                        String cronExpr = input.getOptionalParam("cronExpr", String.class).orElse(null);
                        String actionType = input.getParam("actionType", String.class);
                        var actionParams = (Map<String, Object>) input.getParam("actionParams", Map.class);

                        TriggerType triggerType = TriggerType.valueOf(triggerTypeStr);
                        TaskAction action = parseTaskAction(actionType, actionParams);
                        String id = scheduledTaskService.create(name, triggerType, triggerAt, cronExpr, action, null);
                        return ToolResult.success(Map.of("id", id));
                    } catch (Exception e) {
                        log.error("创建定时任务失败: {}", e.getMessage(), e);
                        return ToolResult.error("创建定时任务失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询定时任务列表工具。 */
    private BuiltinTool buildListTool() {
        return BuiltinTool.builder()
                .id("builtin.scheduled-task.list")
                .name("查询定时任务列表")
                .description("查询所有活跃的定时任务列表")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of()
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        List<ScheduledTask> tasks = scheduledTaskService.listActive();
                        List<Map<String, Object>> items = tasks.stream()
                                .map(this::taskToMap)
                                .toList();
                        return ToolResult.success(Map.of("items", items));
                    } catch (Exception e) {
                        log.error("查询定时任务列表失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询定时任务列表失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询定时任务详情工具。 */
    private BuiltinTool buildGetTool() {
        return BuiltinTool.builder()
                .id("builtin.scheduled-task.get")
                .name("查询定时任务详情")
                .description("根据 ID 查询单个定时任务的详细信息")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "定时任务 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        return scheduledTaskService.findById(id)
                                .map(task -> ToolResult.success(taskToMap(task)))
                                .orElse(ToolResult.error("定时任务不存在: id=" + id));
                    } catch (Exception e) {
                        log.error("查询定时任务详情失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询定时任务详情失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建取消定时任务工具。 */
    private BuiltinTool buildCancelTool() {
        return BuiltinTool.builder()
                .id("builtin.scheduled-task.cancel")
                .name("取消定时任务")
                .description("根据 ID 取消指定的定时任务")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "定时任务 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        scheduledTaskService.cancel(id);
                        return ToolResult.success(Map.of("cancelled", true, "id", id));
                    } catch (Exception e) {
                        log.error("取消定时任务失败: {}", e.getMessage(), e);
                        return ToolResult.error("取消定时任务失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建修改定时任务工具。 */
    @SuppressWarnings("unchecked")
    private BuiltinTool buildUpdateTool() {
        return BuiltinTool.builder()
                .id("builtin.scheduled-task.update")
                .name("修改定时任务")
                .description("修改定时任务的触发时间、cron 表达式、动作类型或名称")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "定时任务 ID"),
                                "name", Map.of("type", "string", "description", "新任务名称"),
                                "triggerAt", Map.of("type", "string", "format", "date-time",
                                        "description", "新触发时间 ISO 8601"),
                                "cronExpr", Map.of("type", "string", "description", "新 cron 表达式"),
                                "actionType", Map.of("type", "string",
                                        "enum", List.of("SendNotification", "InvokeAgent", "ExecuteTool"),
                                        "description", "新动作类型"),
                                "actionParams", Map.of("type", "object", "description", "新动作参数")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        String triggerAt = input.getOptionalParam("triggerAt", String.class).orElse(null);
                        String cronExpr = input.getOptionalParam("cronExpr", String.class).orElse(null);
                        String name = input.getOptionalParam("name", String.class).orElse(null);
                        String actionType = input.getOptionalParam("actionType", String.class).orElse(null);

                        // 仅在 actionType 提供时解析动作
                        TaskAction action = null;
                        if (actionType != null) {
                            var actionParams = (Map<String, Object>) input.getParam("actionParams", Map.class);
                            action = parseTaskAction(actionType, actionParams);
                        }

                        scheduledTaskService.update(id, triggerAt, cronExpr, action, name);
                        return ToolResult.success(Map.of("updated", true, "id", id));
                    } catch (Exception e) {
                        log.error("修改定时任务失败: {}", e.getMessage(), e);
                        return ToolResult.error("修改定时任务失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // ---- 辅助方法 ----

    /**
     * 将 ScheduledTask 转换为 Map 用于 ToolResult 输出。
     *
     * @param task 定时任务实体
     * @return 包含任务字段的 Map
     */
    private Map<String, Object> taskToMap(ScheduledTask task) {
        var map = new HashMap<String, Object>();
        map.put("id", task.id());
        map.put("name", task.name());
        map.put("triggerType", task.triggerType().name());
        map.put("status", task.status().name());
        map.put("createdAt", task.createdAt());
        map.put("updatedAt", task.updatedAt());
        if (task.triggerAt() != null) map.put("triggerAt", task.triggerAt());
        if (task.cronExpr() != null) map.put("cronExpr", task.cronExpr());
        if (task.nextTriggerAt() != null) map.put("nextTriggerAt", task.nextTriggerAt());
        if (task.lastTriggeredAt() != null) map.put("lastTriggeredAt", task.lastTriggeredAt());
        if (task.errorMessage() != null) map.put("errorMessage", task.errorMessage());
        if (task.actionJson() != null) map.put("actionJson", task.actionJson());
        if (task.metadataJson() != null) map.put("metadataJson", task.metadataJson());
        return Map.copyOf(map);
    }

    /**
     * 根据动作类型字符串和参数 Map 构造 TaskAction 实例。
     *
     * @param actionType   动作类型（SendNotification / InvokeAgent / ExecuteTool）
     * @param actionParams 动作参数 Map
     * @return 对应的 TaskAction 子类型实例
     * @throws IllegalArgumentException 当动作类型未知或参数缺失时
     */
    @SuppressWarnings("unchecked")
    private TaskAction parseTaskAction(String actionType, Map<String, Object> actionParams) {
        return switch (actionType) {
            case "SendNotification" -> {
                String content = (String) actionParams.get("content");
                String urgencyStr = (String) actionParams.getOrDefault("urgency", "MEDIUM");
                yield new TaskAction.SendNotification(content, Urgency.valueOf(urgencyStr));
            }
            case "InvokeAgent" -> {
                String message = (String) actionParams.get("message");
                yield new TaskAction.InvokeAgent(message);
            }
            case "ExecuteTool" -> {
                String toolId = (String) actionParams.get("toolId");
                var params = actionParams.containsKey("params")
                        ? (Map<String, Object>) actionParams.get("params")
                        : Map.<String, Object>of();
                yield new TaskAction.ExecuteTool(toolId, params);
            }
            default -> throw new IllegalArgumentException("未知的动作类型: " + actionType);
        };
    }
}

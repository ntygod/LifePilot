package com.lifepilot.skill.builtin.todo;

import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.model.*;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 待办管理内置 Skill 提供者。
 *
 * <p>注册 6 个待办 CRUD 工具到 DynamicToolRegistry，
 * 提供待办管理 Skill 定义蓝图。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@BuiltinSkill(id = "todo", order = 10)
public class TodoSkillProvider implements BuiltinSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(TodoSkillProvider.class);

    private final TodoRepository todoRepository;
    private final PromptRegistry promptRegistry;

    public TodoSkillProvider(TodoRepository todoRepository, PromptRegistry promptRegistry) {
        this.todoRepository = todoRepository;
        this.promptRegistry = promptRegistry;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("todo")
                .name("待办管理")
                .description("管理待办事项，支持创建、查询、更新、删除和完成操作")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .systemPrompt(promptRegistry.render("skill/todo"))
                .allowedTools(List.of(
                        "builtin.todo.create",
                        "builtin.todo.list",
                        "builtin.todo.get",
                        "builtin.todo.update",
                        "builtin.todo.delete",
                        "builtin.todo.complete"
                ))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.LIGHTWEIGHT)
                .metadata(Map.of())
                .build();
    }

    @Override
    public void registerTools(DynamicToolRegistry toolRegistry) {
        toolRegistry.registerBuiltinTool(buildCreateTool());
        toolRegistry.registerBuiltinTool(buildListTool());
        toolRegistry.registerBuiltinTool(buildGetTool());
        toolRegistry.registerBuiltinTool(buildUpdateTool());
        toolRegistry.registerBuiltinTool(buildDeleteTool());
        toolRegistry.registerBuiltinTool(buildCompleteTool());
        log.info("待办 Skill 工具注册完成: count=6");
    }

    // ---- 工具构建方法 ----

    /** 构建创建待办工具。 */
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("builtin.todo.create")
                .name("创建待办")
                .description("创建新的待办事项，支持设置标题、描述、优先级和截止日期")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("title"),
                        "properties", Map.of(
                                "title", Map.of("type", "string", "description", "待办标题"),
                                "description", Map.of("type", "string", "description", "待办描述"),
                                "priority", Map.of("type", "string", "description", "优先级: HIGH/MEDIUM/LOW"),
                                "dueDate", Map.of("type", "string", "description", "截止日期 ISO 8601"),
                                "tags", Map.of("type", "string", "description", "标签，逗号分隔")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String title = input.getParam("title", String.class);
                        String description = input.getOptionalParam("description", String.class).orElse(null);
                        String priorityStr = input.getOptionalParam("priority", String.class).orElse("MEDIUM");
                        String dueDate = input.getOptionalParam("dueDate", String.class).orElse(null);
                        String tagsStr = input.getOptionalParam("tags", String.class).orElse(null);

                        TodoItem.Priority priority = TodoItem.Priority.valueOf(priorityStr.toUpperCase());
                        List<String> tags = tagsStr != null
                                ? List.of(tagsStr.split(",")).stream().map(String::trim).toList()
                                : null;

                        TodoItem item = new TodoItem(
                                null, title, description, priority,
                                TodoItem.Status.PENDING, dueDate, tags, null, null);
                        String id = todoRepository.create(item);
                        return ToolResult.success(Map.of("id", id));
                    } catch (Exception e) {
                        log.error("创建待办失败: {}", e.getMessage(), e);
                        return ToolResult.error("创建待办失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询待办列表工具。 */
    private BuiltinTool buildListTool() {
        return BuiltinTool.builder()
                .id("builtin.todo.list")
                .name("查询待办列表")
                .description("查询待办事项列表，支持按状态和优先级过滤")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "status", Map.of("type", "string", "description", "状态过滤: PENDING/IN_PROGRESS/COMPLETED"),
                                "priority", Map.of("type", "string", "description", "优先级过滤: HIGH/MEDIUM/LOW")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String status = input.getOptionalParam("status", String.class).orElse(null);
                        String priority = input.getOptionalParam("priority", String.class).orElse(null);
                        List<TodoItem> items = todoRepository.list(status, priority);
                        List<Map<String, Object>> itemMaps = items.stream()
                                .map(this::todoItemToMap)
                                .toList();
                        return ToolResult.success(Map.of("items", itemMaps));
                    } catch (Exception e) {
                        log.error("查询待办列表失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询待办列表失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询单个待办工具。 */
    private BuiltinTool buildGetTool() {
        return BuiltinTool.builder()
                .id("builtin.todo.get")
                .name("查询待办详情")
                .description("根据 ID 查询单个待办事项的详细信息")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "待办 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        return todoRepository.findById(id)
                                .map(item -> ToolResult.success(todoItemToMap(item)))
                                .orElse(ToolResult.error("待办不存在: id=" + id));
                    } catch (Exception e) {
                        log.error("查询待办详情失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询待办详情失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建更新待办工具。 */
    private BuiltinTool buildUpdateTool() {
        return BuiltinTool.builder()
                .id("builtin.todo.update")
                .name("更新待办")
                .description("更新待办事项的标题、描述、优先级、状态、截止日期或标签")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "待办 ID"),
                                "title", Map.of("type", "string", "description", "新标题"),
                                "description", Map.of("type", "string", "description", "新描述"),
                                "priority", Map.of("type", "string", "description", "新优先级: HIGH/MEDIUM/LOW"),
                                "status", Map.of("type", "string", "description", "新状态: PENDING/IN_PROGRESS/COMPLETED"),
                                "dueDate", Map.of("type", "string", "description", "新截止日期 ISO 8601"),
                                "tags", Map.of("type", "string", "description", "新标签，逗号分隔")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        var existing = todoRepository.findById(id);
                        if (existing.isEmpty()) {
                            return ToolResult.error("待办不存在: id=" + id);
                        }
                        TodoItem current = existing.get();

                        String title = input.getOptionalParam("title", String.class).orElse(current.title());
                        String description = input.getOptionalParam("description", String.class).orElse(current.description());
                        String priorityStr = input.getOptionalParam("priority", String.class).orElse(current.priority().name());
                        String statusStr = input.getOptionalParam("status", String.class).orElse(current.status().name());
                        String dueDate = input.getOptionalParam("dueDate", String.class).orElse(current.dueDate());
                        String tagsStr = input.getOptionalParam("tags", String.class).orElse(null);

                        TodoItem.Priority priority = TodoItem.Priority.valueOf(priorityStr.toUpperCase());
                        TodoItem.Status status = TodoItem.Status.valueOf(statusStr.toUpperCase());
                        List<String> tags = tagsStr != null
                                ? List.of(tagsStr.split(",")).stream().map(String::trim).toList()
                                : current.tags();

                        TodoItem updated = new TodoItem(
                                id, title, description, priority, status,
                                dueDate, tags, current.createdAt(), current.updatedAt());
                        boolean success = todoRepository.update(id, updated);
                        return success
                                ? ToolResult.success(Map.of("updated", true))
                                : ToolResult.error("更新待办失败: id=" + id);
                    } catch (Exception e) {
                        log.error("更新待办失败: {}", e.getMessage(), e);
                        return ToolResult.error("更新待办失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建删除待办工具。 */
    private BuiltinTool buildDeleteTool() {
        return BuiltinTool.builder()
                .id("builtin.todo.delete")
                .name("删除待办")
                .description("根据 ID 删除待办事项")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "待办 ID")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        boolean success = todoRepository.delete(id);
                        return success
                                ? ToolResult.success(Map.of("deleted", true))
                                : ToolResult.error("待办不存在: id=" + id);
                    } catch (Exception e) {
                        log.error("删除待办失败: {}", e.getMessage(), e);
                        return ToolResult.error("删除待办失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建完成待办工具。 */
    private BuiltinTool buildCompleteTool() {
        return BuiltinTool.builder()
                .id("builtin.todo.complete")
                .name("完成待办")
                .description("将待办事项标记为已完成")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "待办 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        boolean success = todoRepository.complete(id);
                        return success
                                ? ToolResult.success(Map.of("completed", true))
                                : ToolResult.error("待办不存在或无法完成: id=" + id);
                    } catch (Exception e) {
                        log.error("完成待办失败: {}", e.getMessage(), e);
                        return ToolResult.error("完成待办失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // ---- 辅助方法 ----

    /** 将 TodoItem 转换为 Map 用于 ToolResult。 */
    private Map<String, Object> todoItemToMap(TodoItem item) {
        var map = new java.util.HashMap<String, Object>();
        map.put("id", item.id());
        map.put("title", item.title());
        if (item.description() != null) map.put("description", item.description());
        map.put("priority", item.priority().name());
        map.put("status", item.status().name());
        if (item.dueDate() != null) map.put("dueDate", item.dueDate());
        if (item.tags() != null) map.put("tags", item.tags());
        map.put("createdAt", item.createdAt());
        map.put("updatedAt", item.updatedAt());
        return Map.copyOf(map);
    }
}

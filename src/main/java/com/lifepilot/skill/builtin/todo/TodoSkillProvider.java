package com.lifepilot.skill.builtin.todo;

import com.lifepilot.agent.proactive.candidate.CandidateProvider;
import com.lifepilot.agent.proactive.model.InitiativeType;
import com.lifepilot.agent.proactive.model.ProactiveCandidate;
import com.lifepilot.agent.proactive.model.Signal;
import com.lifepilot.agent.proactive.model.SignalBundle;
import com.lifepilot.agent.proactive.model.Urgency;
import com.lifepilot.agent.proactive.signal.SignalSource;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.ProactiveSkillProvider;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 待办管理内置 Skill 提供者。
 *
 * <p>注册 6 个待办 CRUD 工具到 DynamicToolRegistry，
 * 提供待办管理 Skill 定义蓝图。实现 {@link ProactiveSkillProvider}，
 * 提供待办截止日期信号源和候选提供者。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@BuiltinSkill(id = "todo", order = 10)
public class TodoSkillProvider implements ProactiveSkillProvider {

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
                .instructions(promptRegistry.render("skill/todo"))
                .suggestedTools(List.of(
                        "builtin.todo.create",
                        "builtin.todo.list",
                        "builtin.todo.get",
                        "builtin.todo.update",
                        "builtin.todo.delete",
                        "builtin.todo.complete"
                ))
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

    // ---- ProactiveSkillProvider 实现 ----

    @Override
    public List<SignalSource> signalSources() {
        return List.of(new TodoSignalSource());
    }

    @Override
    public List<CandidateProvider> candidateProviders() {
        return List.of(new TodoCandidateProvider());
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
                                "dueDate", Map.of("type", "string", "format", "date-time", "description", "截止日期 ISO 8601"),
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
                                "dueDate", Map.of("type", "string", "format", "date-time", "description", "新截止日期 ISO 8601"),
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

    // ---- 主动推理内部类 ----

    /**
     * 待办截止日期信号源 — 收集 24 小时内到期的 PENDING 待办信号。
     *
     * <p>根据距截止时间的剩余时长计算紧急度：
     * &lt; 2h → HIGH，&lt; 6h → MEDIUM，其余 → LOW。</p>
     */
    private class TodoSignalSource implements SignalSource {

        @Override
        public String id() {
            return "todo-signal";
        }

        @Override
        public List<Signal> collect() {
            List<TodoItem> pendingTodos = todoRepository.list("PENDING", null);
            Instant now = Instant.now();
            Instant deadline = now.plus(Duration.ofHours(24));
            List<Signal> signals = new ArrayList<>();

            for (TodoItem todo : pendingTodos) {
                if (todo.dueDate() == null) {
                    continue;
                }
                try {
                    Instant dueInstant = Instant.parse(todo.dueDate());
                    // 仅收集 24 小时内到期且尚未过期的待办
                    if (dueInstant.isAfter(now) && !dueInstant.isAfter(deadline)) {
                        Duration remaining = Duration.between(now, dueInstant);
                        Urgency urgency = remaining.toHours() < 2 ? Urgency.HIGH
                                : remaining.toHours() < 6 ? Urgency.MEDIUM
                                : Urgency.LOW;

                        signals.add(Signal.builder()
                                .typeId("deadline_reminder")
                                .urgency(urgency)
                                .summary("待办「%s」将于 %s 到期".formatted(todo.title(), todo.dueDate()))
                                .sourceId("todo-signal")
                                .subjectId(todo.id())
                                .metadata(Map.of(
                                        "todoId", todo.id(),
                                        "title", todo.title(),
                                        "dueDate", todo.dueDate()))
                                .build());
                    }
                } catch (Exception e) {
                    log.warn("解析待办截止日期失败: todoId={}, dueDate={}", todo.id(), todo.dueDate(), e);
                }
            }

            log.debug("待办信号收集完成: count={}", signals.size());
            return List.copyOf(signals);
        }
    }

    /**
     * 待办候选提供者 — 将 deadline_reminder 信号映射为 NOTIFICATION 候选。
     */
    private class TodoCandidateProvider implements CandidateProvider {

        @Override
        public String id() {
            return "todo-candidate";
        }

        @Override
        public List<ProactiveCandidate> evaluate(SignalBundle signals) {
            return signals.signals().stream()
                    .filter(s -> "deadline_reminder".equals(s.typeId()))
                    .filter(s -> "todo-signal".equals(s.sourceId()))
                    .map(s -> new ProactiveCandidate(
                            "deadline_reminder",
                            s.urgency(),
                            s.summary(),
                            s.subjectId(),
                            InitiativeType.NOTIFICATION))
                    .toList();
        }
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

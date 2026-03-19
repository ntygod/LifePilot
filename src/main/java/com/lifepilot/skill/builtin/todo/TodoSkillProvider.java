package com.lifepilot.skill.builtin.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.proactive.candidate.CandidateProvider;
import com.lifepilot.agent.proactive.model.InitiativeType;
import com.lifepilot.agent.proactive.model.ProactiveCandidate;
import com.lifepilot.agent.proactive.model.Signal;
import com.lifepilot.agent.proactive.model.SignalBundle;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.adapter.CrudAdapterConfig;
import com.lifepilot.datastore.adapter.DataStoreCrudAdapter;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.FilterOp;
import com.lifepilot.datastore.model.PropertyDefinition;
import com.lifepilot.datastore.model.PropertyType;
import com.lifepilot.datastore.model.QueryFilter;
import com.lifepilot.notification.Urgency;
import com.lifepilot.agent.proactive.signal.SignalSource;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.ProactiveSkillProvider;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolCategory;
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
 * <p>存储层通过 {@link DataStoreCrudAdapter} 委托给 DataStore，
 * 数据以 JSON 格式存储在"待办事项"Collection 中。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@BuiltinSkill(id = "todo", order = 10)
public class TodoSkillProvider implements ProactiveSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(TodoSkillProvider.class);

    private final DataStoreCrudAdapter<TodoEntity> todoAdapter;
    private final PromptRegistry promptRegistry;

    public TodoSkillProvider(DataStoreManager dataStoreManager,
                             ObjectMapper objectMapper,
                             PromptRegistry promptRegistry) {
        this.todoAdapter = new DataStoreCrudAdapter<>(dataStoreManager, objectMapper,
                new CrudAdapterConfig<>(
                        "todo",
                        "待办事项",
                        CollectionType.DOCUMENT,
                        TodoEntity.class,
                        List.of(
                                new PropertyDefinition("title", PropertyType.TEXT, true, "待办标题"),
                                new PropertyDefinition("status", PropertyType.SELECT, true, "状态: PENDING/IN_PROGRESS/COMPLETED"),
                                new PropertyDefinition("priority", PropertyType.SELECT, false, "优先级: HIGH/MEDIUM/LOW"),
                                new PropertyDefinition("dueDate", PropertyType.DATE, false, "截止日期")
                        ),
                        "待办事项管理"
                ));
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
                .description("创建待办事项或简单提醒（设置 dueDate 可到期自动通知）。多步骤自动化任务请使用工作流")
                .category(ToolCategory.STORAGE)
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
                        String priority = input.getOptionalParam("priority", String.class).orElse("MEDIUM");
                        String dueDate = input.getOptionalParam("dueDate", String.class).orElse(null);
                        String tagsStr = input.getOptionalParam("tags", String.class).orElse(null);

                        List<String> tags = tagsStr != null
                                ? List.of(tagsStr.split(",")).stream().map(String::trim).toList()
                                : null;

                        var entity = new TodoEntity(title, "PENDING", priority.toUpperCase(), dueDate, description, tags);
                        return todoAdapter.create(entity);
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
                .category(ToolCategory.STORAGE)
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

                        var filters = new ArrayList<QueryFilter>();
                        if (status != null && !status.isBlank()) {
                            filters.add(new QueryFilter("status", FilterOp.EQ, status));
                        }
                        if (priority != null && !priority.isBlank()) {
                            filters.add(new QueryFilter("priority", FilterOp.EQ, priority));
                        }

                        List<TodoEntity> entities = todoAdapter.list(
                                filters.isEmpty() ? null : filters, null, null, 0, 100);
                        List<Map<String, Object>> itemMaps = entities.stream()
                                .map(this::todoEntityToMap)
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
                .category(ToolCategory.STORAGE)
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
                        return todoAdapter.findById(id)
                                .map(entity -> ToolResult.success(todoEntityToMap(entity)))
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
                .category(ToolCategory.STORAGE)
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
                        var existing = todoAdapter.findById(id);
                        if (existing.isEmpty()) {
                            return ToolResult.error("待办不存在: id=" + id);
                        }
                        TodoEntity current = existing.get();

                        String title = input.getOptionalParam("title", String.class).orElse(current.title());
                        String description = input.getOptionalParam("description", String.class).orElse(current.description());
                        String priority = input.getOptionalParam("priority", String.class).orElse(current.priority());
                        String status = input.getOptionalParam("status", String.class).orElse(current.status());
                        String dueDate = input.getOptionalParam("dueDate", String.class).orElse(current.dueDate());
                        String tagsStr = input.getOptionalParam("tags", String.class).orElse(null);

                        List<String> tags = tagsStr != null
                                ? List.of(tagsStr.split(",")).stream().map(String::trim).toList()
                                : current.tags();

                        var updated = new TodoEntity(title, status.toUpperCase(), priority.toUpperCase(), dueDate, description, tags);
                        return todoAdapter.update(id, updated);
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
                .category(ToolCategory.STORAGE)
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
                        return todoAdapter.delete(id);
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
                .category(ToolCategory.STORAGE)
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
                        var existing = todoAdapter.findById(id);
                        if (existing.isEmpty()) {
                            return ToolResult.error("待办不存在: id=" + id);
                        }
                        TodoEntity current = existing.get();
                        var completed = new TodoEntity(
                                current.title(), "COMPLETED", current.priority(),
                                current.dueDate(), current.description(), current.tags());
                        return todoAdapter.update(id, completed);
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
            // 查询所有 PENDING 状态的待办
            var filters = List.of(new QueryFilter("status", FilterOp.EQ, "PENDING"));
            List<TodoEntity> pendingTodos = todoAdapter.list(filters, null, null, 0, 1000);
            Instant now = Instant.now();
            Instant deadline = now.plus(Duration.ofHours(24));
            List<Signal> signals = new ArrayList<>();

            for (TodoEntity todo : pendingTodos) {
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
                                .subjectId(todo.title())
                                .metadata(Map.of(
                                        "title", todo.title(),
                                        "dueDate", todo.dueDate()))
                                .build());
                    }
                } catch (Exception e) {
                    log.warn("解析待办截止日期失败: title={}, dueDate={}", todo.title(), todo.dueDate(), e);
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

    /** 将 TodoEntity 转换为 Map 用于 ToolResult。 */
    private Map<String, Object> todoEntityToMap(TodoEntity entity) {
        var map = new java.util.HashMap<String, Object>();
        map.put("title", entity.title());
        map.put("status", entity.status());
        map.put("priority", entity.priority());
        if (entity.description() != null) map.put("description", entity.description());
        if (entity.dueDate() != null) map.put("dueDate", entity.dueDate());
        if (entity.tags() != null) map.put("tags", entity.tags());
        return Map.copyOf(map);
    }
}

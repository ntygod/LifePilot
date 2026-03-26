package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowStep;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tool 管理 REST Controller。
 *
 * <p>提供 Tool 列表、详情、测试和使用情况查询端点。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ToolController {

    private static final Logger log = LoggerFactory.getLogger(ToolController.class);

    private final DynamicToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final WorkflowRegistry workflowRegistry;

    public ToolController(DynamicToolRegistry toolRegistry,
                          SkillRegistry skillRegistry,
                          WorkflowRegistry workflowRegistry) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.workflowRegistry = workflowRegistry;
    }

    /**
     * 获取 Tool 列表（支持筛选）。
     *
     * @param source 来源筛选（builtin, mcp）
     * @param status 状态筛选（enabled, disabled）- 当前版本暂不支持，返回所有 Tool
     * @param name 名称筛选（模糊匹配）
     * @return Tool 列表
     */
    @GetMapping("/tools")
    public ResponseEntity<?> listTools(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String name) {
        log.debug("查询 Tool 列表: source={}, status={}, name={}", source, status, name);

        List<ToolContract> allTools = toolRegistry.getAllTools();

        var filtered = allTools.stream()
                .filter(tool -> {
                    if (source != null && !source.isBlank()) {
                        ToolLayer layer = tool.layer();
                        String toolSource = getSourceString(layer);
                        if (!toolSource.equalsIgnoreCase(source)) {
                            return false;
                        }
                    }
                    if (name != null && !name.isBlank()) {
                        String toolName = tool.name() != null ? tool.name().toLowerCase() : "";
                        String toolId = tool.id() != null ? tool.id().toLowerCase() : "";
                        String searchTerm = name.toLowerCase();
                        if (!toolName.contains(searchTerm) && !toolId.contains(searchTerm)) {
                            return false;
                        }
                    }
                    return true;
                })
                .map(this::toToolSummary)
                .collect(Collectors.toList());

        return ResponseEntity.ok(filtered);
    }

    /**
     * 获取 Tool 详情。
     *
     * @param id Tool ID
     * @return Tool 详情
     */
    @GetMapping("/tools/{id}")
    public ResponseEntity<?> getTool(@PathVariable String id) {
        log.debug("查询 Tool 详情: id={}", id);

        return toolRegistry.resolve(id)
                .<ResponseEntity<?>>map(tool -> {
                    Map<String, Object> detail = toToolDetail(tool);
                    detail.put("usage", queryToolUsage(id));
                    return ResponseEntity.ok(detail);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse(404, "Tool 不存在: id=" + id, Instant.now())));
    }

    /**
     * 测试 Tool。
     *
     * @param id Tool ID
     * @param request 测试请求（包含参数）
     * @return 测试结果
     */
    @PostMapping("/tools/{id}/test")
    public ResponseEntity<?> testTool(
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        log.debug("测试 Tool: id={}", id);

        return toolRegistry.resolve(id)
                .<ResponseEntity<?>>map(tool -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> arguments = (Map<String, Object>) request.getOrDefault("arguments", Map.of());
                        ToolInput input = new ToolInput(tool.id(), arguments, tool.inputSchema(), null, null);
                        ToolResult result = tool.execute(input);

                        Map<String, Object> response = new HashMap<>();
                        response.put("success", result.ok());
                        response.put("output", result.data());
                        response.put("error", result.error());
                        response.put("meta", Map.of(
                                "durationMs", result.meta().duration().toMillis(),
                                "toolId", result.meta().toolId(),
                                "action", result.meta().action()
                        ));
                        return ResponseEntity.ok(response);
                    } catch (Exception e) {
                        log.error("测试 Tool 失败: id={}", id, e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(new ErrorResponse(500, "测试失败: " + e.getMessage(), Instant.now()));
                    }
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse(404, "Tool 不存在: id=" + id, Instant.now())));
    }

    /**
     * 获取 Tool 使用情况（被哪些 Skill/Workflow 使用）。
     *
     * @param id Tool ID
     * @return 使用情况
     */
    @GetMapping("/tools/{id}/usage")
    public ResponseEntity<?> getToolUsage(@PathVariable String id) {
        log.debug("查询 Tool 使用情况: id={}", id);

        if (!toolRegistry.resolve(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "Tool 不存在: id=" + id, Instant.now()));
        }

        return ResponseEntity.ok(queryToolUsage(id));
    }

    /**
     * 创建 Tool（运行时注册 BuiltinTool，不持久化）。
     *
     * @param request 创建请求（包含 Tool 配置）
     * @return 201 创建成功，400 参数错误
     */
    @PostMapping("/tools")
    public ResponseEntity<?> createTool(@RequestBody Map<String, Object> request) {
        log.debug("创建 Tool: request={}", request);
        try {
            String id = getString(request, "id");
            String toolName = getString(request, "name");
            String description = getStringOrDefault(request, "description", "");

            if (id == null || id.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "Tool ID 不能为空", Instant.now()));
            }
            if (toolName == null || toolName.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "Tool 名称不能为空", Instant.now()));
            }
            if (toolRegistry.resolve(id).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ErrorResponse(409, "Tool ID 已存在: " + id, Instant.now()));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> inputSchemaMap = (Map<String, Object>) request.getOrDefault("inputSchema", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> outputSchemaMap = (Map<String, Object>) request.getOrDefault("outputSchema", Map.of("type", "object"));
            JsonSchema inputSchema = JsonSchema.of(inputSchemaMap);
            JsonSchema outputSchema = JsonSchema.of(outputSchemaMap);

            @SuppressWarnings("unchecked")
            Map<String, Object> budgetMap = (Map<String, Object>) request.getOrDefault("budget", Map.of());
            long timeoutSeconds = getLongOrDefault(budgetMap, "timeoutSeconds", 30L);
            int maxRetries = getIntOrDefault(budgetMap, "maxRetries", 2);
            int maxCostCents = getIntOrDefault(budgetMap, "maxCostCents", Integer.MAX_VALUE);
            ToolBudget budget = ToolBudget.of(Duration.ofSeconds(timeoutSeconds), maxRetries, maxCostCents);

            String riskLevelStr = getStringOrDefault(request, "riskLevel", "MEDIUM");
            RiskLevel riskLevel = parseRiskLevel(riskLevelStr);
            boolean idempotent = getBooleanOrDefault(request, "idempotent", false);
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) request.getOrDefault("tags", List.of());

            BuiltinTool tool = BuiltinTool.builder()
                    .id(id)
                    .name(toolName)
                    .description(description)
                    .inputSchema(inputSchema)
                    .outputSchema(outputSchema)
                    .riskLevel(riskLevel)
                    .idempotent(idempotent)
                    .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.SEQUENTIAL))
                    .budget(budget)
                    .tags(tags != null ? tags : List.of())
                    .executor(input -> ToolResult.success(Map.of("message", "用户自定义工具，暂无执行逻辑")))
                    .build();

            toolRegistry.registerBuiltinTool(tool);

            log.info("Tool 创建成功: id={}, name={}", id, toolName);
            return ResponseEntity.status(HttpStatus.CREATED).body(toToolDetail(tool));
        } catch (Exception e) {
            log.error("创建 Tool 失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "创建失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 更新 Tool（仅支持更新用户创建的 BuiltinTool）。
     *
     * @param id Tool ID
     * @param request 更新请求
     * @return 200 更新成功，404 不存在，400 参数错误
     */
    @PutMapping("/tools/{id}")
    public ResponseEntity<?> updateTool(@PathVariable String id,
                                         @RequestBody Map<String, Object> request) {
        log.debug("更新 Tool: id={}, request={}", id, request);

        ToolContract existingTool = toolRegistry.resolve(id).orElse(null);
        if (existingTool == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "Tool 不存在: id=" + id, Instant.now()));
        }

        // MCP 工具不可更新
        if (existingTool.layer() == ToolLayer.MCP_EXTERNAL) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400, "MCP 工具不支持更新", Instant.now()));
        }

        try {
            String toolName = getStringOrDefault(request, "name", existingTool.name());
            String description = getStringOrDefault(request, "description", existingTool.description());

            @SuppressWarnings("unchecked")
            Map<String, Object> inputSchemaMap = (Map<String, Object>) request.getOrDefault("inputSchema", existingTool.inputSchema().toMap());
            @SuppressWarnings("unchecked")
            Map<String, Object> outputSchemaMap = (Map<String, Object>) request.getOrDefault("outputSchema", existingTool.outputSchema().toMap());
            JsonSchema inputSchema = JsonSchema.of(inputSchemaMap);
            JsonSchema outputSchema = JsonSchema.of(outputSchemaMap);

            @SuppressWarnings("unchecked")
            Map<String, Object> budgetMap = (Map<String, Object>) request.getOrDefault("budget", Map.of());
            long timeoutSeconds = getLongOrDefault(budgetMap, "timeoutSeconds", existingTool.budget().timeout().getSeconds());
            int maxRetries = getIntOrDefault(budgetMap, "maxRetries", existingTool.budget().maxRetries());
            int maxCostCents = getIntOrDefault(budgetMap, "maxCostCents", existingTool.budget().maxCostCents());
            ToolBudget budget = ToolBudget.of(Duration.ofSeconds(timeoutSeconds), maxRetries, maxCostCents);

            String riskLevelStr = getStringOrDefault(request, "riskLevel", existingTool.riskLevel().name());
            RiskLevel riskLevel = parseRiskLevel(riskLevelStr);
            boolean idempotent = getBooleanOrDefault(request, "idempotent", existingTool.idempotent());
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) request.getOrDefault("tags", existingTool.tags());

            // 注销旧工具，注册新工具
            toolRegistry.unregisterBuiltinTool(id);

            BuiltinTool updatedTool = BuiltinTool.builder()
                    .id(id)
                    .name(toolName)
                    .description(description)
                    .inputSchema(inputSchema)
                    .outputSchema(outputSchema)
                    .riskLevel(riskLevel)
                    .idempotent(idempotent)
                    .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.SEQUENTIAL))
                    .budget(budget)
                    .tags(tags != null ? tags : List.of())
                    .executor(input -> ToolResult.success(Map.of("message", "用户自定义工具，暂无执行逻辑")))
                    .build();

            toolRegistry.registerBuiltinTool(updatedTool);

            log.info("Tool 更新成功: id={}", id);
            return ResponseEntity.ok(toToolDetail(updatedTool));
        } catch (Exception e) {
            log.error("更新 Tool 失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "更新失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 删除 Tool。
     *
     * @param id Tool ID
     * @return 200 删除成功，404 不存在，400 被引用无法删除
     */
    @DeleteMapping("/tools/{id}")
    public ResponseEntity<?> deleteTool(@PathVariable String id) {
        log.debug("删除 Tool: id={}", id);

        ToolContract tool = toolRegistry.resolve(id).orElse(null);
        if (tool == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "Tool 不存在: id=" + id, Instant.now()));
        }

        // MCP 工具不能删除，只能隐藏
        if (tool.layer() == ToolLayer.MCP_EXTERNAL) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400, "MCP Tool 不能删除，只能隐藏", Instant.now()));
        }

        // 检查是否被引用
        Map<String, Object> usage = queryToolUsage(id);
        int skillCount = (Integer) usage.get("skillCount");
        int workflowCount = (Integer) usage.get("workflowCount");

        if (skillCount > 0 || workflowCount > 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400,
                            String.format("Tool 被 %d 个 Skill 和 %d 个 Workflow 使用，无法删除",
                                    skillCount, workflowCount),
                            Instant.now()));
        }

        toolRegistry.unregisterBuiltinTool(id);
        log.info("Tool 删除成功: id={}", id);
        return ResponseEntity.ok(Map.of("message", "Tool 删除成功"));
    }

    /**
     * 启用 Tool（预留接口）。
     *
     * @param id Tool ID
     * @return 200 启用成功，404 不存在
     */
    @PostMapping("/tools/{id}/enable")
    public ResponseEntity<?> enableTool(@PathVariable String id) {
        log.debug("启用 Tool: id={}", id);

        if (!toolRegistry.resolve(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "Tool 不存在: id=" + id, Instant.now()));
        }

        log.info("Tool 启用成功: id={}", id);
        return ResponseEntity.ok(Map.of("message", "Tool 已启用"));
    }

    /**
     * 禁用 Tool（预留接口）。
     *
     * @param id Tool ID
     * @return 200 禁用成功，404 不存在
     */
    @PostMapping("/tools/{id}/disable")
    public ResponseEntity<?> disableTool(@PathVariable String id) {
        log.debug("禁用 Tool: id={}", id);

        if (!toolRegistry.resolve(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "Tool 不存在: id=" + id, Instant.now()));
        }

        log.info("Tool 禁用成功: id={}", id);
        return ResponseEntity.ok(Map.of("message", "Tool 已禁用"));
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private Map<String, Object> toToolSummary(ToolContract tool) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", tool.id());
        summary.put("name", tool.name());
        summary.put("description", tool.description());
        summary.put("source", getSourceString(tool.layer()));
        summary.put("layer", tool.layer().name());
        summary.put("riskLevel", tool.riskLevel().name());
        summary.put("actionType", tool.executionSemantics().actionType().name());
        summary.put("schedulingMode", tool.schedulingMode().name());
        summary.put("idempotent", tool.idempotent());
        summary.put("tags", tool.tags());

        if (tool instanceof com.lifepilot.tool.McpTool mcpTool) {
            summary.put("serverName", mcpTool.serverName());
        }

        return summary;
    }

    private Map<String, Object> toToolDetail(ToolContract tool) {
        Map<String, Object> detail = toToolSummary(tool);
        detail.put("inputSchema", tool.inputSchema().toMap());
        detail.put("outputSchema", tool.outputSchema().toMap());
        detail.put("budget", Map.of(
                "maxCostCents", tool.budget().maxCostCents(),
                "timeoutSeconds", tool.budget().timeout().getSeconds(),
                "maxRetries", tool.budget().maxRetries()
        ));
        detail.put("exportable", tool.exportable());
        return detail;
    }

    private String getSourceString(ToolLayer layer) {
        return switch (layer) {
            case JAVA_NATIVE -> "builtin";
            case MCP_EXTERNAL -> "mcp";
        };
    }

    private Map<String, Object> queryToolUsage(String toolId) {
        Map<String, Object> usage = new HashMap<>();

        List<Map<String, String>> usedBySkills = skillRegistry.listAll().stream()
                .filter(skill -> skill.suggestedTools() != null && skill.suggestedTools().contains(toolId))
                .map(skill -> Map.of(
                        "id", skill.id(),
                        "name", skill.name() != null ? skill.name() : skill.id()
                ))
                .collect(Collectors.toList());

        List<Map<String, String>> usedByWorkflows = new ArrayList<>();
        for (WorkflowDefinition workflow : workflowRegistry.listAll()) {
            if (workflow.steps() != null) {
                if (containsTool(workflow.steps(), toolId)) {
                    usedByWorkflows.add(Map.of(
                            "id", workflow.id(),
                            "name", workflow.name() != null ? workflow.name() : workflow.id()
                    ));
                }
            }
        }

        usage.put("usedBySkills", usedBySkills);
        usage.put("usedByWorkflows", usedByWorkflows);
        usage.put("skillCount", usedBySkills.size());
        usage.put("workflowCount", usedByWorkflows.size());

        return usage;
    }

    private boolean containsTool(List<WorkflowStep> steps, String toolId) {
        for (WorkflowStep step : steps) {
            if (step instanceof WorkflowStep.ToolStep toolStep && toolId.equals(toolStep.toolId())) {
                return true;
            }
            if (step instanceof WorkflowStep.ConditionStep conditionStep) {
                if (containsTool(conditionStep.thenSteps(), toolId) ||
                    containsTool(conditionStep.elseSteps(), toolId)) {
                    return true;
                }
            }
            if (step instanceof WorkflowStep.LoopStep loopStep) {
                if (containsTool(loopStep.body(), toolId)) {
                    return true;
                }
            }
            if (step instanceof WorkflowStep.ParallelStep parallelStep) {
                for (List<WorkflowStep> branch : parallelStep.branches()) {
                    if (containsTool(branch, toolId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        String value = getString(map, key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private long getLongOrDefault(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }

    private RiskLevel parseRiskLevel(String riskLevelStr) {
        try {
            return RiskLevel.valueOf(riskLevelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RiskLevel.MEDIUM;
        }
    }
}

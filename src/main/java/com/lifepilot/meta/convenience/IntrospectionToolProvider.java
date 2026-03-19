package com.lifepilot.meta.convenience;

import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.workflow.model.WorkflowInstance;
import com.lifepilot.workflow.model.WorkflowState;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Stream;

/**
 * 系统自省 Skill 提供者 — 注册 5 个自省工具到 DynamicToolRegistry。
 *
 * <p>工具列表：
 * <ul>
 *   <li>{@code system.list-capabilities} — 聚合所有能力并分组格式化</li>
 *   <li>{@code system.explain} — 按 ID 路由到对应注册中心查找详细信息</li>
 *   <li>{@code system.status} — 聚合系统状态（各注册中心计数）</li>
 *   <li>{@code system.suggest} — 关键词匹配 + 语义搜索推荐能力</li>
 *   <li>{@code system.runtime} — 查询运行时动态信息（工作流实例、MCP 连接状态）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class IntrospectionToolProvider {

    private static final Logger log = LoggerFactory.getLogger(IntrospectionToolProvider.class);
    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final CapabilityAggregator aggregator;
    private final SkillRegistry skillRegistry;
    private final AgentRegistry agentRegistry;
    private final DynamicToolRegistry toolRegistry;
    private final WorkflowRegistry workflowRegistry;
    @Nullable private final WorkflowRepository workflowRepository;
    @Nullable private final McpServerRegistry mcpServerRegistry;

    public IntrospectionToolProvider(CapabilityAggregator aggregator,
                                      SkillRegistry skillRegistry,
                                      AgentRegistry agentRegistry,
                                      DynamicToolRegistry toolRegistry,
                                      WorkflowRegistry workflowRegistry,
                                      @Nullable WorkflowRepository workflowRepository,
                                      @Nullable McpServerRegistry mcpServerRegistry) {
        this.aggregator = aggregator;
        this.skillRegistry = skillRegistry;
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.workflowRegistry = workflowRegistry;
        this.workflowRepository = workflowRepository;
        this.mcpServerRegistry = mcpServerRegistry;
    }

    /**
     * 注册自省工具到 DynamicToolRegistry。
     *
     * @param registry 动态工具注册中心
     */
    public void registerTools(DynamicToolRegistry registry) {
        registry.registerBuiltinTool(buildListCapabilitiesTool());
        registry.registerBuiltinTool(buildExplainTool());
        registry.registerBuiltinTool(buildStatusTool());
        registry.registerBuiltinTool(buildSuggestTool());
        registry.registerBuiltinTool(buildRuntimeTool());

        log.info("系统自省工具注册完成: count=5");
    }

    // ─────────────────────────────────────────────
    //  system.list-capabilities
    // ─────────────────────────────────────────────

    /** 构建能力列表工具。 */
    private BuiltinTool buildListCapabilitiesTool() {
        return BuiltinTool.builder()
                .id("system.list-capabilities")
                .name("列出系统能力")
                .description("列出系统当前所有已注册的能力（Skill、Agent、工具、工作流、MCP Server），支持按类型过滤")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "type", Map.of("type", "string",
                                        "description", "按类型过滤（skill/agent/tool/workflow/mcp），不传则返回全部")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(this::executeListCapabilities)
                .build();
    }

    /** 执行 system.list-capabilities。 */
    private ToolResult executeListCapabilities(ToolInput input) {
        var typeFilter = input.getOptionalParam("type", String.class).orElse(null);

        if (typeFilter != null && !typeFilter.isBlank()) {
            var filtered = aggregator.filterByType(typeFilter);
            return ToolResult.success(Map.of(
                    "type", typeFilter,
                    "count", filtered.size(),
                    "capabilities", formatCapabilityList(filtered)
            ));
        }

        var summary = aggregator.aggregate();
        var result = new LinkedHashMap<String, Object>();
        result.put("totalCount", summary.totalCount());

        if (!summary.skills().isEmpty()) {
            result.put("skills", formatCapabilityList(summary.skills()));
        }
        if (!summary.agents().isEmpty()) {
            result.put("agents", formatCapabilityList(summary.agents()));
        }
        if (!summary.tools().isEmpty()) {
            result.put("tools", formatCapabilityList(summary.tools()));
        }
        if (!summary.workflows().isEmpty()) {
            result.put("workflows", formatCapabilityList(summary.workflows()));
        }
        if (!summary.mcpServers().isEmpty()) {
            result.put("mcpServers", formatCapabilityList(summary.mcpServers()));
        }

        return ToolResult.success(Map.copyOf(result));
    }

    // ─────────────────────────────────────────────
    //  system.explain
    // ─────────────────────────────────────────────

    /** 构建能力详情工具。 */
    private BuiltinTool buildExplainTool() {
        return BuiltinTool.builder()
                .id("system.explain")
                .name("查看能力详情")
                .description("根据能力 ID 查看详细信息，包括功能描述、使用场景和依赖关系。支持通过 type 参数指定查找范围")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string",
                                        "description", "能力 ID"),
                                "type", Map.of("type", "string",
                                        "description", "能力类型（skill/agent/tool/workflow），用于精确路由查找")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(this::executeExplain)
                .build();
    }

    /** 执行 system.explain。 */
    private ToolResult executeExplain(ToolInput input) {
        var id = input.getParam("id", String.class);
        var type = input.getOptionalParam("type", String.class).orElse(null);

        // 如果指定了类型，直接路由
        if (type != null && !type.isBlank()) {
            return explainByType(id, type);
        }

        // 未指定类型，按优先级依次查找：tool → skill → agent → workflow
        var toolResult = explainTool(id);
        if (toolResult != null) return toolResult;

        var skillResult = explainSkill(id);
        if (skillResult != null) return skillResult;

        var agentResult = explainAgent(id);
        if (agentResult != null) return agentResult;

        var workflowResult = explainWorkflow(id);
        if (workflowResult != null) return workflowResult;

        return ToolResult.error("未找到 ID 为 '" + id + "' 的能力");
    }

    /** 按类型路由查找。 */
    private ToolResult explainByType(String id, String type) {
        ToolResult result = switch (type.toLowerCase()) {
            case "tool" -> explainTool(id);
            case "skill" -> explainSkill(id);
            case "agent" -> explainAgent(id);
            case "workflow" -> explainWorkflow(id);
            default -> null;
        };
        return result != null ? result : ToolResult.error("未找到类型为 '" + type + "' 的能力: " + id);
    }

    @Nullable
    private ToolResult explainTool(String id) {
        return toolRegistry.resolve(id).map(tool -> {
            var data = new LinkedHashMap<String, Object>();
            data.put("id", tool.id());
            data.put("name", tool.name());
            data.put("description", tool.description());
            data.put("type", "tool");
            data.put("riskLevel", tool.riskLevel().name());
            data.put("layer", tool.layer().name());
            data.put("tags", tool.tags());
            data.put("idempotent", tool.idempotent());
            return ToolResult.success(Map.copyOf(data));
        }).orElse(null);
    }

    @Nullable
    private ToolResult explainSkill(String id) {
        return skillRegistry.find(id).map(skill -> {
            var data = new LinkedHashMap<String, Object>();
            data.put("id", skill.id());
            data.put("name", skill.name());
            data.put("description", skill.description());
            data.put("type", "skill");
            data.put("version", skill.version());
            data.put("suggestedTools", skill.suggestedTools());
            data.put("metadata", skill.metadata());
            return ToolResult.success(Map.copyOf(data));
        }).orElse(null);
    }

    @Nullable
    private ToolResult explainAgent(String id) {
        return agentRegistry.find(id).map(agent -> {
            var data = new LinkedHashMap<String, Object>();
            data.put("id", agent.id());
            data.put("name", agent.name());
            data.put("description", agent.description());
            data.put("type", "agent");
            data.put("allowedTools", agent.allowedTools());
            data.put("canDelegate", agent.canDelegate());
            return ToolResult.success(Map.copyOf(data));
        }).orElse(null);
    }

    @Nullable
    private ToolResult explainWorkflow(String id) {
        return workflowRegistry.find(id).map(workflow -> {
            var data = new LinkedHashMap<String, Object>();
            data.put("id", workflow.id());
            data.put("name", workflow.name());
            data.put("description", workflow.description() != null ? workflow.description() : "");
            data.put("type", "workflow");
            data.put("version", workflow.version());
            data.put("enabled", workflow.enabled());
            data.put("stepsCount", workflow.steps().size());
            return ToolResult.success(Map.copyOf(data));
        }).orElse(null);
    }

    // ─────────────────────────────────────────────
    //  system.status
    // ─────────────────────────────────────────────

    /** 构建系统状态工具。 */
    private BuiltinTool buildStatusTool() {
        return BuiltinTool.builder()
                .id("system.status")
                .name("查看系统状态")
                .description("查看系统当前状态概览，包括各注册中心的能力计数和工具层次分布")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(this::executeStatus)
                .build();
    }

    /** 执行 system.status。 */
    private ToolResult executeStatus(ToolInput input) {
        var summary = aggregator.aggregate();
        var toolCountByLayer = toolRegistry.getToolCountByLayer();

        var data = new LinkedHashMap<String, Object>();
        data.put("skillCount", summary.skills().size());
        data.put("agentCount", summary.agents().size());
        data.put("toolCount", summary.tools().size());
        data.put("workflowCount", summary.workflows().size());
        data.put("mcpServerCount", summary.mcpServers().size());
        data.put("totalCapabilities", summary.totalCount());

        // 工具层次分布
        var layerDistribution = new LinkedHashMap<String, Object>();
        toolCountByLayer.forEach((layer, count) ->
                layerDistribution.put(layer.name(), count));
        data.put("toolLayerDistribution", Map.copyOf(layerDistribution));

        // JVM 运行时信息
        var runtime = Runtime.getRuntime();
        data.put("jvmMemoryUsedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        data.put("jvmMemoryMaxMB", runtime.maxMemory() / (1024 * 1024));

        return ToolResult.success(Map.copyOf(data));
    }

    // ─────────────────────────────────────────────
    //  system.suggest
    // ─────────────────────────────────────────────

    /** 构建能力推荐工具。 */
    private BuiltinTool buildSuggestTool() {
        return BuiltinTool.builder()
                .id("system.suggest")
                .name("推荐能力")
                .description("根据需求描述推荐匹配的系统能力。先在已有能力中关键词匹配，再通过语义搜索 Skill")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "需求描述，用于匹配已有能力")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(this::executeSuggest)
                .build();
    }

    /** 执行 system.suggest。 */
    private ToolResult executeSuggest(ToolInput input) {
        var query = input.getParam("query", String.class);
        var queryLower = query.toLowerCase();

        // 1. 关键词匹配：在所有能力的 name + description 中搜索
        var summary = aggregator.aggregate();
        var allCapabilities = Stream.of(
                        summary.skills().stream(),
                        summary.agents().stream(),
                        summary.tools().stream(),
                        summary.workflows().stream(),
                        summary.mcpServers().stream())
                .flatMap(s -> s)
                .toList();

        var keywordMatches = allCapabilities.stream()
                .filter(c -> c.name().toLowerCase().contains(queryLower)
                        || c.description().toLowerCase().contains(queryLower))
                .limit(10)
                .toList();

        // 2. 语义搜索：通过 SkillRegistry.search() 搜索 Skill
        List<CapabilityInfo> semanticMatches = List.of();
        try {
            var searchResults = skillRegistry.search(query);
            semanticMatches = searchResults.stream()
                    .map(skill -> new CapabilityInfo(
                            skill.id(), skill.name(), skill.description(),
                            "builtin", "active", "skill"))
                    .limit(5)
                    .toList();
        } catch (Exception e) {
            log.debug("语义搜索异常，跳过: error={}", e.getMessage());
        }

        // 3. 合并去重
        var seen = new HashSet<String>();
        var merged = new ArrayList<Map<String, Object>>();
        for (var cap : keywordMatches) {
            if (seen.add(cap.id())) {
                merged.add(capabilityToMap(cap, "keyword"));
            }
        }
        for (var cap : semanticMatches) {
            if (seen.add(cap.id())) {
                merged.add(capabilityToMap(cap, "semantic"));
            }
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("query", query);
        data.put("matchCount", merged.size());
        data.put("suggestions", List.copyOf(merged));

        if (merged.isEmpty()) {
            data.put("hint", "未找到匹配的已有能力，建议使用 find-skills Skill 搜索开源 Skill，或使用 mcp-installer 安装 MCP Server");
        }

        return ToolResult.success(Map.copyOf(data));
    }

    // ─────────────────────────────────────────────
    //  system.runtime
    // ─────────────────────────────────────────────

    /** 构建运行时信息工具。 */
    private BuiltinTool buildRuntimeTool() {
        return BuiltinTool.builder()
                .id("system.runtime")
                .name("查看运行时信息")
                .description("查询系统运行时动态信息，包括正在执行的工作流实例、MCP Server 连接状态等。支持按 scope 过滤查询范围")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "scope", Map.of("type", "string",
                                        "description", "查询范围（workflow/mcp/all），默认 all"),
                                "workflowId", Map.of("type", "string",
                                        "description", "按工作流 ID 过滤实例（仅 scope=workflow 时有效）")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(this::executeRuntime)
                .build();
    }

    /** 执行 system.runtime。 */
    private ToolResult executeRuntime(ToolInput input) {
        var scope = input.getOptionalParam("scope", String.class).orElse("all");
        var data = new LinkedHashMap<String, Object>();

        if ("all".equalsIgnoreCase(scope) || "workflow".equalsIgnoreCase(scope)) {
            data.put("workflows", collectWorkflowRuntime(input));
        }
        if ("all".equalsIgnoreCase(scope) || "mcp".equalsIgnoreCase(scope)) {
            data.put("mcpServers", collectMcpRuntime());
        }

        return ToolResult.success(Map.copyOf(data));
    }

    /** 收集工作流运行时信息。 */
    private Map<String, Object> collectWorkflowRuntime(ToolInput input) {
        var result = new LinkedHashMap<String, Object>();

        if (workflowRepository == null) {
            result.put("available", false);
            result.put("reason", "WorkflowRepository 未注入");
            return Map.copyOf(result);
        }

        var workflowId = input.getOptionalParam("workflowId", String.class).orElse(null);

        // 查询活跃实例（RUNNING / PAUSED / WAITING / CREATED）
        List<WorkflowInstance> activeInstances;
        if (workflowId != null && !workflowId.isBlank()) {
            activeInstances = workflowRepository.findInstancesByWorkflowId(workflowId).stream()
                    .filter(i -> i.state() == WorkflowState.RUNNING
                            || i.state() == WorkflowState.PAUSED
                            || i.state() == WorkflowState.WAITING
                            || i.state() == WorkflowState.CREATED)
                    .toList();
        } else {
            activeInstances = workflowRepository.findInstancesByState(
                    WorkflowState.RUNNING, WorkflowState.PAUSED,
                    WorkflowState.WAITING, WorkflowState.CREATED);
        }

        result.put("activeCount", activeInstances.size());
        result.put("instances", activeInstances.stream()
                .map(this::formatWorkflowInstance)
                .toList());

        return Map.copyOf(result);
    }

    /** 格式化单个工作流实例。 */
    private Map<String, Object> formatWorkflowInstance(WorkflowInstance instance) {
        var data = new LinkedHashMap<String, Object>();
        data.put("instanceId", instance.id());
        data.put("workflowId", instance.workflowId());
        data.put("state", instance.state().name());
        data.put("completedSteps", instance.completedStepIds() != null
                ? instance.completedStepIds().size() : 0);

        // 查找工作流定义名称
        workflowRegistry.find(instance.workflowId())
                .ifPresent(def -> data.put("workflowName", def.name()));

        if (instance.startedAt() != null) {
            data.put("startedAt", instance.startedAt().toString());
        }
        if (instance.blockedStepId() != null) {
            data.put("blockedStepId", instance.blockedStepId());
            data.put("blockedReason", instance.blockedReason());
        }
        if (instance.pendingApprovalStepId() != null) {
            data.put("pendingApprovalStepId", instance.pendingApprovalStepId());
        }
        if (instance.failureReason() != null) {
            data.put("failureReason", instance.failureReason());
        }
        return Map.copyOf(data);
    }

    /** 收集 MCP Server 运行时信息。 */
    private Map<String, Object> collectMcpRuntime() {
        var result = new LinkedHashMap<String, Object>();

        if (mcpServerRegistry == null) {
            result.put("available", false);
            result.put("reason", "McpServerRegistry 未注入");
            return Map.copyOf(result);
        }

        var servers = mcpServerRegistry.listServers();
        result.put("serverCount", servers.size());
        result.put("servers", servers.stream()
                .map(entry -> {
                    var data = new LinkedHashMap<String, Object>();
                    data.put("name", entry.config().name());
                    data.put("state", entry.state().name());
                    data.put("available", entry.state().isAvailable());
                    if (entry.connectedSince() != null) {
                        data.put("connectedSince", entry.connectedSince().toString());
                    }
                    if (entry.lastHealthCheck() != null) {
                        data.put("lastHealthCheck", entry.lastHealthCheck().toString());
                    }
                    if (entry.lastError() != null) {
                        data.put("lastError", entry.lastError());
                    }
                    data.put("reconnectAttempts", entry.reconnectAttempts());
                    return Map.<String, Object>copyOf(data);
                })
                .toList());

        return Map.copyOf(result);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 格式化能力列表为 Map 列表。 */
    private List<Map<String, Object>> formatCapabilityList(List<CapabilityInfo> capabilities) {
        return capabilities.stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.id(),
                        "name", c.name(),
                        "description", c.description(),
                        "source", c.source(),
                        "status", c.status()
                ))
                .toList();
    }

    /** 将 CapabilityInfo 转为 Map（含匹配方式）。 */
    private Map<String, Object> capabilityToMap(CapabilityInfo cap, String matchType) {
        return Map.of(
                "id", cap.id(),
                "name", cap.name(),
                "description", cap.description(),
                "type", cap.type(),
                "matchType", matchType
        );
    }
}

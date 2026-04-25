package com.lifepilot.meta.convenience;

import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.workflow.model.WorkflowInstance;
import com.lifepilot.workflow.model.WorkflowState;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统自省工具提供者 — 注册系统状态工具到 DynamicToolRegistry。
 *
 * <p>提供 {@code system.status} 工具，聚合系统状态概览，包括各注册中心计数、
 * 工具层分布、运行时工作流实例和 MCP Server 连接状态。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class IntrospectionToolProvider {

    private static final Logger log = LoggerFactory.getLogger(IntrospectionToolProvider.class);

    private final CapabilityAggregator aggregator;
    private final DynamicToolRegistry toolRegistry;
    private final WorkflowRegistry workflowRegistry;
    @Nullable private final WorkflowRepository workflowRepository;
    @Nullable private final McpServerRegistry mcpServerRegistry;

    public IntrospectionToolProvider(CapabilityAggregator aggregator,
                                      DynamicToolRegistry toolRegistry,
                                      WorkflowRegistry workflowRegistry,
                                      @Nullable WorkflowRepository workflowRepository,
                                      @Nullable McpServerRegistry mcpServerRegistry) {
        this.aggregator = aggregator;
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
        var tool = buildStatusTool();
        registry.registerBuiltinTool(tool);
        log.info("系统自省工具注册完成: count=1");
    }

    // ─────────────────────────────────────────────
    //  system.status
    // ─────────────────────────────────────────────

    /** 构建系统状态工具。 */
    private BuiltinTool buildStatusTool() {
        return BuiltinTool.builder()
                .id("system.status")
                .name("查看系统状态")
                .description("查看系统状态：查询当前运行状态、版本、运行时长、健康指标。")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .tags(List.of("系统", "状态", "健康", "运行", "查询", "system", "status", "health"))
                .executor(this::executeStatus)
                .build();
    }

    /** 执行 system.status — 聚合能力计数、工具层分布、运行时工作流实例和 MCP 连接状态。 */
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

        // 工具层分布
        var layerDistribution = new LinkedHashMap<String, Object>();
        toolCountByLayer.forEach((layer, count) ->
                layerDistribution.put(layer.name(), count));
        data.put("toolLayerDistribution", Map.copyOf(layerDistribution));

        // JVM 运行时信息
        var runtime = Runtime.getRuntime();
        data.put("jvmMemoryUsedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        data.put("jvmMemoryMaxMB", runtime.maxMemory() / (1024 * 1024));

        // 运行时工作流实例信息
        data.put("workflows", collectWorkflowRuntime());

        // MCP Server 连接状态
        data.put("mcpServers", collectMcpRuntime());

        return ToolResult.success(Map.copyOf(data));
    }

    // ─────────────────────────────────────────────
    //  运行时信息收集
    // ─────────────────────────────────────────────

    /** 收集工作流运行时信息。 */
    private Map<String, Object> collectWorkflowRuntime() {
        var result = new LinkedHashMap<String, Object>();

        if (workflowRepository == null) {
            result.put("available", false);
            result.put("reason", "WorkflowRepository 未注入");
            return Map.copyOf(result);
        }

        // 查询活跃实例（RUNNING / PAUSED / WAITING / CREATED）
        var activeInstances = workflowRepository.findInstancesByState(
                WorkflowState.RUNNING, WorkflowState.PAUSED,
                WorkflowState.WAITING, WorkflowState.CREATED);

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
}

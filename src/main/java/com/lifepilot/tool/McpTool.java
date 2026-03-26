package com.lifepilot.tool;

import com.lifepilot.mcp.adapter.McpToolExecutor;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.*;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 外部工具（Layer 1）— 通过 McpToolExecutor 执行工具调用。
 *
 * <p>通过 MCP 协议与外部 MCP Server 通信。
 * 执行时委托给 {@link McpToolExecutor}，由其通过 McpServerRegistry
 * 查找 McpClient 并完成调用。</p>
 *
 * @param id 工具唯一标识（格式：mcp.{serverName}.{toolName}）
 * @param name 工具显示名称
 * @param description 工具描述
 * @param inputSchema 输入参数 JSON Schema
 * @param outputSchema 输出类型 JSON Schema
 * @param riskLevel 风险等级
 * @param idempotent 是否幂等
 * @param executionSemantics 执行语义
 * @param budget 执行预算
 * @param tags 工具标签
 * @param serverName MCP 服务器名称
 * @param mcpToolName MCP 工具原始名称
 * @param clientId MCP 客户端标识（即 serverName，用于运行时查找 McpClient）
 * @author zsg
 * @since 2026-02-24
 */
public record McpTool(
        String id,
        String name,
        String description,
        JsonSchema inputSchema,
        JsonSchema outputSchema,
        RiskLevel riskLevel,
        boolean idempotent,
        ToolExecutionSemantics executionSemantics,
        ToolBudget budget,
        List<String> tags,
        String serverName,
        String mcpToolName,
        String clientId
) implements ToolContract {

    private static final Logger log = LoggerFactory.getLogger(McpTool.class);

    /** 全局 McpToolExecutor 引用，启动时由 McpAutoConfiguration 注入。 */
    private static final AtomicReference<McpToolExecutor> EXECUTOR_REF = new AtomicReference<>();

    /**
     * 设置全局 McpToolExecutor 引用。
     *
     * @param executor McpToolExecutor 实例
     */
    public static void setMcpToolExecutor(McpToolExecutor executor) {
        EXECUTOR_REF.set(executor);
        log.info("McpToolExecutor 已注入 McpTool");
    }

    @Override
    public ToolLayer layer() {
        return ToolLayer.MCP_EXTERNAL;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        McpToolExecutor executor = EXECUTOR_REF.get();
        if (executor == null) {
            log.error("McpToolExecutor 未初始化, clientId={}", clientId);
            return ToolResult.error("McpToolExecutor 未初始化");
        }
        return executor.execute(this, input);
    }
}

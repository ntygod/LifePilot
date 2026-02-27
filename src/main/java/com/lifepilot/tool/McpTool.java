package com.lifepilot.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;

import java.util.List;

/**
 * MCP 外部工具（Layer 1）。
 *
 * <p>通过 MCP 协议与外部 MCP Server 通信。
 * 使用 {@link com.lifepilot.mcp.McpClient} 执行实际的工具调用。</p>
 *
 * @param id 工具唯一标识（格式：mcp.{serverName}.{toolName}）
 * @param name 工具显示名称
 * @param description 工具描述
 * @param inputSchema 输入参数 JSON Schema
 * @param outputSchema 输出类型 JSON Schema
 * @param riskLevel 风险等级
 * @param idempotent 是否幂等
 * @param budget 执行预算
 * @param tags 工具标签
 * @param serverName MCP 服务器名称
 * @param mcpToolName MCP 工具原始名称
 * @param client MCP 客户端实例
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
        ToolBudget budget,
        List<String> tags,
        String serverName,
        String mcpToolName,
        com.lifepilot.mcp.McpClient client
) implements ToolContract {

    @Override
    public ToolLayer layer() {
        return ToolLayer.MCP_EXTERNAL;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        try {
            var mcpResult = client.callTool(mcpToolName, input.parameters()).join();

            // 将 MCP 结果转换为 ToolResult
            var meta = ToolResultMeta.builder()
                    .toolId(id)
                    .action("callTool")
                    .duration(java.time.Duration.ZERO)
                    .tokensUsed(0)
                    .cacheHit(false)
                    .retryCount(0)
                    .executorType("MCP")
                    .mcpServerName(serverName)
                    .timestamp(java.time.Instant.now())
                    .build();

            if (mcpResult.isError()) {
                String errorMsg = mcpResult.content().stream()
                        .filter(c -> "text".equals(c.type()))
                        .map(com.lifepilot.mcp.model.McpContent::text)
                        .findFirst()
                        .orElse("MCP 工具调用失败");
                return ToolResult.error(errorMsg, meta);
            }

            // 提取文本内容
            String text = mcpResult.content().stream()
                    .filter(c -> "text".equals(c.type()))
                    .map(com.lifepilot.mcp.model.McpContent::text)
                    .reduce("", (a, b) -> a + b);

            return ToolResult.success(java.util.Map.of("result", text), meta);

        } catch (Exception e) {
            var meta = ToolResultMeta.builder()
                    .toolId(id)
                    .action("callTool")
                    .duration(java.time.Duration.ZERO)
                    .tokensUsed(0)
                    .cacheHit(false)
                    .retryCount(0)
                    .executorType("MCP")
                    .mcpServerName(serverName)
                    .timestamp(java.time.Instant.now())
                    .build();
            return ToolResult.error("MCP 工具调用异常: " + e.getMessage(), meta);
        }
    }
}

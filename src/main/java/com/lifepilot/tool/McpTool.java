package com.lifepilot.tool;

import com.lifepilot.tool.model.*;
import com.lifepilot.tool.schema.JsonSchema;

import java.util.List;

/**
 * MCP 外部工具（Layer 1）— 骨架实现。
 *
 * <p>通过 MCP 协议与外部 MCP Server 通信。
 * 本 spec 仅定义骨架，execute() 抛出 UnsupportedOperationException，
 * 在后续 MCP spec 中实现。</p>
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
        String mcpToolName
) implements ToolContract {

    @Override
    public ToolLayer layer() {
        return ToolLayer.MCP_EXTERNAL;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        throw new UnsupportedOperationException("MCP 工具执行尚未实现");
    }
}

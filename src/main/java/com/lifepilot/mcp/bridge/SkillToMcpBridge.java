package com.lifepilot.mcp.bridge;

import com.lifepilot.mcp.model.McpContent;
import com.lifepilot.mcp.model.McpToolAnnotations;
import com.lifepilot.mcp.model.McpToolResult;
import com.lifepilot.mcp.model.McpToolSchema;
import com.lifepilot.mcp.protocol.McpJsonSupport;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ValidationResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * ZhiWei → MCP Server 反向桥接。
 *
 * <p>将 ZhiWei 的内置工具暴露为 MCP 工具，
 * 使外部 AI 助手（如 Claude Desktop、Cursor）可以通过
 * MCP 协议调用 ZhiWei 的能力。</p>
 *
 * <p>安全约束：仅导出 exportable=true 的工具。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class SkillToMcpBridge {

    private static final Logger log = LoggerFactory.getLogger(SkillToMcpBridge.class);

    private final DynamicToolRegistry toolRegistry;

    public SkillToMcpBridge(DynamicToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 获取可导出的工具列表（响应 tools/list 请求）。
     *
     * @return MCP 工具 Schema 列表
     */
    public List<McpToolSchema> listExportableTools() {
        List<ToolContract> exportable = toolRegistry.getAllTools().stream()
                .filter(ToolContract::exportable)
                .toList();

        log.info("可导出工具列表: count={}", exportable.size());

        return exportable.stream()
                .map(this::toMcpToolSchema)
                .toList();
    }

    /**
     * 处理外部 MCP 工具调用（响应 tools/call 请求）。
     *
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return MCP 工具调用结果
     */
    public McpToolResult handleToolCall(String toolName, Map<String, Object> arguments) {
        log.info("外部 MCP 工具调用: tool={}", toolName);

        // 查找可导出的工具
        var toolOpt = toolRegistry.getAllTools().stream()
                .filter(ToolContract::exportable)
                .filter(t -> t.name().equals(toolName) || t.id().equals(toolName))
                .findFirst();

        if (toolOpt.isEmpty()) {
            log.warn("外部 MCP 调用的工具不存在或不可导出: tool={}", toolName);
            return errorResult("工具不存在或不可导出: " + toolName);
        }

        ToolContract tool = toolOpt.get();

        try {
            // 构建 ToolInput 并校验
            ToolInput input = new ToolInput(tool.id(), arguments, tool.inputSchema(), null, null);
            var validation = input.validate();

            if (!validation.isValid()) {
                String errorMsg = ((ValidationResult.Failed) validation).formatForLlm();
                return errorResult(errorMsg);
            }

            // 执行工具
            ToolResult result = tool.execute(input);

            if (result.ok()) {
                String content = serializeData(result.data());
                return new McpToolResult(
                        List.of(new McpContent("text", content, null, null)),
                        false);
            } else {
                return errorResult(result.error());
            }

        } catch (Exception e) {
            log.error("外部 MCP 工具调用异常: tool={}, error={}", toolName, e.getMessage());
            return errorResult("工具执行异常: " + e.getMessage());
        }
    }

    /**
     * 处理 MCP 初始化请求。
     *
     * @return 服务端能力和信息
     */
    public Map<String, Object> handleInitialize() {
        return Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", true)
                ),
                "serverInfo", Map.of(
                        "name", "ZhiWei",
                        "version", "1.0.0"
                )
        );
    }

    /**
     * 将 ToolContract 转换为 MCP Tool Schema。
     */
    private McpToolSchema toMcpToolSchema(ToolContract tool) {
        return new McpToolSchema(
                tool.name(),
                tool.description(),
                tool.inputSchema().toMap(),
                new McpToolAnnotations(
                        tool.name(),
                        tool.riskLevel() == RiskLevel.LOW,
                        tool.riskLevel().ordinal() >= RiskLevel.HIGH.ordinal(),
                        tool.idempotent(),
                        false // 内置工具不访问外部世界
                )
        );
    }

    /** 创建错误结果。 */
    private McpToolResult errorResult(String message) {
        return new McpToolResult(
                List.of(new McpContent("text", message, null, null)),
                true);
    }

    /** 序列化数据为字符串。 */
    private String serializeData(Map<String, Object> data) {
        try {
            return McpJsonSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (Exception e) {
            return data.toString();
        }
    }
}

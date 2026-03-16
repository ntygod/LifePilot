package com.lifepilot.mcp.adapter;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.model.McpToolAnnotations;
import com.lifepilot.mcp.model.McpToolSchema;
import com.lifepilot.tool.McpTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具适配器 — 将 MCP 工具 Schema 转换为 ToolContract。
 *
 * <p>转换规则：
 * <ul>
 *   <li>工具 ID：mcp.{serverName}.{toolName}</li>
 *   <li>输入 Schema：直接映射 MCP 的 inputSchema</li>
 *   <li>输出 Schema：MCP 不定义输出 Schema，使用通用空 Schema</li>
 *   <li>风险等级：从 MCP annotations 推断，默认 MEDIUM</li>
 *   <li>幂等性：从 annotations.idempotentHint 推断，默认 false</li>
 *   <li>预算：MCP 工具默认 60 秒超时，2 次重试</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class McpToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    /** MCP 工具通用输出 Schema。 */
    private static final JsonSchema MCP_OUTPUT_SCHEMA = JsonSchema.empty();

    /**
     * 批量转换 MCP 工具 Schema 为 ToolContract。
     *
     * <p>单个工具转换失败不影响其他工具。
     * clientId 使用 serverName，运行时通过 McpServerRegistry 查找 McpClient。</p>
     *
     * @param serverName MCP 服务器名称
     * @param schemas MCP 工具 Schema 列表
     * @param client MCP 客户端实例（仅用于兼容签名，不再持有引用）
     * @return ToolContract 列表
     */
    public List<ToolContract> toToolContracts(
            String serverName,
            List<McpToolSchema> schemas,
            McpClient client) {
        var tools = new ArrayList<ToolContract>();

        for (McpToolSchema schema : schemas) {
            try {
                ToolContract tool = toToolContract(serverName, schema);
                tools.add(tool);
                log.debug("MCP 工具转换成功: server={}, tool={}, risk={}",
                        serverName, schema.name(), tool.riskLevel());
            } catch (Exception e) {
                log.warn("MCP 工具转换失败: server={}, error={}",
                        serverName, e.getMessage());
            }
        }

        return List.copyOf(tools);
    }

    /**
     * 转换单个 MCP 工具 Schema 为 ToolContract。
     *
     * @param serverName MCP 服务器名称（同时作为 clientId）
     * @param schema MCP 工具 Schema
     * @return McpTool 实例
     */
    public ToolContract toToolContract(
            String serverName,
            McpToolSchema schema) {

        String toolId = generateToolId(serverName, schema.name());
        RiskLevel riskLevel = inferRiskLevel(schema);
        boolean idempotent = inferIdempotency(schema);
        ToolBudget budget = assignBudget(riskLevel);
        List<String> tags = generateTags(serverName, schema);

        JsonSchema inputSchema = schema.inputSchema() != null
                ? JsonSchema.of(schema.inputSchema())
                : JsonSchema.empty();

        return new McpTool(
                toolId,
                schema.name(),
                schema.description() != null ? schema.description() : "MCP 工具: " + schema.name(),
                inputSchema,
                MCP_OUTPUT_SCHEMA,
                riskLevel,
                idempotent,
                budget,
                tags,
                serverName,
                schema.name(),
                serverName
        );
    }

    /**
     * 生成工具 ID（格式：mcp.{serverName}.{toolName}）。
     */
    String generateToolId(String serverName, String toolName) {
        return "mcp.%s.%s".formatted(serverName, toolName);
    }

    /**
     * 从 MCP annotations 推断风险等级。
     *
     * <p>推断规则：
     * <ol>
     *   <li>readOnlyHint=true → LOW</li>
     *   <li>destructiveHint=true 且 openWorldHint=true → CRITICAL</li>
     *   <li>destructiveHint=true → HIGH</li>
     *   <li>其他 → MEDIUM（默认）</li>
     * </ol></p>
     */
    RiskLevel inferRiskLevel(McpToolSchema schema) {
        McpToolAnnotations annotations = schema.annotations();
        if (annotations == null) {
            return RiskLevel.MEDIUM;
        }

        boolean readOnly = Boolean.TRUE.equals(annotations.readOnlyHint());
        boolean destructive = Boolean.TRUE.equals(annotations.destructiveHint());
        boolean openWorld = Boolean.TRUE.equals(annotations.openWorldHint());

        if (readOnly) {
            return RiskLevel.LOW;
        }
        if (destructive && openWorld) {
            return RiskLevel.CRITICAL;
        }
        if (destructive) {
            return RiskLevel.HIGH;
        }
        return RiskLevel.MEDIUM;
    }

    /** 从 annotations 推断幂等性。 */
    private boolean inferIdempotency(McpToolSchema schema) {
        if (schema.annotations() == null) return false;
        return Boolean.TRUE.equals(schema.annotations().idempotentHint());
    }

    /** 根据风险等级分配预算。 */
    private ToolBudget assignBudget(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW, MEDIUM -> ToolBudget.MCP_DEFAULT;
            case HIGH -> ToolBudget.of(Duration.ofSeconds(30), 1, Integer.MAX_VALUE);
            case CRITICAL -> ToolBudget.of(Duration.ofSeconds(15), 0, Integer.MAX_VALUE);
        };
    }

    /** 生成工具标签。 */
    private List<String> generateTags(String serverName, McpToolSchema schema) {
        var tags = new ArrayList<String>();
        tags.add("mcp");
        tags.add("mcp:" + serverName);

        if (schema.annotations() != null) {
            if (Boolean.TRUE.equals(schema.annotations().readOnlyHint())) {
                tags.add("read-only");
            }
            if (Boolean.TRUE.equals(schema.annotations().destructiveHint())) {
                tags.add("destructive");
            }
        }

        return List.copyOf(tags);
    }
}

package com.lifepilot.tool;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.*;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 外部工具（Layer 1）— 通过 clientId 延迟查找 McpClient。
 *
 * <p>通过 MCP 协议与外部 MCP Server 通信。
 * 执行时通过 {@link McpServerRegistry#getClient(String)} 查找
 * McpClient，避免持有可变引用导致序列化和值语义问题。</p>
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
        ToolBudget budget,
        List<String> tags,
        String serverName,
        String mcpToolName,
        String clientId
) implements ToolContract {

    private static final Logger log = LoggerFactory.getLogger(McpTool.class);

    /** 全局 McpServerRegistry 引用，启动时由 ToolAutoConfiguration 注入。 */
    private static final AtomicReference<McpServerRegistry> REGISTRY_REF = new AtomicReference<>();

    /**
     * 设置全局 McpServerRegistry 引用。
     *
     * @param registry McpServerRegistry 实例
     */
    public static void setMcpServerRegistry(McpServerRegistry registry) {
        REGISTRY_REF.set(registry);
        log.info("McpServerRegistry 已注入 McpTool");
    }

    @Override
    public ToolLayer layer() {
        return ToolLayer.MCP_EXTERNAL;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        McpServerRegistry registry = REGISTRY_REF.get();
        if (registry == null) {
            log.error("McpServerRegistry 未初始化, clientId={}", clientId);
            return ToolResult.error("McpServerRegistry 未初始化");
        }

        // 通过 clientId 查找 McpClient
        Optional<McpClient> clientOpt = registry.getClient(clientId);
        if (clientOpt.isEmpty()) {
            log.warn("MCP 客户端不存在或已断开: clientId={}", clientId);
            return ToolResult.error("MCP 客户端不存在或已断开: " + clientId);
        }

        McpClient client = clientOpt.get();
        try {
            var mcpResult = client.callTool(mcpToolName, input.parameters()).join();

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
                        .map(content -> Optional.ofNullable(content.text()).orElse(""))
                        .findFirst()
                        .orElse("MCP 工具调用失败");
                return ToolResult.error(errorMsg, meta);
            }

            String text = mcpResult.content().stream()
                    .filter(c -> "text".equals(c.type()))
                    .map(content -> Optional.ofNullable(content.text()).orElse(""))
                    .reduce("", (a, b) -> a + b);

            return ToolResult.success(Map.of("result", text), meta);

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

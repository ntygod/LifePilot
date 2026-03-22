package com.lifepilot.mcp.adapter;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.tool.McpTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolResultMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 工具执行器 — 负责通过 McpServerRegistry 查找 McpClient 并执行 MCP 工具调用。
 *
 * <p>将执行逻辑从 McpTool record 中抽出，由 Spring 管理生命周期，
 * 避免 record 持有静态可变状态，提升可测试性和多实例兼容性。</p>
 *
 * @author zsg
 * @since 2026-03-22
 */
public class McpToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);

    private final McpServerRegistry registry;

    public McpToolExecutor(McpServerRegistry registry) {
        this.registry = registry;
    }

    /**
     * 执行 MCP 工具调用。
     *
     * @param tool MCP 工具定义
     * @param input 工具输入
     * @return 工具执行结果
     */
    public ToolResult execute(McpTool tool, ToolInput input) {
        Optional<McpClient> clientOpt = registry.getClient(tool.clientId());
        if (clientOpt.isEmpty()) {
            log.warn("MCP 客户端不存在或已断开: clientId={}", tool.clientId());
            return ToolResult.error("MCP 客户端不存在或已断开: " + tool.clientId());
        }

        McpClient client = clientOpt.get();
        Instant start = Instant.now();
        try {
            var mcpResult = client.callTool(tool.mcpToolName(), input.parameters()).join();

            var meta = buildMeta(tool, start);

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
            var meta = buildMeta(tool, start);
            return ToolResult.error("MCP 工具调用异常: " + e.getMessage(), meta);
        }
    }

    /** 构建执行元信息。 */
    private ToolResultMeta buildMeta(McpTool tool, Instant start) {
        return ToolResultMeta.builder()
                .toolId(tool.id())
                .action("callTool")
                .duration(Duration.between(start, Instant.now()))
                .tokensUsed(0)
                .cacheHit(false)
                .retryCount(0)
                .executorType("MCP")
                .mcpServerName(tool.serverName())
                .timestamp(Instant.now())
                .build();
    }
}

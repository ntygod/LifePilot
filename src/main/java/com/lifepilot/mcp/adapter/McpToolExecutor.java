package com.lifepilot.mcp.adapter;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.exception.McpServerUnavailableException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP 工具执行器 — 懒连接 + 超时保护。
 *
 * <p>通过 {@link McpServerRegistry#ensureConnected} 实现按需连接：
 * 首次调用某个 MCP Server 的工具时自动连接，后续调用直接复用。</p>
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
     * <p>流程：懒连接 → 记录调用时间 → 带超时执行 → 解析结果。</p>
     *
     * @param tool MCP 工具定义
     * @param input 工具输入
     * @return 工具执行结果
     */
    public ToolResult execute(McpTool tool, ToolInput input) {
        Instant start = Instant.now();
        try {
            // 懒连接：Server 未连接时同步连接
            McpClient client = registry.ensureConnected(tool.clientId());

            // 记录调用时间（空闲超时检测用）
            registry.recordToolCall(tool.clientId());

            // 获取超时配置
            Duration timeout = registry.getServer(tool.clientId())
                    .map(entry -> entry.config().timeout())
                    .orElse(McpServerConfig.DEFAULT_TIMEOUT);

            // 带超时的工具调用
            var mcpResult = client.callTool(tool.mcpToolName(), input.parameters())
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();

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

        } catch (McpServerUnavailableException e) {
            log.warn("MCP Server 不可用: clientId={}, error={}", tool.clientId(), e.getMessage());
            return ToolResult.error(e.getMessage(), buildMeta(tool, start));
        } catch (java.util.concurrent.CompletionException e) {
            var meta = buildMeta(tool, start);
            if (e.getCause() instanceof TimeoutException) {
                log.warn("MCP 工具调用超时: tool={}, server={}", tool.mcpToolName(), tool.serverName());
                return ToolResult.error("MCP 工具调用超时: " + tool.mcpToolName(), meta);
            }
            return ToolResult.error("MCP 工具调用异常: " + e.getMessage(), meta);
        } catch (Exception e) {
            return ToolResult.error("MCP 工具调用异常: " + e.getMessage(), buildMeta(tool, start));
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

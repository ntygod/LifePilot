package com.lifepilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.model.McpServerCapabilities;
import com.lifepilot.mcp.model.McpServerInfo;
import com.lifepilot.mcp.model.McpToolResult;
import com.lifepilot.mcp.model.McpToolSchema;
import com.lifepilot.mcp.transport.*;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 客户端 — 封装与单个 MCP Server 的完整通信。
 *
 * <p>生命周期：{@code new McpClient(config)} → {@link #initialize()} →
 * {@link #listTools()} / {@link #callTool} → {@link #shutdown()}。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** MCP 协议版本。 */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpServerConfig config;
    private final McpTransport transport;

    /** 服务端能力（初始化后填充）。 */
    @Nullable
    private McpServerCapabilities serverCapabilities;

    /** 服务端信息（初始化后填充）。 */
    @Nullable
    private McpServerInfo serverInfo;

    public McpClient(McpServerConfig config) {
        this.config = config;
        this.transport = createTransport(config);
    }

    /**
     * 内部构造 — 允许注入自定义 McpTransport（用于测试）。
     *
     * @param config 服务器配置
     * @param transport 传输实例
     */
    McpClient(McpServerConfig config, McpTransport transport) {
        this.config = config;
        this.transport = transport;
    }

    /**
     * 根据配置创建对应的传输实例。
     */
    private static McpTransport createTransport(McpServerConfig config) {
        return switch (config.transport()) {
            case STDIO -> new StdioTransport(config);
            case STREAMABLE_HTTP -> new StreamableHttpTransport(config);
            case SSE_LEGACY -> new SseTransport(config);
        };
    }

    /**
     * 初始化 MCP 连接。
     *
     * <p>完整流程：
     * <ol>
     *   <li>建立传输连接</li>
     *   <li>发送 initialize 请求（交换能力和协议版本）</li>
     *   <li>发送 notifications/initialized 通知</li>
     * </ol></p>
     *
     * @return 初始化完成的 Future
     */
    public CompletableFuture<Void> initialize() {
        log.info("MCP 客户端初始化开始: server={}, transport={}",
                config.name(), config.transport());

        return transport.connect()
                .thenCompose(v -> {
                    // 发送 initialize 请求
                    var initParams = Map.<String, Object>of(
                            "protocolVersion", PROTOCOL_VERSION,
                            "capabilities", Map.of(
                                    "tools", Map.of("listChanged", true)
                            ),
                            "clientInfo", Map.of(
                                    "name", "ZhiWei",
                                    "version", "1.0.0"
                            )
                    );
                    return transport.sendRequest("initialize", initParams);
                })
                .thenAccept(result -> {
                    // 解析服务端能力
                    this.serverCapabilities = parseCapabilities(result);
                    this.serverInfo = parseServerInfo(result);

                    // 发送 initialized 通知
                    transport.sendNotification("notifications/initialized", Map.of());

                    log.info("MCP 客户端初始化完成: server={}, capabilities={}",
                            config.name(), serverCapabilities);
                });
    }

    /**
     * 获取 MCP Server 提供的工具列表。
     *
     * @return 工具 Schema 列表
     */
    public CompletableFuture<List<McpToolSchema>> listTools() {
        log.debug("请求工具列表: server={}", config.name());

        return transport.sendRequest("tools/list", Map.of())
                .thenApply(result -> {
                    var tools = parseToolSchemas(result);
                    log.info("工具列表获取成功: server={}, count={}",
                            config.name(), tools.size());
                    return tools;
                });
    }

    /**
     * 调用 MCP 工具。
     *
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具调用结果
     */
    public CompletableFuture<McpToolResult> callTool(
            String toolName, Map<String, Object> arguments) {
        log.debug("MCP 工具调用: server={}, tool={}", config.name(), toolName);

        var params = Map.<String, Object>of(
                "name", toolName,
                "arguments", arguments
        );

        return transport.sendRequest("tools/call", params)
                .thenApply(result -> {
                    var toolResult = parseToolResult(result);
                    log.debug("MCP 工具调用完成: server={}, tool={}, isError={}",
                            config.name(), toolName, toolResult.isError());
                    return toolResult;
                });
    }

    /**
     * 关闭 MCP 连接。
     *
     * @return 关闭完成的 Future
     */
    public CompletableFuture<Void> shutdown() {
        log.info("MCP 客户端关闭: server={}", config.name());
        return transport.disconnect();
    }

    /** 获取服务器名称。 */
    public String getServerName() {
        return config.name();
    }

    /** 获取服务器配置。 */
    public McpServerConfig getConfig() {
        return config;
    }

    /** 检查连接是否活跃。 */
    public boolean isConnected() {
        return transport.isConnected();
    }

    /** 获取传输类型。 */
    public TransportType getTransportType() {
        return transport.transportType();
    }

    /** 获取服务端能力（初始化后可用）。 */
    @Nullable
    public McpServerCapabilities getServerCapabilities() {
        return serverCapabilities;
    }

    /** 获取服务端信息（初始化后可用）。 */
    @Nullable
    public McpServerInfo getServerInfo() {
        return serverInfo;
    }

    // ─────────────────────────────────────────────
    //  解析方法
    // ─────────────────────────────────────────────

    private McpServerCapabilities parseCapabilities(JsonNode result) {
        try {
            JsonNode caps = result.get("capabilities");
            boolean supportsTools = caps != null && caps.has("tools");
            boolean supportsResources = caps != null && caps.has("resources");
            boolean supportsPrompts = caps != null && caps.has("prompts");
            return new McpServerCapabilities(supportsTools, supportsResources, supportsPrompts);
        } catch (Exception e) {
            log.warn("服务端能力解析失败: server={}", config.name());
            return new McpServerCapabilities(true, false, false);
        }
    }

    private McpServerInfo parseServerInfo(JsonNode result) {
        try {
            JsonNode info = result.get("serverInfo");
            String name = info != null ? info.get("name").asText("unknown") : "unknown";
            String version = info != null ? info.get("version").asText("unknown") : "unknown";
            return new McpServerInfo(name, version);
        } catch (Exception e) {
            return new McpServerInfo("unknown", "unknown");
        }
    }

    private List<McpToolSchema> parseToolSchemas(JsonNode result) {
        try {
            JsonNode toolsNode = result.get("tools");
            if (toolsNode == null || !toolsNode.isArray()) {
                return List.of();
            }
            return MAPPER.readerForListOf(McpToolSchema.class).readValue(toolsNode);
        } catch (Exception e) {
            log.warn("工具 Schema 解析失败: server={}, error={}",
                    config.name(), e.getMessage());
            return List.of();
        }
    }

    private McpToolResult parseToolResult(JsonNode result) {
        try {
            return MAPPER.treeToValue(result, McpToolResult.class);
        } catch (Exception e) {
            return new McpToolResult(List.of(), true);
        }
    }
}

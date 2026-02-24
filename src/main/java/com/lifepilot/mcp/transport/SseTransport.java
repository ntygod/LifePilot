package com.lifepilot.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.mcp.config.McpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SSE 传输实现（已弃用，仅兼容旧版 MCP Server）。
 *
 * <p>SSE 传输在 MCP 规范 2025-03-26 版本中被 Streamable HTTP 替代。
 * 新部署应使用 {@link StreamableHttpTransport}。</p>
 *
 * @author zsg
 * @since 2026-02-24
 * @deprecated 自 MCP 规范 2025-03-26 起弃用，使用 StreamableHttpTransport 替代
 */
@Deprecated(since = "2025-03-26", forRemoval = false)
public final class SseTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(SseTransport.class);

    private final McpServerConfig config;
    private volatile boolean connected = false;

    public SseTransport(McpServerConfig config) {
        this.config = config;
        log.warn("SSE 传输已弃用，建议迁移到 Streamable HTTP: server={}", config.name());
    }

    @Override
    public CompletableFuture<Void> connect() {
        connected = true;
        log.info("SSE 传输连接建立（已弃用）: server={}, url={}",
                config.name(), config.url());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<JsonNode> sendRequest(String method, Map<String, Object> params) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("SSE 传输已弃用，请使用 Streamable HTTP"));
    }

    @Override
    public void sendNotification(String method, Map<String, Object> params) {
        log.debug("SSE 通知发送（已弃用）: method={}, server={}", method, config.name());
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        connected = false;
        log.info("SSE 传输已断开: server={}", config.name());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public TransportType transportType() {
        return TransportType.SSE_LEGACY;
    }
}

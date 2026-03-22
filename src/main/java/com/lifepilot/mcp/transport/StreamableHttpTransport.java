package com.lifepilot.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.exception.McpToolCallException;
import com.lifepilot.mcp.exception.McpTransportException;
import com.lifepilot.mcp.protocol.JsonRpcMessage;
import com.lifepilot.mcp.protocol.McpJsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streamable HTTP 传输实现。
 *
 * <p>自 MCP 规范 2025-03-26 起推荐的远程传输方式。
 * 使用标准 HTTP POST 发送 JSON-RPC 请求。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public final class StreamableHttpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StreamableHttpTransport.class);

    /** 共享的 Virtual Thread Executor，避免每次调用创建新实例。 */
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final McpServerConfig config;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong requestIdCounter = new AtomicLong(0);

    private HttpClient httpClient;
    private String baseUrl;
    private volatile String sessionId;

    public StreamableHttpTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            this.baseUrl = config.url();
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .executor(VIRTUAL_EXECUTOR)
                    .build();

            connected.set(true);
            log.info("Streamable HTTP 传输就绪: server={}, url={}",
                    config.name(), baseUrl);
        }, VIRTUAL_EXECUTOR);
    }

    @Override
    public CompletableFuture<JsonNode> sendRequest(String method, Map<String, Object> params) {
        if (!connected.get()) {
            return CompletableFuture.failedFuture(
                    new McpTransportException("传输未连接: " + config.name()));
        }

        long id = requestIdCounter.incrementAndGet();
        var message = JsonRpcMessage.request(id, method, params);

        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = McpJsonSupport.MAPPER.writeValueAsString(message);

                var requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(config.timeout() != null
                                ? config.timeout()
                                : Duration.ofSeconds(60));

                // 携带会话 ID
                if (sessionId != null) {
                    requestBuilder.header("Mcp-Session-Id", sessionId);
                }

                var response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                // 提取会话 ID
                response.headers().firstValue("Mcp-Session-Id")
                        .ifPresent(sid -> this.sessionId = sid);

                if (response.statusCode() != 200) {
                    throw new McpTransportException(
                            "HTTP 请求失败: status=%d, server=%s"
                                    .formatted(response.statusCode(), config.name()));
                }

                // 根据 Content-Type 分支处理 JSON 和 SSE 响应
                String contentType = response.headers()
                        .firstValue("Content-Type").orElse("application/json");
                String body = response.body();

                JsonNode responseNode;
                if (contentType.contains("text/event-stream")) {
                    responseNode = parseSseResponse(body);
                } else {
                    responseNode = McpJsonSupport.MAPPER.readTree(body);
                }

                if (responseNode.has("error")) {
                    String errorMsg = responseNode.get("error").get("message").asText();
                    throw new McpToolCallException(errorMsg);
                }

                log.debug("Streamable HTTP 请求完成: id={}, method={}, server={}",
                        id, method, config.name());

                return responseNode.get("result");

            } catch (McpTransportException | McpToolCallException e) {
                throw new CompletionException(e);
            } catch (Exception e) {
                throw new CompletionException(
                        new McpTransportException("HTTP 请求异常: " + method, e));
            }
        }, VIRTUAL_EXECUTOR);
    }

    @Override
    public void sendNotification(String method, Map<String, Object> params) {
        if (!connected.get()) return;

        CompletableFuture.runAsync(() -> {
            try {
                var message = JsonRpcMessage.notification(method, params);
                String json = McpJsonSupport.MAPPER.writeValueAsString(message);

                var requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));

                if (sessionId != null) {
                    requestBuilder.header("Mcp-Session-Id", sessionId);
                }

                httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());

            } catch (Exception e) {
                log.warn("通知发送失败: method={}, server={}, error={}",
                        method, config.name(), e.getMessage());
            }
        }, VIRTUAL_EXECUTOR);
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        connected.set(false);
        sessionId = null;
        log.info("Streamable HTTP 传输已断开: server={}", config.name());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public TransportType transportType() {
        return TransportType.STREAMABLE_HTTP;
    }

    /**
     * 解析 SSE 格式的响应体，提取最后一个 JSON-RPC 消息。
     *
     * <p>SSE 格式为 {@code event: message\ndata: {json}\n\n}，
     * 取最后一个 data 行作为最终响应（MCP 规范中 SSE 流的最后一条消息为最终结果）。</p>
     *
     * @param body SSE 响应体
     * @return 解析后的 JSON-RPC 响应节点
     */
    private JsonNode parseSseResponse(String body) throws Exception {
        JsonNode lastMessage = null;
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                String data = trimmed.substring(5).trim();
                if (!data.isEmpty() && !data.equals("[DONE]")) {
                    lastMessage = McpJsonSupport.MAPPER.readTree(data);
                }
            }
        }
        if (lastMessage == null) {
            throw new McpTransportException("SSE 响应中未找到有效的 JSON-RPC 消息: server=" + config.name());
        }
        return lastMessage;
    }
}

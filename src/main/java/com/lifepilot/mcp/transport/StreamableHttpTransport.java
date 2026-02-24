package com.lifepilot.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.exception.McpToolCallException;
import com.lifepilot.mcp.exception.McpTransportException;
import com.lifepilot.mcp.protocol.JsonRpcMessage;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .build();

            connected.set(true);
            log.info("Streamable HTTP 传输就绪: server={}, url={}",
                    config.name(), baseUrl);
        }, Executors.newVirtualThreadPerTaskExecutor());
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
                String json = MAPPER.writeValueAsString(message);

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

                JsonNode responseNode = MAPPER.readTree(response.body());

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
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public void sendNotification(String method, Map<String, Object> params) {
        if (!connected.get()) return;

        CompletableFuture.runAsync(() -> {
            try {
                var message = JsonRpcMessage.notification(method, params);
                String json = MAPPER.writeValueAsString(message);

                var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            } catch (Exception e) {
                log.warn("通知发送失败: method={}, server={}, error={}",
                        method, config.name(), e.getMessage());
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
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
}

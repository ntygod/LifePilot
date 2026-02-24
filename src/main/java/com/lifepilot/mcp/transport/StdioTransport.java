package com.lifepilot.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.exception.McpToolCallException;
import com.lifepilot.mcp.exception.McpTransportException;
import com.lifepilot.mcp.protocol.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio 传输实现。
 *
 * <p>通过 ProcessBuilder 启动 MCP Server 子进程，
 * 使用标准输入（stdin）发送请求，标准输出（stdout）接收响应。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public final class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerConfig config;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong requestIdCounter = new AtomicLong(0);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pendingRequests
            = new ConcurrentHashMap<>();

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;

    public StdioTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("启动 MCP Server 子进程: command={}, args={}",
                        config.command(), config.args());

                var command = new ArrayList<String>();
                command.add(config.command());
                command.addAll(config.args());

                var pb = new ProcessBuilder(command);
                if (!config.env().isEmpty()) {
                    pb.environment().putAll(config.env());
                }
                pb.redirectErrorStream(false);
                process = pb.start();

                stdin = new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream()));
                stdout = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));

                // Virtual Thread 读取 stdout
                Thread.ofVirtual()
                        .name("mcp-stdio-reader-" + config.name())
                        .start(this::readLoop);

                // Virtual Thread 读取 stderr
                Thread.ofVirtual()
                        .name("mcp-stdio-stderr-" + config.name())
                        .start(this::stderrLoop);

                connected.set(true);
                log.info("MCP Server 子进程启动成功: name={}, pid={}",
                        config.name(), process.pid());

            } catch (IOException e) {
                throw new McpTransportException(
                        "MCP Server 子进程启动失败: " + config.name(), e);
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public CompletableFuture<JsonNode> sendRequest(String method, Map<String, Object> params) {
        if (!connected.get()) {
            return CompletableFuture.failedFuture(
                    new McpTransportException("传输未连接: " + config.name()));
        }

        long id = requestIdCounter.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        pendingRequests.put(id, future);

        try {
            var message = JsonRpcMessage.request(id, method, params);
            String json = MAPPER.writeValueAsString(message);

            synchronized (stdin) {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            }

            log.debug("JSON-RPC 请求已发送: id={}, method={}, server={}",
                    id, method, config.name());

        } catch (IOException e) {
            pendingRequests.remove(id);
            future.completeExceptionally(
                    new McpTransportException("请求发送失败: " + method, e));
        }

        return future;
    }

    @Override
    public void sendNotification(String method, Map<String, Object> params) {
        if (!connected.get()) {
            log.warn("传输未连接，通知丢弃: method={}, server={}", method, config.name());
            return;
        }

        try {
            var message = JsonRpcMessage.notification(method, params);
            String json = MAPPER.writeValueAsString(message);

            synchronized (stdin) {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            }

            log.debug("JSON-RPC 通知已发送: method={}, server={}", method, config.name());

        } catch (IOException e) {
            log.warn("通知发送失败: method={}, server={}, error={}",
                    method, config.name(), e.getMessage());
        }
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        return CompletableFuture.runAsync(() -> {
            connected.set(false);

            // 完成所有待处理的请求
            pendingRequests.forEach((id, future) ->
                    future.completeExceptionally(
                            new McpTransportException("传输已断开")));
            pendingRequests.clear();

            // 关闭管道
            try {
                if (stdin != null) stdin.close();
                if (stdout != null) stdout.close();
            } catch (IOException e) {
                log.warn("管道关闭异常: server={}, error={}", config.name(), e.getMessage());
            }

            // 销毁子进程
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    boolean exited = process.waitFor(5, TimeUnit.SECONDS);
                    if (!exited) {
                        log.warn("MCP Server 子进程未在 5 秒内退出，强制终止: name={}",
                                config.name());
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }

            log.info("MCP Server 连接已断开: name={}", config.name());
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public boolean isConnected() {
        return connected.get() && process != null && process.isAlive();
    }

    @Override
    public TransportType transportType() {
        return TransportType.STDIO;
    }

    /** stdout 读取循环（在 Virtual Thread 中运行）。 */
    private void readLoop() {
        try {
            String line;
            while (connected.get() && (line = stdout.readLine()) != null) {
                try {
                    JsonNode node = MAPPER.readTree(line);

                    if (node.has("id") && node.has("result")) {
                        // JSON-RPC 响应
                        long id = node.get("id").asLong();
                        var future = pendingRequests.remove(id);
                        if (future != null) {
                            future.complete(node.get("result"));
                        } else {
                            log.warn("收到未知请求 ID 的响应: id={}, server={}",
                                    id, config.name());
                        }
                    } else if (node.has("id") && node.has("error")) {
                        // JSON-RPC 错误响应
                        long id = node.get("id").asLong();
                        var future = pendingRequests.remove(id);
                        if (future != null) {
                            String errorMsg = node.get("error").get("message").asText();
                            future.completeExceptionally(new McpToolCallException(errorMsg));
                        }
                    } else if (node.has("method") && !node.has("id")) {
                        // JSON-RPC 通知（来自 Server）
                        String method = node.get("method").asText();
                        log.debug("收到 Server 通知: method={}, server={}",
                                method, config.name());
                    }
                } catch (Exception e) {
                    log.warn("JSON-RPC 消息解析失败: server={}, error={}",
                            config.name(), e.getMessage());
                }
            }
        } catch (IOException e) {
            if (connected.get()) {
                log.error("stdout 读取异常: server={}, error={}",
                        config.name(), e.getMessage());
            }
        }
        log.debug("stdout 读取循环结束: server={}", config.name());
    }

    /** stderr 读取循环（仅记录日志）。 */
    private void stderrLoop() {
        try (var stderr = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = stderr.readLine()) != null) {
                log.debug("[MCP Server stderr] {}: {}", config.name(), line);
            }
        } catch (IOException e) {
            if (connected.get()) {
                log.warn("stderr 读取异常: server={}", config.name());
            }
        }
    }
}

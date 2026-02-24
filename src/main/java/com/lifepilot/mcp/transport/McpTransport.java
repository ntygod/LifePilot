package com.lifepilot.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 传输层抽象 — sealed interface 穷举所有传输类型。
 *
 * <p>支持的传输类型：
 * <ul>
 *   <li>{@link StdioTransport} — 标准输入/输出（本地子进程）</li>
 *   <li>{@link StreamableHttpTransport} — Streamable HTTP（推荐的远程传输）</li>
 *   <li>{@link SseTransport} — SSE（已弃用，仅兼容旧服务器）</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public sealed interface McpTransport
        permits StdioTransport, StreamableHttpTransport, SseTransport {

    /**
     * 建立连接。
     *
     * @return 连接完成的 Future
     */
    CompletableFuture<Void> connect();

    /**
     * 发送 JSON-RPC 请求并等待响应。
     *
     * @param method JSON-RPC 方法名
     * @param params 请求参数
     * @return 响应结果的 Future
     */
    CompletableFuture<JsonNode> sendRequest(String method, Map<String, Object> params);

    /**
     * 发送 JSON-RPC 通知（无需响应）。
     *
     * @param method JSON-RPC 方法名
     * @param params 通知参数
     */
    void sendNotification(String method, Map<String, Object> params);

    /**
     * 断开连接并释放资源。
     *
     * @return 断开完成的 Future
     */
    CompletableFuture<Void> disconnect();

    /** 检查连接是否活跃。 */
    boolean isConnected();

    /** 获取传输类型。 */
    TransportType transportType();
}

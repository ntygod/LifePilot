package com.lifepilot.mcp.config;

import com.lifepilot.mcp.transport.TransportType;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置。
 *
 * <p>从 application.yml 的 lifepilot.mcp.servers 配置项加载。</p>
 *
 * @param name 服务器名称（唯一标识）
 * @param transport 传输类型
 * @param command stdio 传输的启动命令
 * @param args stdio 传输的命令参数
 * @param url 远程传输的 URL
 * @param env 环境变量
 * @param timeout 请求超时时间
 * @param autoConnect 是否在应用启动时自动连接
 * @param reconnect 断开后是否自动重连
 * @param reconnectDelay 重连初始延迟
 * @param maxReconnectAttempts 最大重连次数
 * @param healthCheckInterval 健康检查间隔
 * @param idleTimeout 空闲超时时间（无工具调用后自动断开）
 * @author zsg
 * @since 2026-02-24
 */
@Builder(toBuilder = true)
public record McpServerConfig(
        String name,
        TransportType transport,
        @Nullable String command,
        @Nullable List<String> args,
        @Nullable String url,
        @Nullable Map<String, String> env,
        Duration timeout,
        boolean autoConnect,
        boolean reconnect,
        Duration reconnectDelay,
        int maxReconnectAttempts,
        Duration healthCheckInterval,
        Duration idleTimeout
) {

    /** 默认超时：60 秒。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** 默认重连延迟：500 毫秒。 */
    public static final Duration DEFAULT_RECONNECT_DELAY = Duration.ofMillis(500);

    /** 默认最大重连次数：5 次。 */
    public static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;

    /** 默认健康检查间隔：30 秒。 */
    public static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = Duration.ofSeconds(30);

    /** 默认空闲超时：10 分钟。 */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);

    /** 校验配置合法性并填充默认值。 */
    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP Server 名称不能为空");
        }
        if (transport == TransportType.STDIO && (command == null || command.isBlank())) {
            throw new IllegalArgumentException("stdio 传输必须指定 command: " + name);
        }
        if ((transport == TransportType.STREAMABLE_HTTP || transport == TransportType.SSE_LEGACY)
                && (url == null || url.isBlank())) {
            throw new IllegalArgumentException("远程传输必须指定 url: " + name);
        }
        if (timeout == null) timeout = DEFAULT_TIMEOUT;
        if (reconnectDelay == null) reconnectDelay = DEFAULT_RECONNECT_DELAY;
        if (maxReconnectAttempts <= 0) maxReconnectAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS;
        if (healthCheckInterval == null) healthCheckInterval = DEFAULT_HEALTH_CHECK_INTERVAL;
        if (idleTimeout == null) idleTimeout = DEFAULT_IDLE_TIMEOUT;
        if (args == null) args = List.of();
        if (env == null) env = Map.of();
    }
}

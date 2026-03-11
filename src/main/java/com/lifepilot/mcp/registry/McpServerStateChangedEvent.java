package com.lifepilot.mcp.registry;

import java.time.Instant;

import org.springframework.lang.Nullable;

/**
 * MCP Server 状态变化事件。
 *
 * <p>当 {@link McpServerRegistry} 中任一 Server 的连接状态发生变化时发布。
 * 通过 Spring {@link org.springframework.context.ApplicationEventPublisher} 传播，
 * 监听方通过 {@code @EventListener} 接收。</p>
 *
 * @param serverName 服务器名称
 * @param oldState   旧状态
 * @param newState   新状态
 * @param timestamp  状态变化时间戳
 * @param error      错误信息（可为空）
 * @author zsg
 * @since 2026-03-11
 */
public record McpServerStateChangedEvent(
        String serverName,
        McpServerState oldState,
        McpServerState newState,
        Instant timestamp,
        @Nullable String error
) {}

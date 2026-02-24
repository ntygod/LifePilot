package com.lifepilot.mcp.registry;

/**
 * MCP Server 连接状态枚举。
 *
 * @author zsg
 * @since 2026-02-24
 */
public enum McpServerState {

    /** 未连接。 */
    DISCONNECTED,

    /** 正在建立传输连接。 */
    CONNECTING,

    /** 传输已连接，正在执行 MCP 初始化握手。 */
    INITIALIZING,

    /** 已连接且初始化完成，可用。 */
    CONNECTED,

    /** 正在执行健康检查。 */
    HEALTH_CHECK,

    /** 连接断开，正在重连。 */
    RECONNECTING,

    /** 正在断开连接。 */
    DISCONNECTING;

    /**
     * 判断当前状态是否可用（可以发送请求）。
     *
     * @return CONNECTED 和 HEALTH_CHECK 状态可用
     */
    public boolean isAvailable() {
        return this == CONNECTED || this == HEALTH_CHECK;
    }
}

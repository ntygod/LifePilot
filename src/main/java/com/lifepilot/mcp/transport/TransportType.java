package com.lifepilot.mcp.transport;

/**
 * MCP 传输类型枚举。
 *
 * @author zsg
 * @since 2026-02-24
 */
public enum TransportType {

    /** 标准输入/输出 — 本地子进程通信。 */
    STDIO,

    /** Streamable HTTP — 推荐的远程传输（MCP 规范 2025-03-26+）。 */
    STREAMABLE_HTTP,

    /** SSE — 已弃用的远程传输（仅兼容旧服务器）。 */
    SSE_LEGACY
}

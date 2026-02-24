/**
 * MCP Server 注册中心 — 管理外部 MCP Server 的生命周期。
 *
 * <p>{@link com.lifepilot.mcp.registry.McpServerRegistry} 负责连接、
 * 健康检查、自动重连和优雅关闭；{@link com.lifepilot.mcp.registry.McpServerEntry}
 * 记录每个服务器的运行时状态快照。</p>
 */
package com.lifepilot.mcp.registry;

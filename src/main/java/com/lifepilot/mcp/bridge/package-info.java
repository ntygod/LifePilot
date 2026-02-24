/**
 * 反向桥接 — 将 LifePilot 内置工具暴露为 MCP Server。
 *
 * <p>{@link com.lifepilot.mcp.bridge.SkillToMcpBridge} 筛选可导出工具并处理
 * MCP 协议请求；{@link com.lifepilot.mcp.bridge.McpServerEndpoint} 提供
 * HTTP 端点接收外部 MCP Client 的调用。</p>
 */
package com.lifepilot.mcp.bridge;

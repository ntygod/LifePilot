/**
 * MCP 异常体系。
 *
 * <p>分层异常覆盖传输故障（{@link com.lifepilot.mcp.exception.McpTransportException}）、
 * 连接超时（{@link com.lifepilot.mcp.exception.McpConnectionTimeoutException}）、
 * 服务器不可用（{@link com.lifepilot.mcp.exception.McpServerUnavailableException}）
 * 和工具调用失败（{@link com.lifepilot.mcp.exception.McpToolCallException}）。</p>
 */
package com.lifepilot.mcp.exception;

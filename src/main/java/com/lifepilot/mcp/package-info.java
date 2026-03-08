/**
 * MCP 协议支持模块。
 *
 * <p>实现 Model Context Protocol 的完整双向集成：
 * <ul>
 *   <li>MCP Client — 连接外部 MCP Server，发现并调用远程工具</li>
 *   <li>MCP Server — 将 ZhiWei 内置工具暴露给外部 AI 助手</li>
 * </ul></p>
 *
 * <p>子包结构：
 * <ul>
 *   <li>{@code transport} — 传输层（stdio / Streamable HTTP / SSE）</li>
 *   <li>{@code protocol} — JSON-RPC 2.0 消息</li>
 *   <li>{@code model} — MCP 数据模型</li>
 *   <li>{@code registry} — MCP Server 注册中心</li>
 *   <li>{@code adapter} — MCP 工具适配器</li>
 *   <li>{@code bridge} — 反向桥接（ZhiWei → MCP Server）</li>
 *   <li>{@code config} — 配置与自动装配</li>
 *   <li>{@code exception} — MCP 异常体系</li>
 * </ul></p>
 */
package com.lifepilot.mcp;

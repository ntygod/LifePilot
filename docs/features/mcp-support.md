# MCP 协议支持 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.mcp`
> **最后更新**：2026-03

## 1. 功能概述

MCP 模块让知微能够连接外部 MCP 服务器获取工具能力，同时也能将自身工具暴露为 MCP 服务。通过 MCP 协议，知微可以接入丰富的第三方工具生态，如数据库查询、文件操作、API 调用等。

## 2. 核心特性

### 2.1 三种传输方式

- **STDIO**：通过子进程 stdin/stdout 通信，适合本地 MCP 服务器
- **STREAMABLE_HTTP**：通过 HTTP POST 通信，推荐的远程传输（MCP 规范 2025-03-26+）
- **SSE_LEGACY**：通过 Server-Sent Events 通信，兼容旧版 MCP 服务器

### 2.2 自动工具发现与注册

启动时自动连接配置的 MCP 服务器，获取工具列表并注册到 DynamicToolRegistry。Agent 可以像使用内置工具一样使用 MCP 工具。

### 2.3 MCP 服务端能力

知微可以将标记为 exportable 的内部工具通过 MCP 协议暴露给外部系统，支持跨系统工具共享。

### 2.4 风险等级自动推断

MCP 协议本身不包含风险等级信息，知微根据工具 Schema 自动推断风险等级，确保 MCP 工具也受护栏系统保护。

### 2.5 异步通信

所有 MCP 通信基于 CompletableFuture 异步执行，不阻塞 Agent 主循环。

## 3. 使用场景

用户在 `application.yml` 中配置 MCP 服务器（如一个本地的文件系统 MCP 服务器、一个远程的数据库查询 MCP 服务器），系统启动后自动连接并注册工具。Agent 在规划阶段可以选择使用这些 MCP 工具完成任务。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.mcp.servers.{name}.command` | — | STDIO 模式启动命令 |
| `lifepilot.mcp.servers.{name}.args` | — | 启动参数列表 |
| `lifepilot.mcp.servers.{name}.url` | — | HTTP/SSE 模式 URL |
| `lifepilot.mcp.servers.{name}.transport` | `STDIO` | 传输类型（STDIO/STREAMABLE_HTTP/SSE_LEGACY） |
| `lifepilot.mcp.servers.{name}.env` | — | 环境变量 |

## 5. 限制与未来方向

- MCP 工具的执行延迟受网络和外部服务器性能影响
- 当前不支持 MCP Resources 和 Prompts 能力（仅支持 Tools）
- 未来计划：支持 MCP Resources 用于知识库扩展、支持工具变更通知（listChanged）

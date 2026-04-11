# MCP 协议支持 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.mcp`
> **最后更新**：2026-04

## 1. 功能概述

MCP 模块让知微能够连接外部 MCP 服务器获取工具能力，同时也能将自身工具暴露为 MCP 服务。通过 MCP 协议，知微可以接入丰富的第三方工具生态，如数据库查询、文件操作、API 调用等。

核心体验：**零等待启动，按需连接**。启动时仅注册配置和缓存的工具桩，Agent 首次调用 MCP 工具时才连接对应 Server；长时间无调用自动断开释放资源。

## 2. 核心特性

### 2.1 懒连接模式

启动时不连接任何 MCP Server，仅注册配置。如果本地有工具缓存，从缓存加载工具桩注册到 DynamicToolRegistry，LLM 在 Server 未连接时也能感知可用工具。Agent 首次调用某个 MCP 工具时，自动连接对应 Server（懒连接）。

### 2.2 工具清单缓存

MCP Server 连接成功后，工具 Schema 自动缓存到 `~/.zhiwei/mcp/tool-cache/{serverName}.json`。下次启动时从缓存加载工具桩，无需重新连接即可让 LLM 感知工具。Server 断开连接后也会从缓存恢复工具桩，保持工具可见性。

### 2.3 空闲超时自动断开

每个 Server 可配置 `idleTimeout`（默认 10 分钟）。无工具调用超过此时间后自动断开连接，释放资源。再次调用时通过懒连接重新连接。

### 2.4 后台发现

对 `autoConnect=true` 且无本地缓存的 Server，启动时后台连接发现工具并写入缓存。发现失败不影响应用启动，用户可从前端手动连接或等待首次工具调用触发懒连接。

### 2.5 自动配置发现

系统自动扫描多个路径发现 MCP 服务器配置，零配置开箱可用：
1. `~/.zhiwei/mcp/servers.json` — 知微内置 + 用户自定义（最高优先级）
2. `~/.mcp/servers.json` — 用户全局 MCP 配置
3. `{project-root}/.mcp.json` — 项目本地配置
4. 自定义路径 — 通过 `lifepilot.mcp.discovery.paths` 配置

内置 MCP 服务器配置在首次启动时自动释放到 `~/.zhiwei/mcp/servers.json`，仅补充新增，不覆盖用户已有配置。

### 2.6 三种传输方式

- **STDIO**：通过子进程 stdin/stdout 通信，适合本地 MCP 服务器
- **STREAMABLE_HTTP**：通过 HTTP POST 通信，推荐的远程传输（MCP 规范 2025-03-26+）
- **SSE_LEGACY**：通过 Server-Sent Events 通信，兼容旧版 MCP 服务器

### 2.7 健康检查与自动重连

已连接的 Server 定期执行 ping 健康检查（默认 30 秒间隔）。健康检查失败时触发指数退避重连（500ms x 2^n，上限 5s，最多 5 次）。健康检查和空闲超时检测合并为单一定时任务，避免任务泄漏。

### 2.8 MCP 服务端能力

知微可以将标记为 exportable 的内部工具通过 MCP 协议暴露给外部系统，支持跨系统工具共享。

### 2.9 风险等级自动推断

MCP 协议本身不包含风险等级信息，知微根据工具 Schema 自动推断风险等级，确保 MCP 工具也受护栏系统保护。

### 2.10 优雅关闭

McpServerRegistry 实现 `DisposableBean`，Spring 容器关闭时自动取消所有定时任务并断开所有连接，确保子进程和网络资源正确释放。

## 3. 使用场景

用户通过 `~/.zhiwei/mcp/servers.json`（推荐）或 `application.yml` 配置 MCP 服务器。系统启动后注册所有配置并从缓存加载工具桩。MCP server 条目自动出现在系统提示词的 Skill 目录中（`<available_mcp_servers>` 标签），Agent 根据用户请求调用 `file.read(skill="mcp:server-name")` 加载该 Server 的所有工具。MCP 工具不再全量注入上下文，而是随 Skill 激活动态加载。长时间无调用后 Server 自动断开，下次调用时再次连接。

## 4. 配置项

### 4.1 Server 连接配置

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.mcp.enabled` | `true` | MCP 支持总开关 |
| `lifepilot.mcp.servers[].name` | — | 服务器名称（唯一标识） |
| `lifepilot.mcp.servers[].description` | — | 用户自定义描述，用于 Skill 目录中展示；为空时从工具描述自动聚合 |
| `lifepilot.mcp.servers[].transport` | `STDIO` | 传输类型（STDIO/STREAMABLE_HTTP/SSE_LEGACY） |
| `lifepilot.mcp.servers[].command` | — | STDIO 模式启动命令 |
| `lifepilot.mcp.servers[].args` | `[]` | 启动参数列表 |
| `lifepilot.mcp.servers[].url` | — | HTTP/SSE 模式 URL |
| `lifepilot.mcp.servers[].env` | `{}` | 环境变量 |
| `lifepilot.mcp.servers[].timeout` | `60s` | 请求超时时间 |
| `lifepilot.mcp.servers[].autoConnect` | `true` | 无缓存时是否后台发现工具 |
| `lifepilot.mcp.servers[].reconnect` | `true` | 断开后是否自动重连 |
| `lifepilot.mcp.servers[].reconnectDelay` | `500ms` | 重连初始延迟 |
| `lifepilot.mcp.servers[].maxReconnectAttempts` | `5` | 最大重连次数 |
| `lifepilot.mcp.servers[].healthCheckInterval` | `30s` | 健康检查间隔 |
| `lifepilot.mcp.servers[].idleTimeout` | `10min` | 空闲超时（无调用后自动断开） |

### 4.2 自动发现配置

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.mcp.discovery.enabled` | `true` | 自动发现开关 |
| `lifepilot.mcp.discovery.paths` | `[]` | 额外发现路径 |
| `lifepilot.mcp.discovery.seedBuiltinServers` | `true` | 启动时释放内置配置到用户目录 |

## 5. 限制与未来方向

- MCP 工具的执行延迟受网络和外部服务器性能影响
- 首次调用未缓存的 MCP 工具时，懒连接会增加一次性延迟（后续调用不受影响）
- 当前不支持 MCP Resources 和 Prompts 能力（仅支持 Tools）
- 未来计划：支持 MCP Resources 用于知识库扩展、支持工具变更通知（listChanged）

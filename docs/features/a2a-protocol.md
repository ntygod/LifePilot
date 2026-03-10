# A2A 协议支持 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.a2a`
> **最后更新**：2026-03

## 1. 功能概述

A2A 协议支持使知微能够与其他 A2A 兼容的 Agent 系统进行跨系统互操作。知微既可以作为 A2A Server 对外暴露自身能力供其他 Agent 调用，也可以作为 A2A Client 发现和调用远程 Agent，实现跨系统的 Agent 协作。

## 2. 核心特性

### 2.1 A2A Server — 对外暴露能力

知微作为 A2A Server 提供标准化的 REST 端点：
- `/.well-known/agent.json`：Agent Card 能力声明，自动从 AgentRegistry 生成 skills 列表
- `/api/a2a/message/send`：同步消息处理
- `/api/a2a/message/stream`：SSE 流式消息处理（可配置开关）
- `/api/a2a/tasks/{id}`：Task 查询和取消

支持 API Key 认证，保护端点安全。

### 2.2 A2A Client — 调用远程 Agent

知微作为 A2A Client 支持：
- 远程 Agent 发现：通过 `/.well-known/agent.json` 获取 Agent Card
- 消息发送：向远程 Agent 发送任务消息
- Task 生命周期管理：查询 Task 状态、取消 Task
- 自动发现：启动时自动发现配置列表中的远程 Agent

### 2.3 远程 Agent 工具化

已发现的远程 Agent 自动注册为本地 BuiltinTool（ID 格式 `a2a_remote_{name}`），本地 Agent 可像调用本地工具一样调用远程 Agent，无需感知 A2A 协议细节。

### 2.4 Agent Card 缓存

远程 Agent Card 缓存在内存中，支持 TTL 过期自动刷新。刷新失败时使用旧缓存兜底，确保服务可用性。

### 2.5 多态消息内容

消息内容支持三种类型：文本（Text）、文件（File）、结构化数据（Data），通过 `sealed interface` 建模，JSON 序列化时自动区分类型。

### 2.6 Task 状态机

Task 具有完整的生命周期状态：SUBMITTED → WORKING → COMPLETED / FAILED / CANCELED / INPUT_REQUIRED / REJECTED / AUTH_REQUIRED。终态判断内聚在枚举中。

## 3. 使用场景

企业内部部署了多个 A2A 兼容的 Agent 系统（如知微负责个人助手、另一个系统负责数据分析），通过 A2A 协议实现跨系统协作。用户向知微提出"分析上周销售数据"的请求，知微通过 `a2a_remote_data_analyst` 工具将分析任务委托给远程数据分析 Agent，获取结果后整合呈现给用户。

外部 Agent 系统也可以通过知微的 A2A Server 端点调用知微的能力，如调用知微的日程管理、待办管理等 Skill。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.a2a.enabled` | `true` | A2A 模块总开关 |
| `lifepilot.a2a.server.enabled` | `true` | Server 端开关 |
| `lifepilot.a2a.server.api-key` | `""` | API Key 认证（空则不启用） |
| `lifepilot.a2a.server.streaming-enabled` | `true` | SSE 流式端点开关 |
| `lifepilot.a2a.client.enabled` | `true` | Client 端开关 |
| `lifepilot.a2a.client.remote-agents` | `[]` | 远程 Agent URL 列表 |
| `lifepilot.a2a.client.connect-timeout-seconds` | `10` | 连接超时（秒） |
| `lifepilot.a2a.client.read-timeout-seconds` | `60` | 读取超时（秒） |
| `lifepilot.a2a.client.card-cache-ttl-minutes` | `30` | Card 缓存 TTL（分钟） |

## 5. 限制与未来方向

当前限制：
- Task 存储为内存实现，重启后丢失
- 不支持 A2A 推送通知（Push Notification）
- 流式端点仅支持 SSE，不支持 WebSocket
- 远程调用为同步阻塞，不支持异步轮询

未来方向：
- Task 持久化到 SQLite
- 支持 A2A Push Notification 机制
- 支持异步 Task 轮询模式
- 远程 Agent 健康检查和自动重连

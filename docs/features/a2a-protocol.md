# A2A 协议支持功能说明

> **模块编号**：22（Phase 6）
> **最后更新**：2026-02-27

---

## 1. 功能概述

### 1.1 核心价值

A2A（Agent-to-Agent）协议支持使 ZhiWei 能够与其他 AI Agent 系统进行标准化通信。用户可以：

- 让 ZhiWei 调用外部专业 Agent（如企业内部的报表 Agent、翻译 Agent）
- 将 ZhiWei 的能力暴露给其他系统（如企业工作流平台调用 ZhiWei 的规划能力）
- 构建跨系统的 Agent 协作网络

### 1.2 双重角色

ZhiWei 同时扮演两个角色：

| 角色 | 说明 | 典型场景 |
|------|------|---------|
| A2A Server | 暴露自身 Agent 能力供外部调用 | 企业系统调用 ZhiWei 的写作/规划能力 |
| A2A Client | 发现并调用外部 A2A Agent | ZhiWei 调用企业内部的数据分析 Agent |

---

## 2. 核心特性

### 2.1 Agent Card 能力声明

ZhiWei 自动生成标准 A2A Agent Card，暴露在 `/.well-known/agent.json` 路径。Agent Card 包含：

- ZhiWei 的名称、描述、版本
- 所有已注册 Agent 映射为 A2A skills（writer / life-coach / planner 等）
- 支持的输入/输出模式（text）
- 认证要求（API Key）
- 流式响应能力声明

外部系统通过标准发现路径即可了解 ZhiWei 的全部能力。

### 2.2 A2A Server — 接收外部请求

外部 Agent 可以向 ZhiWei 发送消息，ZhiWei 自动路由到对应的内部 Agent 执行：

```
外部 Agent → POST /api/a2a/message/send
           → ZhiWei 解析消息
           → 路由到 writer / life-coach / planner
           → 执行并返回结果（Task + Artifact）
```

支持同步响应和 SSE 流式响应两种模式。

### 2.3 A2A Client — 调用外部 Agent

用户可以配置远程 A2A Agent 的 URL，ZhiWei 自动发现其能力并注册为可调用工具：

```
用户: 帮我用公司的数据分析 Agent 分析这个月的销售数据
主 Agent: [调用 a2a_remote_data_analyst(task="分析本月销售数据")]
         → A2aClientService.sendMessage() → 远程 Agent 执行
         → 结果返回主 Agent → 整合回复用户
```

远程 Agent 自动注册为 BuiltinTool（工具 ID 格式：`a2a_remote_{agentName}`），主 Agent 的 LLM 可通过 Function Call 自主决策何时调用。

### 2.4 Task 生命周期管理

A2A 协议的 Task 支持长时间运行的异步任务：

- 任务提交后立即返回 Task ID
- 外部系统可轮询 Task 状态
- 支持 SSE 实时推送状态更新
- 支持任务取消

Task 状态流转：submitted → working → completed / failed / canceled

---

## 3. 使用场景

### 3.1 ZhiWei 作为 Server

企业工作流平台调用 ZhiWei 的写作能力：

```
企业系统 → 发现 ZhiWei Agent Card（GET /.well-known/agent.json）
        → 找到 "writer" skill
        → 发送消息（POST /api/a2a/message/send）
           { "task": "撰写本周团队周报", "skillId": "writer" }
        → ZhiWei 执行写作任务
        → 返回 Task（含 Artifact：周报内容）
```

### 3.2 ZhiWei 作为 Client

用户让 ZhiWei 调用外部翻译 Agent：

```
用户: 把这段话翻译成英文（使用公司的翻译服务）
主 Agent: [发现远程 translator Agent 的能力]
        → [调用 a2a_remote_translator(task="翻译：...")]
        → 远程 Agent 返回翻译结果
主 Agent: 翻译结果是：...
```

### 3.3 多 Agent 跨系统协作

ZhiWei 协调本地和远程 Agent 完成复杂任务：

```
用户: 帮我准备明天的客户演示
主 Agent: [调用本地 planner 规划演示流程]
        → [调用远程 slide_generator 生成幻灯片]
        → [调用本地 writer 撰写演讲稿]
        → 整合所有结果返回用户
```

---

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.a2a.enabled` | `true` | 是否启用 A2A 协议支持 |
| `lifepilot.a2a.server.enabled` | `true` | 是否启用 A2A Server |
| `lifepilot.a2a.server.api-key` | 空 | Server 端 API Key（空则不启用认证） |
| `lifepilot.a2a.server.agent-name` | `ZhiWei` | Agent Card 中的名称 |
| `lifepilot.a2a.server.agent-description` | `个人生活助手` | Agent Card 中的描述 |
| `lifepilot.a2a.server.streaming-enabled` | `true` | 是否支持 SSE 流式响应 |
| `lifepilot.a2a.client.enabled` | `true` | 是否启用 A2A Client |
| `lifepilot.a2a.client.remote-agents` | 空列表 | 远程 Agent URL 列表 |
| `lifepilot.a2a.client.connect-timeout-seconds` | `10` | 连接超时 |
| `lifepilot.a2a.client.read-timeout-seconds` | `60` | 读取超时 |
| `lifepilot.a2a.task.ttl-minutes` | `60` | Task 内存存储 TTL |

---

## 5. 限制与未来扩展

### 5.1 当前限制

- 仅支持 HTTP+JSON/REST 传输（不支持 JSON-RPC 2.0 和 gRPC）
- 仅支持 API Key 认证（不支持 OAuth 2.0 / OpenID Connect）
- Task 存储在内存中，重启后丢失
- 不支持 Push Notification（仅支持轮询和 SSE）
- 仅支持 text 输入/输出模式（不支持 file / data）
- 远程 Agent 需手动配置 URL（不支持自动发现/注册中心）

### 5.2 未来扩展方向

- OAuth 2.0 认证支持（上云部署时）
- Push Notification 支持（Webhook 回调）
- 多模态 Part 支持（file / data）
- Agent 注册中心集成（自动发现远程 Agent）
- Task 持久化（SQLite 存储，支持跨重启恢复）
- 迁移到官方 A2A Java SDK（当 Spring Boot 集成成熟时）

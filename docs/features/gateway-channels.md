# Gateway 与多通道交互 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.interaction`
> **最后更新**：2026-03

## 1. 功能概述

知微（ZhiWei）通过 Gateway + 中间件管道架构，将多种交互通道统一接入 Agent 引擎。用户可以通过 Web UI、企业微信、钉钉、飞书与 Agent 对话，所有通道共享同一套认证、限流、安全检查和审计策略。通道差异在适配器层完全消化，Agent 业务逻辑与通道无关。

## 2. 核心特性

### 2.1 统一消息网关

所有通道的消息经过通道适配器标准化为 `GatewayMessage` 后，进入同一条中间件管道处理。网关支持运行时动态注册/注销通道，单个通道故障不影响其他通道。

> ⚠️ 渠道适配层已重构为插件架构，`ChannelAdapter` / `AbstractChannelAdapter` 等接口已迁移，详见 [channel-plugin-architecture.md](../architecture/channel-plugin-architecture.md)。

### 2.2 6 层中间件管道

| 层级 | 中间件 | 默认顺序 | 职责 |
|------|--------|---------|------|
| 1 | AuthMiddleware | 100 | 认证鉴权，验证消息来源身份 |
| 2 | RateLimitMiddleware | 200 | Token 感知限流 + 请求数限流 |
| 3 | SecurityMiddleware | 300 | Prompt 注入检测、敏感数据过滤 |
| 4 | RouterMiddleware | 400 | 意图路由，快速路径分流 |
| 5 | ExecutionMiddleware | 500 | 调用 AgentOrchestrator 执行任务 |
| 6 | AuditMiddleware | 600 | 审计日志记录 |

每个中间件可独立启用/禁用、调整顺序。任何中间件可短路终止管道（如认证失败直接返回 401）。

### 2.3 Token 感知限流

限流不仅按请求数计数，还按 Token 消耗量限制。支持每用户每小时/每天的 Token 配额和每分钟请求数上限，防止 Token 耗尽攻击。

### 2.4 多通道消息类型

通过 `MessageContent` sealed interface 支持 5 种消息类型：

- 纯文本消息（TextMessage）
- 命令消息（CommandMessage，`/` 开头的快捷命令）
- 文件消息（FileMessage，图片/文档/音频）
- 卡片消息（CardMessage，企业 IM 交互式卡片回调）
- 事件消息（EventMessage，系统事件如用户加入、心跳）

### 2.5 通道适配器自动重连

企业 IM 通道适配器内置指数退避重连机制。连接断开后自动重连，延迟从 1 秒指数增长到最大 60 秒，最多重试 10 次。失败消息入队重试，不丢消息。

### 2.6 请求级上下文隔离

每次请求创建独立的 `MiddlewareContext`，中间件之间通过类型安全的属性包传递数据（认证结果、信任等级、限流剩余量等），请求间完全隔离。

## 3. 使用场景

### 3.1 Web UI 对话

用户通过 Vue 3 前端发送消息，经 REST Controller 接入 Web 通道适配器，标准化后进入中间件管道。Agent 响应通过 SSE（Server-Sent Events）流式推送到前端，实现逐字输出效果。

### 3.2 企业微信集成

企业微信通过 Webhook 回调将用户消息推送到企业微信通道适配器。适配器解密 AES 加密的 XML 消息体，验证 SHA1 签名，标准化为 GatewayMessage 后异步提交到 Gateway（Virtual Thread）。响应通过企业微信 API 主动推送给用户。

### 3.3 钉钉集成

钉钉自定义机器人通过 Webhook 回调推送 JSON 消息。适配器验证 HmacSHA256 签名，支持 @消息触发和交互式卡片（ActionCard）回调。

### 3.4 飞书集成

飞书通过事件订阅 v2.0 推送加密 JSON 消息。适配器解密消息、验证 Token，支持事件去重（基于 eventId 缓存）。响应支持富文本（Post）格式。

详细接入步骤可参考：[guides/feishu-integration-guide.md](../guides/feishu-integration-guide.md)

### 3.5 快速路径命令

以 `/` 开头的命令消息被 RouterMiddleware 识别后走快速路径，直接路由到对应 Skill，跳过 LLM 推理。延迟 < 100ms，不消耗 Token。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.gateway.enabled` | `true` | 网关总开关 |
| `lifepilot.gateway.channels.web.enabled` | `false` | Web 通道开关 |
| `lifepilot.gateway.channels.wecom.enabled` | `false` | 企业微信通道开关 |
| `lifepilot.gateway.channels.dingtalk.enabled` | `false` | 钉钉通道开关 |
| `lifepilot.gateway.channels.feishu.enabled` | `false` | 飞书通道开关 |
| `lifepilot.gateway.rate-limit.max-tokens-per-hour` | `100000` | 每用户每小时 Token 上限 |
| `lifepilot.gateway.rate-limit.max-requests-per-minute` | `30` | 每用户每分钟请求上限 |
| `lifepilot.gateway.execution.timeout-seconds` | `120` | Agent 执行超时（秒） |
| `lifepilot.gateway.execution.streaming-enabled` | `true` | 流式响应开关 |
| `lifepilot.gateway.reconnect.max-attempts` | `10` | 通道最大重连次数 |
| `lifepilot.gateway.audit.retention-days` | `90` | 审计日志保留天数 |

详细配置参考：[architecture/gateway-middleware.md](../architecture/gateway-middleware.md)

## 5. 限制与未来方向

### 当前限制

- CLI 交互层尚未实现，`ChannelType` 枚举中无 `CLI` 值
- Web 通道和企业 IM 通道默认关闭，需手动配置启用
- 企业 IM 通道需要各平台的应用凭证（corpId/appKey/appId 等）
- Telegram 等国际 IM 平台尚未支持

### 未来方向

- 实现 CLI 交互层（JLine 3 REPL + 快捷命令）
- CLI 可选通过 HTTP 客户端模式接入 Gateway
- 支持更多 IM 平台（Telegram、Slack、Discord）
- 中间件管道可视化监控

> ⚠️ 渠道适配层已重构为插件架构，详见 [channel-plugin-architecture.md](../architecture/channel-plugin-architecture.md)。

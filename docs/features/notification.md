# 通知系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.notification`
> **最后更新**：2026-03

## 1. 功能概述

通知系统负责把“需要用户看到的结果”直接投递到用户当前或指定渠道，并把结果写入通知历史。当前实现不再区分 `HIGH / MEDIUM / LOW`，也不再维护被动队列和通知偏好设置。

对 cron、heartbeat 这类自主任务，推荐协议是：
- 没有结果时静默
- 有结果时直接通知

## 2. 核心特性

### 2.1 统一通知服务

所有模块都通过 `NotificationService.send(NotificationRequest)` 发送通知。调用方只需要提供：
- `targetUserId`
- `content`
- 可选 `channel`
- 可选 `typeId`
- 可选 `metadata`

### 2.2 直接发送策略

通知服务收到请求后直接执行投递：
- 指定 `channel` 时，定向发送到该渠道
- 未指定 `channel` 时，按默认路由选择可用渠道
- Web 渠道会通过 SSE 立即推送到前端

系统不再做“低优先级入队稍后推送”的分流。

### 2.3 多渠道广播

通知默认可投递到所有已注册的渠道插件实例：
- Web SSE
- 企业微信
- 飞书
- 钉钉

单个渠道失败不会中断其他渠道，失败会记录到通知历史中。

### 2.4 富媒体内容支持

通知内容基于 `ResponseContent`，可承载：
- `TextContent`
- `MarkdownContent`
- `CardContent`
- `ImageContent`

不同渠道通过各自的 `MessageConverter` 做格式适配；不支持的格式会自动降级。

### 2.5 通知历史与已读管理

所有发送结果都会持久化到 `notification_history`，支持：
- 分页查询通知历史
- 查询未读数
- 标记单条已读
- 批量标记全部已读

### 2.6 工作流 NotifyStep

工作流里的 `NotifyStep` 专门用于“有结果就通知”的场景，当前只保留三个核心字段：
- `targetUserId`
- `content`
- `contentType`

没有结果需要告知时，推荐用 `condition + noop` 静默结束，而不是再发一条“低优先级通知”。

### 2.7 Web 实时推送

Web 端通过 `/api/notifications/stream` 建立通知专用 SSE 连接：
- 建连后先推送一次未读数快照
- 后续新通知实时广播
- 前端通知中心直接消费同一条数据流

## 3. 使用场景

### 场景 1：自主任务结果通知

cron 任务执行后，如果产生了新的摘要、异常或结论，直接把正文发送给用户；如果没有新结果，则返回 `TASK_SILENT`，不生成通知。

### 场景 2：工作流完成通知

工作流在关键节点或执行结束后，通过 `NotifyStep` 把最终结果直接推送给用户，例如“周报已生成”“同步完成，共更新 42 条记录”。

### 场景 3：Agent 主动结果告知

`interact.notify` 工具用于 Agent 在非阻塞场景下向用户告知结果，默认优先回到当前会话渠道，避免额外的跨渠道广播噪声。

## 4. REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/notifications` | 分页查询通知历史 |
| GET | `/api/notifications/stream` | 建立通知 SSE 流 |
| PUT | `/api/notifications/{id}/read` | 标记单条通知已读 |
| PUT | `/api/notifications/read-all` | 批量标记所有通知已读 |

## 5. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.notification.enabled` | `true` | 是否启用通知系统 |
| `lifepilot.notification.history-page-size` | `20` | 通知历史默认分页大小 |
| `lifepilot.notification.max-history-page-size` | `100` | 通知历史最大分页大小 |
| `lifepilot.notification.default-user-id` | `default` | 内部通知默认目标用户 |

## 6. 当前约束

- 通知系统当前只负责“结果投递”，不再负责通知等级、偏好过滤或被动队列聚合
- “无结果静默”由上游任务协议保证，例如 cron 的 `TASK_SILENT`、heartbeat 的 `HEARTBEAT_OK`
- 通知模板仍以调用方拼装内容为主，后续如需统一样式，可再引入模板层

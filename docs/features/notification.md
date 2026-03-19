# 通知系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.notification`
> **最后更新**：2026-03

## 1. 功能概述

通知系统为知微提供统一的通知投递能力。工作流引擎、自主任务执行以及未来扩展模块均可通过 `NotificationService` 接口发送通知，支持多渠道广播（Web SSE / 企微 / 飞书 / 钉钉）、富媒体内容（文本 / Markdown / 交互式卡片 / 图片）、紧急度路由、被动队列持久化和用户通知偏好管理。

通知系统作为独立基础设施，解决了被动队列重启丢失、channel 字段硬编码等问题。

## 2. 核心特性

### 2.1 统一通知服务

`NotificationService` 提供标准化的通知发送入口，任意模块通过构造函数注入即可使用。发送通知只需构造 `NotificationRequest`（目标用户、内容、紧急度），无需关心渠道路由和格式转换细节。

### 2.2 Urgency 路由策略

通知根据紧急程度自动路由：

| Urgency | 行为 |
|---------|------|
| HIGH | 实时推送到所有已注册渠道 |
| MEDIUM | 实时推送到所有已注册渠道 |
| LOW | 入队被动通知队列，由定时调度器批量推送 |

### 2.3 多渠道广播

通知默认广播到所有已注册的 `ChannelAdapter`：
- Web — 通过 SSE 实时推送到浏览器
- 企业微信 — 通过 WecomApiClient 发送
- 飞书 — 通过 FeishuApiClient 发送
- 钉钉 — 通过 DingtalkChannelAdapter 发送

单渠道发送失败不影响其他渠道，确保通知投递的可靠性。

### 2.4 富媒体内容支持

通知内容使用 `ResponseContent` sealed interface，支持四种格式：

| 格式 | 说明 | 企微渲染 | 飞书渲染 |
|------|------|---------|---------|
| TextContent | 纯文本 | 文本消息 | 文本消息 |
| MarkdownContent | Markdown 格式 | 企微 Markdown | 富文本 post（链接转 `{tag:"a"}` 标签） |
| CardContent | 交互式卡片（标题 + 正文 + 操作按钮） | Markdown 降级（标题加粗 + `[label](url)` 链接） | 交互式消息卡片（button 元素 + multi_url 跳转） |
| ImageContent | 图片（URL + 替代文本 + 说明） | 图文消息（news 类型） | 图片消息 |

不支持的格式自动降级：ImageContent → 包含图片链接的文本，CardContent 按钮 → `[label](url)` 链接。

### 2.5 被动通知队列持久化

LOW 紧急度通知入队 `PassiveNotificationQueue`，同时写入 SQLite 和内存队列。系统重启时自动从数据库加载未投递的通知，确保不丢失。`NotificationScheduler` 按配置间隔（默认 30 秒）定时 drain 队列，通过 SSE 广播给对应用户。

### 2.6 用户通知设置

用户可通过 REST API 管理通知偏好：
- 按通知类型启用/禁用
- 按渠道启用/禁用（如仅接收 Web 和飞书通知）
- 设置最低紧急度阈值（如仅接收 MEDIUM 以上通知）

未配置时使用系统默认：所有类型启用、所有渠道启用、最低紧急度 LOW。

### 2.7 工作流 NotifyStep

工作流引擎新增 `NotifyStep` 步骤类型，支持在工作流任意节点声明式发送通知：
- `targetUserId` 和 `content` 支持 `${}` 表达式引用前置步骤输出
- `contentType` 支持 TEXT / MARKDOWN / CARD 三种格式
- 通过 `NotificationService` 统一投递，享受完整的路由和渠道能力

### 2.8 通知历史与已读管理

所有通知持久化到 `notification_history` 表，支持：
- 分页查询通知历史（按 sentAt 降序）
- 按 urgency 过滤
- 单条标记已读 / 批量标记所有已读

## 3. 使用场景

### 场景 1：自主任务通知

自主任务执行引擎检测到 cron 任务触发，执行完成后通过 `NotificationService` 发送 MEDIUM 紧急度通知。通知自动广播到用户的 Web 浏览器（SSE）和企微，内容为任务执行结果摘要。

### 场景 2：工作流通知

用户定义了一个数据同步工作流，在同步完成后通过 `NotifyStep` 发送通知。工作流表达式引擎解析 `${sync.result.count}` 变量，生成"同步完成，共更新 42 条记录"的通知内容，通过所有渠道推送。

### 场景 3：低优先级通知

系统生成一条 LOW 紧急度的每日总结通知。通知入队被动队列，不立即打扰用户。30 秒后 NotificationScheduler 定时 drain，通过 SSE 推送到用户浏览器。

## 4. REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/notifications` | 分页查询通知历史（支持 urgency 过滤） |
| PUT | `/api/notifications/{id}/read` | 标记单条通知已读 |
| PUT | `/api/notifications/read-all` | 批量标记所有通知已读 |
| GET | `/api/notification-settings` | 查询用户通知设置 |
| PUT | `/api/notification-settings/{typeId}` | 更新通知设置（UPSERT） |

## 5. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.notification.enabled` | `true` | 是否启用通知系统 |
| `lifepilot.notification.passive-drain-interval` | `30` | 被动队列 drain 间隔（秒） |
| `lifepilot.notification.history-page-size` | `20` | 通知历史默认分页大小 |
| `lifepilot.notification.max-history-page-size` | `100` | 通知历史最大分页大小 |

## 6. 限制与未来方向

当前限制：
- 通知设置仅支持 REST API 管理，前端通知设置页面尚未实现
- 被动队列 drain 通过 SSE 推送，用户未在线时通知等待下次连接
- 通知模板为硬编码格式，未来可支持用户自定义模板

未来方向：
- 前端通知中心页面（通知列表、未读角标、设置面板）
- 通知模板引擎（支持用户自定义通知格式）
- 通知聚合（同类型通知合并，避免信息过载）
- 推送通道扩展（邮件、Telegram、Webhook）

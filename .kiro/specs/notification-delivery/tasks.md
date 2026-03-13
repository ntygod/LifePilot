# 实现任务：通知投递能力补齐（notification-delivery）

## 任务列表

- [x] 1. 创建 notification 模块基础设施
  - [x] 1.1 创建 `com.lifepilot.notification.Urgency` 枚举（从 `agent.proactive.model.Urgency` 迁移，内容不变：HIGH / MEDIUM / LOW）
  - [x] 1.2 创建 `NotificationRequest` record（targetUserId、content、urgency、channel、typeId、metadata）
  - [x] 1.3 创建 `NotificationRecord` record（通知历史记录数据载体）
  - [x] 1.4 创建 `NotificationSettingRecord` record（通知设置数据载体）
  - [x] 1.5 创建 `PassiveQueueEntry` record（被动队列条目数据载体）
  - [x] 1.6 创建 `NotificationProperties` 配置类（enabled、passiveDrainInterval、historyPageSize、maxHistoryPageSize）
  - [x] 1.7 创建 `NotificationService` 接口（send 方法）
  - [x] 1.8 在 `application.yml` 中添加 `lifepilot.notification.*` 配置项

- [x] 2. 数据库迁移脚本
  - [x] 2.1 创建 Flyway 迁移脚本：`notification_history` 表（含索引）
  - [x] 2.2 创建 Flyway 迁移脚本：`passive_notification_queue` 表（含索引）
  - [x] 2.3 创建 Flyway 迁移脚本：`notification_settings` 表（含唯一约束）

- [x] 3. 通知数据访问层与核心实现
  - [x] 3.1 创建 `NotificationRepository`（notification_history 表 CRUD + 分页查询 + 已读标记 + urgency 过滤）
  - [x] 3.2 创建持久化版 `PassiveNotificationQueue`（enqueue 写入数据库 + 内存队列，drainAll 更新 delivered，启动时加载未投递记录）
  - [x] 3.3 创建 `DefaultNotificationService` 实现（urgency 路由、ChannelAdapter 遍历广播、MessageConverter 转换、用户设置过滤、通知持久化）
  - [x] 3.4 创建 `NotificationScheduler`（@Scheduled 定时 drain 被动队列，通过 SseSessionManager 广播）
  - [x] 3.5 创建 `NotificationAutoConfiguration`（注册 NotificationService、PassiveNotificationQueue、NotificationRepository、NotificationScheduler Bean）

- [x] 4. Urgency 枚举全局迁移与旧组件清理
  - [x] 4.1 全局替换 `import com.lifepilot.agent.proactive.model.Urgency` 为 `import com.lifepilot.notification.Urgency`（涉及 ProactiveReasoner、ProactiveNotification、ProactiveCandidate、FrequencyStateManager、WebChannelAdapter、NotificationSseEvent 等）
  - [x] 4.2 重构 `ProactiveReasoner`：构造函数从 `NotificationDispatcher` 改为 `NotificationService`，通知发送改为构造 `NotificationRequest` 调用 `NotificationService.send()`
  - [x] 4.3 重构 `WebChannelAdapter`：移除 `PassiveNotificationQueue`（旧版）依赖和 `drainAndBroadcastPassiveNotifications()` 方法，简化 `doSendResponse()`
  - [x] 4.4 清理 `ProactiveAutoConfiguration`：移除 `logNotificationChannel()`、`gatewayNotificationChannel()`、`passiveNotificationQueue()`、`notificationDispatcher()` Bean 注册，`proactiveReasoner()` 参数从 NotificationDispatcher 改为 NotificationService
  - [x] 4.5 删除旧类：`NotificationChannel` 接口、`LogNotificationChannel`、`GatewayNotificationChannel`、`NotificationDispatcher`、`PassiveNotificationQueue`（旧版）、`Urgency`（旧位置）
  - [x] 4.6 全量编译验证（`mvn compile`）

- [x] 5. ResponseContent 扩展与 MessageConverter 适配
  - [x] 5.1 在 `ResponseContent` sealed interface 中新增 `ImageContent` record（imageUrl、altText、caption）
  - [x] 5.2 更新 `FeishuMessageConverter`：CardContent 渲染为飞书交互式消息卡片（interactive 类型），新增 ImageContent 分支，MarkdownContent 链接语法转 post 富文本 `{tag:"a"}` 标签
  - [x] 5.3 更新 `WecomMessageConverter`：新增 ImageContent 分支（降级为图文链接格式）
  - [x] 5.4 更新所有引用 `ResponseContent` 的 switch 表达式，补充 `ImageContent` 分支

- [x] 6. FeishuApiClient / WecomApiClient 扩展
  - [x] 6.1 `FeishuApiClient` 新增 `sendInteractiveCard(String chatId, String cardJson)` 方法
  - [x] 6.2 `FeishuApiClient` 新增 `sendImage(String chatId, String imageKey)` 方法
  - [x] 6.3 `WecomApiClient` 新增 `sendNews(String userId, String title, String description, String url, String picurl)` 方法
  - [x] 6.4 更新 `FeishuChannelAdapter.doSendResponse()`：根据 MessageConverter 结果选择 sendText / sendPost / sendInteractiveCard / sendImage
  - [x] 6.5 更新 `WecomChannelAdapter.doSendResponse()`：根据 MessageConverter 结果选择 sendText / sendMarkdown / sendNews

- [x] 7. WorkflowStep.NotifyStep 与 StepExecutor 扩展
  - [x] 7.1 在 `WorkflowStep` sealed interface 中新增 `NotifyStep` record（id、name、targetUserId、content、contentType、urgency、dependsOn、errorStrategy）+ JsonSubTypes 注解
  - [x] 7.2 在 `StepExecutor` 中新增 `executeNotify(NotifyStep, WorkflowContext, ExpressionEngine)` 方法：解析表达式 → 构造 ResponseContent → 调用 NotificationService.send() → 写入步骤输出
  - [x] 7.3 更新 `StepExecutor.execute()` switch 表达式，新增 `NotifyStep` 分支

- [x] 8. 通知管理 REST API
  - [x] 8.1 创建 `NotificationController`：GET `/api/notifications`（分页 + urgency 过滤）、PUT `/api/notifications/{id}/read`、PUT `/api/notifications/read-all`
  - [x] 8.2 创建 `NotificationSettingsController`：GET `/api/notification-settings`、PUT `/api/notification-settings/{typeId}`
  - [x] 8.3 创建 API 对应的 DTO record（NotificationDto、NotificationSettingDto、PagedResult 等）

- [x] 9. 单元测试与集成测试
  - [x] 9.1 `DefaultNotificationService` 单元测试（urgency 路由、广播逻辑、用户设置过滤、单渠道失败不中断）
  - [x] 9.2 `PassiveNotificationQueue` 单元测试（持久化 enqueue/drainAll、启动加载、数据库写入失败降级）
  - [x] 9.3 `NotificationScheduler` 单元测试（定时 drain + SSE 广播、异常不中断）
  - [x] 9.4 `FeishuMessageConverter` 单元测试（ImageContent 渲染、CardContent→interactive 卡片、MarkdownContent 链接转换）
  - [x] 9.5 `WecomMessageConverter` 单元测试（ImageContent 降级、CardContent Markdown 格式）
  - [x] 9.6 `StepExecutor` NotifyStep 单元测试（表达式解析、NotificationService 调用、错误策略）
  - [x] 9.7 `NotificationController` 集成测试（分页查询、已读标记、urgency 过滤）
  - [x] 9.8 `NotificationSettingsController` 集成测试（查询设置、更新设置）
  - [x] 9.9 全量测试验证（`mvn test`）

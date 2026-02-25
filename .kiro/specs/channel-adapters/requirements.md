# 需求文档：Channel Adapters（企微 / 钉钉 / 飞书通道适配器）

## 简介

本需求定义 LifePilot Gateway 的企业 IM 通道适配器实现，包括企业微信（WeCom）、钉钉（DingTalk）、飞书（Feishu）三个通道的 Webhook 回调接入。通道适配器将各平台的异构消息格式标准化为 `GatewayMessage`，经过统一中间件管道处理后，将 `GatewayResponse` 转换为平台特定格式回复用户。

本 spec 是 Phase 3 模块 13（Gateway + Channel 适配器）的补充实现，在已完成的 Gateway 框架层和中间件实现基础上，实现真正的企业 IM 通道对接。

参考文档：
- 架构设计：#[[file:docs/architecture/gateway-middleware.md]]
- 特性设计：#[[file:docs/features/gateway-channels.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 集成检查：#[[file:.kiro/steering/integration-checklist.md]]

## 术语表

- **Gateway**：消息网关，`MessageGateway` 接口，统一消息入口
- **ChannelAdapter**：通道适配器接口，定义 `channelType()`、`normalize()`、`sendResponse()`、`start()`、`stop()` 契约
- **GatewayMessage**：统一网关消息 record，所有通道消息标准化后的表示
- **GatewayResponse**：统一网关响应 record，中间件管道的输出
- **ChannelMetadata**：通道元数据 sealed interface，包含 `WecomMetadata`、`DingtalkMetadata`、`FeishuMetadata` 等 permit
- **MessageContent**：消息内容 sealed interface，包含 `TextMessage`、`CommandMessage`、`FileMessage`、`CardMessage`、`EventMessage`
- **ResponseContent**：响应内容 sealed interface，包含 `TextContent`、`MarkdownContent`、`CardContent`
- **GatewayProperties**：Gateway 配置属性 record，绑定 `lifepilot.gateway` 前缀
- **AuthStrategy**：认证策略 sealed interface，按通道类型分发认证逻辑
- **WebhookController**：Spring MVC Controller，统一接收各通道 Webhook 回调请求
- **WecomCrypto**：企微消息加解密工具，实现 AES-256-CBC 加解密和 SHA1 签名验证
- **MiddlewarePipeline**：中间件管道，按顺序执行 Auth → RateLimit → Security → Router → Execution → Audit

## 需求

### 需求 1：Webhook 统一接收控制器

**用户故事：** 作为系统管理员，我希望通过一个统一的 Spring MVC Controller 接收所有企业 IM 平台的 Webhook 回调，以便集中管理 Webhook 端点和路由逻辑。

#### 验收标准

1. THE WebhookController SHALL 在路径 `/api/webhook/{channel}` 下为每个已启用的通道注册 Webhook 端点
2. WHEN 企业微信发送 GET 请求到 `/api/webhook/wecom` 进行 URL 验证时，THE WebhookController SHALL 将请求委派给 WecomChannelAdapter 处理验证逻辑
3. WHEN 企业微信发送 POST 请求到 `/api/webhook/wecom` 推送用户消息时，THE WebhookController SHALL 将请求委派给 WecomChannelAdapter 进行消息处理
4. WHEN 钉钉发送 POST 请求到 `/api/webhook/dingtalk` 推送用户消息时，THE WebhookController SHALL 将请求委派给 DingtalkChannelAdapter 进行消息处理
5. WHEN 飞书发送 POST 请求到 `/api/webhook/feishu` 推送事件回调时，THE WebhookController SHALL 将请求委派给 FeishuChannelAdapter 进行事件处理
6. IF 请求的通道类型未启用或不存在，THEN THE WebhookController SHALL 返回 HTTP 404 状态码
7. IF 请求处理过程中发生未捕获异常，THEN THE WebhookController SHALL 返回平台要求的成功响应（企微返回 "success"、钉钉返回空 JSON、飞书返回 `{"code": 0}`），避免平台重试风暴


### 需求 2：企业微信通道适配器

**用户故事：** 作为企业微信用户，我希望通过企业微信应用与 LifePilot Agent 对话，以便在工作场景中随时使用 AI 助手。

#### 验收标准

1. WHEN 企业微信发送 URL 验证 GET 请求（携带 `msg_signature`、`timestamp`、`nonce`、`echostr` 参数）时，THE WecomChannelAdapter SHALL 验证签名并返回解密后的 `echostr` 明文
2. WHEN 企业微信推送 AES 加密的 XML 消息时，THE WecomChannelAdapter SHALL 验证消息签名（`SHA1(sort(token, timestamp, nonce, encrypt))`）
3. IF 企业微信消息签名验证失败，THEN THE WecomChannelAdapter SHALL 记录 WARN 级别日志并返回 "success" 字符串
4. WHEN 签名验证通过后，THE WecomChannelAdapter SHALL 使用 AES-256-CBC 算法解密消息体，提取 XML 中的 `FromUserName`、`MsgType`、`Content`、`MsgId` 等字段
5. WHEN 解密后的消息类型为 `text` 时，THE WecomChannelAdapter SHALL 将消息内容转换为 `TextMessage`（普通文本）或 `CommandMessage`（以 `/` 开头的命令）
6. THE WecomChannelAdapter SHALL 将企微消息标准化为 `GatewayMessage`，填充 `channelType=WECOM`、`userId=FromUserName`、`sessionId=wecom:{corpId}:{fromUser}`、`channelMetadata=WecomMetadata`
7. WHEN 消息标准化完成后，THE WecomChannelAdapter SHALL 在 Virtual Thread 上异步调用 `MessageGateway.process()` 推入中间件管道，并在 Webhook 回调中立即返回 "success"（满足企微 5 秒响应要求）
8. WHEN Agent 处理完成生成 `GatewayResponse` 后，THE WecomChannelAdapter SHALL 通过企业微信 API 主动推送响应消息给用户
9. WHEN 响应内容包含 Markdown 格式标记时，THE WecomChannelAdapter SHALL 使用企微 Markdown 消息类型发送
10. WHEN 响应内容为纯文本时，THE WecomChannelAdapter SHALL 使用企微文本消息类型发送
11. IF 企业微信 API 调用失败，THEN THE WecomChannelAdapter SHALL 将失败消息加入重试队列

### 需求 3：钉钉通道适配器

**用户故事：** 作为钉钉用户，我希望通过钉钉机器人与 LifePilot Agent 对话，以便在钉钉工作台中使用 AI 助手。

#### 验收标准

1. WHEN 钉钉推送 JSON 格式的机器人回调消息时，THE DingtalkChannelAdapter SHALL 从 HTTP Header 中提取 `timestamp` 和 `sign` 字段进行签名验证
2. THE DingtalkChannelAdapter SHALL 使用 `HmacSHA256(timestamp + "\n" + appSecret)` 算法计算签名并与请求中的 `sign` 比对
3. IF 钉钉消息签名验证失败，THEN THE DingtalkChannelAdapter SHALL 记录 WARN 级别日志并返回错误提示 JSON
4. WHEN 签名验证通过后，THE DingtalkChannelAdapter SHALL 从回调 JSON 中提取 `text.content`、`senderStaffId`（或 `senderId`）、`conversationId`、`conversationType`、`senderNick` 等字段
5. WHEN 消息内容包含 `@机器人` 前缀时，THE DingtalkChannelAdapter SHALL 去除 `@` 前缀后提取实际文本内容
6. THE DingtalkChannelAdapter SHALL 将钉钉消息标准化为 `GatewayMessage`，填充 `channelType=DINGTALK`、`userId=senderStaffId`、`sessionId=dingtalk:{conversationId}`、`channelMetadata=DingtalkMetadata`
7. THE DingtalkChannelAdapter SHALL 同步调用 `MessageGateway.process()` 处理消息，并将 `GatewayResponse` 转换为钉钉响应格式在 Webhook 回调中返回（钉钉允许 20 秒同步响应）
8. WHEN 响应内容较长（超过 500 字符）或包含 Markdown 格式时，THE DingtalkChannelAdapter SHALL 使用钉钉 ActionCard 消息类型
9. WHEN 响应内容为短文本时，THE DingtalkChannelAdapter SHALL 使用钉钉 text 消息类型
10. IF 同步响应超时，THEN THE DingtalkChannelAdapter SHALL 通过钉钉 API 异步推送响应消息


### 需求 4：飞书通道适配器

**用户故事：** 作为飞书用户，我希望通过飞书应用与 LifePilot Agent 对话，以便在飞书工作台中使用 AI 助手。

#### 验收标准

1. WHEN 飞书发送 Challenge 验证请求（JSON 中包含 `challenge` 字段）时，THE FeishuChannelAdapter SHALL 原样返回 `{"challenge": "<challenge_value>"}` 完成验证
2. WHEN 飞书推送的事件回调包含 `encrypt` 字段时，THE FeishuChannelAdapter SHALL 使用 AES-256-CBC 算法解密事件内容
3. WHEN 飞书推送 `im.message.receive_v1` 类型的消息事件时，THE FeishuChannelAdapter SHALL 从事件 JSON 中提取 `sender.sender_id.open_id`、`message.message_id`、`message.chat_id`、`message.chat_type`、`message.content` 等字段
4. WHEN 消息类型为 `text` 时，THE FeishuChannelAdapter SHALL 从 `content` JSON 中提取 `text` 字段作为消息文本
5. THE FeishuChannelAdapter SHALL 将飞书消息标准化为 `GatewayMessage`，填充 `channelType=FEISHU`、`userId=open_id`、`sessionId=feishu:{chatId}:{userId}`、`channelMetadata=FeishuMetadata`
6. THE FeishuChannelAdapter SHALL 使用 `event_id` 进行事件去重，对已处理的事件直接返回 `{"code": 0}` 而不重复处理
7. WHEN 消息标准化完成后，THE FeishuChannelAdapter SHALL 在 Virtual Thread 上异步调用 `MessageGateway.process()` 推入中间件管道，并在 Webhook 回调中立即返回 `{"code": 0}`
8. WHEN Agent 处理完成生成 `GatewayResponse` 后，THE FeishuChannelAdapter SHALL 通过飞书 API 主动推送响应消息给用户
9. WHEN 响应内容较长或包含 Markdown 格式时，THE FeishuChannelAdapter SHALL 使用飞书 Post 富文本消息类型发送
10. WHEN 响应内容为短文本时，THE FeishuChannelAdapter SHALL 使用飞书 text 消息类型发送
11. IF 飞书 API 调用失败，THEN THE FeishuChannelAdapter SHALL 将失败消息加入重试队列
12. THE FeishuChannelAdapter SHALL 维护事件去重缓存，缓存容量上限通过 `GatewayProperties` 配置，过期事件自动清理

### 需求 5：Webhook 签名验证与安全

**用户故事：** 作为系统管理员，我希望所有 Webhook 回调都经过严格的签名验证，以防止伪造请求和重放攻击。

#### 验收标准

1. THE WecomSignatureVerifier SHALL 使用 `SHA1(sort(token, timestamp, nonce, encrypt))` 算法验证企微消息签名
2. THE DingtalkSignatureVerifier SHALL 使用 `HmacSHA256(timestamp + "\n" + appSecret)` 算法验证钉钉消息签名
3. THE WecomCrypto SHALL 使用 AES-256-CBC 算法对企微消息进行加解密，密钥从 `EncodingAESKey` Base64 解码获得
4. THE FeishuCrypto SHALL 使用 AES-256-CBC 算法对飞书事件回调进行解密
5. WHEN 签名验证中的时间戳与服务器当前时间差超过配置的容忍窗口时，THE SignatureVerifier SHALL 拒绝该请求（防重放攻击）
6. THE ChannelAdapter SHALL 将各通道的 `token`、`secret`、`encodingAesKey`、`appSecret` 等敏感配置通过 `GatewayProperties` 外部化，禁止硬编码
7. THE ChannelAdapter SHALL 在日志中对敏感字段（签名、密钥、加密消息体）进行脱敏处理

### 需求 6：通道配置外部化

**用户故事：** 作为系统管理员，我希望通过 `application.yml` 配置各通道的连接参数，以便灵活管理通道启用状态和认证凭据。

#### 验收标准

1. THE GatewayProperties SHALL 在 `channels.wecom` 下提供 `corp-id`、`agent-id`、`secret`、`token`、`encoding-aes-key` 配置项
2. THE GatewayProperties SHALL 在 `channels.dingtalk` 下提供 `app-key`、`app-secret`、`robot-code` 配置项
3. THE GatewayProperties SHALL 在 `channels.feishu` 下提供 `app-id`、`app-secret`、`verification-token`、`encrypt-key` 配置项
4. WHILE 通道的 `enabled` 配置为 `false` 时，THE ChannelAutoConfiguration SHALL 不注册该通道的 ChannelAdapter Bean 和相关组件
5. WHILE 通道的 `enabled` 配置为 `true` 时，THE ChannelAutoConfiguration SHALL 验证必填配置项（如 `corp-id`、`app-key`、`app-id`）是否已设置
6. IF 通道已启用但必填配置项缺失，THEN THE ChannelAutoConfiguration SHALL 在启动时记录 ERROR 日志并跳过该通道的注册
7. THE GatewayProperties SHALL 提供 `channels.webhook.timestamp-tolerance-seconds` 配置项，默认值 300 秒，用于签名验证的时间戳容忍窗口
8. THE GatewayProperties SHALL 提供 `channels.feishu.event-cache-max-size` 配置项，默认值 10000，用于飞书事件去重缓存容量上限

### 需求 7：通道认证策略扩展

**用户故事：** 作为开发者，我希望各通道的 Webhook 签名验证能集成到现有的 `AuthMiddleware` 认证管道中，以便复用统一的认证框架。

#### 验收标准

1. THE AuthStrategy sealed interface SHALL 扩展 permits 列表，新增 `WecomAuthStrategy`、`DingtalkAuthStrategy`、`FeishuAuthStrategy` 三个实现
2. THE WecomAuthStrategy SHALL 在 `authenticate()` 方法中从 `GatewayMessage.channelMetadata()` 提取 `WecomMetadata`，执行 SHA1 签名验证
3. THE DingtalkAuthStrategy SHALL 在 `authenticate()` 方法中从 `GatewayMessage.channelMetadata()` 提取 `DingtalkMetadata`，执行 HmacSHA256 签名验证
4. THE FeishuAuthStrategy SHALL 在 `authenticate()` 方法中从 `GatewayMessage.channelMetadata()` 提取 `FeishuMetadata`，执行事件验证（验证 Token 比对）
5. WHEN 认证通过时，THE AuthStrategy SHALL 返回 `AuthResult.success()` 并携带用户标识
6. IF 认证失败，THEN THE AuthStrategy SHALL 返回 `AuthResult.failure()` 并携带失败原因

### 需求 8：消息格式转换器

**用户故事：** 作为开发者，我希望 `GatewayResponse` 到各通道特定格式的转换逻辑被封装为独立的 `MessageConverter`，以便各通道适配器复用统一的转换接口。

#### 验收标准

1. THE MessageConverter interface SHALL 定义 `convert(ResponseContent)` 方法，将统一响应内容转换为通道特定的字符串格式
2. THE WecomMessageConverter SHALL 将 `TextContent` 转换为纯文本、将 `MarkdownContent` 保留 Markdown 格式、将 `CardContent` 转换为企微 Markdown 卡片格式
3. THE DingtalkMessageConverter SHALL 将 `TextContent` 转换为纯文本、将 `MarkdownContent` 保留 Markdown 格式、将 `CardContent` 转换为钉钉 ActionCard 标题+正文格式
4. THE FeishuMessageConverter SHALL 将 `TextContent` 转换为纯文本、将 `MarkdownContent` 保留 Markdown 格式、将 `CardContent` 转换为飞书 Post 富文本格式
5. FOR ALL ResponseContent 实例，THE MessageConverter SHALL 对 `convert()` 方法的输入输出满足：转换后的字符串不为空且不丢失原始文本内容（保留性）

### 需求 9：通道适配器生命周期与重连

**用户故事：** 作为系统管理员，我希望通道适配器具备自动重连和失败消息重发能力，以保证企业 IM 通道的高可用性。

#### 验收标准

1. THE AbstractChannelAdapter SHALL 管理通道状态机（CREATED → STARTING → RUNNING → STOPPING → STOPPED / ERROR）
2. WHEN 通道启动失败或运行时发生异常进入 ERROR 状态时，THE AbstractChannelAdapter SHALL 使用指数退避策略自动重连（初始延迟、倍数、上限从 `GatewayProperties.reconnect` 读取）
3. IF 重连次数超过 `GatewayProperties.reconnect.maxAttempts` 配置的上限，THEN THE AbstractChannelAdapter SHALL 停止重连并记录 ERROR 日志
4. WHEN 响应发送失败时，THE AbstractChannelAdapter SHALL 将失败消息加入内存重试队列
5. THE FailedMessageRetryScheduler SHALL 定期扫描各通道的失败消息队列，对健康通道的失败消息进行重试
6. IF 失败消息重试次数超过上限，THEN THE FailedMessageRetryScheduler SHALL 将消息持久化到 `failed_messages` 数据库表
7. THE MessageGateway SHALL 在应用启动时调用已注册通道的 `start()` 方法，在应用关闭时调用 `stop()` 方法

### 需求 10：通道适配器自动配置

**用户故事：** 作为开发者，我希望通道适配器通过 Spring AutoConfiguration 自动注册，以便根据配置动态启用或禁用通道。

#### 验收标准

1. THE ChannelAdapterAutoConfiguration SHALL 根据 `GatewayProperties.channels.wecom.enabled` 条件注册 WecomChannelAdapter Bean
2. THE ChannelAdapterAutoConfiguration SHALL 根据 `GatewayProperties.channels.dingtalk.enabled` 条件注册 DingtalkChannelAdapter Bean
3. THE ChannelAdapterAutoConfiguration SHALL 根据 `GatewayProperties.channels.feishu.enabled` 条件注册 FeishuChannelAdapter Bean
4. WHEN 通道 Bean 注册成功后，THE ChannelAdapterAutoConfiguration SHALL 自动将其注册到 `MessageGateway`
5. THE ChannelAdapterAutoConfiguration SHALL 同时注册对应通道的 `AuthStrategy` 和 `MessageConverter` Bean
6. THE WebhookController Bean SHALL 仅在至少一个 Webhook 通道启用时注册

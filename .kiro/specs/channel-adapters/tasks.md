# Implementation Plan: Channel Adapters（企微 / 钉钉 / 飞书通道适配器）

## Overview

在已完成的 Gateway 框架层和中间件实现基础上，实现企业微信、钉钉、飞书三个通道的 Webhook 回调接入。按自底向上顺序：先扩展配置和基础设施，再实现加解密/签名工具，然后实现各通道适配器及其依赖组件，最后实现自动配置和集成测试。

## Tasks

- [x] 1. 扩展 GatewayProperties 配置与基础设施
  - [x] 1.1 扩展 GatewayProperties 通道配置 record
    - 扩展 `WecomChannelProperties` 添加 `corpId`、`agentId`、`secret`、`token`、`encodingAesKey` 字段
    - 扩展 `DingtalkChannelProperties` 添加 `appKey`、`appSecret`、`robotCode` 字段
    - 扩展 `FeishuChannelProperties` 添加 `appId`、`appSecret`、`verificationToken`、`encryptKey`、`eventCacheMaxSize` 字段
    - 新增 `WebhookProperties` 嵌套 record（`timestampToleranceSeconds`、`maxRetryCount`、`retryIntervalSeconds`）
    - 在 `GatewayProperties` 中添加 `webhook` 字段
    - 更新 `application.yml` 声明所有新增配置项及默认值
    - _Requirements: 6.1, 6.2, 6.3, 6.7, 6.8_

  - [x] 1.2 实现 ChannelState 枚举和 AbstractChannelAdapter 基类
    - 创建 `ChannelState` 枚举（CREATED / STARTING / RUNNING / STOPPING / STOPPED / ERROR）
    - 实现 `AbstractChannelAdapter` 抽象类，封装状态机管理（模板方法 start/stop/sendResponse）
    - 实现 `submitAsync()` 方法（Virtual Thread 异步提交到 Gateway）
    - 实现 `submitSync()` 方法（同步提交到 Gateway）
    - 实现指数退避重连逻辑 `scheduleReconnect()`
    - 实现 `FailedMessage` record 和失败消息队列
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [ ]* 1.3 写属性测试：AbstractChannelAdapter 状态机转换
    - **Property 11: AbstractChannelAdapter 状态机转换正确性**
    - **Validates: Requirements 9.1**

  - [ ]* 1.4 写属性测试：指数退避延迟计算
    - **Property 12: 指数退避延迟计算正确性**
    - **Validates: Requirements 9.2**

  - [x] 1.5 实现 MessageConverter 接口
    - 创建 `MessageConverter` 接口，定义 `channelType()` 和 `convert(ResponseContent)` 方法
    - 包路径：`com.lifepilot.interaction.channel.converter`
    - _Requirements: 8.1_

  - [x] 1.6 扩展 AuthStrategy sealed interface
    - 修改 `AuthStrategy` permits 列表，新增 `WecomAuthStrategy`、`DingtalkAuthStrategy`、`FeishuAuthStrategy`
    - 更新所有 `AuthStrategy` 的 switch 穷举点
    - _Requirements: 7.1_

- [x] 2. 实现加解密与签名验证工具
  - [x] 2.1 实现 WecomCrypto（AES-256-CBC 加解密）
    - 密钥从 `EncodingAESKey` Base64 解码获得
    - 实现 `encrypt(String)` 和 `decrypt(String)` 方法
    - 包路径：`com.lifepilot.interaction.channel.wecom`
    - _Requirements: 2.4, 5.3_

  - [ ]* 2.2 写属性测试：WecomCrypto 加解密 round-trip
    - **Property 1: WecomCrypto AES-256-CBC 加解密 round-trip**
    - **Validates: Requirements 2.4, 5.3**

  - [x] 2.3 实现 WecomSignatureVerifier（SHA1 签名验证）
    - 实现 `compute(token, timestamp, nonce, encrypt)` → SHA1 签名
    - 实现 `verify(msgSignature, timestamp, nonce, encrypt)` → boolean
    - 包路径：`com.lifepilot.interaction.channel.wecom`
    - _Requirements: 2.2, 5.1_

  - [ ]* 2.4 写属性测试：WecomSignatureVerifier 签名验证正确性
    - **Property 3: WecomSignatureVerifier SHA1 签名验证正确性**
    - **Validates: Requirements 2.2, 5.1, 7.2**

  - [x] 2.5 实现 DingtalkSignatureVerifier（HmacSHA256 签名验证）
    - 实现 `verify(sign, timestamp, appSecret)` → boolean
    - 包路径：`com.lifepilot.interaction.channel.dingtalk`
    - _Requirements: 3.1, 3.2, 5.2_

  - [ ]* 2.6 写属性测试：DingtalkSignatureVerifier 签名验证正确性
    - **Property 4: DingtalkSignatureVerifier HmacSHA256 签名验证正确性**
    - **Validates: Requirements 3.1, 3.2, 5.2, 7.3**

  - [x] 2.7 实现 FeishuCrypto（AES-256-CBC 解密）
    - 密钥从 `encryptKey` SHA256 派生
    - 实现 `decrypt(String)` 方法
    - 包路径：`com.lifepilot.interaction.channel.feishu`
    - _Requirements: 4.2, 5.4_

  - [ ]* 2.8 写属性测试：FeishuCrypto 加解密 round-trip
    - **Property 2: FeishuCrypto AES-256-CBC 加解密 round-trip**
    - **Validates: Requirements 4.2, 5.4**

  - [ ]* 2.9 写属性测试：时间戳容忍窗口拒绝过期请求
    - **Property 5: 时间戳容忍窗口拒绝过期请求**
    - **Validates: Requirements 5.5**

- [x] 3. Checkpoint - 确保基础设施和加解密工具测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. 实现企业微信通道
  - [x] 4.1 实现 WecomApiClient（主动推送 API）
    - 使用 `RestClient` 发起 HTTP 请求
    - 实现 access_token 获取（缓存 + 自动刷新）
    - 实现 `sendText(userId, text)` 和 `sendMarkdown(userId, markdown)` 方法
    - _Requirements: 2.8, 2.9, 2.10_

  - [x] 4.2 实现 WecomMessageConverter
    - TextContent → 纯文本，MarkdownContent → 企微 Markdown，CardContent → 企微 Markdown 卡片
    - _Requirements: 8.2_

  - [x] 4.3 实现 WecomAuthStrategy
    - 从 `WecomMetadata` 提取签名参数，调用 `WecomSignatureVerifier` 验证
    - 通过后返回 `AuthResult.success(userId, TrustLevel.VERIFIED)`
    - _Requirements: 7.2, 7.5, 7.6_

  - [x] 4.4 实现 WecomChannelAdapter
    - 继承 `AbstractChannelAdapter`
    - 实现 `handleVerification()` URL 验证（GET 请求）
    - 实现 `handleMessage()` 消息接收（POST 请求）：签名验证 → AES 解密 → XML 解析 → normalize → 异步提交
    - 实现 `normalize()` 将企微消息转换为 `GatewayMessage`（channelType=WECOM, sessionId=wecom:{corpId}:{fromUser}）
    - 实现 `doSendResponse()` 通过 `WecomApiClient` 主动推送
    - 文本以 `/` 开头转为 `CommandMessage`，否则转为 `TextMessage`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11_

  - [ ]* 4.5 写属性测试：消息内容分类正确性
    - **Property 6: 消息内容分类正确性**
    - **Validates: Requirements 2.5**

  - [x] 4.6 写单元测试：WecomChannelAdapter
    - 测试 URL 验证流程、消息处理流程、签名失败场景、XML 解析边界
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

- [x] 5. 实现钉钉通道
  - [x] 5.1 实现 DingtalkApiClient（主动推送 API）
    - 使用 `RestClient` 发起 HTTP 请求
    - 实现 access_token 获取（缓存 + 自动刷新）
    - 实现 `sendText(userId, text)` 和 `sendActionCard(userId, title, text)` 方法
    - _Requirements: 3.10_

  - [x] 5.2 实现 DingtalkMessageConverter
    - TextContent → text 类型 JSON，MarkdownContent → markdown 类型 JSON，CardContent → ActionCard JSON
    - _Requirements: 8.3_

  - [x] 5.3 实现 DingtalkAuthStrategy
    - 从 `DingtalkMetadata` 提取 sign/timestamp，调用 `DingtalkSignatureVerifier` 验证
    - _Requirements: 7.3, 7.5, 7.6_

  - [x] 5.4 实现 DingtalkChannelAdapter
    - 继承 `AbstractChannelAdapter`
    - 实现 `handleMessage()` 消息接收：签名验证 → JSON 解析 → @ 前缀去除 → normalize → 同步提交
    - 实现 `normalize()` 将钉钉消息转换为 `GatewayMessage`（channelType=DINGTALK, sessionId=dingtalk:{conversationId}）
    - 同步超时时降级为异步推送（通过 `DingtalkApiClient`）
    - 响应超过 500 字符或含 Markdown 时使用 ActionCard 类型
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10_

  - [ ]* 5.5 写属性测试：钉钉 @ 前缀去除
    - **Property 7: 钉钉 @ 前缀去除**
    - **Validates: Requirements 3.5**

  - [x] 5.6 写单元测试：DingtalkChannelAdapter
    - 测试消息处理流程、@ 去除、同步/异步切换、签名失败场景
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

- [x] 6. 实现飞书通道
  - [x] 6.1 实现 FeishuApiClient（主动推送 API）
    - 使用 `RestClient` 发起 HTTP 请求
    - 实现 tenant_access_token 获取（缓存 + 自动刷新）
    - 实现 `sendText(chatId, text)` 和 `sendPost(chatId, richText)` 方法
    - _Requirements: 4.8, 4.9, 4.10_

  - [x] 6.2 实现 FeishuMessageConverter
    - TextContent → text 类型 JSON，MarkdownContent → post 富文本 JSON，CardContent → post 富文本 JSON
    - _Requirements: 8.4_

  - [x] 6.3 实现 FeishuAuthStrategy
    - 从 `FeishuMetadata` 提取 eventId，验证 verification token
    - _Requirements: 7.4, 7.5, 7.6_

  - [x] 6.4 实现 FeishuChannelAdapter
    - 继承 `AbstractChannelAdapter`
    - 实现 `handleEvent()` 事件接收：Challenge 验证 → 加密解密 → event_id 去重 → JSON 解析 → normalize → 异步提交
    - 实现事件去重缓存（`ConcurrentHashMap` + 容量上限从 `GatewayProperties` 读取）
    - 实现 `normalize()` 将飞书消息转换为 `GatewayMessage`（channelType=FEISHU, sessionId=feishu:{chatId}:{userId}）
    - 实现 `doSendResponse()` 通过 `FeishuApiClient` 主动推送
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11, 4.12_

  - [ ]* 6.5 写属性测试：飞书事件去重幂等性
    - **Property 10: 飞书事件去重幂等性**
    - **Validates: Requirements 4.6**

  - [x] 6.6 写单元测试：FeishuChannelAdapter
    - 测试 Challenge 验证、事件去重、加密事件处理、消息解析边界
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 7. Checkpoint - 确保三个通道适配器测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. 实现 WebhookController 和 MessageConverter 属性测试
  - [x] 8.1 实现 WebhookController
    - 统一 Webhook 端点：GET/POST `/api/webhook/wecom`、POST `/api/webhook/dingtalk`、POST `/api/webhook/feishu`
    - 按通道类型分发到对应适配器
    - 未知通道返回 HTTP 404
    - 异常时返回平台要求的成功响应（企微 "success"、钉钉 `{}`、飞书 `{"code": 0}`）
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [x] 8.2 写单元测试：WebhookController
    - 测试路由分发、未知通道 404、异常处理
    - _Requirements: 1.1, 1.6, 1.7_

  - [ ]* 8.3 写属性测试：MessageConverter 内容保留性
    - **Property 9: MessageConverter 内容保留性**
    - **Validates: Requirements 8.2, 8.3, 8.4, 8.5**

  - [ ]* 8.4 写属性测试：通道 normalize 字段映射正确性
    - **Property 8: 通道 normalize 字段映射正确性**
    - **Validates: Requirements 2.6, 3.6, 4.5**

- [x] 9. 实现失败消息重试与数据库迁移
  - [x] 9.1 创建 Flyway 迁移脚本（failed_messages 表）
    - 创建 `failed_messages` 表（id, channel, user_id, response_id, content, retry_count, error, created_at, updated_at）
    - 创建索引 `idx_failed_messages_channel` 和 `idx_failed_messages_created_at`
    - _Requirements: 9.6_

  - [x] 9.2 实现 FailedMessageRetryScheduler
    - `@Scheduled` 定期扫描各通道的失败消息队列
    - 对 state == RUNNING 的通道重试发送
    - 超过最大重试次数的消息持久化到 `failed_messages` 表
    - _Requirements: 9.5, 9.6_

- [x] 10. 实现 ChannelAdapterAutoConfiguration 和通道生命周期
  - [x] 10.1 实现 ChannelAdapterAutoConfiguration
    - 根据 `GatewayProperties.channels.xxx.enabled` 条件注册各通道 Bean
    - 同时注册对应的 `AuthStrategy` 和 `MessageConverter` Bean
    - `WebhookController` 仅在至少一个 Webhook 通道启用时注册
    - 通道 Bean 注册后自动注册到 `MessageGateway`
    - 已启用但必填配置缺失时记录 ERROR 日志并跳过注册
    - _Requirements: 6.4, 6.5, 6.6, 10.1, 10.2, 10.3, 10.4, 10.5, 10.6_

  - [x] 10.2 实现通道生命周期管理
    - `MessageGateway` 启动时调用已注册通道的 `start()`，关闭时调用 `stop()`
    - _Requirements: 9.7_

  - [ ]* 10.3 写属性测试：通道条件注册正确性
    - **Property 13: 通道条件注册正确性**
    - **Validates: Requirements 6.4, 10.1, 10.2, 10.3**

- [x] 11. 集成测试
  - [x] 11.1 写集成测试：ChannelAdapter_Gateway 端到端流程
    - 通道适配器 → Gateway → 中间件管道端到端流程验证
    - Mock 外部 API（企微/钉钉/飞书 API），不依赖真实平台
    - _Requirements: 2.7, 3.7, 4.7_

  - [x] 11.2 写集成测试：ChannelAdapterAutoConfiguration 条件注册
    - 验证配置驱动的通道启用/禁用
    - 验证 Spring Context 加载、Bean 注入链完整
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6_

  - [x] 11.3 写集成测试：WebhookController HTTP 端点
    - 使用 `@SpringBootTest` + `MockMvc` 测试 HTTP 请求/响应
    - 验证端点注册、路由分发、异常处理
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

- [x] 12. Final checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests use jqwik (already in project dependencies)
- All API Clients use Spring `RestClient`, access_token cached in memory with auto-refresh
- Integration tests mock external IM platform APIs, no real platform dependency
- 遵循编码规范：中文注释/Javadoc/测试方法名，@author zsg，@since 2026-02-25

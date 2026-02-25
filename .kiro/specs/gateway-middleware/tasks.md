# Implementation Plan: gateway-middleware

## Overview

基于已完成的 Agent 引擎、工具系统、MCP、记忆系统、Skill 系统、CLI 交互层和主动推理引擎，实现 Gateway 核心框架。实现顺序：PBT 依赖配置 → 统一消息模型（ChannelType → MessageContent → ChannelMetadata → ResponseContent + TokenUsage → GatewayMessage → GatewayResponse）→ 中间件管道引擎（GatewayMiddleware → MiddlewareContext → MiddlewareChain → MiddlewarePipeline）→ 通道适配器接口 → 消息网关（MessageGateway + DefaultMessageGateway）→ 配置属性 → Flyway V12 → AutoConfiguration → 集成测试。

## Tasks

- [ ] 1. 项目配置与 PBT 依赖
  - [ ] 1.1 在 pom.xml 中添加 jqwik 依赖
    - 添加 `net.jqwik:jqwik:1.9.2`，scope 为 test
    - 创建 `src/test/resources/jqwik.properties`，设置 `jqwik.tries.default=100`
    - _Requirements: 无（基础设施）_

- [ ] 2. 统一消息模型 — 枚举与基础类型
  - [ ] 2.1 实现 ChannelType 枚举
    - 包路径 `com.lifepilot.interaction.model`
    - 五个枚举值：CLI("cli", false)、WEB("web", false)、WECOM("wecom", true)、DINGTALK("dingtalk", true)、FEISHU("feishu", true)
    - 字段：value（String）、requiresWebhook（boolean）
    - 静态方法 fromValue(String)：遍历枚举值匹配，未知值抛 IllegalArgumentException
    - 类级别 Javadoc 包含 @author zsg 和 @since 日期
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [ ]* 2.2 编写 ChannelType 属性测试
    - **Property 1: ChannelType fromValue 往返一致**
    - **Property 2: ChannelType fromValue 拒绝无效值**
    - **Validates: Requirements 1.2, 1.3, 1.4**

  - [ ] 2.3 实现 MessageContent sealed interface 及其 5 个 permits
    - 包路径 `com.lifepilot.interaction.model`
    - sealed interface 声明 toPlainText() 方法
    - TextMessage(String text)：紧凑构造器验证非空非 blank
    - CommandMessage(String command, List<String> args, String rawText)：List.copyOf(args)，静态 parse(String) 方法
    - FileMessage(String fileName, String mimeType, byte[] data, @Nullable String caption)
    - CardMessage(String title, String description, List<CardAction> actions)：List.copyOf(actions)，内嵌 CardAction record
    - EventMessage(String eventType, Map<String, Object> payload)：Map.copyOf(payload)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

  - [ ]* 2.4 编写 MessageContent 属性测试
    - **Property 3: MessageContent 的 toPlainText 非空**
    - **Property 4: TextMessage 拒绝空白文本**
    - **Property 5: CommandMessage.parse 往返一致**
    - **Property 6: CommandMessage.parse 拒绝非命令文本**
    - **Property 7: Record 集合字段防御性拷贝**（CommandMessage.args、CardMessage.actions、EventMessage.payload）
    - **Validates: Requirements 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8**

  - [ ] 2.5 实现 ChannelMetadata sealed interface 及其 5 个 permits
    - 包路径 `com.lifepilot.interaction.model`
    - sealed interface 声明 channelType() 方法
    - CliMetadata(String terminalType, int terminalWidth, boolean colorSupported)
    - WebMetadata(String userAgent, String remoteAddr, @Nullable String sessionToken, boolean acceptsSse)
    - WecomMetadata(String corpId, String agentId, String msgSignature, String timestamp, String nonce, @Nullable String encryptedMsg)
    - DingtalkMetadata(String chatbotUserId, String conversationId, String conversationType, String senderNick, String sign, long timestamp, boolean isAtAll)
    - FeishuMetadata(String appId, String tenantKey, String messageId, @Nullable String chatId, String chatType, String eventId, String eventType)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

  - [ ]* 2.6 编写 ChannelMetadata 属性测试
    - **Property 8: ChannelMetadata 与 ChannelType 一致**
    - **Validates: Requirements 3.2**

- [ ] 3. 统一消息模型 — ResponseContent、TokenUsage
  - [ ] 3.1 实现 ResponseContent sealed interface 及其 4 个 permits
    - 包路径 `com.lifepilot.interaction.model`
    - sealed interface 声明 toPlainText() 方法
    - TextContent(String text)
    - MarkdownContent(String markdown)
    - CardContent(String title, String body, List<CardAction> actions)：List.copyOf(actions)，内嵌 CardAction(String label, String url) record
    - StreamingContent(String streamId)
    - _Requirements: 6.1, 6.2, 6.3_

  - [ ] 3.2 实现 TokenUsage record
    - 包路径 `com.lifepilot.interaction.model`
    - 字段：promptTokens、completionTokens、totalTokens、modelId
    - 静态常量 ZERO = new TokenUsage(0, 0, 0, "none")
    - _Requirements: 6.4, 6.5_

- [ ] 4. 统一消息模型 — GatewayMessage 与 GatewayResponse
  - [ ] 4.1 实现 GatewayMessage record
    - 包路径 `com.lifepilot.interaction.model`
    - 字段：messageId、channelType、userId、sessionId、content（MessageContent）、attachments（List<Attachment>）、channelMetadata（ChannelMetadata）、timestamp（Instant）、traceHeaders（Map<String, String>）
    - 紧凑构造器：messageId 默认 UUID.randomUUID().toString()，timestamp 默认 Instant.now()，集合 List.copyOf / Map.copyOf
    - 便捷方法：contentAsText()、isCommand()、isEvent()
    - 内嵌 Attachment record：attachmentId、fileName、mimeType、data（byte[]）、size（long）
    - @Builder(toBuilder = true)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_

  - [ ]* 4.2 编写 GatewayMessage 属性测试
    - **Property 9: GatewayMessage 默认值填充**
    - **Property 10: GatewayMessage 便捷方法与内容类型一致**
    - **Property 11: GatewayMessage 的 toBuilder 往返一致**
    - **Property 7: Record 集合字段防御性拷贝**（attachments、traceHeaders）
    - **Validates: Requirements 4.2, 4.3, 4.4, 4.5, 4.6**

  - [ ] 4.3 实现 GatewayResponse record
    - 包路径 `com.lifepilot.interaction.model`
    - 字段：responseId、channelType、content（ResponseContent）、attachments（List<Attachment>）、metadata（Map<String, Object>）、latency（Duration）、tokenUsage（@Nullable TokenUsage）、statusCode（int）、errorMessage（@Nullable String）
    - 紧凑构造器：responseId 默认 UUID，集合 List.copyOf / Map.copyOf
    - 工厂方法：success(ChannelType, ResponseContent)、error(ChannelType, String, int)、rateLimited(ChannelType)、unauthorized(ChannelType)
    - 便捷方法：isSuccess()（statusCode 200-299）
    - @Builder(toBuilder = true)
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_

  - [ ]* 4.4 编写 GatewayResponse 属性测试
    - **Property 12: GatewayResponse.error 工厂方法保持状态码**
    - **Property 13: GatewayResponse.isSuccess 与状态码范围一致**
    - **Property 11: GatewayResponse 的 toBuilder 往返一致**
    - **Property 7: Record 集合字段防御性拷贝**（attachments、metadata）
    - **Validates: Requirements 5.2, 5.4, 5.7, 5.8**

- [ ] 5. Checkpoint — 确认统一消息模型编译通过
  - 确保所有消息模型类编译通过，getDiagnostics 无错误，ask the user if questions arise.

- [ ] 6. 中间件管道引擎
  - [ ] 6.1 实现 GatewayMiddleware 接口
    - 包路径 `com.lifepilot.interaction.middleware`
    - 方法：process(GatewayMessage, MiddlewareChain) → GatewayResponse、order() → int、name() → String
    - 默认方法：enabled() 默认返回 true
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [ ] 6.2 实现 MiddlewareContext
    - 包路径 `com.lifepilot.interaction.middleware`
    - ConcurrentHashMap<String, Object> 存储属性
    - 方法：set(String, Object)、get(String, Class<T>) → Optional<T>、require(String, Class<T>) → T、has(String) → boolean、remove(String)、snapshot() → Map<String, Object>（不可变）
    - 预定义键常量：KEY_AUTH_RESULT、KEY_TRUST_LEVEL、KEY_RATE_LIMIT_REMAINING、KEY_SECURITY_CHECK_RESULT、KEY_ROUTE_DECISION、KEY_AGENT_RESPONSE、KEY_TOKEN_USAGE
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_

  - [ ]* 6.3 编写 MiddlewareContext 属性测试
    - **Property 16: MiddlewareContext 类型安全存取**
    - **Property 17: MiddlewareContext set/has/remove 往返一致**
    - **Property 18: MiddlewareContext snapshot 不可变性**
    - **Validates: Requirements 9.2, 9.3, 9.4, 9.5**

  - [ ] 6.4 实现 MiddlewareChain
    - 包路径 `com.lifepilot.interaction.middleware`
    - 持有有序中间件列表、MiddlewareContext、当前索引（AtomicInteger 或 int）
    - next(GatewayMessage)：索引推进，跳过 enabled()==false 的中间件，执行下一个启用的中间件
    - 所有中间件执行完毕后返回 statusCode=500 的默认错误响应
    - context() 方法暴露共享上下文
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

  - [ ]* 6.5 编写 MiddlewareChain 属性测试
    - **Property 14: MiddlewareChain 按 order 执行启用的中间件并跳过禁用的**
    - **Property 15: MiddlewareChain 耗尽后返回 500**
    - **Validates: Requirements 8.2, 8.3, 8.4**

  - [ ] 6.6 实现 MiddlewarePipeline
    - 包路径 `com.lifepilot.interaction.middleware`
    - 构造时收集 List<GatewayMiddleware>，按 order() 排序，存入 CopyOnWriteArrayList
    - execute(GatewayMessage)：每次创建新 MiddlewareContext + MiddlewareChain
    - register(GatewayMiddleware)：动态注册，重新排序
    - unregister(String name)：按名称注销
    - getMiddlewares()：返回不可变快照 List.copyOf
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6_

  - [ ]* 6.7 编写 MiddlewarePipeline 属性测试
    - **Property 19: MiddlewarePipeline 排序不变量**
    - **Property 20: MiddlewarePipeline 请求隔离**
    - **Property 21: MiddlewarePipeline 动态注销**
    - **Validates: Requirements 10.1, 10.2, 10.3, 10.4**

- [ ] 7. Checkpoint — 确认中间件管道引擎编译通过
  - 确保 GatewayMiddleware、MiddlewareContext、MiddlewareChain、MiddlewarePipeline 编译通过，getDiagnostics 无错误，ask the user if questions arise.

- [ ] 8. 通道适配器接口与消息网关
  - [ ] 8.1 实现 ChannelAdapter 接口
    - 包路径 `com.lifepilot.interaction.channel`
    - 方法：channelType() → ChannelType、normalize(Object rawMessage) → GatewayMessage、sendResponse(String userId, GatewayResponse response)、start()、stop()
    - _Requirements: 13.1, 13.2, 13.3, 13.4_

  - [ ] 8.2 实现 MessageGateway 接口
    - 包路径 `com.lifepilot.interaction.gateway`
    - 方法：process(GatewayMessage) → GatewayResponse、registerChannel(ChannelAdapter)、unregisterChannel(ChannelType)、getChannel(ChannelType) → Optional<ChannelAdapter>、getAllChannels() → List<ChannelAdapter>、start()、stop()、isRunning() → boolean
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

  - [ ] 8.3 实现 DefaultMessageGateway
    - 包路径 `com.lifepilot.interaction.gateway`
    - ConcurrentHashMap<ChannelType, ChannelAdapter> 通道注册表
    - AtomicBoolean 运行状态
    - 构造函数注入 MiddlewarePipeline
    - process()：未运行返回 503，正常时推入 pipeline.execute()，补充 latency，异常返回 500
    - registerChannel()：已存在抛 IllegalStateException，网关运行中立即启动新通道
    - unregisterChannel()：移除通道
    - start()：启动所有通道，单通道失败不影响其他
    - stop()：停止所有通道，单通道失败不影响其他
    - 参数化日志记录入站消息、处理结果和异常
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 12.8, 12.9_

  - [ ]* 8.4 编写 DefaultMessageGateway 属性测试
    - **Property 22: DefaultMessageGateway 未运行时返回 503**
    - **Property 23: DefaultMessageGateway 处理后补充延迟**
    - **Property 24: DefaultMessageGateway 管道异常返回 500**
    - **Property 25: DefaultMessageGateway 重复注册抛异常**
    - **Property 26: DefaultMessageGateway 通道故障隔离**
    - **Validates: Requirements 12.2, 12.3, 12.4, 12.5, 12.7, 12.8**

- [ ] 9. Checkpoint — 确认通道适配器和消息网关编译通过
  - 确保 ChannelAdapter、MessageGateway、DefaultMessageGateway 编译通过，getDiagnostics 无错误，ask the user if questions arise.

- [ ] 10. 配置属性与数据库迁移
  - [ ] 10.1 实现 GatewayProperties 配置属性
    - 包路径 `com.lifepilot.interaction.config`
    - @ConfigurationProperties(prefix = "lifepilot.gateway")
    - 顶层 enabled 属性默认 true
    - 嵌套 record：MiddlewareProperties（含 auth/rateLimit/security/router/execution/audit 子 record，各含 enabled + order）、RateLimitProperties、SecurityProperties、AuthProperties、RouterProperties、ExecutionProperties、AuditProperties、ChannelsProperties、ReconnectProperties、SessionProperties
    - 所有字段使用 @DefaultValue 提供默认值
    - _Requirements: 14.1, 14.2, 14.3, 14.4_

  - [ ] 10.2 在 application.yml 中声明所有 Gateway 配置项及默认值
    - 配置前缀 `lifepilot.gateway`，键名使用 kebab-case
    - 包含所有嵌套配置的默认值
    - _Requirements: 14.3_

  - [ ] 10.3 创建 Flyway 迁移脚本 V12__create_gateway_tables.sql
    - 创建 gateway_sessions 表（session_id PK、user_id、channel_type CHECK、state CHECK、total_tokens、total_requests、metadata_json、created_at、updated_at、last_active_at）
    - 创建 gateway_audit_log 表（audit_id PK、message_id、session_id、channel_type、user_id、request_content_hash、request_summary、response_status_code、response_summary、route_type CHECK、latency_ms、prompt_tokens、completion_tokens、total_tokens、model_id、middleware_results_json、created_at）
    - 创建 rate_limit_counters 表（counter_id PK、user_id、channel_type、counter_type CHECK、window_start、window_end、current_count、max_count、created_at、updated_at）
    - 创建 failed_messages 表（message_id PK、channel_type、user_id、content_json、retry_count、max_retries、last_error、next_retry_at、status CHECK、created_at、updated_at）
    - 创建 user_behavior 表（user_id PK、total_interactions、security_incidents、last_incident_at、first_seen_at、created_at、updated_at）
    - 为所有表创建必要索引
    - 为包含 updated_at 的表创建自动更新触发器
    - 遵循 LifePilot 数据库规范（TEXT UUID、TEXT ISO 8601、INTEGER 布尔、TEXT _json 后缀）
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9_

- [ ] 11. Spring 自动配置
  - [ ] 11.1 实现 GatewayAutoConfiguration
    - 包路径 `com.lifepilot.interaction.config`
    - @Configuration + @ConditionalOnProperty(name = "lifepilot.gateway.enabled", matchIfMissing = true)
    - @EnableConfigurationProperties(GatewayProperties.class)
    - 注册 @Bean：MiddlewarePipeline（注入 List<GatewayMiddleware>）、MessageGateway（DefaultMessageGateway，注入 MiddlewarePipeline + List<ChannelAdapter>，自动注册所有通道）
    - @EventListener(ApplicationReadyEvent.class) 启动 MessageGateway
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5_

  - [ ] 11.2 在 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 中注册 GatewayAutoConfiguration
    - _Requirements: 16.6_

- [ ] 12. 集成测试
  - [ ]* 12.1 编写 GatewayAutoConfiguration 集成测试
    - 验证 Spring Context 加载、所有 Bean 正确注册
    - 验证 @ConditionalOnProperty 条件（enabled=false 时不注册）
    - 验证 ApplicationReadyEvent 触发后网关启动
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5, 16.6_

  - [ ]* 12.2 编写 Flyway V12 集成测试
    - 验证迁移脚本执行成功
    - 验证表结构、索引、触发器
    - 使用 @SpringBootTest + 内存 SQLite
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9_

  - [ ]* 12.3 编写 MiddlewarePipeline + Gateway 端到端集成测试
    - 构造 Mock 中间件链，验证消息从 Gateway 入口经 Pipeline 处理后返回响应
    - 验证 MiddlewareContext 跨中间件数据传递
    - _Requirements: 10.2, 12.3_

- [ ] 13. Final checkpoint — 确保所有测试通过
  - 确保所有编译通过，所有单元测试和集成测试通过，ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 每个子任务完成后独立 git commit，遵循 `<type>(gateway): <中文描述>` 格式
- 所有代码遵循编码规范：中文注释/Javadoc/日志/异常消息/测试方法名，英文类名/方法名/变量名
- 所有类级别 Javadoc 包含 @author zsg 和 @since 日期
- 所有服务通过 @Bean 注册在 GatewayAutoConfiguration 中，不使用 @Service/@Component
- 属性测试使用 jqwik，每个属性测试标注对应的设计属性编号
- 每个 sealed interface 及其 permits 放在同一文件中
- GatewayProperties 使用 record + @DefaultValue 绑定
- 本 spec 只定义框架和接口，不实现具体中间件和通道适配器

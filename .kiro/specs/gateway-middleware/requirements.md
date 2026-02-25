# Requirements Document

## Introduction

本文档定义 LifePilot Gateway 核心框架的需求。Gateway 是所有交互通道（CLI / Web / 企微 / 钉钉 / 飞书）的统一消息入口，负责将异构通道消息标准化为统一格式，通过可组合的中间件管道进行处理，并将响应路由回对应通道。

本 spec 聚焦于 Gateway 核心框架（统一消息模型、中间件管道引擎、消息网关、配置属性、数据库迁移、Spring 自动配置），不包含具体中间件实现和通道适配器实现。

参考文档：
- 架构设计：#[[file:docs/architecture/gateway-middleware.md]]
- 特性设计：#[[file:docs/features/gateway-channels.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## Glossary

- **Gateway**：消息网关，所有通道的统一入口，负责接收消息、推入中间件管道处理、路由响应
- **ChannelType**：通道类型枚举，标识消息来源通道（CLI / WEB / WECOM / DINGTALK / FEISHU）
- **GatewayMessage**：统一网关消息 record，所有通道消息标准化后的不可变表示
- **GatewayResponse**：统一网关响应 record，中间件管道的输出
- **MessageContent**：消息内容 sealed interface，穷举所有消息类型（文本、命令、文件、卡片、事件）
- **ChannelMetadata**：通道元数据 sealed interface，穷举所有通道的元数据类型
- **ResponseContent**：响应内容 sealed interface，穷举所有响应类型（文本、Markdown、卡片、流式）
- **TokenUsage**：Token 消耗统计 record
- **GatewayMiddleware**：中间件接口，责任链模式的处理节点
- **MiddlewareChain**：中间件链，索引式责任链执行器
- **MiddlewareContext**：中间件上下文，跨中间件的类型安全共享数据容器
- **MiddlewarePipeline**：中间件管道，收集中间件 Bean 并按 order 排序组装链路
- **MessageGateway**：消息网关接口，定义网关的核心操作
- **DefaultMessageGateway**：消息网关默认实现
- **ChannelAdapter**：通道适配器接口，定义通道消息标准化和响应发送的契约
- **GatewayProperties**：Gateway 配置属性 record，映射 application.yml 中 lifepilot.gateway 下的配置
- **GatewayAutoConfiguration**：Spring Boot 自动配置类，注册 Gateway 相关 Bean

## Requirements

### Requirement 1: 统一消息模型 — ChannelType 枚举

**User Story:** As a 开发者, I want 通过枚举标识所有支持的通道类型, so that 系统可以类型安全地区分消息来源

#### Acceptance Criteria

1. THE ChannelType SHALL 定义五个枚举值：CLI、WEB、WECOM、DINGTALK、FEISHU
2. THE ChannelType SHALL 为每个枚举值提供字符串值（cli、web、wecom、dingtalk、feishu）和是否需要 Webhook 的标识
3. WHEN 提供有效的字符串值时, THE ChannelType SHALL 通过 fromValue 方法解析为对应的枚举值
4. IF 提供未知的字符串值, THEN THE ChannelType SHALL 抛出 IllegalArgumentException 并包含描述性错误消息

### Requirement 2: 统一消息模型 — MessageContent sealed interface

**User Story:** As a 开发者, I want 通过 sealed interface 穷举所有消息类型, so that switch 表达式可以编译时检查完整性

#### Acceptance Criteria

1. THE MessageContent SHALL 定义为 sealed interface，permits TextMessage、CommandMessage、FileMessage、CardMessage、EventMessage 五个 record
2. THE MessageContent SHALL 声明 toPlainText 方法，每个 permit 提供对应的纯文本表示
3. THE TextMessage SHALL 在紧凑构造器中验证文本内容非空非 blank
4. THE CommandMessage SHALL 提供静态 parse 方法，从以 / 开头的原始文本解析命令名和参数列表
5. IF CommandMessage.parse 接收到不以 / 开头的文本, THEN THE CommandMessage SHALL 抛出 IllegalArgumentException
6. THE CommandMessage SHALL 在紧凑构造器中使用 List.copyOf 确保参数列表不可变
7. THE CardMessage SHALL 在紧凑构造器中使用 List.copyOf 确保操作列表不可变
8. THE EventMessage SHALL 在紧凑构造器中使用 Map.copyOf 确保负载数据不可变

### Requirement 3: 统一消息模型 — ChannelMetadata sealed interface

**User Story:** As a 开发者, I want 通过 sealed interface 穷举所有通道的元数据类型, so that 认证和审计逻辑可以类型安全地处理通道特定信息

#### Acceptance Criteria

1. THE ChannelMetadata SHALL 定义为 sealed interface，permits CliMetadata、WebMetadata、WecomMetadata、DingtalkMetadata、FeishuMetadata 五个 record
2. THE ChannelMetadata SHALL 声明 channelType 方法，每个 permit 返回对应的 ChannelType 枚举值
3. THE CliMetadata SHALL 包含 terminalType、terminalWidth、colorSupported 字段
4. THE WebMetadata SHALL 包含 userAgent、remoteAddr、sessionToken（可空）、acceptsSse 字段
5. THE WecomMetadata SHALL 包含 corpId、agentId、msgSignature、timestamp、nonce、encryptedMsg（可空）字段
6. THE DingtalkMetadata SHALL 包含 chatbotUserId、conversationId、conversationType、senderNick、sign、timestamp、isAtAll 字段
7. THE FeishuMetadata SHALL 包含 appId、tenantKey、messageId、chatId（可空）、chatType、eventId、eventType 字段

### Requirement 4: 统一消息模型 — GatewayMessage record

**User Story:** As a 开发者, I want 将所有通道的消息标准化为不可变的 GatewayMessage record, so that 中间件管道可以统一处理所有通道的消息

#### Acceptance Criteria

1. THE GatewayMessage SHALL 定义为 record，包含 messageId、channelType、userId、sessionId、content（MessageContent）、attachments、channelMetadata、timestamp、traceHeaders 字段
2. THE GatewayMessage SHALL 在紧凑构造器中为 messageId 和 timestamp 提供默认值（UUID 和当前时间）
3. THE GatewayMessage SHALL 在紧凑构造器中使用 List.copyOf 和 Map.copyOf 确保 attachments 和 traceHeaders 不可变
4. THE GatewayMessage SHALL 提供 contentAsText 便捷方法，返回消息内容的纯文本表示
5. THE GatewayMessage SHALL 提供 isCommand 和 isEvent 便捷方法，通过 instanceof 判断消息类型
6. THE GatewayMessage SHALL 支持 Builder 模式（toBuilder）用于创建修改后的副本
7. THE GatewayMessage SHALL 定义内嵌 Attachment record，包含 attachmentId、fileName、mimeType、data、size 字段

### Requirement 5: 统一消息模型 — GatewayResponse record

**User Story:** As a 开发者, I want 通过 GatewayResponse record 统一表示网关响应, so that 通道适配器可以将响应转换为通道特定格式

#### Acceptance Criteria

1. THE GatewayResponse SHALL 定义为 record，包含 responseId、channelType、content（ResponseContent）、attachments、metadata、latency、tokenUsage（可空）、statusCode、errorMessage（可空）字段
2. THE GatewayResponse SHALL 在紧凑构造器中为 responseId 提供默认值（UUID），并使用 List.copyOf 和 Map.copyOf 确保集合不可变
3. THE GatewayResponse SHALL 提供 success 静态工厂方法，创建状态码 200 的成功响应
4. THE GatewayResponse SHALL 提供 error 静态工厂方法，创建指定状态码的错误响应
5. THE GatewayResponse SHALL 提供 rateLimited 静态工厂方法，创建状态码 429 的限流响应
6. THE GatewayResponse SHALL 提供 unauthorized 静态工厂方法，创建状态码 401 的未认证响应
7. THE GatewayResponse SHALL 提供 isSuccess 便捷方法，判断状态码是否在 200-299 范围内
8. THE GatewayResponse SHALL 支持 Builder 模式（toBuilder）用于创建修改后的副本

### Requirement 6: 统一消息模型 — ResponseContent sealed interface 与 TokenUsage record

**User Story:** As a 开发者, I want 通过 sealed interface 穷举所有响应内容类型并统计 Token 消耗, so that 通道适配器可以根据响应类型选择最佳渲染方式

#### Acceptance Criteria

1. THE ResponseContent SHALL 定义为 sealed interface，permits TextContent、MarkdownContent、CardContent、StreamingContent 四个 record
2. THE ResponseContent SHALL 声明 toPlainText 方法，每个 permit 提供对应的纯文本表示
3. THE CardContent SHALL 在紧凑构造器中使用 List.copyOf 确保操作列表不可变
4. THE TokenUsage SHALL 定义为 record，包含 promptTokens、completionTokens、totalTokens、modelId 字段
5. THE TokenUsage SHALL 提供 ZERO 静态常量，表示零 Token 消耗（用于不涉及 LLM 的快速路径响应）

### Requirement 7: 中间件管道引擎 — GatewayMiddleware 接口

**User Story:** As a 开发者, I want 定义统一的中间件接口, so that 中间件可以独立开发、测试和组合

#### Acceptance Criteria

1. THE GatewayMiddleware SHALL 声明 process 方法，接收 GatewayMessage 和 MiddlewareChain 参数，返回 GatewayResponse
2. THE GatewayMiddleware SHALL 声明 order 方法，返回执行顺序（数值越小越先执行）
3. THE GatewayMiddleware SHALL 声明 name 方法，返回中间件名称（用于日志和配置）
4. THE GatewayMiddleware SHALL 提供 enabled 默认方法，默认返回 true，支持通过配置动态控制

### Requirement 8: 中间件管道引擎 — MiddlewareChain

**User Story:** As a 开发者, I want 通过索引式责任链执行中间件, so that 消息可以按顺序经过所有启用的中间件处理

#### Acceptance Criteria

1. THE MiddlewareChain SHALL 持有有序的中间件列表和共享的 MiddlewareContext
2. WHEN 调用 next 方法时, THE MiddlewareChain SHALL 执行下一个启用的中间件
3. WHILE 存在禁用的中间件时, THE MiddlewareChain SHALL 跳过禁用的中间件继续执行下一个
4. WHEN 所有中间件执行完毕后调用 next 方法时, THE MiddlewareChain SHALL 返回状态码 500 的默认错误响应
5. THE MiddlewareChain SHALL 提供 context 方法，允许中间件访问共享上下文

### Requirement 9: 中间件管道引擎 — MiddlewareContext

**User Story:** As a 开发者, I want 通过类型安全的属性包在中间件之间共享数据, so that 中间件之间无需直接依赖即可传递信息

#### Acceptance Criteria

1. THE MiddlewareContext SHALL 使用 ConcurrentHashMap 存储属性，支持并发读写
2. THE MiddlewareContext SHALL 提供类型安全的 get 方法，接收键和期望类型，返回 Optional
3. THE MiddlewareContext SHALL 提供 require 方法，当属性不存在或类型不匹配时抛出 IllegalStateException
4. THE MiddlewareContext SHALL 提供 set、has、remove 方法用于属性管理
5. THE MiddlewareContext SHALL 提供 snapshot 方法，返回所有属性的不可变快照（用于审计日志）
6. THE MiddlewareContext SHALL 定义预定义的上下文键常量（KEY_AUTH_RESULT、KEY_TRUST_LEVEL、KEY_RATE_LIMIT_REMAINING、KEY_SECURITY_CHECK_RESULT、KEY_ROUTE_DECISION、KEY_AGENT_RESPONSE、KEY_TOKEN_USAGE）

### Requirement 10: 中间件管道引擎 — MiddlewarePipeline

**User Story:** As a 开发者, I want 自动收集中间件 Bean 并按顺序组装管道, so that 每次请求可以通过完整的中间件链处理

#### Acceptance Criteria

1. THE MiddlewarePipeline SHALL 在构造时收集所有 GatewayMiddleware Bean，按 order 值从小到大排序
2. WHEN 执行 execute 方法时, THE MiddlewarePipeline SHALL 为每次请求创建新的 MiddlewareChain 和 MiddlewareContext 实例
3. THE MiddlewarePipeline SHALL 提供 register 方法用于动态注册中间件，注册后自动重新排序
4. THE MiddlewarePipeline SHALL 提供 unregister 方法用于按名称动态注销中间件
5. THE MiddlewarePipeline SHALL 提供 getMiddlewares 方法，返回当前中间件列表的不可变快照
6. THE MiddlewarePipeline SHALL 使用 CopyOnWriteArrayList 保证读多写少场景下的线程安全


### Requirement 11: 消息网关 — MessageGateway 接口

**User Story:** As a 开发者, I want 定义消息网关的统一接口, so that 网关实现可以替换而不影响上层调用方

#### Acceptance Criteria

1. THE MessageGateway SHALL 声明 process 方法，接收 GatewayMessage 返回 GatewayResponse
2. THE MessageGateway SHALL 声明 registerChannel 和 unregisterChannel 方法用于管理通道适配器
3. THE MessageGateway SHALL 声明 getChannel 方法，返回 Optional 类型的通道适配器
4. THE MessageGateway SHALL 声明 getAllChannels 方法，返回不可变的通道适配器列表
5. THE MessageGateway SHALL 声明 start、stop、isRunning 方法用于管理网关生命周期

### Requirement 12: 消息网关 — DefaultMessageGateway 实现

**User Story:** As a 开发者, I want 实现线程安全的默认消息网关, so that 多个通道可以并发提交消息并通过中间件管道处理

#### Acceptance Criteria

1. THE DefaultMessageGateway SHALL 使用 ConcurrentHashMap 管理通道注册表，使用 AtomicBoolean 管理运行状态
2. WHEN 网关未运行时收到消息, THE DefaultMessageGateway SHALL 返回状态码 503 的错误响应
3. WHEN 处理消息时, THE DefaultMessageGateway SHALL 将消息推入 MiddlewarePipeline 执行，并在响应中补充处理延迟信息
4. IF 中间件管道抛出未捕获异常, THEN THE DefaultMessageGateway SHALL 返回状态码 500 的错误响应并记录错误日志
5. WHEN 注册已存在类型的通道适配器时, THE DefaultMessageGateway SHALL 抛出 IllegalStateException
6. WHILE 网关已在运行时注册新通道适配器, THE DefaultMessageGateway SHALL 立即启动新注册的通道
7. WHEN 启动网关时, THE DefaultMessageGateway SHALL 启动所有已注册的通道适配器，单个通道启动失败不影响其他通道
8. WHEN 停止网关时, THE DefaultMessageGateway SHALL 停止所有通道适配器，单个通道停止失败不影响其他通道
9. THE DefaultMessageGateway SHALL 使用参数化日志记录入站消息、处理结果和异常信息

### Requirement 13: 通道适配器 — ChannelAdapter 接口

**User Story:** As a 开发者, I want 定义通道适配器的统一接口, so that 后续可以为每个通道实现具体的适配器

#### Acceptance Criteria

1. THE ChannelAdapter SHALL 声明 channelType 方法，返回通道类型枚举值
2. THE ChannelAdapter SHALL 声明 normalize 方法，将通道特定的原始消息转换为 GatewayMessage
3. THE ChannelAdapter SHALL 声明 sendResponse 方法，将 GatewayResponse 转换为通道特定格式并发送
4. THE ChannelAdapter SHALL 声明 start 和 stop 方法用于管理通道生命周期

### Requirement 14: 配置属性 — GatewayProperties

**User Story:** As a 开发者, I want 通过 @ConfigurationProperties 外部化所有 Gateway 配置, so that 配置可以在不修改代码的情况下调整

#### Acceptance Criteria

1. THE GatewayProperties SHALL 使用 @ConfigurationProperties(prefix = "lifepilot.gateway") 绑定配置
2. THE GatewayProperties SHALL 定义为 record，使用嵌套 record 组织子配置（enabled、middleware、rateLimit、security、auth、router、execution、audit、channels、reconnect、session）
3. THE GatewayProperties SHALL 在 application.yml 中声明所有配置项及默认值
4. THE GatewayProperties SHALL 包含顶层 enabled 属性，默认值为 true，用于全局启用/禁用 Gateway

### Requirement 15: 数据库迁移 — Flyway V12

**User Story:** As a 开发者, I want 通过 Flyway 迁移脚本创建 Gateway 相关数据库表, so that 会话管理、审计日志、限流计数等数据可以持久化

#### Acceptance Criteria

1. THE Flyway 迁移脚本 SHALL 命名为 V12__create_gateway_tables.sql
2. THE 迁移脚本 SHALL 创建 gateway_sessions 表，包含 session_id（主键）、user_id、channel_type（CHECK 约束）、state（CHECK 约束）、total_tokens、total_requests、metadata_json、created_at、updated_at、last_active_at 字段
3. THE 迁移脚本 SHALL 创建 gateway_audit_log 表，包含 audit_id（主键）、message_id、session_id、channel_type、user_id、request_content_hash、request_summary、response_status_code、response_summary、route_type（CHECK 约束）、latency_ms、prompt_tokens、completion_tokens、total_tokens、model_id、middleware_results_json、created_at 字段
4. THE 迁移脚本 SHALL 创建 rate_limit_counters 表，包含 counter_id（主键）、user_id、channel_type、counter_type（CHECK 约束）、window_start、window_end、current_count、max_count、created_at、updated_at 字段
5. THE 迁移脚本 SHALL 创建 failed_messages 表，包含 message_id（主键）、channel_type、user_id、content_json、retry_count、max_retries、last_error、next_retry_at、status（CHECK 约束）、created_at、updated_at 字段
6. THE 迁移脚本 SHALL 创建 user_behavior 表，包含 user_id（主键）、total_interactions、security_incidents、last_incident_at、first_seen_at、created_at、updated_at 字段
7. THE 迁移脚本 SHALL 为所有表创建必要的索引以支持常见查询模式
8. THE 迁移脚本 SHALL 为所有包含 updated_at 的表创建自动更新触发器
9. THE 迁移脚本 SHALL 遵循 LifePilot 数据库规范（主键 TEXT 存 UUID、时间 TEXT 存 ISO 8601、布尔 INTEGER、JSON 用 TEXT + _json 后缀）

### Requirement 16: Spring 自动配置 — GatewayAutoConfiguration

**User Story:** As a 开发者, I want Gateway 相关 Bean 在 Spring Boot 启动时自动注册, so that 无需手动配置即可使用 Gateway 功能

#### Acceptance Criteria

1. THE GatewayAutoConfiguration SHALL 使用 @ConditionalOnProperty(name = "lifepilot.gateway.enabled", matchIfMissing = true) 控制是否启用
2. THE GatewayAutoConfiguration SHALL 注册 MiddlewarePipeline Bean，自动收集所有 GatewayMiddleware Bean
3. THE GatewayAutoConfiguration SHALL 注册 MessageGateway Bean（DefaultMessageGateway），自动注册所有 ChannelAdapter Bean
4. THE GatewayAutoConfiguration SHALL 注册 GatewayProperties Bean
5. WHEN ApplicationReadyEvent 触发时, THE GatewayAutoConfiguration SHALL 启动 MessageGateway
6. THE GatewayAutoConfiguration SHALL 在 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 中注册

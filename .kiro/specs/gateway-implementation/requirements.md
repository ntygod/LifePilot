# Requirements Document

## Introduction

本文档定义 LifePilot Gateway 模块的 6 个具体中间件实现和 CLI 通道适配器桥接的需求。Gateway 框架层（GatewayMiddleware 接口、MiddlewarePipeline、MiddlewareChain、MiddlewareContext、ChannelAdapter 接口、MessageGateway、DefaultMessageGateway、GatewayProperties、Flyway V12）已在 gateway-middleware spec 中完成。本 spec 聚焦于填充框架中缺失的具体实现。

参考文档：
- 架构设计：#[[file:docs/architecture/gateway-middleware.md]]
- 特性设计：#[[file:docs/features/gateway-channels.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 已完成框架：#[[file:.kiro/specs/gateway-middleware/design.md]]

## Glossary

- **MiddlewarePipeline**: 中间件管道，按 order 排序执行所有已注册的 GatewayMiddleware
- **MiddlewareChain**: 索引式责任链，通过 next() 推进到下一个启用的中间件
- **MiddlewareContext**: 跨中间件共享的类型安全属性包
- **GatewayMessage**: 统一网关消息 record，所有通道消息标准化后的不可变表示
- **GatewayResponse**: 统一网关响应 record，中间件管道的输出
- **AuthMiddleware**: 认证鉴权中间件（order=100），根据通道类型选择认证策略
- **AuthStrategy**: 认证策略接口，每个通道实现自己的认证逻辑
- **TrustLevel**: 信任等级枚举（TRUSTED / VERIFIED / ANONYMOUS）
- **RateLimitMiddleware**: 限流控制中间件（order=200），Token 感知的双维度限流
- **TokenBucket**: 令牌桶，以 LLM Token 为单位的限流数据结构
- **SlidingWindowCounter**: 滑动窗口计数器，按请求数限流
- **SecurityMiddleware**: 安全检查中间件（order=300），多层安全检查 + 认知记忆增强
- **PromptInjectionDetector**: Prompt 注入检测器，基于正则模式匹配
- **SensitiveDataDetector**: 敏感数据检测器，检测手机号/身份证/银行卡/邮箱并脱敏
- **TrustScoreCalculator**: 信任分数计算器，基于用户历史行为动态评估信任度
- **RouterMiddleware**: 意图路由中间件（order=400），快速路径 vs 慢速路径分流
- **RouteDecision**: 路由决策 sealed interface（FastRoute / AgentRoute / ErrorRoute）
- **ExecutionMiddleware**: Agent 执行中间件（order=500），GatewayMessage → AgentRequest 桥接
- **AuditMiddleware**: 审计日志中间件（order=600），全链路请求/响应审计
- **AuditEvent**: 审计事件 record，记录一次完整的请求/响应处理过程
- **CliChannelAdapter**: CLI 通道适配器，桥接已有的 CliShell 到 Gateway 管道
- **GatewayProperties**: Gateway 配置属性，已包含所有中间件的启用/排序/参数配置
- **AgentLoop**: Agent 控制循环，接收 AgentRequest 返回 AgentResponse
- **DataRedactor**: 数据脱敏工具，用于审计日志中的敏感数据脱敏
- **GatewayMiddlewareAutoConfiguration**: 中间件 Bean 注册的自动配置类

## Requirements

### Requirement 1: 认证鉴权中间件

**User Story:** As a system operator, I want all inbound messages to be authenticated based on their channel type, so that only verified users can access the system.

#### Acceptance Criteria

1. WHEN a GatewayMessage enters the pipeline, THE AuthMiddleware SHALL select an AuthStrategy based on the message's channelType
2. WHEN the selected AuthStrategy returns authenticated=true, THE AuthMiddleware SHALL place the AuthResult and TrustLevel into the MiddlewareContext and call chain.next()
3. WHEN the selected AuthStrategy returns authenticated=false, THE AuthMiddleware SHALL return a 401 GatewayResponse and stop the pipeline
4. WHEN no AuthStrategy is registered for the message's channelType, THE AuthMiddleware SHALL return a 400 GatewayResponse
5. THE CliAuthStrategy SHALL return AuthResult with TrustLevel.TRUSTED for all CLI channel messages without additional verification
6. THE AuthMiddleware SHALL read its enabled flag and order value from GatewayProperties.middleware().auth()

### Requirement 2: 限流控制中间件

**User Story:** As a system operator, I want to limit resource consumption per user using both Token budget and request frequency, so that no single user can exhaust system resources.

#### Acceptance Criteria

1. WHEN a message passes authentication, THE RateLimitMiddleware SHALL check the user's request frequency using a SlidingWindowCounter with the configured maxRequestsPerMinute
2. WHEN the user's request frequency exceeds the configured limit, THE RateLimitMiddleware SHALL return a 429 GatewayResponse
3. WHEN the request frequency check passes, THE RateLimitMiddleware SHALL check the user's Token quota using a TokenBucket with the configured maxTokensPerHour
4. WHEN the user's Token quota is insufficient for the estimated consumption, THE RateLimitMiddleware SHALL return a 429 GatewayResponse
5. WHEN both checks pass, THE RateLimitMiddleware SHALL reserve the estimated Token amount, call chain.next(), and settle the actual Token consumption from the response's TokenUsage after the pipeline returns
6. WHEN the response contains no TokenUsage (fast path), THE RateLimitMiddleware SHALL refund the full estimated Token amount
7. THE TokenBucket SHALL refill tokens linearly based on elapsed time at a rate of maxTokensPerHour per hour
8. THE RateLimitMiddleware SHALL read all configurable parameters from GatewayProperties.rateLimit()

### Requirement 3: 安全检查中间件

**User Story:** As a system operator, I want inbound messages to be checked for prompt injection, sensitive data, and trust-based risk, so that the system is protected from malicious input and data leakage.

#### Acceptance Criteria

1. WHEN a message reaches SecurityMiddleware, THE PromptInjectionDetector SHALL scan the message content for direct injection patterns and jailbreak attempt patterns using regex matching
2. WHEN a CRITICAL or HIGH severity prompt injection violation is detected, THE SecurityMiddleware SHALL return a 403 GatewayResponse and stop the pipeline
3. WHEN the message content contains sensitive data (phone numbers, ID card numbers, bank card numbers, or email addresses), THE SensitiveDataDetector SHALL detect and record violations, and produce a redacted version of the content using DataRedactor
4. THE SensitiveDataDetector SHALL validate ID card numbers using the 18-digit checksum algorithm and bank card numbers using the Luhn algorithm before reporting violations
5. WHILE the user's TrustLevel is below TRUSTED, THE TrustScoreCalculator SHALL compute a trust score based on historical interaction count, security incident count, account age, and recency of last incident
6. THE SecurityMiddleware SHALL place the SecurityCheckResult into the MiddlewareContext for downstream middleware consumption
7. THE SecurityMiddleware SHALL read its enabled flag from GatewayProperties.middleware().security()

### Requirement 4: 意图路由中间件

**User Story:** As a user, I want command-style messages (starting with /) to be executed immediately without LLM processing, so that simple operations respond quickly with zero Token cost.

#### Acceptance Criteria

1. WHEN the message content is a CommandMessage or a TextMessage starting with /, THE RouterMiddleware SHALL parse the command and delegate to a CommandRouter for route resolution
2. WHEN the CommandRouter finds a matching command registration, THE RouterMiddleware SHALL execute the command via the fast path and return a GatewayResponse with TokenUsage.ZERO
3. WHEN the CommandRouter finds no matching command, THE RouterMiddleware SHALL return a GatewayResponse listing available commands
4. WHEN the message content is a natural language TextMessage, THE RouterMiddleware SHALL create an AgentRoute decision and call chain.next() to pass the message to ExecutionMiddleware
5. THE RouterMiddleware SHALL place the RouteDecision into the MiddlewareContext
6. THE RouterMiddleware SHALL read its enabled flag and order value from GatewayProperties.middleware().router()

### Requirement 5: Agent 执行中间件

**User Story:** As a user, I want my natural language messages to be processed by the Agent engine, so that I receive intelligent responses powered by LLM reasoning.

#### Acceptance Criteria

1. WHEN a message reaches ExecutionMiddleware via the slow path, THE ExecutionMiddleware SHALL convert the GatewayMessage into an AgentRequest by extracting userId, sessionId, and content while excluding channelType and channelMetadata
2. THE ExecutionMiddleware SHALL invoke AgentLoop.run(AgentRequest) to process the request
3. WHEN AgentLoop.run() returns an AgentResponse, THE ExecutionMiddleware SHALL convert it into a GatewayResponse including the TokenUsage and latency
4. WHEN AgentLoop.run() throws an exception, THE ExecutionMiddleware SHALL return a 500 GatewayResponse with a user-friendly error message
5. IF AgentLoop.run() does not complete within the configured timeout (from GatewayProperties.execution().timeoutSeconds()), THEN THE ExecutionMiddleware SHALL cancel the execution and return a 504 GatewayResponse
6. THE ExecutionMiddleware SHALL place the AgentResponse and TokenUsage into the MiddlewareContext for AuditMiddleware consumption

### Requirement 6: 审计日志中间件

**User Story:** As a system operator, I want all gateway requests and responses to be recorded in an audit log, so that I can perform compliance auditing and usage analysis.

#### Acceptance Criteria

1. THE AuditMiddleware SHALL wrap the remaining pipeline execution by calling chain.next() and recording the response
2. WHEN a request/response cycle completes, THE AuditMiddleware SHALL construct an AuditEvent containing: messageId, sessionId, channelType, userId, request content hash (SHA-256), redacted request summary, response status code, redacted response summary, route type, latency, Token usage, and middleware results from the MiddlewareContext
3. THE AuditMiddleware SHALL redact all request and response content using DataRedactor before recording
4. THE AuditMiddleware SHALL persist the AuditEvent asynchronously (using CompletableFuture.runAsync) so that audit recording does not block the response
5. IF audit persistence fails, THEN THE AuditMiddleware SHALL log the error and continue without affecting the response
6. THE AuditMiddleware SHALL truncate request and response summaries to the configured maximum length from GatewayProperties.audit()

### Requirement 7: 审计事件持久化

**User Story:** As a system operator, I want audit events stored in SQLite, so that I can query historical request data for analysis and compliance.

#### Acceptance Criteria

1. THE AuditEventRepository SHALL persist AuditEvent records to the gateway_audit_log table (created by Flyway V12)
2. THE AuditEventRepository SHALL store latency as milliseconds (INTEGER), Token usage as JSON (TEXT), and middleware results as JSON (TEXT)
3. WHEN querying audit events, THE AuditEventRepository SHALL support filtering by userId, channelType, routeType, and time range

### Requirement 8: 中间件自动配置

**User Story:** As a developer, I want all 6 middleware implementations registered as Spring Beans via a dedicated AutoConfiguration class, so that the MiddlewarePipeline automatically collects and orders them.

#### Acceptance Criteria

1. THE GatewayMiddlewareAutoConfiguration SHALL register each middleware (AuthMiddleware, RateLimitMiddleware, SecurityMiddleware, RouterMiddleware, ExecutionMiddleware, AuditMiddleware) as a @Bean
2. WHEN a middleware's enabled flag in GatewayProperties is false, THE corresponding middleware Bean SHALL report enabled()=false so that MiddlewareChain skips it
3. THE GatewayMiddlewareAutoConfiguration SHALL register supporting Beans (AuthStrategy implementations, TokenBucket, SlidingWindowCounter, PromptInjectionDetector, SensitiveDataDetector, TrustScoreCalculator, AuditEventRepository) required by the middleware
4. THE GatewayMiddlewareAutoConfiguration SHALL be activated by the @ConditionalOnProperty("lifepilot.gateway.enabled") condition

### Requirement 9: CLI 通道适配器桥接

**User Story:** As a CLI user, I want my terminal interactions to flow through the Gateway middleware pipeline, so that authentication, rate limiting, security, routing, and auditing apply consistently to CLI usage.

#### Acceptance Criteria

1. THE CliChannelAdapter SHALL implement the ChannelAdapter interface with channelType() returning ChannelType.CLI
2. WHEN the CliShell receives user input, THE CliChannelAdapter SHALL convert it into a GatewayMessage with CliMetadata and submit it to MessageGateway.process()
3. WHEN MessageGateway.process() returns a GatewayResponse, THE CliChannelAdapter SHALL delegate response rendering to the existing ResponseRenderer
4. THE CliChannelAdapter SHALL be registered as a @Bean in GatewayMiddlewareAutoConfiguration, conditional on GatewayProperties.channels().cli().enabled()

### Requirement 10: 限流数据结构正确性

**User Story:** As a developer, I want the rate limiting data structures to behave correctly under concurrent access, so that rate limits are enforced accurately.

#### Acceptance Criteria

1. FOR ALL sequences of tryConsume(n) calls on a TokenBucket, the total consumed tokens SHALL never exceed the bucket capacity plus tokens refilled over elapsed time
2. FOR ALL sequences of tryAcquire() calls on a SlidingWindowCounter, the count of successful acquisitions within any sliding window SHALL never exceed maxRequests
3. WHEN TokenBucket.tryConsume(n) returns false, THE available token count SHALL remain unchanged (no partial consumption)
4. WHEN TokenBucket.refund(n) is called, THE available token count SHALL increase by n but never exceed the bucket capacity

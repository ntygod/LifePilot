# Implementation Plan: Gateway 中间件实现

## Overview

实现 LifePilot Gateway 的 6 个具体中间件（Auth / RateLimit / Security / Router / Execution / Audit）、审计事件持久化、中间件自动配置和 CLI 通道适配器桥接。框架层已在 gateway-middleware spec 中完成，本 spec 聚焦于填充具体实现。

按中间件执行顺序（AuditMiddleware(50) → AuthMiddleware(100) → RateLimitMiddleware(200) → SecurityMiddleware(300) → RouterMiddleware(400) → ExecutionMiddleware(500)）自底向上实现，先实现无依赖的数据结构和辅助类，再实现各中间件，最后组装自动配置和 CLI 适配器。

## Tasks

- [ ] 1. 实现认证鉴权中间件
  - [ ] 1.1 创建 TrustLevel 枚举和 AuthResult record
    - 在 `com.lifepilot.interaction.middleware.auth` 包下创建 `TrustLevel` 枚举（TRUSTED / VERIFIED / ANONYMOUS）
    - 创建 `AuthResult` record，包含 `authenticated`、`userId`、`trustLevel`、`failureReason` 字段
    - 提供 `success()` 和 `failure()` 静态工厂方法
    - _Requirements: 1.2, 1.5_

  - [ ] 1.2 创建 AuthStrategy sealed interface 和 CliAuthStrategy
    - 在 `com.lifepilot.interaction.middleware.auth` 包下创建 `AuthStrategy` sealed interface，permits `CliAuthStrategy`
    - 方法：`authenticate(GatewayMessage)` 返回 `AuthResult`，`supportedChannel()` 返回 `ChannelType`
    - 实现 `CliAuthStrategy`：始终返回 `AuthResult.success(userId, TrustLevel.TRUSTED)`
    - _Requirements: 1.1, 1.5_

  - [ ] 1.3 实现 AuthMiddleware
    - 在 `com.lifepilot.interaction.middleware.auth` 包下创建 `AuthMiddleware` 实现 `GatewayMiddleware`
    - 从 `Map<ChannelType, AuthStrategy>` 查找策略，无匹配返回 400，认证失败返回 401
    - 认证成功将 `AuthResult` 和 `TrustLevel` 放入 `MiddlewareContext`，调用 `chain.next()`
    - order / enabled 从 `GatewayProperties.middleware().auth()` 读取
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.6_

  - [ ]* 1.4 编写 AuthMiddleware 属性测试
    - **Property 5: AuthMiddleware 认证成功传播**
    - **Property 6: AuthMiddleware 认证失败短路**
    - **Property 7: CliAuthStrategy 始终信任**
    - **Validates: Requirements 1.1, 1.2, 1.3, 1.5**

- [ ] 2. 实现限流数据结构和中间件
  - [ ] 2.1 实现 TokenBucket
    - 在 `com.lifepilot.interaction.middleware.ratelimit` 包下创建 `TokenBucket`
    - 使用 `AtomicLong` + CAS 保证线程安全
    - 实现 `tryConsume(long)`（不足时不部分消费）、`refund(long)`（不超过 capacity）、`availableTokens()`
    - 基于 elapsed time 线性补充（refillRatePerMs = capacity / 3_600_000.0）
    - 参数校验：tryConsume/refund 参数 ≤ 0 抛 IllegalArgumentException
    - _Requirements: 10.1, 10.3, 10.4_

  - [ ]* 2.2 编写 TokenBucket 属性测试
    - **Property 1: TokenBucket 消费不变量** — availableTokens 始终在 [0, capacity]
    - **Property 3: TokenBucket 消费原子性** — 不足时 tryConsume 返回 false 且余额不变
    - **Property 4: TokenBucket refund 上界** — refund 后不超过 capacity
    - **Property 11: TokenBucket 线性补充** — elapsed time 后补充量 ≈ rate * time，capped at capacity
    - **Validates: Requirements 10.1, 10.3, 10.4, 2.7**

  - [ ] 2.3 实现 SlidingWindowCounter
    - 在 `com.lifepilot.interaction.middleware.ratelimit` 包下创建 `SlidingWindowCounter`
    - 使用 `ConcurrentLinkedDeque<Long>` 存储请求时间戳
    - 实现 `tryAcquire()`（窗口内超限返回 false）、`currentCount()`
    - 参数校验：maxRequests ≤ 0 抛 IllegalArgumentException
    - _Requirements: 10.2_

  - [ ]* 2.4 编写 SlidingWindowCounter 属性测试
    - **Property 2: SlidingWindowCounter 窗口不变量** — 窗口内成功次数不超过 maxRequests
    - **Validates: Requirements 10.2**

  - [ ] 2.5 实现 RateLimitMiddleware
    - 在 `com.lifepilot.interaction.middleware.ratelimit` 包下创建 `RateLimitMiddleware` 实现 `GatewayMiddleware`
    - 使用 `ConcurrentHashMap<String, TokenBucket>` 和 `ConcurrentHashMap<String, SlidingWindowCounter>` 按用户隔离
    - 先检查 SlidingWindowCounter，再检查 TokenBucket，两项通过后调用 chain.next()
    - 响应返回后结算 Token：有 TokenUsage 则 refund 差额，无 TokenUsage 则全额 refund
    - order / enabled 从 GatewayProperties 读取
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.8_

  - [ ]* 2.6 编写 RateLimitMiddleware 属性测试
    - **Property 8: RateLimitMiddleware 频率超限拒绝**
    - **Property 9: RateLimitMiddleware Token 配额不足拒绝**
    - **Property 10: RateLimitMiddleware Token 结算**
    - **Validates: Requirements 2.2, 2.4, 2.5, 2.6**

- [ ] 3. Checkpoint — 确认认证和限流中间件
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. 实现安全检查中间件
  - [ ] 4.1 创建 SecurityViolation 和 SecurityCheckResult record
    - 在 `com.lifepilot.interaction.middleware.security` 包下创建 `SecurityViolation` record（type, severity, description, matchedPattern）
    - 创建 `SecurityCheckResult` record（violations, blocked, redactedContent, trustScore）
    - _Requirements: 3.6_

  - [ ] 4.2 实现 PromptInjectionDetector
    - 在 `com.lifepilot.interaction.middleware.security` 包下创建 `PromptInjectionDetector`
    - 内置直接注入模式（CRITICAL）和越狱尝试模式（HIGH）的正则列表
    - `detect(String content)` 返回 `List<SecurityViolation>`
    - _Requirements: 3.1_

  - [ ] 4.3 实现 SensitiveDataDetector
    - 在 `com.lifepilot.interaction.middleware.security` 包下创建 `SensitiveDataDetector`
    - 检测手机号（`1[3-9]\d{9}`）、身份证号（18 位 + 校验位验证）、银行卡号（16-19 位 + Luhn 校验）、邮箱
    - 身份证使用加权求和校验算法（权重 7-9-10-5-8-4-2-1-6-3-7-9-10-5-8-4-2，校验码 1-0-X-9-8-7-6-5-4-3-2）
    - 银行卡使用 Luhn 算法
    - 返回 `SensitiveDataResult(violations, redactedContent)`
    - _Requirements: 3.3, 3.4_

  - [ ] 4.4 实现 TrustScoreCalculator
    - 在 `com.lifepilot.interaction.middleware.security` 包下创建 `TrustScoreCalculator`
    - 使用 JdbcTemplate 查询 `user_behavior` 表
    - 计算公式：基础分 0.5 + 交互次数贡献 + 账户年龄贡献 - 安全事件惩罚 - 最近事件衰减
    - 结果 clamp 到 [0.0, 1.0]
    - 查询失败时降级返回默认分数，记录 WARN 日志
    - _Requirements: 3.5_

  - [ ] 4.5 实现 SecurityMiddleware
    - 在 `com.lifepilot.interaction.middleware.security` 包下创建 `SecurityMiddleware` 实现 `GatewayMiddleware`
    - 依次执行：PromptInjectionDetector → SensitiveDataDetector → TrustScoreCalculator（仅 TrustLevel < TRUSTED 时）
    - CRITICAL/HIGH 注入违规返回 403
    - 构建 SecurityCheckResult 放入 MiddlewareContext，调用 chain.next()
    - enabled 从 GatewayProperties.middleware().security() 读取
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 3.6, 3.7_

  - [ ]* 4.6 编写安全中间件属性测试
    - **Property 12: PromptInjectionDetector 检测与阻断**
    - **Property 13: SensitiveDataDetector 检测与脱敏**
    - **Property 14: TrustScoreCalculator 分数范围**
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**

- [ ] 5. 实现路由和执行中间件
  - [ ] 5.1 创建 RouteDecision sealed interface
    - 在 `com.lifepilot.interaction.middleware.router` 包下创建 `RouteDecision` sealed interface
    - permits: `FastRoute`（command, args, responseText）、`AgentRoute`（content）、`ErrorRoute`（message, statusCode）
    - _Requirements: 4.1, 4.4_

  - [ ] 5.2 实现 RouterMiddleware
    - 在 `com.lifepilot.interaction.middleware.router` 包下创建 `RouterMiddleware` 实现 `GatewayMiddleware`
    - CommandMessage 或 `/` 开头的 TextMessage → 快速路径
    - 已知命令（fastPathCommands 列表）→ 返回 GatewayResponse with TokenUsage.ZERO
    - 未知命令 → 返回 400 列出可用命令
    - 自然语言 TextMessage → 创建 AgentRoute 放入 MiddlewareContext，调用 chain.next()
    - order / enabled 从 GatewayProperties.middleware().router() 读取
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

  - [ ]* 5.3 编写 RouterMiddleware 属性测试
    - **Property 15: RouterMiddleware 命令识别**
    - **Property 16: RouterMiddleware 自然语言路由**
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5**

  - [ ] 5.4 实现 ExecutionMiddleware
    - 在 `com.lifepilot.interaction.middleware.execution` 包下创建 `ExecutionMiddleware` 实现 `GatewayMiddleware`
    - GatewayMessage → AgentRequest 转换（message=contentAsText, sessionId=sessionId, channel=channelType.value()）
    - 使用 CompletableFuture + timeout 调用 AgentLoop.run()
    - AgentResponse → GatewayResponse 转换，包含 TokenUsage
    - 异常处理：TimeoutException → 504，ExecutionException → 500，InterruptedException → 恢复中断标志 + 500
    - 将 AgentResponse 和 TokenUsage 放入 MiddlewareContext
    - order / enabled 从 GatewayProperties.middleware().execution() 读取
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [ ]* 5.5 编写执行中间件属性测试
    - **Property 17: ExecutionMiddleware 消息转换**
    - **Property 18: ExecutionMiddleware 响应转换**
    - **Validates: Requirements 5.1, 5.3, 5.6**

- [ ] 6. Checkpoint — 确认路由和执行中间件
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. 实现审计中间件和持久化
  - [ ] 7.1 创建 AuditEvent record
    - 在 `com.lifepilot.interaction.middleware.audit` 包下创建 `AuditEvent` record
    - 字段：auditId, messageId, sessionId, channelType, userId, requestContentHash, requestSummary, responseStatusCode, responseSummary, routeType, latencyMs, tokenUsage, middlewareResultsJson, createdAt
    - 使用 `@Builder(toBuilder = true)`
    - _Requirements: 6.2_

  - [ ] 7.2 实现 DataRedactor（轻量级临时实现）
    - 在 `com.lifepilot.interaction.middleware.audit` 包下创建 `DataRedactor`
    - 脱敏规则：手机号 138****1234、身份证 110***********1234、银行卡 6222****1234、邮箱 u***@example.com
    - null 输入返回空字符串
    - _Requirements: 6.3_

  - [ ] 7.3 实现 AuditEventRepository
    - 在 `com.lifepilot.interaction.middleware.audit` 包下创建 `AuditEventRepository`
    - 使用 JdbcTemplate 操作 `gateway_audit_log` 表（Flyway V12 已创建）
    - `save(AuditEvent)` 持久化审计事件，latency 存 INTEGER，TokenUsage 拆分为 prompt_tokens/completion_tokens/total_tokens/model_id
    - `findByFilters(userId, channelType, routeType, from, to)` 支持动态过滤查询
    - _Requirements: 7.1, 7.2, 7.3_

  - [ ] 7.4 实现 AuditMiddleware
    - 在 `com.lifepilot.interaction.middleware.audit` 包下创建 `AuditMiddleware` 实现 `GatewayMiddleware`
    - order=50（最先执行，包裹整个管道）
    - 调用 chain.next() 前记录开始时间，返回后构建 AuditEvent
    - 请求/响应内容通过 DataRedactor 脱敏，截断到配置的最大长度
    - 使用 CompletableFuture.runAsync 异步持久化，失败记录 ERROR 日志不影响响应
    - enabled 从 GatewayProperties.audit() 读取
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [ ]* 7.5 编写审计中间件属性测试
    - **Property 19: AuditEvent 完整性与脱敏**
    - **Validates: Requirements 6.2, 6.3, 6.6**

- [ ] 8. 实现自动配置和 CLI 通道适配器
  - [ ] 8.1 创建 GatewayMiddlewareAutoConfiguration
    - 在 `com.lifepilot.interaction.config` 包下创建 `GatewayMiddlewareAutoConfiguration`
    - `@AutoConfiguration(after = GatewayAutoConfiguration.class)` + `@ConditionalOnProperty("lifepilot.gateway.enabled")`
    - 注册所有 6 个中间件 Bean 和支撑 Bean（CliAuthStrategy, PromptInjectionDetector, SensitiveDataDetector, TrustScoreCalculator, DataRedactor, AuditEventRepository）
    - AuthMiddleware 通过 `List<AuthStrategy>` 自动收集策略并构建 Map
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [ ]* 8.2 编写中间件 enabled 标志属性测试
    - **Property 20: 中间件 enabled 标志**
    - **Validates: Requirements 8.2**

  - [ ] 8.3 实现 CliChannelAdapter
    - 在 `com.lifepilot.interaction.channel` 包下创建 `CliChannelAdapter` 实现 `ChannelAdapter`
    - `channelType()` 返回 `ChannelType.CLI`
    - `normalize(Object)` 将 String 输入转换为 GatewayMessage（`/` 开头 → CommandMessage，否则 → TextMessage）
    - `sendResponse(String, GatewayResponse)` 委托 ResponseRenderer 渲染
    - `handleInput(String)` 组合 normalize → gateway.process() → sendResponse
    - 在 GatewayMiddlewareAutoConfiguration 中注册，条件 `@ConditionalOnProperty("lifepilot.gateway.channels.cli.enabled")`
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [ ]* 8.4 编写 CliChannelAdapter 属性测试
    - **Property 21: CliChannelAdapter normalize**
    - **Validates: Requirements 9.2**

- [ ] 9. 集成测试和最终验证
  - [ ] 9.1 编写 GatewayMiddlewareAutoConfiguration 集成测试
    - 验证所有中间件 Bean 注册成功
    - 验证 `@ConditionalOnProperty` 条件激活/禁用
    - 验证依赖注入链完整
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [ ] 9.2 编写 AuditEventRepository 集成测试
    - 使用内存 SQLite + Flyway V12 表结构
    - 验证 save/findByFilters CRUD 操作
    - 验证过滤查询（userId, channelType, routeType, 时间范围）
    - _Requirements: 7.1, 7.2, 7.3_

  - [ ] 9.3 编写端到端管道集成测试
    - 验证完整管道执行：Audit(50) → Auth(100) → RateLimit(200) → Security(300) → Router(400) → Execution(500)
    - 验证快速路径（命令消息）和慢速路径（自然语言）两条链路
    - Mock AgentLoop，验证 GatewayMessage → AgentRequest → AgentResponse → GatewayResponse 全链路
    - _Requirements: 1.1-1.6, 2.1-2.8, 3.1-3.7, 4.1-4.6, 5.1-5.6, 6.1-6.6_

- [ ] 10. Final checkpoint — 确认所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties (21 properties from design)
- 所有代码注释、Javadoc、日志、异常消息、测试方法名使用中文
- 类级别 Javadoc 包含 `@author zsg` 和 `@since 2026-02-25`
- 业务可调参数从 GatewayProperties 读取，不硬编码
- jqwik 属性测试已在 pom.xml 中配置

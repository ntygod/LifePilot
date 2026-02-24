# Implementation Plan: LLM Router

## Overview

按依赖顺序实现 LLM Router 核心路由引擎。从 Maven 依赖和数据模型开始，逐步构建适配器层、熔断器、注册表，最终实现路由策略引擎并通过 Spring Boot 自动配置装配。每个任务增量可编译，属性测试使用 jqwik。

## Tasks

- [x] 1. Maven 依赖配置与启动类调整
  - 在 pom.xml 中添加 spring-ai-core、spring-ai-ollama、spring-ai-openai 依赖（版本由 Spring AI BOM 管理）
  - 添加 jqwik 测试依赖（1.9.2，scope test）
  - 在 LifePilotApplication 上添加 `@SpringBootApplication(exclude = {OllamaAutoConfiguration.class, OpenAiAutoConfiguration.class})`
  - _Requirements: 17.1, 17.2_

- [x] 2. Provider 配置数据模型
  - [x] 2.1 实现 ProviderCapability 枚举和 ProviderType 枚举
    - 创建 `com.lifepilot.llm.config.ProviderCapability` 枚举（CHAT、EMBEDDING、STRUCTURED_OUTPUT、FUNCTION_CALLING、STREAMING、VISION）
    - 创建 `com.lifepilot.llm.config.ProviderType` 枚举，包含 configKey 字段和 isOpenAiCompatible() 方法
    - _Requirements: 1.3, 1.4, 1.5_

  - [x] 2.2 实现 ProviderConfig record
    - 创建 `com.lifepilot.llm.config.ProviderConfig` record，包含所有字段
    - 紧凑构造器中执行非空校验（id、type、apiUrl、modelName）和防御性拷贝（scenes → List.copyOf、capabilities → Set.copyOf）
    - 实现 isLocal()、hasCapability()、supportsScene()、estimateCost() 方法
    - _Requirements: 1.1, 1.2, 1.6, 1.7_

  - [ ]* 2.3 编写 ProviderConfig 属性测试
    - **Property 1: ProviderConfig 防御性拷贝不变量**
    - **Property 2: ProviderConfig 本地模型成本不变量**
    - 创建 `ProviderConfigPropertyTest`，使用 jqwik 生成随机 ProviderConfig 验证防御性拷贝和成本计算
    - **Validates: Requirements 1.2, 1.6, 1.7**

  - [ ]* 2.4 编写 ProviderType 单元测试
    - 创建 `ProviderTypeTest`，验证 isOpenAiCompatible() 对所有枚举值的返回值
    - _Requirements: 1.4_

- [x] 3. 场景常量与基础响应模型
  - [x] 3.1 实现 LlmScene 场景常量类
    - 创建 `com.lifepilot.llm.LlmScene`，定义 9 个场景常量、all() 方法、private 构造器
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 3.2 实现 LlmResponse 统一响应 record
    - 创建 `com.lifepilot.llm.LlmResponse` record，包含 totalTokens() 和 cached() 静态工厂方法
    - _Requirements: 12.1, 12.2, 12.3_

  - [x] 3.3 实现 LlmUnavailableException 异常类
    - 创建 `com.lifepilot.llm.LlmUnavailableException`，继承 RuntimeException，携带 scene 和 attemptedProviders（List.copyOf 防御性拷贝），支持 cause 参数
    - _Requirements: 13.1, 13.2, 13.3_

  - [x] 3.4 实现 ExponentialBackoff 指数退避 record
    - 创建 `com.lifepilot.llm.ExponentialBackoff` record，包含 defaults() 工厂方法和 delayForAttempt() 计算
    - _Requirements: 11.1, 11.2, 11.3_

  - [ ]* 3.5 编写基础模型属性测试
    - **Property 13: 指数退避公式正确性** — 创建 `ExponentialBackoffPropertyTest`
    - **Property 14: LlmResponse totalTokens 一致性** — 创建 `LlmResponsePropertyTest`
    - **Property 15: LlmUnavailableException 防御性拷贝** — 创建 `LlmUnavailableExceptionPropertyTest`
    - **Validates: Requirements 11.2, 12.2, 13.3**

  - [ ]* 3.6 编写 LlmScene 单元测试
    - 创建 `LlmSceneTest`，验证 all() 返回 9 个场景、private 构造器不可实例化
    - _Requirements: 2.1, 2.2, 2.3_

- [x] 4. Checkpoint — 验证数据模型层编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. 熔断器状态模型与实现
  - [x] 5.1 实现 CircuitState sealed interface
    - 创建 `com.lifepilot.llm.circuit.CircuitState` sealed interface，包含 Closed、Open、HalfOpen 三种 record 实现
    - Closed 携带 consecutiveFailures 和 initial() 工厂方法；Open 携带 openedAt、failureCount；HalfOpen 携带 transitionedAt
    - 实现 stateName() 默认方法，使用 switch 表达式穷举匹配
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [x] 5.2 实现 CircuitBreaker 熔断器
    - 创建 `com.lifepilot.llm.circuit.CircuitBreaker`，使用 AtomicReference<CircuitState> + CAS 保证并发安全
    - 实现 isCallPermitted()（CLOSED→true、OPEN→检查 resetTimeout 自动转 HALF_OPEN、HALF_OPEN→限制探测次数）
    - 实现 recordSuccess()（CLOSED→重置失败计数、HALF_OPEN→恢复 CLOSED）
    - 实现 recordFailure()（CLOSED→累加失败/触发 OPEN、HALF_OPEN→重新 OPEN）
    - 实现 reset() 强制重置为 Closed(0)
    - 提供两个构造器：标准构造器 + 从持久化恢复的构造器（接受 initialState）
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9_

  - [ ]* 5.3 编写 CircuitBreaker 属性测试
    - **Property 7: 熔断器状态机转换正确性** — 创建 `CircuitBreakerPropertyTest`，使用 jqwik 生成随机 success/failure 操作序列验证状态转换
    - **Property 8: 熔断器强制重置** — 验证任意状态下 reset() 后为 Closed(0)
    - **Validates: Requirements 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9**

- [x] 6. 熔断器管理器与持久化
  - [x] 6.1 创建 Flyway 迁移脚本
    - 创建 `src/main/resources/db/migration/V2__llm_circuit_breaker_states.sql`
    - 定义 circuit_breaker_states 表，包含 provider_capability（TEXT PK）、state（TEXT + CHECK 约束）、failure_count、last_failure_at、state_changed_at、updated_at
    - _Requirements: 16.1, 16.2_

  - [x] 6.2 实现 CircuitBreakerManager
    - 创建 `com.lifepilot.llm.circuit.CircuitBreakerManager`，使用 ConcurrentHashMap 以 providerId:capabilityType 为键管理 CircuitBreaker 实例
    - 实现 isCallPermitted()、recordSuccess()、recordFailure()、getState()、getAllStates()、reset()
    - 初始化时从 circuit_breaker_states 表恢复状态，异常时记录 WARN 日志继续
    - 状态变更时异步持久化（INSERT ... ON CONFLICT DO UPDATE）
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 16.3_

  - [ ]* 6.3 编写 CircuitBreakerManager 属性测试
    - **Property 9: 熔断器能力级别隔离** — 创建 `CircuitBreakerManagerPropertyTest`，验证不同 capabilityType 的熔断器互不影响
    - **Property 10: 熔断器状态持久化往返** — 验证持久化后恢复的状态与原状态一致
    - **Validates: Requirements 9.1, 9.3, 9.4, 10.7**

- [x] 7. Checkpoint — 验证熔断器层编译和测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Provider 适配器层
  - [x] 8.1 实现 ProviderAdapter sealed interface 和 SpringAiProviderAdapter
    - 创建 `com.lifepilot.llm.adapter.ProviderAdapter` sealed interface，定义 call()、callEntity()、embed()、stream()、chatClient()、healthCheck() 方法
    - 创建 `com.lifepilot.llm.adapter.SpringAiProviderAdapter`，封装 ChatModel、EmbeddingModel（可选）、ChatClient（延迟构建）
    - call() 通过 ChatModel 执行调用并构建 LlmResponse（含 latencyMs 计时）
    - embed() 和 stream() 在不支持对应能力时抛出 UnsupportedOperationException
    - healthCheck() 发送简单 prompt 验证可用性
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_

  - [x] 8.2 实现 ProviderAdapterFactory 适配器工厂
    - 创建 `com.lifepilot.llm.adapter.ProviderAdapterFactory`
    - 根据 ProviderType 创建对应的 SpringAiProviderAdapter：OLLAMA 使用 OllamaChatModel + OllamaEmbeddingModel；DEEPSEEK/QWEN/GLM/OPENAI_COMPATIBLE 使用 OpenAiChatModel
    - OLLAMA 类型排除 DataRedactorAdvisor
    - 支持无参构造器用于测试场景
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [ ]* 8.3 编写 ProviderAdapter 单元测试
    - 创建 `SpringAiProviderAdapterTest`，使用 Mock ChatModel/EmbeddingModel 验证 call/embed/stream 行为和能力检查异常
    - 创建 `ProviderAdapterFactoryTest`，验证各 ProviderType 的适配器创建
    - **Property 6: 不支持的能力调用抛出异常**
    - **Validates: Requirements 5.4, 5.5, 5.6, 6.1, 6.2, 6.3**

- [x] 9. Provider 注册表与健康检查
  - [x] 9.1 实现 ProviderHealthChecker
    - 创建 `com.lifepilot.llm.registry.ProviderHealthChecker`
    - 使用 Virtual Thread（Executors.newVirtualThreadPerTaskExecutor）并行检查所有 Provider
    - 单个 Provider 健康检查超时 10 秒标记为不健康
    - 异常时标记为不健康并记录 WARN 日志
    - 返回 Map.copyOf 不可变结果
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

  - [x] 9.2 实现 ProviderRegistry
    - 创建 `com.lifepilot.llm.registry.ProviderRegistry`，使用 ConcurrentHashMap 存储配置和适配器
    - 实现 register()（重复 ID 抛 IllegalArgumentException）、deregister()、findByScene()、findByCapability()（按 priority 升序）、getAdapter()、getConfig()、healthCheckAll()、registeredIds()
    - 初始化时从 LlmConfigProperties 读取已启用 Provider 并自动注册
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

  - [ ]* 9.3 编写 ProviderRegistry 属性测试
    - **Property 3: Provider 注册表查询结果过滤与排序** — 创建 `ProviderRegistryPropertyTest`
    - **Property 4: Provider 注册表重复注册拒绝**
    - **Property 5: Provider 注册与注销一致性**
    - **Validates: Requirements 3.4, 3.5, 3.6, 3.7**

- [x] 10. Checkpoint — 验证适配器和注册表层编译和测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. 配置属性绑定与自动配置
  - [x] 11.1 实现 LlmConfigProperties 配置属性绑定
    - 创建 `com.lifepilot.llm.config.LlmConfigProperties`，使用 @ConfigurationProperties(prefix = "lifepilot.llm")
    - 定义 providers（Map<String, ProviderConfig>）和 circuitBreaker（CircuitBreakerConfig record）配置项
    - CircuitBreakerConfig 包含 failureThreshold（默认 3）、resetTimeoutSeconds（默认 60）、halfOpenMaxAttempts（默认 1）、retryInitialDelayMs（默认 500）、retryMultiplier（默认 2.0）、retryMaxDelayMs（默认 5000）
    - _Requirements: 14.1, 14.2, 14.3, 14.4_

  - [x] 11.2 实现 LlmAutoConfiguration 自动配置
    - 创建 `com.lifepilot.llm.config.LlmAutoConfiguration`
    - 使用 @AutoConfiguration + @EnableConfigurationProperties + @ConditionalOnProperty 注解
    - 注册 ProviderAdapterFactory、ProviderHealthChecker、ProviderRegistry、CircuitBreakerManager、LlmRouter 为 Spring Bean
    - 每个 Bean 使用 @ConditionalOnMissingBean 允许用户覆盖
    - 在 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 中注册
    - _Requirements: 15.1, 15.2, 15.3, 15.4_

  - [x] 11.3 添加 application.yml LLM 配置示例
    - 在 application.yml 中添加 lifepilot.llm 配置段（参考 design.md 的 YAML 配置模型）
    - 在 application-test.yml 中添加测试用 LLM 配置（禁用或使用 Mock）
    - _Requirements: 14.1, 14.2, 14.3_

  - [ ]* 11.4 编写自动配置集成测试
    - 创建 `LlmAutoConfigurationTest`，验证自动配置加载、Bean 注册、@ConditionalOnMissingBean 覆盖
    - 创建 `LlmConfigPropertiesTest`，验证 YAML 配置绑定和默认值
    - **Validates: Requirements 14.1, 14.2, 14.3, 15.1, 15.2, 15.3, 15.4**

- [x] 12. 路由策略引擎
  - [x] 12.1 实现 LlmRouter 路由策略引擎
    - 创建 `com.lifepilot.llm.LlmRouter`
    - 实现 call(scene, prompt, outputSchema)：场景匹配 → 能力过滤（CHAT）→ 熔断器过滤 → 优先级排序 → 故障转移循环
    - 成功时调用 recordSuccess 并返回 LlmResponse；失败时调用 recordFailure 并按指数退避等待后尝试下一个
    - 所有候选失败时抛出 LlmUnavailableException
    - 实现 callEntity(scene, prompt, responseType)：优先选择支持 STRUCTURED_OUTPUT 的 Provider
    - 实现 getChatClient(scene)：返回最高优先级可用 Provider 的 ChatClient
    - 实现 embed(text)：使用独立 embedding 熔断器隔离，在 EMBEDDING 能力 Provider 间故障转移
    - 实现 stream(scene, prompt)：选择支持 STREAMING 的最高优先级可用 Provider，不执行中途故障转移
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9_

  - [ ]* 12.2 编写 LlmRouter 属性测试
    - **Property 11: 路由故障转移循环** — 创建 `LlmRouterPropertyTest`，使用 Mock ProviderAdapter 模拟部分成功/部分失败场景
    - **Property 12: 空场景路由立即失败** — 验证无候选 Provider 时立即抛出 LlmUnavailableException
    - **Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.9**

- [x] 13. Final Checkpoint — 验证所有测试通过
  - 运行完整测试套件确认无回归
  - 确认所有编译诊断无错误
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 属性测试使用 jqwik，每个属性测试注释引用 Property 编号
- 每个任务/子任务完成后独立 git commit，遵循 `<type>(<scope>): <中文描述>` 格式
- 所有代码遵循编码规范：中文注释、record 优先、sealed interface、Javadoc 含 @author zsg
- 集成测试使用内存 SQLite，单元测试 Mock 外部依赖

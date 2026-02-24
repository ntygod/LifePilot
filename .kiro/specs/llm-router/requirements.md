# Requirements Document

## Introduction

本文档定义 LLM Router 模块（Phase 1a）的需求。LLM Router 是 LifePilot 的多模型路由层，位于基础设施层，向上为 Agent 层提供统一的 LLM 调用接口，向下适配多种 LLM Provider。

本 spec 聚焦核心路由引擎，包括：Provider 配置与注册、Spring AI 适配器、熔断器保护、路由策略引擎和基础配置。语义缓存、Token 预算管理和使用追踪将在后续 spec（llm-router-advanced）中实现。

参考文档：
- 架构设计：docs/architecture/llm-router.md
- 特性设计：docs/features/llm-router.md
- 编码规范：.kiro/steering/coding-standards.md

## Glossary

- **LLM_Router**: LLM 路由策略引擎，根据调用场景和 Provider 可用性选择最优 Provider 执行 LLM 调用
- **Provider**: 一个 LLM 服务端点实例，由 ProviderConfig 描述其连接参数、能力声明和场景绑定
- **Provider_Registry**: Provider 注册表，管理所有已注册 Provider 的配置和适配器实例，使用 ConcurrentHashMap 存储
- **Provider_Adapter**: Provider 适配器 sealed interface，定义 LLM 路由层与底层 LLM SDK 之间的统一调用契约
- **Spring_AI_Provider_Adapter**: Spring AI 统一适配器，封装 Spring AI 的 ChatModel、EmbeddingModel 和 ChatClient
- **Provider_Adapter_Factory**: 适配器工厂，根据 ProviderType 创建对应的 SpringAiProviderAdapter 实例
- **Provider_Health_Checker**: Provider 健康检查器，使用 Virtual Thread 并行检查所有 Provider 的可用性
- **Circuit_Breaker**: 熔断器，管理单个 providerId:capabilityType 组合的熔断状态，三态状态机（CLOSED/OPEN/HALF_OPEN）
- **Circuit_Breaker_Manager**: 熔断器管理器，以 providerId:capabilityType 复合键隔离熔断器实例
- **Circuit_State**: 熔断器状态 sealed interface，包含 Closed、Open、HalfOpen 三种状态 record
- **Provider_Config**: Provider 配置 record，描述 LLM 服务端点的完整信息
- **Provider_Type**: Provider 类型枚举（OLLAMA/DEEPSEEK/WENXIN/QWEN/GLM/OPENAI_COMPATIBLE）
- **Provider_Capability**: Provider 能力枚举（CHAT/EMBEDDING/STRUCTURED_OUTPUT/FUNCTION_CALLING/STREAMING/VISION）
- **LLM_Scene**: 场景常量类，定义 LLM 调用场景（意图理解、任务规划、通用对话等）
- **LLM_Response**: LLM 调用统一响应 record
- **LLM_Config_Properties**: @ConfigurationProperties("lifepilot.llm") 配置属性绑定类
- **LLM_Auto_Configuration**: LLM 路由层 Spring Boot 自动配置类
- **LLM_Unavailable_Exception**: 路由失败异常，当指定场景的所有 Provider 都不可用时抛出
- **Exponential_Backoff**: 指数退避计算器，初始延迟 500ms，倍数 2.0，上限 5000ms

## Requirements

### Requirement 1: Provider 配置数据模型

**User Story:** As a 开发者, I want 一套完整的 Provider 配置数据模型, so that LLM 路由层可以描述和管理多种 LLM 服务端点的连接参数、能力声明和场景绑定。

#### Acceptance Criteria

1. THE Provider_Config SHALL 使用 Java record 定义不可变数据载体，包含 id、type、apiUrl、apiKey、modelName、timeoutSeconds、priority、scenes、capabilities、enabled、costPerInputToken、costPerOutputToken、maxContextWindow、embeddingDimension、supportsStreaming 字段
2. WHEN Provider_Config 实例被创建时, THE Provider_Config SHALL 在紧凑构造器中执行参数校验（id、type、apiUrl、modelName 非空）和防御性拷贝（scenes 使用 List.copyOf、capabilities 使用 Set.copyOf）
3. THE Provider_Type SHALL 使用枚举定义六种 Provider 类型：OLLAMA、DEEPSEEK、WENXIN、QWEN、GLM、OPENAI_COMPATIBLE，每种类型携带 configKey 字段
4. THE Provider_Type SHALL 提供 isOpenAiCompatible() 方法，对 DEEPSEEK、QWEN、GLM、OPENAI_COMPATIBLE 返回 true，对 OLLAMA、WENXIN 返回 false
5. THE Provider_Capability SHALL 使用枚举定义六种能力：CHAT、EMBEDDING、STRUCTURED_OUTPUT、FUNCTION_CALLING、STREAMING、VISION
6. THE Provider_Config SHALL 提供 isLocal() 方法，当 type 为 OLLAMA 时返回 true
7. THE Provider_Config SHALL 提供 estimateCost(inputTokens, outputTokens) 方法，本地模型返回 0，云端模型按 costPerInputToken 和 costPerOutputToken 计算成本（分）

### Requirement 2: 场景定义

**User Story:** As a 开发者, I want 一套预定义的 LLM 调用场景常量, so that 调用方可以声明意图而无需指定具体模型。

#### Acceptance Criteria

1. THE LLM_Scene SHALL 定义以下场景常量：INTENT_UNDERSTANDING、TASK_PLANNING、CHAT、CODE_GENERATION、KNOWLEDGE_EXTRACTION、MEMORY_COMPRESSION、DOCUMENT_SUMMARY、EMBEDDING、PROACTIVE_REASONING
2. THE LLM_Scene SHALL 提供 all() 方法返回所有场景常量的不可变列表
3. THE LLM_Scene SHALL 使用 private 构造器阻止实例化

### Requirement 3: Provider 注册表

**User Story:** As a 开发者, I want 一个线程安全的 Provider 注册表, so that LLM 路由层可以在运行时管理多个 Provider 的配置和适配器实例。

#### Acceptance Criteria

1. THE Provider_Registry SHALL 使用 ConcurrentHashMap 存储 Provider 配置和适配器实例，支持并发读写
2. WHEN Provider_Registry 初始化时, THE Provider_Registry SHALL 从 LLM_Config_Properties 中读取所有已启用的 Provider 配置并自动注册
3. WHEN 一个新的 Provider 配置被注册时, THE Provider_Registry SHALL 通过 Provider_Adapter_Factory 创建对应的适配器实例并存储
4. IF 注册的 Provider ID 已存在, THEN THE Provider_Registry SHALL 抛出 IllegalArgumentException
5. WHEN findByScene(scene) 被调用时, THE Provider_Registry SHALL 返回支持该场景的已启用 Provider 列表，按 priority 升序排序
6. WHEN findByCapability(capability) 被调用时, THE Provider_Registry SHALL 返回支持该能力的已启用 Provider 列表，按 priority 升序排序
7. WHEN deregister(providerId) 被调用时, THE Provider_Registry SHALL 移除对应的配置和适配器实例
8. WHEN healthCheckAll() 被调用时, THE Provider_Registry SHALL 委托 Provider_Health_Checker 对所有已注册 Provider 执行并行健康检查并返回结果

### Requirement 4: Provider 健康检查

**User Story:** As a 开发者, I want Provider 健康检查能力, so that 路由层可以感知 Provider 的可用性。

#### Acceptance Criteria

1. THE Provider_Health_Checker SHALL 使用 Virtual Thread（Executors.newVirtualThreadPerTaskExecutor）并行检查所有 Provider
2. WHEN 单个 Provider 健康检查超过 10 秒时, THE Provider_Health_Checker SHALL 将该 Provider 标记为不健康
3. IF 健康检查过程中发生异常, THEN THE Provider_Health_Checker SHALL 将该 Provider 标记为不健康并记录警告日志
4. THE Provider_Health_Checker SHALL 返回 Map<String, Boolean> 类型的不可变结果

### Requirement 5: Spring AI Provider 适配器

**User Story:** As a 开发者, I want 一个统一的 Provider 适配器层, so that LLM 路由层可以通过一致的接口调用不同的 LLM Provider。

#### Acceptance Criteria

1. THE Provider_Adapter SHALL 使用 sealed interface 定义，仅允许 Spring_AI_Provider_Adapter 实现
2. THE Provider_Adapter SHALL 定义以下方法：call(prompt, outputSchema, timeout) 返回 LLM_Response、callEntity(prompt, responseType) 返回泛型 T、embed(text) 返回 float[]、stream(prompt) 返回 Flux<String>、chatClient() 返回 Optional<ChatClient>、healthCheck() 返回 boolean
3. THE Spring_AI_Provider_Adapter SHALL 封装 Spring AI 的 ChatModel、EmbeddingModel（可选）和 ChatClient（延迟构建）
4. WHEN call() 被调用时, THE Spring_AI_Provider_Adapter SHALL 通过 ChatModel 执行调用并返回包含 content、inputTokens、outputTokens、providerId、modelName、latencyMs 的 LLM_Response
5. WHEN embed() 被调用且 Provider 不支持 EMBEDDING 能力时, THE Spring_AI_Provider_Adapter SHALL 抛出 UnsupportedOperationException
6. WHEN stream() 被调用且 Provider 不支持流式输出时, THE Spring_AI_Provider_Adapter SHALL 抛出 UnsupportedOperationException
7. WHEN chatClient() 首次被调用时, THE Spring_AI_Provider_Adapter SHALL 延迟构建 ChatClient 实例并注入默认 Advisor 链
8. WHEN healthCheck() 被调用时, THE Spring_AI_Provider_Adapter SHALL 发送简单 prompt 验证 Provider 可用性

### Requirement 6: 适配器工厂

**User Story:** As a 开发者, I want 一个适配器工厂, so that 可以根据 Provider 类型自动创建对应的 Spring AI 适配器实例。

#### Acceptance Criteria

1. THE Provider_Adapter_Factory SHALL 根据 Provider_Type 创建对应的 Spring_AI_Provider_Adapter 实例
2. WHEN Provider_Type 为 OLLAMA 时, THE Provider_Adapter_Factory SHALL 创建使用 OllamaChatModel 和 OllamaEmbeddingModel（如支持 EMBEDDING 能力）的适配器
3. WHEN Provider_Type 为 DEEPSEEK、QWEN、GLM 或 OPENAI_COMPATIBLE 时, THE Provider_Adapter_Factory SHALL 创建使用 OpenAiChatModel 的适配器，apiUrl 分别映射到对应的 API 端点
4. WHEN 为 OLLAMA 类型创建适配器时, THE Provider_Adapter_Factory SHALL 从默认 Advisor 列表中排除 DataRedactorAdvisor（本地模型不需要脱敏）
5. THE Provider_Adapter_Factory SHALL 支持无参构造器用于测试场景

### Requirement 7: 熔断器状态模型

**User Story:** As a 开发者, I want 一个类型安全的熔断器状态模型, so that 熔断器状态转换在编译期可穷举匹配。

#### Acceptance Criteria

1. THE Circuit_State SHALL 使用 sealed interface 定义，包含 Closed、Open、HalfOpen 三种 record 实现
2. THE Closed record SHALL 携带 consecutiveFailures 字段，提供 initial() 静态工厂方法返回失败计数为 0 的初始状态
3. THE Open record SHALL 携带 openedAt（Instant）和 failureCount 字段
4. THE HalfOpen record SHALL 携带 transitionedAt（Instant）字段
5. THE Circuit_State SHALL 提供 stateName() 默认方法，使用 switch 表达式返回状态名称字符串

### Requirement 8: 熔断器实现

**User Story:** As a 开发者, I want 一个线程安全的熔断器实现, so that 路由层可以自动隔离故障 Provider 并在恢复后自动恢复。

#### Acceptance Criteria

1. THE Circuit_Breaker SHALL 使用 AtomicReference<CircuitState> 存储状态，通过 CAS 操作保证并发安全
2. WHILE Circuit_Breaker 处于 CLOSED 状态, THE Circuit_Breaker SHALL 允许所有调用（isCallPermitted 返回 true）
3. WHEN 连续失败次数达到 failureThreshold 时, THE Circuit_Breaker SHALL 从 CLOSED 转换为 OPEN 状态
4. WHILE Circuit_Breaker 处于 OPEN 状态且未超过 resetTimeout, THE Circuit_Breaker SHALL 拒绝所有调用（isCallPermitted 返回 false）
5. WHEN OPEN 状态持续时间超过 resetTimeout 时, THE Circuit_Breaker SHALL 自动转换为 HALF_OPEN 状态并允许探测调用
6. WHEN HALF_OPEN 状态下探测调用成功时, THE Circuit_Breaker SHALL 转换为 CLOSED 状态并重置失败计数为 0
7. WHEN HALF_OPEN 状态下探测调用失败时, THE Circuit_Breaker SHALL 转换回 OPEN 状态并重新计时 resetTimeout
8. WHEN recordSuccess() 在 CLOSED 状态下被调用时, THE Circuit_Breaker SHALL 将 consecutiveFailures 重置为 0
9. THE Circuit_Breaker SHALL 提供 reset() 方法强制重置为 CLOSED 初始状态

### Requirement 9: 熔断器管理器

**User Story:** As a 开发者, I want 一个熔断器管理器, so that 路由层可以按 providerId:capabilityType 复合键隔离管理多个熔断器实例。

#### Acceptance Criteria

1. THE Circuit_Breaker_Manager SHALL 以 providerId:capabilityType 复合键隔离熔断器实例（如 "deepseek-chat:chat" 和 "deepseek-chat:embedding" 是独立的熔断器）
2. WHEN isCallPermitted(providerId, capabilityType) 被调用且对应熔断器不存在时, THE Circuit_Breaker_Manager SHALL 自动创建新的熔断器实例（使用配置的 failureThreshold 和 resetTimeout）
3. WHEN 熔断器状态发生变更时, THE Circuit_Breaker_Manager SHALL 异步将状态持久化到 circuit_breaker_states 表
4. WHEN Circuit_Breaker_Manager 初始化时, THE Circuit_Breaker_Manager SHALL 从 circuit_breaker_states 表恢复之前的熔断器状态
5. IF 状态恢复过程中发生异常（如首次启动表不存在）, THEN THE Circuit_Breaker_Manager SHALL 记录警告日志并继续正常初始化
6. THE Circuit_Breaker_Manager SHALL 提供 getAllStates() 方法返回所有熔断器状态的不可变快照
7. THE Circuit_Breaker_Manager SHALL 提供 reset(providerId, capabilityType) 方法手动重置指定熔断器

### Requirement 10: 路由策略引擎

**User Story:** As a 开发者, I want 一个意图驱动的路由策略引擎, so that 调用方只需声明场景意图即可获得最优 Provider 的 LLM 调用结果。

#### Acceptance Criteria

1. WHEN call(scene, prompt, outputSchema) 被调用时, THE LLM_Router SHALL 按以下顺序执行路由决策：场景匹配 → 能力过滤 → 熔断器过滤 → 优先级排序 → 故障转移循环
2. WHEN 故障转移循环中某个 Provider 调用成功时, THE LLM_Router SHALL 调用 circuitBreakerManager.recordSuccess() 并返回 LLM_Response
3. WHEN 故障转移循环中某个 Provider 调用失败时, THE LLM_Router SHALL 调用 circuitBreakerManager.recordFailure() 并按指数退避等待后尝试下一个 Provider
4. IF 所有候选 Provider 调用均失败, THEN THE LLM_Router SHALL 抛出 LLM_Unavailable_Exception，携带 scene 和 attemptedProviders 信息
5. WHEN callEntity(scene, prompt, responseType) 被调用时, THE LLM_Router SHALL 优先选择支持 STRUCTURED_OUTPUT 能力的 Provider，不支持时降级为 prompt 引导加输出解析
6. WHEN getChatClient(scene) 被调用时, THE LLM_Router SHALL 返回最高优先级可用 Provider 的 ChatClient 实例
7. WHEN embed(text) 被调用时, THE LLM_Router SHALL 使用独立的 embedding 熔断器隔离（capabilityType 为 embedding），在支持 EMBEDDING 能力的 Provider 间故障转移
8. WHEN stream(scene, prompt) 被调用时, THE LLM_Router SHALL 选择支持 STREAMING 能力的最高优先级可用 Provider，不执行中途故障转移（避免输出不连贯）
9. IF 指定场景无任何候选 Provider, THEN THE LLM_Router SHALL 抛出 LLM_Unavailable_Exception

### Requirement 11: 指数退避策略

**User Story:** As a 开发者, I want 故障转移间使用指数退避等待, so that 避免短时间内对故障 Provider 的重复冲击。

#### Acceptance Criteria

1. THE Exponential_Backoff SHALL 使用 record 定义，包含 initialDelayMs（默认 500）、multiplier（默认 2.0）、maxDelayMs（默认 5000）、maxRetries（默认 2）字段
2. WHEN delayForAttempt(attempt) 被调用时, THE Exponential_Backoff SHALL 返回 min(initialDelayMs * multiplier^attempt, maxDelayMs)
3. THE Exponential_Backoff SHALL 提供 defaults() 静态工厂方法返回符合编码规范 §6 的默认配置

### Requirement 12: 统一响应模型

**User Story:** As a 开发者, I want 一个统一的 LLM 响应模型, so that 调用方无论底层使用哪个 Provider 都收到相同结构的响应。

#### Acceptance Criteria

1. THE LLM_Response SHALL 使用 record 定义，包含 content、inputTokens、outputTokens、providerId、modelName、latencyMs、cached 字段
2. THE LLM_Response SHALL 提供 totalTokens() 方法返回 inputTokens 与 outputTokens 之和
3. THE LLM_Response SHALL 提供 cached(content, providerId, modelName) 静态工厂方法，创建 Token 数和延迟均为 0 的缓存响应

### Requirement 13: 异常模型

**User Story:** As a 开发者, I want 携带路由上下文的异常类型, so that 上层 Agent 可以根据异常信息做降级决策。

#### Acceptance Criteria

1. THE LLM_Unavailable_Exception SHALL 继承 RuntimeException，携带 scene（String）和 attemptedProviders（List<String>）字段
2. THE LLM_Unavailable_Exception SHALL 提供接受 cause 参数的构造器，支持异常链
3. THE LLM_Unavailable_Exception SHALL 对 attemptedProviders 执行 List.copyOf 防御性拷贝

### Requirement 14: 配置属性绑定

**User Story:** As a 开发者, I want 通过 application.yml 声明式配置 LLM 路由层, so that 用户可以通过修改配置文件管理 Provider 和路由参数。

#### Acceptance Criteria

1. THE LLM_Config_Properties SHALL 使用 @ConfigurationProperties(prefix = "lifepilot.llm") 绑定配置
2. THE LLM_Config_Properties SHALL 支持 providers（Map<String, ProviderConfig>）配置项，每个 Provider 包含完整的连接和能力参数
3. THE LLM_Config_Properties SHALL 支持 circuitBreaker 配置项，包含 failureThreshold（默认 3）、resetTimeoutSeconds（默认 60）、halfOpenMaxAttempts（默认 1）、retryInitialDelayMs（默认 500）、retryMultiplier（默认 2.0）、retryMaxDelayMs（默认 5000）
4. THE LLM_Config_Properties SHALL 使用 record 定义 CircuitBreakerConfig 嵌套配置类型

### Requirement 15: Spring Boot 自动配置

**User Story:** As a 开发者, I want LLM 路由层通过 Spring Boot 自动配置加载, so that 引入依赖后即可使用，无需手动装配 Bean。

#### Acceptance Criteria

1. THE LLM_Auto_Configuration SHALL 使用 @AutoConfiguration 和 @EnableConfigurationProperties(LlmConfigProperties.class) 注解
2. THE LLM_Auto_Configuration SHALL 通过 @ConditionalOnProperty(prefix = "lifepilot.llm", name = "enabled", havingValue = "true", matchIfMissing = true) 控制启用
3. WHEN LLM 自动配置生效时, THE LLM_Auto_Configuration SHALL 注册 ProviderAdapterFactory、ProviderHealthChecker、ProviderRegistry、CircuitBreakerManager、LlmRouter 为 Spring Bean
4. THE LLM_Auto_Configuration SHALL 对每个 Bean 使用 @ConditionalOnMissingBean 注解，允许用户自定义覆盖

### Requirement 16: 熔断器状态持久化 Schema

**User Story:** As a 开发者, I want 熔断器状态持久化到数据库, so that 应用重启后可以恢复之前的熔断状态，避免冲击已故障的 Provider。

#### Acceptance Criteria

1. THE circuit_breaker_states 表 SHALL 包含 provider_capability（TEXT PRIMARY KEY）、state（TEXT，CHECK 约束 CLOSED/OPEN/HALF_OPEN）、failure_count（INTEGER）、last_failure_at（TEXT）、state_changed_at（TEXT）、updated_at（TEXT）字段
2. THE circuit_breaker_states 表 SHALL 通过 Flyway 迁移脚本创建
3. WHEN 熔断器状态变更时, THE Circuit_Breaker_Manager SHALL 使用 INSERT ... ON CONFLICT DO UPDATE 语句异步持久化状态

### Requirement 17: Maven 依赖配置

**User Story:** As a 开发者, I want 正确配置 Spring AI 相关 Maven 依赖, so that LLM 路由层可以使用 Spring AI 的 ChatModel、EmbeddingModel 和 ChatClient。

#### Acceptance Criteria

1. THE pom.xml SHALL 引入 spring-ai-core、spring-ai-ollama、spring-ai-openai 依赖（不使用 starter，手动管理模型实例）
2. THE LifePilot_Application SHALL 排除 Spring AI 的默认自动配置（OllamaAutoConfiguration、OpenAiAutoConfiguration），由 LLM_Auto_Configuration 统一管理

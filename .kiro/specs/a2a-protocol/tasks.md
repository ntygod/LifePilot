# Implementation Plan: A2A 协议支持（模块 22）

## Overview

按自底向上顺序实现 A2A 协议支持模块：先建立数据模型层（11 个 record/enum/sealed interface），再验证 Jackson 序列化（属性测试），然后实现 Server 层核心组件（A2aTaskStore、AgentCardGenerator、A2aAgentExecutor），接着实现 REST Controller 和 API Key 过滤器，再实现 Client 层组件（A2aClientService、RemoteAgentRegistry、RemoteAgentToolFactory），最后完成配置属性、自动装配和集成测试。

## Tasks

- [ ] 1. 实现 A2A 数据模型层（model 包）
  - [ ] 1.1 实现枚举类型 A2aTaskState 和 A2aRole
    - 创建 `com.lifepilot.a2a.model.A2aTaskState` 枚举
    - 8 个枚举值：SUBMITTED / WORKING / INPUT_REQUIRED / COMPLETED / CANCELED / FAILED / REJECTED / AUTH_REQUIRED
    - 使用 `@JsonFormat(shape = JsonFormat.Shape.STRING)` + `@JsonProperty` 注解实现小写序列化
    - 实现 `isTerminal()` 方法（COMPLETED / FAILED / CANCELED / REJECTED 为终态）
    - 创建 `com.lifepilot.a2a.model.A2aRole` 枚举（USER / AGENT），同样小写序列化
    - _Requirements: 1.3_

  - [ ] 1.2 实现 A2aPart sealed interface 和子类型
    - 创建 `com.lifepilot.a2a.model.A2aFileContent` record（name, mimeType, bytes, uri 均 @Nullable）
    - 创建 `com.lifepilot.a2a.model.A2aPart` sealed interface，permits Text / File / Data
    - 使用 `@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")` + `@JsonSubTypes` 实现多态序列化
    - `Text` record：text 字段 + @Nullable metadata
    - `File` record：A2aFileContent file 字段 + @Nullable metadata
    - `Data` record：Map<String, Object> data 字段 + @Nullable metadata
    - 可空字段使用 `@JsonInclude(JsonInclude.Include.NON_NULL)`
    - _Requirements: 1.2, 2.2_

  - [ ] 1.3 实现核心 record 类型
    - 创建 `com.lifepilot.a2a.model.A2aArtifact` record（artifactId, parts, @Nullable name, @Nullable description）
    - 紧凑构造器中 parts 使用 `List.copyOf()` 防御性拷贝
    - 创建 `com.lifepilot.a2a.model.A2aTaskStatus` record（state, @Nullable message, @Nullable timestamp）
    - 创建 `com.lifepilot.a2a.model.A2aMessage` record（messageId, role, parts, @Nullable taskId, @Nullable contextId, @Nullable metadata）
    - 紧凑构造器中 parts 使用 `List.copyOf()` 防御性拷贝
    - 创建 `com.lifepilot.a2a.model.A2aTask` record（id, contextId, status, @Nullable history, @Nullable artifacts, @Nullable metadata）
    - _Requirements: 1.5, 1.6_

  - [ ] 1.4 实现 Agent Card 相关 record 类型
    - 创建 `com.lifepilot.a2a.model.A2aAgentCapabilities` record（streaming）
    - 创建 `com.lifepilot.a2a.model.A2aAgentSkill` record（id, name, description, @Nullable inputModes, @Nullable outputModes）
    - 创建 `com.lifepilot.a2a.model.A2aAgentCard` record（name, description, url, version, @Nullable protocolVersion, skills, capabilities, defaultInputModes, defaultOutputModes, @Nullable securitySchemes）
    - 紧凑构造器中 skills / defaultInputModes / defaultOutputModes 使用 `List.copyOf()` 防御性拷贝
    - defaultInputModes / defaultOutputModes 为 null 时默认 `List.of("text")`
    - _Requirements: 1.1, 1.4_


- [ ] 2. 序列化属性测试
  - [ ]* 2.1 编写 A2aPart type 鉴别器属性测试
    - **Property 4: A2aPart JSON type 鉴别器**
    - 随机生成 A2aPart（Text / File / Data），序列化后 JSON 包含 `"type"` 字段，值分别为 `"text"` / `"file"` / `"data"`
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 2.2**

  - [ ]* 2.2 编写 A2aTaskState 小写序列化属性测试
    - **Property 5: A2aTaskState 小写序列化**
    - 所有 A2aTaskState 枚举值序列化为 JSON 字符串后为小写/snake_case
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 2.3**

  - [ ]* 2.3 编写 A2aAgentCard 序列化 round-trip 属性测试
    - **Property 1: A2aAgentCard 序列化 round-trip**
    - 随机 A2aAgentCard（随机 skills 列表、随机 capabilities、随机可空字段组合）
    - 序列化为 JSON 后再反序列化应产生与原始实例相等的对象
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 2.4**

  - [ ]* 2.4 编写 A2aTask 序列化 round-trip 属性测试
    - **Property 2: A2aTask 序列化 round-trip**
    - 随机 A2aTask（随机状态、随机 history、随机 artifacts）
    - 序列化为 JSON 后再反序列化应产生与原始实例相等的对象
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 2.5**

  - [ ]* 2.5 编写 A2aMessage 序列化 round-trip 属性测试
    - **Property 3: A2aMessage 序列化 round-trip**
    - 随机 A2aMessage（随机 role、随机 A2aPart 子类型组合、随机可空字段）
    - 序列化为 JSON 后再反序列化应产生与原始实例相等的对象
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 2.6**

- [ ] 3. Checkpoint - 确认数据模型和序列化
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. 实现 Server 层核心组件
  - [ ] 4.1 实现 A2aTaskStore 内存存储
    - 创建 `com.lifepilot.a2a.server.A2aTaskStore`
    - 使用 `ConcurrentHashMap<String, A2aTask>` 存储
    - 实现 `create(A2aMessage)` 方法：生成 UUID 作为 Task ID，初始状态 SUBMITTED
    - 实现 `find(String taskId)` 返回 `Optional<A2aTask>`
    - 实现 `listByContextId(String contextId)` 按 contextId 过滤
    - 实现 `updateStatus(String taskId, A2aTaskState newState, @Nullable String message)` 状态转换
    - 实现 `addArtifact(String taskId, A2aArtifact artifact)` 添加产出物
    - 实现 `appendHistory(String taskId, A2aMessage message)` 追加 history，超过 maxHistoryLength 时移除最早消息
    - 实现 `cancel(String taskId)` 仅 SUBMITTED / WORKING 可取消，终态返回 false
    - 实现 `cleanupExpired()` TTL 清理，返回清理数量
    - 依赖注入：A2aProperties
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7_

  - [ ]* 4.2 编写 A2aTaskStore 属性测试
    - **Property 8: Task 创建初始状态为 SUBMITTED**
    - **Property 9: TaskStore 存取 round-trip**
    - **Property 10: TaskStore contextId 列表完整性**
    - **Property 11: TaskStore 取消仅对非终态 Task 生效**
    - **Property 12: TaskStore history 长度限制**
    - **Property 13: TaskStore TTL 清理**
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 5.3, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7**

  - [ ] 4.3 实现 AgentCardGenerator
    - 创建 `com.lifepilot.a2a.server.AgentCardGenerator`
    - 从 `AgentRegistry.listAll()` 获取所有 AgentDefinition
    - 映射：AgentDefinition.id → A2aAgentSkill.id、name → name、description → description
    - inputModes / outputModes 固定 `["text"]`
    - 从 A2aProperties.server 读取 agentName / agentDescription / agentVersion / protocolVersion
    - 构建 A2aAgentCapabilities（streaming = properties.server.streamingEnabled）
    - 依赖注入：AgentRegistry, A2aProperties
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [ ]* 4.4 编写 AgentCardGenerator 属性测试
    - **Property 6: AgentCardGenerator skill 映射保持身份**
    - **Property 7: AgentCardGenerator 反映注册表当前状态**
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 3.1, 3.2, 3.5**

  - [ ] 4.5 实现 A2aAgentExecutor
    - 创建 `com.lifepilot.a2a.server.A2aAgentExecutor`
    - 实现 `execute(A2aMessage, @Nullable String skillId)` 同步执行方法
    - 路由逻辑：有 skillId → AgentRegistry.find(skillId) → AgentExecutor.execute()；无 skillId → AgentLoop.run()
    - 检查 message.taskId 是否引用已有 Task：是 → 追加 history 继续执行；否 → 创建新 Task
    - 状态转换：SUBMITTED → WORKING → COMPLETED/FAILED
    - 执行成功：封装 A2aArtifact 附加到 Task
    - 执行异常：Task 状态设为 FAILED，message 包含错误描述
    - 实现 `executeStreaming(A2aMessage, @Nullable String skillId, Consumer<A2aTask> listener)` 流式执行
    - 在 Virtual Thread 中异步执行，通过 Consumer 回调推送状态更新
    - 依赖注入：AgentRegistry, AgentExecutor, AgentLoop, A2aTaskStore
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7_

  - [ ]* 4.6 编写 A2aAgentExecutor 单元测试
    - 测试 skillId 路由到对应 AgentDefinition（5.1）
    - 测试无 skillId 路由到主 AgentLoop（5.2）
    - 测试成功执行流：SUBMITTED → WORKING → COMPLETED + Artifact（5.4, 5.5）
    - 测试失败执行流：异常 → FAILED + 错误消息（5.6）
    - 测试引用已有 taskId 追加 history（5.7）
    - 测试 skillId 不存在 → FAILED（未知 Skill）
    - _Requirements: 5.1, 5.2, 5.4, 5.5, 5.6, 5.7_


- [ ] 5. 实现 REST Controller 和 API Key 过滤器
  - [ ] 5.1 实现 A2aApiKeyFilter
    - 创建 `com.lifepilot.a2a.server.A2aApiKeyFilter` 继承 `OncePerRequestFilter`
    - 拦截 `/api/a2a/**` 路径，校验 `X-API-Key` Header
    - `/.well-known/agent.json` 不拦截（公开发现端点）
    - api-key 配置为空时不启用认证（shouldNotFilter 返回 true）
    - 无效 Key 返回 HTTP 401 Unauthorized
    - 依赖注入：A2aProperties
    - _Requirements: 12.1, 12.2, 12.3, 12.4_

  - [ ]* 5.2 编写 A2aApiKeyFilter 属性测试和单元测试
    - **Property 16: API Key 过滤器拒绝无效请求**
    - 单元测试：well-known 路径免认证（12.3）、空 api-key 不启用认证（12.4）
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 12.1, 12.2, 12.3, 12.4**

  - [ ] 5.3 实现 AgentCardController
    - 创建 `com.lifepilot.a2a.server.AgentCardController`
    - `GET /.well-known/agent.json` — 标准发现路径
    - `GET /api/a2a/agent-card` — 备用路径
    - 两个端点均返回 AgentCardGenerator.generateCard() 结果
    - 依赖注入：AgentCardGenerator
    - _Requirements: 4.1, 4.2_

  - [ ] 5.4 实现 A2aMessageController
    - 创建 `com.lifepilot.a2a.server.A2aMessageController`
    - `POST /api/a2a/message/send` — 同步消息处理，接收 A2aMessage，返回 A2aTask
    - `POST /api/a2a/message/stream` — SSE 流式消息处理，返回 SseEmitter
    - SSE 事件类型：task-status-update / task-artifact-update / task-complete
    - streaming-enabled=false 时 stream 端点返回 HTTP 405
    - 执行完成或失败时关闭 SSE 连接
    - 依赖注入：A2aAgentExecutor, A2aProperties
    - _Requirements: 4.3, 4.4, 6.1, 6.2, 6.3, 6.4_

  - [ ] 5.5 实现 A2aTaskController
    - 创建 `com.lifepilot.a2a.server.A2aTaskController`
    - `GET /api/a2a/tasks/{id}` — 查询 Task 状态，不存在返回 404
    - `POST /api/a2a/tasks/{id}/cancel` — 取消 Task，终态 Task 返回 409
    - 依赖注入：A2aTaskStore
    - _Requirements: 4.5, 4.6_

- [ ] 6. Checkpoint - 确认 Server 层完整
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. 实现 Client 层组件
  - [ ] 7.1 实现 A2aClientService
    - 创建 `com.lifepilot.a2a.client.A2aClientService`
    - 使用 Spring RestClient 进行 HTTP 调用
    - connectTimeout / readTimeout 从 A2aProperties.client 读取
    - 实现 `discoverAgent(String agentUrl)` — GET `{agentUrl}/.well-known/agent.json`，失败返回空 Optional + WARN 日志
    - 实现 `sendMessage(String agentUrl, A2aMessage message)` — POST `{agentUrl}/api/a2a/message/send`，失败返回 FAILED 状态 Task
    - 实现 `getTask(String agentUrl, String taskId)` — GET `{agentUrl}/api/a2a/tasks/{taskId}`，失败返回空 Optional
    - 实现 `cancelTask(String agentUrl, String taskId)` — POST `{agentUrl}/api/a2a/tasks/{taskId}/cancel`
    - 依赖注入：A2aProperties
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 9.1, 9.2, 9.3, 9.4, 9.5_

  - [ ]* 7.2 编写 A2aClientService 单元测试
    - 测试发现失败返回空 Optional + WARN 日志（8.3）
    - 测试调用失败返回 FAILED Task + WARN 日志（9.4）
    - 测试 client.enabled=false 不执行远程调用（8.5）
    - _Requirements: 8.3, 8.5, 9.4_

  - [ ] 7.3 实现 RemoteAgentRegistry
    - 创建 `com.lifepilot.a2a.client.RemoteAgentRegistry`
    - 使用 `ConcurrentHashMap<String, CacheEntry>` 缓存（CacheEntry 包含 A2aAgentCard + fetchedAt）
    - 实现 `register(String agentUrl)` — 调用 A2aClientService.discoverAgent() 获取并缓存 Agent Card
    - 实现 `unregister(String agentUrl)` — 移除缓存
    - 实现 `listAll()` — 返回所有已缓存 Agent Card
    - 实现 `findByUrl(String agentUrl)` — 查找并检查 TTL，过期则重新获取
    - 实现 `discoverConfiguredAgents()` — 从 A2aProperties.client.remoteAgents 列表自动发现，失败跳过 + WARN 日志
    - TTL 由 A2aProperties.client.cardCacheTtlMinutes 控制
    - 依赖注入：A2aClientService, A2aProperties
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7_

  - [ ]* 7.4 编写 RemoteAgentRegistry 属性测试
    - **Property 14: RemoteAgentRegistry 注册/查找/注销 round-trip**
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 10.1, 10.2, 10.4**

  - [ ] 7.5 实现 RemoteAgentToolFactory
    - 创建 `com.lifepilot.a2a.client.RemoteAgentToolFactory`
    - 实现 `registerRemoteTool(String agentUrl, A2aAgentCard card)` — 创建 BuiltinTool 并注册到 DynamicToolRegistry
    - 工具 ID 格式：`a2a_remote_{agentName}`（agentName 转 snake_case）
    - inputSchema：task（必填 String）+ context（选填 String）
    - riskLevel = MEDIUM（远程调用）
    - executor：构建 A2aMessage → A2aClientService.sendMessage() → 提取 Artifact 文本作为 ToolResult
    - 实现 `unregisterRemoteTool(String agentName)` — 从 DynamicToolRegistry 注销
    - 实现 `static toToolId(String agentName)` — name 转 snake_case 工具 ID
    - 依赖注入：A2aClientService, DynamicToolRegistry
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_

  - [ ]* 7.6 编写 RemoteAgentToolFactory 属性测试和单元测试
    - **Property 15: RemoteAgentToolFactory 工具 ID 命名规范**
    - 单元测试：inputSchema 结构验证（11.3）、工具执行调用 sendMessage（11.4）
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 11.1, 11.3, 11.4**

- [ ] 8. Checkpoint - 确认 Client 层完整
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. 实现配置和自动装配
  - [ ] 9.1 实现 A2aProperties 配置属性类
    - 创建 `com.lifepilot.a2a.config.A2aProperties`
    - `@ConfigurationProperties(prefix = "lifepilot.a2a")`
    - 顶层：enabled（默认 true）
    - 嵌套 Server 类：enabled(true)、apiKey("")、agentName("LifePilot")、agentDescription("个人生活助手")、agentVersion("1.0.0")、protocolVersion("0.2.5")、streamingEnabled(true)
    - 嵌套 Client 类：enabled(true)、remoteAgents(空列表)、connectTimeoutSeconds(10)、readTimeoutSeconds(60)、cardCacheTtlMinutes(30)
    - 嵌套 Task 类：ttlMinutes(60)、maxHistoryLength(50)
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_

  - [ ] 9.2 添加 application.yml 配置项
    - 在 `src/main/resources/application.yml` 中添加 `lifepilot.a2a` 配置段
    - 包含所有配置项及默认值
    - api-key 通过环境变量 `${LIFEPILOT_A2A_API_KEY:}` 注入
    - _Requirements: 13.3, 13.4, 13.5_

  - [ ] 9.3 实现 A2aAutoConfiguration 自动装配
    - 创建 `com.lifepilot.a2a.config.A2aAutoConfiguration`
    - `@AutoConfiguration(after = MultiAgentAutoConfiguration.class)`
    - `@EnableConfigurationProperties(A2aProperties.class)`
    - `@ConditionalOnProperty(prefix = "lifepilot.a2a", name = "enabled", havingValue = "true", matchIfMissing = true)`
    - Server Bean（server.enabled=true）：AgentCardGenerator、A2aTaskStore、A2aAgentExecutor、AgentCardController、A2aMessageController、A2aTaskController、A2aApiKeyFilter
    - Client Bean（client.enabled=true）：A2aClientService、RemoteAgentRegistry、RemoteAgentToolFactory
    - `@EventListener(ApplicationReadyEvent.class)` 中：
      1. client.enabled → RemoteAgentRegistry.discoverConfiguredAgents()
      2. server.enabled → 启动 A2aTaskStore TTL 清理定时任务（ScheduledExecutorService）
    - 依赖：AgentRegistry、AgentExecutor、AgentLoop（来自 MultiAgentAutoConfiguration）、DynamicToolRegistry（来自工具系统）
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6_

  - [ ] 9.4 注册 AutoConfiguration 到 spring.factories / imports
    - 在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中添加 A2aAutoConfiguration
    - _Requirements: 14.1_

- [ ] 10. 集成测试
  - [ ]* 10.1 编写 A2aServer_集成测试
    - `@SpringBootTest` + MockMvc 验证 REST 端点可达性
    - 测试 `GET /.well-known/agent.json` 返回 Agent Card（4.1）
    - 测试 `GET /api/a2a/agent-card` 返回 Agent Card（4.2）
    - 测试 `POST /api/a2a/message/send` 端到端消息处理（4.3）
    - 测试 `POST /api/a2a/message/stream` SSE 流式响应（4.4, 6.1, 6.2, 6.3）
    - 测试 `GET /api/a2a/tasks/{id}` 查询 Task（4.5）
    - 测试 `POST /api/a2a/tasks/{id}/cancel` 取消 Task（4.6）
    - 测试 streaming-enabled=false 时 stream 端点返回 405（6.4）
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 6.1, 6.2, 6.3, 6.4_

  - [ ]* 10.2 编写 A2aAutoConfiguration_集成测试
    - `@SpringBootTest` + `lifepilot.a2a.server.enabled=false` 验证不注册 Server Bean（4.7）
    - `@SpringBootTest` + `lifepilot.a2a.client.enabled=false` 验证不注册 Client Bean（8.5）
    - `@SpringBootTest` + `lifepilot.a2a.enabled=false` 验证不注册任何 Bean（14.6）
    - _Requirements: 4.7, 8.5, 14.6_

  - [ ]* 10.3 编写 A2aClient_WireMock_集成测试
    - 使用 WireMock 模拟远程 A2A Agent
    - 测试远程 Agent 发现（8.1）
    - 测试消息发送（9.1）
    - 测试 Task 查询（9.2）
    - 测试 Task 取消（9.3）
    - 测试超时配置生效（8.2）
    - _Requirements: 8.1, 8.2, 9.1, 9.2, 9.3_

  - [ ]* 10.4 编写 RemoteAgentRegistry_集成测试
    - 测试启动自动发现配置的远程 Agent（10.6）
    - 测试发现失败跳过不阻塞启动（10.7）
    - 测试 TTL 缓存过期后重新获取（10.5）
    - _Requirements: 10.5, 10.6, 10.7_

  - [ ]* 10.5 编写 RemoteAgentToolFactory_集成测试
    - 测试注册远程 Agent 时自动创建 BuiltinTool（11.6）
    - 测试注销远程 Agent 时自动删除 BuiltinTool（11.7）
    - 测试工具注册到 DynamicToolRegistry 可查询（11.5）
    - _Requirements: 11.5, 11.6, 11.7_

  - [ ]* 10.6 编写 A2aProperties_集成测试
    - `@SpringBootTest` 验证配置绑定正确
    - 验证所有默认值（13.2, 13.3, 13.4, 13.5）
    - _Requirements: 13.2, 13.3, 13.4, 13.5_

- [ ] 11. Final checkpoint - 确认所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties (16 properties from design)
- 数据模型不依赖 A2A Java SDK（io.github.a2asdk），使用自定义 record 实现
- 跨模块依赖：AgentRegistry / AgentExecutor（模块 21）、DynamicToolRegistry（模块 3）、AgentLoop（模块 2）
- 所有代码遵循编码规范：中文注释/Javadoc/测试方法名/日志/异常消息，@author zsg，@since 2026-02-28
- 使用 jqwik 进行属性测试，每个属性 `@Property(tries = 100)`
- A2aTaskStore 使用 ConcurrentHashMap 内存存储，不持久化到 SQLite
- SSE 流式响应使用 Spring MVC SseEmitter
- API Key 通过环境变量 `LIFEPILOT_A2A_API_KEY` 注入，空值时不启用认证

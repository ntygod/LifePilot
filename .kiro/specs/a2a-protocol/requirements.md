# Requirements Document

## Introduction

A2A（Agent-to-Agent）协议支持模块为 LifePilot 提供跨系统 Agent 互操作能力。LifePilot 同时作为 A2A Server（暴露自身 Agent 能力供外部调用）和 A2A Client（发现并调用外部 A2A Agent），实现与其他 AI Agent 系统的标准化通信。

本模块基于 A2A Protocol v0.2.5（Google 发布，已捐赠 Linux Foundation），采用 HTTP+JSON/REST 传输、API Key 认证、内存 Task 存储的轻量级实现方案。LifePilot 整体作为单一 A2A Agent 暴露，内部 Agent（writer / life-coach / planner）映射为 A2A skills。

参考文档：
- 架构设计：#[[file:docs/architecture/a2a-protocol.md]]
- 特性设计：#[[file:docs/features/a2a-protocol.md]]
- 多 Agent 架构（依赖）：#[[file:docs/architecture/multi-agent-v2.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 集成检查规范：#[[file:.kiro/steering/integration-checklist.md]]

## Glossary

- **A2A_Server**: LifePilot 暴露的 A2A 协议服务端，接收外部 Agent 请求并路由到内部 Agent 执行
- **A2A_Client**: LifePilot 的 A2A 协议客户端，发现并调用外部 A2A Agent
- **Agent_Card**: A2A 协议中的 Agent 能力声明文档（JSON），描述 Agent 的身份、技能、端点和认证要求
- **A2A_Task**: A2A 协议中的工作单元，具有生命周期状态管理（submitted → working → completed/failed/canceled）
- **A2A_Message**: A2A 协议中 Agent 间交换信息的基本单元，包含多个 Part
- **A2A_Part**: A2A 消息内容片段，支持文本（TextPart）、文件（FilePart）、结构化数据（DataPart）
- **A2A_Artifact**: A2A Task 的产出物，包含一个或多个 Part
- **A2A_TaskState**: A2A Task 状态枚举（SUBMITTED / WORKING / INPUT_REQUIRED / COMPLETED / CANCELED / FAILED / REJECTED / AUTH_REQUIRED）
- **AgentCardGenerator**: 从 AgentRegistry 生成标准 A2A Agent Card 的组件
- **A2aTaskStore**: 管理 A2A Task 生命周期状态的内存存储组件
- **A2aClientService**: 远程 Agent 调用服务，负责发现、发送消息和查询任务
- **RemoteAgentRegistry**: 远程 Agent 注册表，缓存远程 Agent Card
- **RemoteAgentToolFactory**: 将远程 A2A Agent 注册为 BuiltinTool 的工厂
- **A2aProperties**: A2A 模块的配置属性类
- **A2aAutoConfiguration**: A2A 模块的 Spring Boot 自动装配类
- **AgentRegistry**: 多 Agent 模块（模块 21）的 Agent 定义注册中心
- **AgentExecutor**: 多 Agent 模块（模块 21）的 Agent 执行器
- **DynamicToolRegistry**: 工具系统（模块 3）的动态工具注册中心

## Requirements

### Requirement 1: A2A 数据模型

**User Story:** As a 开发者, I want LifePilot 拥有完整的 A2A 协议数据模型, so that A2A Server 和 Client 可以基于统一的类型进行消息交换和任务管理

#### Acceptance Criteria

1. THE A2A_Data_Model SHALL 使用 Java 22 record 定义以下核心类型：A2aAgentCard、A2aAgentSkill、A2aAgentCapabilities、A2aTask、A2aTaskStatus、A2aTaskState（枚举）、A2aMessage、A2aRole（枚举）、A2aPart（sealed interface，permits TextPart / FilePart / DataPart）、A2aFileContent、A2aArtifact
2. THE A2aPart SHALL 使用 sealed interface 定义，permits 三个 record 子类型：Text（包含 text 字段）、File（包含 A2aFileContent 字段）、Data（包含 Map<String, Object> data 字段）
3. THE A2aTaskState SHALL 定义以下枚举值：SUBMITTED、WORKING、INPUT_REQUIRED、COMPLETED、CANCELED、FAILED、REJECTED、AUTH_REQUIRED
4. THE A2aAgentCard SHALL 包含以下字段：name、description、url、version、protocolVersion（可空）、skills（A2aAgentSkill 列表）、capabilities（A2aAgentCapabilities）、defaultInputModes、defaultOutputModes、securitySchemes（可空）
5. THE A2aTask SHALL 包含以下字段：id、contextId、status（A2aTaskStatus）、history（可空 A2aMessage 列表）、artifacts（可空 A2aArtifact 列表）、metadata（可空）
6. THE A2aMessage SHALL 包含以下字段：messageId、role（A2aRole）、parts（A2aPart 列表）、taskId（可空）、contextId（可空）、metadata（可空）
7. THE A2A_Data_Model SHALL 不依赖 A2A Java SDK（io.github.a2asdk），使用自定义 record 实现以避免 Quarkus 传递依赖

### Requirement 2: A2A 数据模型序列化

**User Story:** As a 开发者, I want A2A 数据模型支持 JSON 序列化和反序列化, so that A2A Server 和 Client 可以通过 HTTP+JSON 进行通信

#### Acceptance Criteria

1. THE A2A_Data_Model SHALL 支持通过 Jackson 进行 JSON 序列化和反序列化
2. WHEN A2aPart 进行 JSON 序列化时, THE A2A_Data_Model SHALL 使用 type 字段区分 Text / File / Data 三种子类型
3. WHEN A2aTaskState 进行 JSON 序列化时, THE A2A_Data_Model SHALL 使用小写字符串表示（如 "submitted"、"working"、"completed"）
4. FOR ALL 有效的 A2aAgentCard JSON 字符串, 反序列化后再序列化 SHALL 产生语义等价的 JSON（round-trip 属性）
5. FOR ALL 有效的 A2aTask JSON 字符串, 反序列化后再序列化 SHALL 产生语义等价的 JSON（round-trip 属性）
6. FOR ALL 有效的 A2aMessage JSON 字符串, 反序列化后再序列化 SHALL 产生语义等价的 JSON（round-trip 属性）

### Requirement 3: Agent Card 生成

**User Story:** As a 外部系统, I want 通过标准路径发现 LifePilot 的 Agent 能力, so that 外部系统可以了解 LifePilot 支持的技能并发起调用

#### Acceptance Criteria

1. THE AgentCardGenerator SHALL 从 AgentRegistry 中读取所有已注册 AgentDefinition，将每个 AgentDefinition 映射为一个 A2aAgentSkill
2. THE AgentCardGenerator SHALL 将 AgentDefinition.id 映射为 A2aAgentSkill.id，AgentDefinition.name 映射为 A2aAgentSkill.name，AgentDefinition.description 映射为 A2aAgentSkill.description
3. THE AgentCardGenerator SHALL 从 A2aProperties 读取 agent-name、agent-description、agent-version、protocol-version 填充 A2aAgentCard 的顶层字段
4. THE AgentCardGenerator SHALL 在 A2aAgentCapabilities 中声明 streaming 能力（根据 A2aProperties.server.streaming-enabled 配置）
5. WHEN AgentRegistry 中的 Agent 列表发生变化时, THE AgentCardGenerator SHALL 生成包含最新 skills 列表的 Agent Card

### Requirement 4: A2A Server 端点

**User Story:** As a 外部 Agent, I want 通过标准 HTTP 端点与 LifePilot 通信, so that 外部 Agent 可以发现能力、发送消息和管理任务

#### Acceptance Criteria

1. THE A2A_Server SHALL 在 `/.well-known/agent.json` 路径暴露 GET 端点，返回 AgentCardGenerator 生成的 Agent Card JSON
2. THE A2A_Server SHALL 在 `/api/a2a/agent-card` 路径暴露 GET 端点，作为 Agent Card 的备用获取路径
3. THE A2A_Server SHALL 在 `/api/a2a/message/send` 路径暴露 POST 端点，接收 A2aMessage JSON 请求体，返回 A2aTask JSON 响应
4. THE A2A_Server SHALL 在 `/api/a2a/message/stream` 路径暴露 POST 端点，接收 A2aMessage JSON 请求体，返回 SSE（Server-Sent Events）流式响应
5. THE A2A_Server SHALL 在 `/api/a2a/tasks/{id}` 路径暴露 GET 端点，返回指定 Task 的当前状态
6. THE A2A_Server SHALL 在 `/api/a2a/tasks/{id}/cancel` 路径暴露 POST 端点，取消指定 Task
7. WHEN A2aProperties.server.enabled 为 false 时, THE A2A_Server SHALL 不注册任何 REST 端点

### Requirement 5: A2A Server 消息处理

**User Story:** As a 外部 Agent, I want 发送消息给 LifePilot 并获得执行结果, so that 外部 Agent 可以利用 LifePilot 的 Agent 能力完成任务

#### Acceptance Criteria

1. WHEN A2A_Server 收到包含 skillId 的 A2aMessage 时, THE A2A_Server SHALL 将消息路由到 AgentRegistry 中对应 ID 的 AgentDefinition，通过 AgentExecutor 执行
2. WHEN A2A_Server 收到不包含 skillId 的 A2aMessage 时, THE A2A_Server SHALL 将消息路由到主 AgentLoop 执行
3. WHEN A2A_Server 开始处理消息时, THE A2aTaskStore SHALL 创建一个新的 A2aTask，初始状态为 SUBMITTED
4. WHILE A2A_Server 正在执行 Agent 任务时, THE A2aTaskStore SHALL 将 A2aTask 状态更新为 WORKING
5. WHEN Agent 执行成功完成时, THE A2aTaskStore SHALL 将 A2aTask 状态更新为 COMPLETED，并将执行结果封装为 A2aArtifact 附加到 Task
6. IF Agent 执行过程中发生异常, THEN THE A2aTaskStore SHALL 将 A2aTask 状态更新为 FAILED，并在 A2aTaskStatus.message 中包含错误描述
7. WHEN A2A_Server 收到引用已有 taskId 的 A2aMessage 时, THE A2A_Server SHALL 将新消息追加到该 Task 的 history 中并继续执行

### Requirement 6: A2A Server SSE 流式响应

**User Story:** As a 外部 Agent, I want 通过 SSE 实时接收任务执行进度, so that 外部 Agent 可以在长时间任务执行过程中获得实时反馈

#### Acceptance Criteria

1. WHEN 外部 Agent 通过 `/api/a2a/message/stream` 发送消息时, THE A2A_Server SHALL 返回 SSE 流，包含 Task 状态变更事件
2. THE A2A_Server SHALL 在 SSE 流中发送以下事件类型：task-status-update（状态变更）、task-artifact-update（产出物更新）、task-complete（任务完成）
3. WHEN Agent 执行完成或失败时, THE A2A_Server SHALL 关闭 SSE 连接
4. WHEN A2aProperties.server.streaming-enabled 为 false 时, THE A2A_Server SHALL 对 `/api/a2a/message/stream` 端点返回 HTTP 405 Method Not Allowed

### Requirement 7: A2A Task 存储

**User Story:** As a 系统管理者, I want A2A Task 状态在内存中管理并自动清理, so that 系统资源不会因过期 Task 而耗尽

#### Acceptance Criteria

1. THE A2aTaskStore SHALL 使用 ConcurrentHashMap 在内存中存储 A2aTask，不持久化到 SQLite
2. THE A2aTaskStore SHALL 支持按 Task ID 查询 A2aTask
3. THE A2aTaskStore SHALL 支持按 contextId 列出所有关联的 A2aTask
4. THE A2aTaskStore SHALL 支持取消处于 SUBMITTED 或 WORKING 状态的 A2aTask，将状态更新为 CANCELED
5. IF 取消请求针对已处于终态（COMPLETED / FAILED / CANCELED / REJECTED）的 A2aTask, THEN THE A2aTaskStore SHALL 返回取消失败
6. THE A2aTaskStore SHALL 按照 A2aProperties.task.ttl-minutes 配置的 TTL 自动清理过期 Task
7. THE A2aTaskStore SHALL 按照 A2aProperties.task.max-history-length 配置限制每个 Task 的 history 消息数量，超出时移除最早的消息

### Requirement 8: A2A Client 远程 Agent 发现

**User Story:** As a LifePilot 用户, I want LifePilot 能够发现远程 A2A Agent 的能力, so that LifePilot 可以调用外部 Agent 完成任务

#### Acceptance Criteria

1. THE A2aClientService SHALL 通过 HTTP GET 请求远程 Agent 的 `/.well-known/agent.json` 路径获取 Agent Card
2. THE A2aClientService SHALL 使用 A2aProperties.client.connect-timeout-seconds 和 A2aProperties.client.read-timeout-seconds 配置的超时参数
3. IF 远程 Agent 的 Agent Card 获取失败（网络错误、超时、非 200 响应）, THEN THE A2aClientService SHALL 记录 WARN 日志并返回空 Optional
4. THE A2aClientService SHALL 将获取到的 Agent Card 反序列化为 A2aAgentCard record
5. WHEN A2aProperties.client.enabled 为 false 时, THE A2aClientService SHALL 不执行任何远程调用

### Requirement 9: A2A Client 消息发送与任务管理

**User Story:** As a LifePilot 主 Agent, I want 向远程 A2A Agent 发送消息并管理任务, so that LifePilot 可以利用外部 Agent 的能力

#### Acceptance Criteria

1. THE A2aClientService SHALL 通过 HTTP POST 向远程 Agent 的消息端点发送 A2aMessage JSON，接收 A2aTask JSON 响应
2. THE A2aClientService SHALL 通过 HTTP GET 查询远程 Agent 的 Task 状态
3. THE A2aClientService SHALL 通过 HTTP POST 取消远程 Agent 的 Task
4. IF 远程 Agent 调用失败（网络错误、超时、非 2xx 响应）, THEN THE A2aClientService SHALL 记录 WARN 日志并返回包含错误描述的结果
5. THE A2aClientService SHALL 使用 Spring RestClient 进行 HTTP 调用

### Requirement 10: 远程 Agent 注册表

**User Story:** As a LifePilot 用户, I want 管理已配置的远程 A2A Agent 列表, so that LifePilot 可以维护可调用的外部 Agent 目录

#### Acceptance Criteria

1. THE RemoteAgentRegistry SHALL 支持通过 URL 注册远程 Agent，自动获取并缓存 Agent Card
2. THE RemoteAgentRegistry SHALL 支持注销远程 Agent
3. THE RemoteAgentRegistry SHALL 支持列出所有已注册的远程 Agent 及其 Agent Card
4. THE RemoteAgentRegistry SHALL 支持按 URL 查找远程 Agent
5. THE RemoteAgentRegistry SHALL 按照 A2aProperties.client.card-cache-ttl-minutes 配置的 TTL 缓存 Agent Card，过期后重新获取
6. WHEN 应用启动时, THE RemoteAgentRegistry SHALL 从 A2aProperties.client.remote-agents 配置的 URL 列表自动发现并注册远程 Agent
7. IF 启动时某个远程 Agent 发现失败, THEN THE RemoteAgentRegistry SHALL 记录 WARN 日志并跳过该 Agent，不阻塞启动流程

### Requirement 11: 远程 Agent 工具桥接

**User Story:** As a LifePilot 主 Agent, I want 远程 A2A Agent 自动注册为可调用工具, so that 主 Agent 的 LLM 可以通过 Function Call 自主决策何时调用远程 Agent

#### Acceptance Criteria

1. THE RemoteAgentToolFactory SHALL 为每个已注册的远程 Agent 创建一个 BuiltinTool 实例，工具 ID 格式为 `a2a_remote_{agentName}`（agentName 取自 Agent Card 的 name 字段，转为 snake_case）
2. THE RemoteAgentToolFactory SHALL 将 BuiltinTool 的 description 设置为远程 Agent Card 的 description
3. THE RemoteAgentToolFactory SHALL 将 BuiltinTool 的 inputSchema 定义为包含 task（必填，String）和 context（选填，String）两个参数
4. WHEN BuiltinTool 被执行时, THE RemoteAgentToolFactory SHALL 通过 A2aClientService.sendMessage() 向远程 Agent 发送消息，并将 A2aTask 的 Artifact 内容作为 ToolResult 返回
5. THE RemoteAgentToolFactory SHALL 通过 DynamicToolRegistry.registerBuiltinTool() 注册工具，通过 DynamicToolRegistry.unregisterBuiltinTool() 注销工具
6. WHEN RemoteAgentRegistry 中注册新的远程 Agent 时, THE RemoteAgentToolFactory SHALL 自动创建并注册对应的 BuiltinTool
7. WHEN RemoteAgentRegistry 中注销远程 Agent 时, THE RemoteAgentToolFactory SHALL 自动注销对应的 BuiltinTool

### Requirement 12: A2A Server 认证

**User Story:** As a 系统管理者, I want A2A Server 端点受 API Key 保护, so that 未授权的外部系统无法调用 LifePilot 的 Agent 能力

#### Acceptance Criteria

1. WHEN A2aProperties.server.api-key 配置为非空值时, THE A2A_Server SHALL 要求所有 `/api/a2a/**` 端点的请求在 HTTP Header 中携带 `X-API-Key` 字段
2. IF 请求未携带 `X-API-Key` Header 或值与配置不匹配, THEN THE A2A_Server SHALL 返回 HTTP 401 Unauthorized
3. THE A2A_Server SHALL 对 `/.well-known/agent.json` 端点不要求认证（Agent Card 是公开的发现信息）
4. WHEN A2aProperties.server.api-key 配置为空字符串时, THE A2A_Server SHALL 不启用认证（所有端点公开访问）

### Requirement 13: A2A 配置属性

**User Story:** As a LifePilot 用户, I want 通过配置文件控制 A2A 模块的行为, so that 用户可以按需启用/禁用 Server 和 Client 功能并调整参数

#### Acceptance Criteria

1. THE A2aProperties SHALL 使用 `@ConfigurationProperties(prefix = "lifepilot.a2a")` 绑定配置
2. THE A2aProperties SHALL 包含以下顶层配置：enabled（默认 true）
3. THE A2aProperties SHALL 包含以下 server 子配置：enabled（默认 true）、api-key（默认空）、agent-name（默认 "LifePilot"）、agent-description（默认 "个人生活助手"）、agent-version（默认 "1.0.0"）、protocol-version（默认 "0.2.5"）、streaming-enabled（默认 true）
4. THE A2aProperties SHALL 包含以下 client 子配置：enabled（默认 true）、remote-agents（默认空列表）、connect-timeout-seconds（默认 10）、read-timeout-seconds（默认 60）、card-cache-ttl-minutes（默认 30）
5. THE A2aProperties SHALL 包含以下 task 子配置：ttl-minutes（默认 60）、max-history-length（默认 50）

### Requirement 14: A2A 自动装配

**User Story:** As a 开发者, I want A2A 模块通过 Spring Boot 自动装配按需加载, so that 模块可以根据配置条件自动启用或禁用

#### Acceptance Criteria

1. THE A2aAutoConfiguration SHALL 在 `lifepilot.a2a.enabled=true` 时注册所有 A2A 相关 Bean
2. THE A2aAutoConfiguration SHALL 在 `lifepilot.a2a.server.enabled=true` 时注册 Server 相关 Bean（AgentCardGenerator、A2aAgentExecutor、A2aTaskStore、REST Controller）
3. THE A2aAutoConfiguration SHALL 在 `lifepilot.a2a.client.enabled=true` 时注册 Client 相关 Bean（A2aClientService、RemoteAgentRegistry、RemoteAgentToolFactory）
4. THE A2aAutoConfiguration SHALL 依赖 MultiAgentAutoConfiguration 提供的 AgentRegistry 和 AgentExecutor Bean
5. THE A2aAutoConfiguration SHALL 依赖工具系统提供的 DynamicToolRegistry Bean
6. WHEN `lifepilot.a2a.enabled=false` 时, THE A2aAutoConfiguration SHALL 不注册任何 Bean

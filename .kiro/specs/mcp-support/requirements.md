# MCP 协议支持需求文档

参考文档：
- 架构设计：#[[file:docs/architecture/tool-ecosystem.md]]（§4-7：MCP 协议、McpServerRegistry、McpToolAdapter、SkillToMcpBridge）
- 特性设计：#[[file:docs/features/mcp-support.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 工具系统 spec：#[[file:.kiro/specs/tool-system/design.md]]

---

## 概述

LifePilot 实现 MCP（Model Context Protocol）协议支持，包括：MCP Client 连接外部 MCP Server 并将其工具适配为 ToolContract，MCP Server Registry 管理所有 MCP Server 连接的生命周期（健康检查、自动重连），以及反向桥接（SkillToMcpBridge）将 LifePilot 内置工具暴露为 MCP Server。本 spec 基于已完成的工具系统（ToolContract / DynamicToolRegistry / ToolExecutionPipeline），补全 McpTool 的实际执行能力。

---

## 术语表

- **MCP**：Model Context Protocol，AI Agent 与外部工具交互的行业标准协议，基于 JSON-RPC 2.0
- **MCP_Client**：LifePilot 中管理与单个 MCP Server 连接生命周期的客户端组件
- **MCP_Server**：提供工具的外部服务进程（如 filesystem MCP Server、GitHub MCP Server）
- **McpTransport**：MCP 协议的传输层抽象，sealed interface，包含 StdioTransport、StreamableHttpTransport、SseTransport 三种实现
- **StdioTransport**：通过标准输入/输出与本地子进程通信的传输实现
- **StreamableHttpTransport**：通过 HTTP POST 与远程 MCP Server 通信的传输实现（MCP 规范推荐）
- **SseTransport**：基于 Server-Sent Events 的传输实现（已弃用，仅兼容旧服务器）
- **McpServerRegistry**：管理所有 MCP Server 连接生命周期的注册中心组件
- **McpToolAdapter**：将 MCP 工具 Schema 转换为 ToolContract（McpTool）的适配器组件
- **SkillToMcpBridge**：将 LifePilot 内置工具暴露为 MCP Server 的反向桥接组件
- **McpServerEndpoint**：提供 Streamable HTTP 端点的 REST 控制器
- **JsonRpcMessage**：JSON-RPC 2.0 消息格式的 record 封装
- **McpServerState**：MCP Server 连接状态枚举（DISCONNECTED / CONNECTING / INITIALIZING / CONNECTED / HEALTH_CHECK / RECONNECTING / DISCONNECTING）
- **DynamicToolRegistry**：已实现的动态工具注册中心（工具系统 spec）
- **ToolContract**：已实现的工具契约 sealed interface（工具系统 spec）
- **McpTool**：已存在的骨架 record（工具系统 spec），本 spec 将补全其执行能力

---

## 需求

### 需求 1：MCP 传输层

**用户故事：** 作为核心开发者，我需要一个可扩展的 MCP 传输层抽象，使 MCP Client 能够通过 stdio、Streamable HTTP 和 SSE 三种方式与 MCP Server 通信

#### 验收标准

1. THE McpTransport SHALL 定义为 sealed interface，permits StdioTransport、StreamableHttpTransport、SseTransport
2. THE McpTransport SHALL 提供 connect()、sendRequest()、sendNotification()、disconnect()、isConnected()、transportType() 方法
3. THE TransportType SHALL 定义为枚举，包含 STDIO、STREAMABLE_HTTP、SSE_LEGACY 三个值
4. WHEN connect() 被调用时，THE McpTransport SHALL 返回 CompletableFuture<Void> 表示连接完成
5. WHEN sendRequest() 被调用时，THE McpTransport SHALL 返回 CompletableFuture<JsonNode> 表示响应结果
6. IF 传输连接失败，THEN THE McpTransport SHALL 抛出 McpTransportException 并包含服务器名称和失败原因

### 需求 2：Stdio 传输实现

**用户故事：** 作为核心开发者，我需要 stdio 传输实现，使 LifePilot 能够通过启动本地子进程与 MCP Server 通信

#### 验收标准

1. WHEN connect() 被调用时，THE StdioTransport SHALL 使用 ProcessBuilder 启动 MCP Server 子进程
2. THE StdioTransport SHALL 使用 Virtual Thread 持续读取子进程的 stdout 并分发 JSON-RPC 响应
3. THE StdioTransport SHALL 使用 Virtual Thread 读取子进程的 stderr 并记录为 DEBUG 日志
4. WHEN sendRequest() 被调用时，THE StdioTransport SHALL 生成唯一请求 ID，将 CompletableFuture 放入 pendingRequests，通过 stdin 发送 JSON-RPC 消息
5. WHEN 收到 JSON-RPC 响应时，THE StdioTransport SHALL 根据响应 ID 完成对应的 pendingRequests Future
6. WHEN 收到 JSON-RPC 错误响应时，THE StdioTransport SHALL 以 McpToolCallException 完成对应的 Future
7. WHEN disconnect() 被调用时，THE StdioTransport SHALL 关闭 stdin/stdout 管道，销毁子进程，等待最多 5 秒后强制终止
8. WHEN disconnect() 被调用时，THE StdioTransport SHALL 以 McpTransportException 完成所有 pendingRequests
9. IF McpServerConfig 包含 env 配置，THEN THE StdioTransport SHALL 将环境变量注入子进程

### 需求 3：Streamable HTTP 传输实现

**用户故事：** 作为核心开发者，我需要 Streamable HTTP 传输实现，使 LifePilot 能够通过 HTTP 与远程 MCP Server 通信

#### 验收标准

1. WHEN connect() 被调用时，THE StreamableHttpTransport SHALL 创建使用 Virtual Thread 执行器的 HttpClient 实例
2. WHEN sendRequest() 被调用时，THE StreamableHttpTransport SHALL 通过 HTTP POST 发送 JSON-RPC 消息到 MCP Server 端点
3. THE StreamableHttpTransport SHALL 在请求头中携带 Content-Type: application/json 和 Accept: application/json, text/event-stream
4. WHEN 响应包含 Mcp-Session-Id 头时，THE StreamableHttpTransport SHALL 保存会话 ID 并在后续请求中携带
5. IF HTTP 响应状态码不为 200，THEN THE StreamableHttpTransport SHALL 抛出 McpTransportException 并包含状态码
6. WHEN disconnect() 被调用时，THE StreamableHttpTransport SHALL 清除会话 ID 并标记为未连接

### 需求 4：SSE 传输骨架

**用户故事：** 作为核心开发者，我需要 SSE 传输的骨架实现，以兼容尚未升级到 Streamable HTTP 的旧版 MCP Server

#### 验收标准

1. THE SseTransport SHALL 标注 @Deprecated(since = "2025-03-26", forRemoval = false)
2. WHEN SseTransport 被创建时，THE SseTransport SHALL 记录 WARN 级别日志建议迁移到 Streamable HTTP
3. THE SseTransport SHALL 实现 McpTransport 接口的所有方法，connect() 和 disconnect() 返回已完成的 Future
4. WHEN sendRequest() 被调用时，THE SseTransport SHALL 抛出 UnsupportedOperationException 提示使用 Streamable HTTP

### 需求 5：JSON-RPC 协议层

**用户故事：** 作为核心开发者，我需要 JSON-RPC 2.0 消息封装，使传输层能够构建和解析标准的 MCP 协议消息

#### 验收标准

1. THE JsonRpcMessage SHALL 定义为 record，包含 jsonrpc、id、method、params、result、error 字段
2. THE JsonRpcMessage SHALL 提供 request(id, method, params)、notification(method, params)、response(id, result) 静态工厂方法
3. THE JsonRpcMessage SHALL 将 jsonrpc 字段固定为 "2.0"
4. WHEN 创建 notification 时，THE JsonRpcMessage SHALL 将 id 设为 null
5. FOR ALL 通过 request() 创建的 JsonRpcMessage，序列化后再反序列化 SHALL 产生等价对象（往返属性）

### 需求 6：MCP 数据模型

**用户故事：** 作为核心开发者，我需要 MCP 协议相关的数据模型，使 MCP Client 能够解析服务端能力、工具 Schema 和调用结果

#### 验收标准

1. THE McpServerCapabilities SHALL 定义为 record，包含 supportsTools、supportsResources、supportsPrompts 布尔字段
2. THE McpServerInfo SHALL 定义为 record，包含 name 和 version 字符串字段
3. THE McpToolSchema SHALL 定义为 record，包含 name、description、inputSchema、annotations 字段
4. THE McpToolAnnotations SHALL 定义为 record，包含 title、readOnlyHint、destructiveHint、idempotentHint、openWorldHint 字段
5. THE McpToolResult SHALL 定义为 record，包含 content 列表和 isError 布尔字段
6. THE McpContent SHALL 定义为 record，包含 type、text、data、mimeType 字段

### 需求 7：MCP Client

**用户故事：** 作为核心开发者，我需要 MCP Client 管理与单个 MCP Server 的完整生命周期，包括连接、初始化、工具发现和工具调用

#### 验收标准

1. WHEN initialize() 被调用时，THE MCP_Client SHALL 依次执行：建立传输连接、发送 initialize 请求（携带协议版本 "2025-06-18" 和客户端能力）、发送 notifications/initialized 通知
2. WHEN initialize() 成功时，THE MCP_Client SHALL 解析并保存服务端能力（McpServerCapabilities）和服务端信息（McpServerInfo）
3. WHEN listTools() 被调用时，THE MCP_Client SHALL 发送 tools/list 请求并将响应解析为 List<McpToolSchema>
4. WHEN callTool(toolName, arguments) 被调用时，THE MCP_Client SHALL 发送 tools/call 请求并将响应解析为 McpToolResult
5. WHEN shutdown() 被调用时，THE MCP_Client SHALL 断开传输连接并释放资源
6. THE MCP_Client SHALL 根据 McpServerConfig 的 transport 类型自动创建对应的 McpTransport 实例
7. IF initialize() 过程中传输连接失败，THEN THE MCP_Client SHALL 抛出 McpConnectionTimeoutException

### 需求 8：MCP 异常体系

**用户故事：** 作为核心开发者，我需要结构化的 MCP 异常体系，使调用方能够区分传输错误、工具调用错误、连接超时和服务器不可用

#### 验收标准

1. THE McpTransportException SHALL 作为 MCP 传输层异常的基类，继承 RuntimeException
2. THE McpToolCallException SHALL 表示 MCP Server 返回的工具调用错误，继承 RuntimeException
3. THE McpConnectionTimeoutException SHALL 继承 McpTransportException，包含服务器名称
4. THE McpServerUnavailableException SHALL 继承 McpTransportException，包含服务器名称并提供 getServerName() 方法

### 需求 9：MCP Server Registry

**用户故事：** 作为核心开发者，我需要 MCP Server Registry 管理所有 MCP Server 连接的生命周期，包括自动连接、健康检查和断开重连

#### 验收标准

1. WHEN initializeAll(configs) 被调用时，THE McpServerRegistry SHALL 遍历所有 autoConnect=true 的配置，依次建立连接并注册工具
2. WHEN connectServer(config) 被调用时，THE McpServerRegistry SHALL 依次执行：创建 McpClient、初始化连接、获取工具列表、通过 McpToolAdapter 转换工具、注册到 DynamicToolRegistry、启动健康检查
3. THE McpServerRegistry SHALL 使用 McpServerState 枚举跟踪每个服务器的连接状态（DISCONNECTED → CONNECTING → INITIALIZING → CONNECTED）
4. WHILE 服务器处于 CONNECTED 状态，THE McpServerRegistry SHALL 按 healthCheckInterval 定期执行健康检查（使用 tools/list 作为 ping）
5. IF 健康检查失败，THEN THE McpServerRegistry SHALL 注销该服务器的工具并启动自动重连
6. WHEN 自动重连时，THE McpServerRegistry SHALL 使用指数退避策略（初始 500ms，倍数 2.0，上限 5s），最多重试 maxReconnectAttempts 次
7. IF 重连次数耗尽，THEN THE McpServerRegistry SHALL 将服务器状态设为 DISCONNECTED 并记录 ERROR 日志
8. WHEN disconnectServer(serverName) 被调用时，THE McpServerRegistry SHALL 注销工具、关闭客户端、更新状态为 DISCONNECTED
9. WHEN shutdownAll() 被调用时，THE McpServerRegistry SHALL 关闭所有服务器连接并清理资源
10. THE McpServerRegistry SHALL 提供 getClient(serverName) 方法返回可用的 McpClient
11. THE McpServerRegistry SHALL 提供 listServers() 方法返回所有服务器条目的不可变列表
12. IF 单个服务器连接失败，THEN THE McpServerRegistry SHALL 继续连接其他服务器，不影响整体初始化

### 需求 10：MCP 工具适配器

**用户故事：** 作为核心开发者，我需要 MCP 工具适配器将 MCP Server 返回的工具 Schema 转换为 LifePilot 的 ToolContract

#### 验收标准

1. THE McpToolAdapter SHALL 将 McpToolSchema 转换为 McpTool（ToolContract 实现）
2. THE McpToolAdapter SHALL 生成工具 ID 格式为 mcp.{serverName}.{toolName}
3. WHEN McpToolAnnotations 的 readOnlyHint 为 true 时，THE McpToolAdapter SHALL 将风险等级推断为 LOW
4. WHEN McpToolAnnotations 的 destructiveHint 为 true 时，THE McpToolAdapter SHALL 将风险等级推断为 HIGH
5. WHEN McpToolAnnotations 的 destructiveHint 和 openWorldHint 均为 true 时，THE McpToolAdapter SHALL 将风险等级推断为 CRITICAL
6. WHEN McpToolAnnotations 为 null 或无特殊标记时，THE McpToolAdapter SHALL 将风险等级默认为 MEDIUM
7. THE McpToolAdapter SHALL 从 McpToolAnnotations 的 idempotentHint 推断幂等性，默认为 false
8. THE McpToolAdapter SHALL 为 MCP 工具分配默认预算（60 秒超时，2 次重试），HIGH 风险工具使用更严格预算（30 秒超时，1 次重试）
9. THE McpToolAdapter SHALL 为每个工具生成标签列表，包含 "mcp" 和 "mcp:{serverName}"
10. IF 单个工具转换失败，THEN THE McpToolAdapter SHALL 记录 WARN 日志并跳过该工具，继续转换其他工具

### 需求 11：McpTool 执行能力补全

**用户故事：** 作为核心开发者，我需要补全 McpTool 的 execute() 方法，使其能够通过 McpClient 实际调用 MCP Server 的工具

#### 验收标准

1. THE McpTool record SHALL 新增 client 字段（McpClient 类型），用于执行 MCP 工具调用
2. WHEN execute(input) 被调用时，THE McpTool SHALL 通过 client.callTool(mcpToolName, input.parameters()) 发送 tools/call 请求
3. WHEN MCP 工具调用成功时，THE McpTool SHALL 返回 ToolResult.success()，data 包含 content 字段，meta 包含 executorType="MCP" 和 mcpServerName
4. IF MCP 工具调用失败，THEN THE McpTool SHALL 返回 ToolResult.error()，包含错误信息和执行元信息
5. THE McpTool SHALL 在 execute() 中记录调用开始和完成的 DEBUG 日志，包含 serverName、toolName 和耗时

### 需求 12：反向桥接（SkillToMcpBridge）

**用户故事：** 作为核心开发者，我需要反向桥接组件将 LifePilot 的可导出工具暴露为 MCP Server，使外部 AI 助手能够调用 LifePilot 的能力

#### 验收标准

1. THE SkillToMcpBridge SHALL 从 DynamicToolRegistry 筛选 exportable=true 的工具并转换为 McpToolSchema 列表
2. WHEN handleToolCall(toolName, arguments) 被调用时，THE SkillToMcpBridge SHALL 查找可导出工具、构建 ToolInput、执行参数校验、调用 tool.execute() 并返回 McpToolResult
3. IF 调用的工具不存在或不可导出，THEN THE SkillToMcpBridge SHALL 返回 isError=true 的 McpToolResult
4. IF 参数校验失败，THEN THE SkillToMcpBridge SHALL 返回包含校验错误信息的 McpToolResult
5. THE SkillToMcpBridge SHALL 提供 handleInitialize() 方法返回服务端能力（协议版本 "2025-06-18"、支持 tools）
6. THE SkillToMcpBridge SHALL 将 ToolContract 的 RiskLevel 映射到 McpToolAnnotations（LOW→readOnlyHint=true，HIGH/CRITICAL→destructiveHint=true）

### 需求 13：MCP Server HTTP 端点

**用户故事：** 作为核心开发者，我需要 MCP Server HTTP 端点，使外部 MCP Client 能够通过 Streamable HTTP 协议调用 LifePilot 的工具

#### 验收标准

1. THE McpServerEndpoint SHALL 在 lifepilot.mcp.server.enabled=true 时启用（@ConditionalOnProperty）
2. THE McpServerEndpoint SHALL 提供 POST /mcp 端点，接收 JSON-RPC 请求并返回 JSON-RPC 响应
3. WHEN 收到 initialize 请求时，THE McpServerEndpoint SHALL 委托 SkillToMcpBridge.handleInitialize() 处理
4. WHEN 收到 tools/list 请求时，THE McpServerEndpoint SHALL 委托 SkillToMcpBridge.listExportableTools() 处理
5. WHEN 收到 tools/call 请求时，THE McpServerEndpoint SHALL 委托 SkillToMcpBridge.handleToolCall() 处理
6. IF 收到不支持的方法，THEN THE McpServerEndpoint SHALL 返回 JSON-RPC 错误响应（code=-32601，message="方法不支持"）

### 需求 14：MCP 配置

**用户故事：** 作为核心开发者，我需要 Spring Boot 配置绑定，使用户能够通过 YAML 配置文件声明 MCP Server 连接

#### 验收标准

1. THE McpServerConfig SHALL 定义为 record，包含 name、transport、command、args、url、env、timeout、autoConnect、reconnect、reconnectDelay、maxReconnectAttempts、healthCheckInterval 字段
2. THE McpConfigProperties SHALL 绑定 lifepilot.mcp 配置前缀，包含 enabled、server.enabled 和 servers 列表
3. THE McpAutoConfiguration SHALL 在 lifepilot.mcp.enabled=true 时自动创建 McpToolAdapter、McpServerRegistry、SkillToMcpBridge Bean
4. IF McpServerConfig 的 transport 为 STDIO 且 command 为空，THEN THE McpServerConfig SHALL 在构造时抛出 IllegalArgumentException
5. IF McpServerConfig 的 transport 为 STREAMABLE_HTTP 或 SSE_LEGACY 且 url 为空，THEN THE McpServerConfig SHALL 在构造时抛出 IllegalArgumentException
6. THE McpServerConfig SHALL 提供默认值：timeout=60s、reconnectDelay=500ms、maxReconnectAttempts=5、healthCheckInterval=30s

---

## 正确性属性

### CP-1：工具 ID 唯一性
FOR ALL 通过 McpToolAdapter 转换的工具，工具 ID 格式 SHALL 为 mcp.{serverName}.{toolName}，同一 serverName 下不同 toolName 产生不同 ID。

### CP-2：风险等级推断一致性
FOR ALL McpToolSchema，相同的 annotations 组合 SHALL 始终推断出相同的 RiskLevel。

### CP-3：连接状态机完整性
McpServerState 的状态转换 SHALL 遵循定义的状态机图，不存在非法状态转换。

### CP-4：重连指数退避
重连延迟 SHALL 满足 delay = min(500ms × 2^(attempt-1), 5000ms)，不超过上限。

### CP-5：JsonRpcMessage 往返属性
FOR ALL 通过 request() 创建的 JsonRpcMessage，Jackson 序列化后再反序列化 SHALL 产生等价对象。

### CP-6：反向桥接安全约束
SkillToMcpBridge 暴露的工具集合 SHALL 是 DynamicToolRegistry 中 exportable=true 工具的子集。

---

## 非功能需求

- MCP 传输层 I/O 操作使用 Virtual Thread，不阻塞平台线程
- McpServerRegistry 使用 ConcurrentHashMap 保证线程安全
- 所有日志、注释、异常消息使用中文
- MCP 配置通过 lifepilot.mcp.enabled 控制启用/禁用，默认 true
- MCP Server 模式通过 lifepilot.mcp.server.enabled 控制，默认 false

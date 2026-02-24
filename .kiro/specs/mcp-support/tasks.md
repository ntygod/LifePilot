# MCP 协议支持任务列表

参考文档：
- 需求文档：#[[file:.kiro/specs/mcp-support/requirements.md]]
- 设计文档：#[[file:.kiro/specs/mcp-support/design.md]]
- 架构设计：#[[file:docs/architecture/tool-ecosystem.md]]

---

## 任务

- [x] 1. 实现 MCP 异常体系和数据模型
  - [x] 1.1 创建 MCP 异常类（McpTransportException、McpToolCallException、McpConnectionTimeoutException、McpServerUnavailableException）在 `com.lifepilot.mcp.exception` 包下
  - [x] 1.2 创建 MCP 数据模型 record（McpServerCapabilities、McpServerInfo、McpToolSchema、McpToolAnnotations、McpToolResult、McpContent）在 `com.lifepilot.mcp.model` 包下
  - [x] 1.3 创建 JsonRpcMessage record 在 `com.lifepilot.mcp.protocol` 包下，包含 request()、notification()、response() 静态工厂方法
  - [x] 1.4 编写 JsonRpcMessage 单元测试：验证工厂方法正确性、Jackson 序列化/反序列化往返属性（CP-5）

- [x] 2. 实现 MCP 传输层
  - [x] 2.1 创建 TransportType 枚举（STDIO、STREAMABLE_HTTP、SSE_LEGACY）在 `com.lifepilot.mcp.transport` 包下
  - [x] 2.2 创建 McpTransport sealed interface（permits StdioTransport、StreamableHttpTransport、SseTransport），定义 connect()、sendRequest()、sendNotification()、disconnect()、isConnected()、transportType() 方法
  - [x] 2.3 实现 StdioTransport：ProcessBuilder 启动子进程、Virtual Thread 读取 stdout/stderr、pendingRequests 管理、JSON-RPC 消息收发
  - [x] 2.4 实现 StreamableHttpTransport：HttpClient 创建（Virtual Thread 执行器）、HTTP POST 发送 JSON-RPC、Mcp-Session-Id 会话管理
  - [x] 2.5 实现 SseTransport 骨架：@Deprecated 注解、connect/disconnect 返回已完成 Future、sendRequest 抛出 UnsupportedOperationException

- [x] 3. 实现 MCP 配置
  - [x] 3.1 创建 McpServerConfig record（含构造校验：stdio 必须有 command，远程传输必须有 url，默认值填充）
  - [x] 3.2 创建 McpConfigProperties（@ConfigurationProperties 绑定 lifepilot.mcp 前缀）
  - [x] 3.3 编写 McpServerConfig 单元测试：验证构造校验（缺少 command/url 抛异常）、默认值填充

- [x] 4. 实现 MCP Client
  - [x] 4.1 实现 McpClient 类：根据 McpServerConfig 创建 McpTransport、initialize()（连接 + 初始化握手 + notifications/initialized）、listTools()、callTool()、shutdown()
  - [x] 4.2 编写 McpClient 单元测试：使用 Mock McpTransport 验证 initialize 流程、listTools 解析、callTool 请求构建

- [x] 5. 实现 MCP 工具适配器
  - [x] 5.1 实现 McpToolAdapter：toToolContracts() 批量转换、toToolContract() 单个转换、ID 生成（mcp.{server}.{tool}）、风险等级推断、幂等性推断、预算分配、标签生成
  - [x] 5.2 编写 McpToolAdapter 单元测试：穷举 annotations 组合验证风险等级推断一致性（CP-2）、ID 格式验证（CP-1）、单个工具转换失败不影响其他工具

- [x] 6. 补全 McpTool 执行能力
  - [x] 6.1 更新 McpTool record：新增 client 字段（McpClient 类型），实现 execute() 方法（通过 client.callTool() 调用 MCP Server，构建 ToolResult 含 ToolResultMeta）
  - [x] 6.2 编写 McpTool 单元测试：使用 Mock McpClient 验证 execute() 成功路径（返回 ToolResult.success）和失败路径（返回 ToolResult.error）

- [x] 7. 实现 MCP Server Registry
  - [x] 7.1 创建 McpServerState 枚举（DISCONNECTED、CONNECTING、INITIALIZING、CONNECTED、HEALTH_CHECK、RECONNECTING、DISCONNECTING），含 isAvailable() 方法
  - [x] 7.2 创建 McpServerEntry record（config、client、state、serverInfo、lastHealthCheck、reconnectAttempts、connectedSince、lastError），使用 @Builder(toBuilder = true)
  - [x] 7.3 实现 McpServerRegistry：initializeAll()、connectServer()（创建 McpClient → 初始化 → 工具发现 → 适配 → 注册到 DynamicToolRegistry）、disconnectServer()、shutdownAll()、getClient()、listServers()
  - [x] 7.4 实现健康检查和自动重连：scheduleHealthCheck()（定期 tools/list）、scheduleReconnect()（指数退避：500ms × 2^(n-1)，上限 5s）
  - [x] 7.5 编写 McpServerRegistry 单元测试：使用 Mock McpClient 和 Mock DynamicToolRegistry 验证连接→注册→断开→注销流程、重连次数耗尽后状态为 DISCONNECTED

- [x] 8. 实现反向桥接
  - [x] 8.1 实现 SkillToMcpBridge：listExportableTools()（筛选 exportable=true 并转换为 McpToolSchema）、handleToolCall()（查找工具→校验→执行→转换结果）、handleInitialize()
  - [x] 8.2 实现 McpServerEndpoint：POST /mcp 端点，@ConditionalOnProperty(name = "lifepilot.mcp.server.enabled")，分发 initialize/tools/list/tools/call 请求
  - [x] 8.3 编写 SkillToMcpBridge 单元测试：验证仅返回 exportable 工具（CP-6）、工具不存在返回错误、RiskLevel→annotations 映射正确性
  - [ ] 8.4* 编写 McpServerEndpoint 集成测试：@WebMvcTest 验证 HTTP 端点请求/响应

- [x] 9. 实现 MCP 自动配置
  - [x] 9.1 创建 McpAutoConfiguration：@ConditionalOnProperty(name = "lifepilot.mcp.enabled")，注册 McpToolAdapter、McpServerRegistry、SkillToMcpBridge Bean，@EventListener(ApplicationReadyEvent) 触发 initializeAll()
  - [x] 9.2 更新 application.yml 添加 lifepilot.mcp 默认配置
  - [ ] 9.3* 编写 McpAutoConfiguration 集成测试：验证 enabled=true 时 Bean 创建、enabled=false 时 Bean 不创建

- [x] 10. 更新 package-info 和文档
  - [x] 10.1 更新 `com.lifepilot.mcp.package-info.java`，新增各子包的 package-info.java
  - [ ] 10.2* 更新 pom.xml 添加必要依赖（如有新增）

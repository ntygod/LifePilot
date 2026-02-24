# MCP 协议支持设计文档

参考文档：
- 架构设计：#[[file:docs/architecture/tool-ecosystem.md]]（§4-7）
- 特性设计：#[[file:docs/features/mcp-support.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 工具系统设计：#[[file:.kiro/specs/tool-system/design.md]]
- 需求文档：#[[file:.kiro/specs/mcp-support/requirements.md]]

---

## 1. 范围

本 spec 实现 MCP 协议支持，包括：
- `com.lifepilot.mcp.transport` — McpTransport sealed interface 及三种传输实现
- `com.lifepilot.mcp.protocol` — JsonRpcMessage record
- `com.lifepilot.mcp.model` — MCP 数据模型（McpServerCapabilities、McpServerInfo、McpToolSchema、McpToolAnnotations、McpToolResult、McpContent）
- `com.lifepilot.mcp` — McpClient
- `com.lifepilot.mcp.registry` — McpServerRegistry、McpServerState、McpServerEntry
- `com.lifepilot.mcp.adapter` — McpToolAdapter
- `com.lifepilot.mcp.bridge` — SkillToMcpBridge、McpServerEndpoint
- `com.lifepilot.mcp.exception` — MCP 异常体系
- `com.lifepilot.mcp.config` — McpServerConfig、McpConfigProperties、McpAutoConfiguration
- 更新 `com.lifepilot.tool.McpTool` — 补全 execute() 方法，新增 client 字段

不包含：MCP Resource/Prompt 支持、SSE 传输完整实现（仅骨架）、YAML 声明式工具加载。

---

## 2. 包结构

```
com.lifepilot.mcp/
├── McpClient.java                    # MCP 客户端
├── package-info.java                 # 包说明（已存在，需更新）
├── transport/
│   ├── McpTransport.java             # sealed interface
│   ├── TransportType.java            # 传输类型枚举
│   ├── StdioTransport.java           # stdio 传输
│   ├── StreamableHttpTransport.java  # Streamable HTTP 传输
│   └── SseTransport.java            # SSE 传输（骨架）
├── protocol/
│   └── JsonRpcMessage.java           # JSON-RPC 2.0 消息
├── model/
│   ├── McpServerCapabilities.java    # 服务端能力
│   ├── McpServerInfo.java            # 服务端信息
│   ├── McpToolSchema.java            # 工具 Schema
│   ├── McpToolAnnotations.java       # 工具注解
│   ├── McpToolResult.java            # 工具调用结果
│   └── McpContent.java               # 内容项
├── registry/
│   ├── McpServerRegistry.java        # 服务器注册中心
│   ├── McpServerState.java           # 连接状态枚举
│   └── McpServerEntry.java           # 服务器条目
├── adapter/
│   └── McpToolAdapter.java           # 工具适配器
├── bridge/
│   ├── SkillToMcpBridge.java         # 反向桥接
│   └── McpServerEndpoint.java        # MCP Server HTTP 端点
├── exception/
│   ├── McpTransportException.java    # 传输异常
│   ├── McpToolCallException.java     # 工具调用异常
│   ├── McpConnectionTimeoutException.java
│   └── McpServerUnavailableException.java
└── config/
    ├── McpServerConfig.java          # 服务器配置 record
    ├── McpConfigProperties.java      # Spring Boot 配置绑定
    └── McpAutoConfiguration.java     # 自动配置

com.lifepilot.tool/
└── McpTool.java                      # 更新：新增 client 字段，补全 execute()
```

---

## 3. 核心设计决策

### 3.1 自定义 MCP 协议层而非使用 MCP Java SDK
虽然 MCP Java SDK（io.modelcontextprotocol.sdk:mcp）提供了现成实现，但 LifePilot 选择自定义实现以获得：
- 与三层工具架构的深度集成（McpToolAdapter 直接生成 McpTool）
- 对传输层的完全控制（Virtual Thread、自定义重连策略）
- 更轻量的依赖（仅依赖 Jackson，不引入 SDK 的传递依赖）

### 3.2 McpTool record 变更策略
现有 McpTool 有 11 个字段，需要新增 `client` 字段（McpClient 类型）。由于 record 是不可变的，这是一个破坏性变更。需要同时更新 DynamicToolRegistry 中 registerMcpTools 的调用方（McpServerRegistry）。

### 3.3 SSE 传输仅骨架
SSE 传输已在 MCP 规范中弃用。本 spec 仅实现骨架（connect/disconnect 返回已完成 Future，sendRequest 抛出 UnsupportedOperationException），在需要时再补全。

### 3.4 健康检查使用 tools/list
MCP 规范没有定义专门的 ping 方法。使用 tools/list 作为健康检查请求，既验证连接可用性，又能检测工具列表变更。

### 3.5 McpAutoConfiguration 条件装配
通过 `lifepilot.mcp.enabled=true`（默认）控制 MCP 支持启用。McpServerEndpoint 额外通过 `lifepilot.mcp.server.enabled=false`（默认）控制。

### 3.6 McpServerRegistry 启动时机
使用 `@EventListener(ApplicationReadyEvent.class)` 在应用完全启动后初始化 MCP Server 连接，确保 DynamicToolRegistry 和 McpToolAdapter 已就绪。

---

## 4. 关键接口

### 4.1 McpTransport sealed interface
```java
public sealed interface McpTransport
    permits StdioTransport, StreamableHttpTransport, SseTransport {
    CompletableFuture<Void> connect();
    CompletableFuture<JsonNode> sendRequest(String method, Map<String, Object> params);
    void sendNotification(String method, Map<String, Object> params);
    CompletableFuture<Void> disconnect();
    boolean isConnected();
    TransportType transportType();
}
```

### 4.2 McpClient 核心方法
```java
public class McpClient {
    CompletableFuture<Void> initialize();
    CompletableFuture<List<McpToolSchema>> listTools();
    CompletableFuture<McpToolResult> callTool(String toolName, Map<String, Object> arguments);
    CompletableFuture<Void> shutdown();
    boolean isConnected();
    String getServerName();
}
```

### 4.3 McpServerRegistry 核心方法
```java
@Service
public class McpServerRegistry {
    void initializeAll(List<McpServerConfig> configs);
    void connectServer(McpServerConfig config);
    void disconnectServer(String serverName);
    void shutdownAll();
    Optional<McpClient> getClient(String serverName);
    List<McpServerEntry> listServers();
}
```

### 4.4 McpToolAdapter 核心方法
```java
@Component
public class McpToolAdapter {
    List<ToolContract> toToolContracts(String serverName, List<McpToolSchema> schemas, McpClient client);
    ToolContract toToolContract(String serverName, McpToolSchema schema, McpClient client);
}
```

### 4.5 更新后的 McpTool record
```java
public record McpTool(
    String id, String name, String description,
    JsonSchema inputSchema, JsonSchema outputSchema,
    RiskLevel riskLevel, boolean idempotent, ToolBudget budget,
    List<String> tags, String serverName, String mcpToolName,
    McpClient client  // 新增字段
) implements ToolContract {
    @Override
    public ToolResult execute(ToolInput input) {
        // 通过 client.callTool() 实际调用 MCP Server
    }
}
```

---

## 5. 状态机设计

### McpServerState 状态转换
```
DISCONNECTED → CONNECTING      : connectServer() 调用
CONNECTING → INITIALIZING      : 传输连接成功
CONNECTING → DISCONNECTED      : 传输连接失败（重试耗尽）
INITIALIZING → CONNECTED       : initialize 成功
INITIALIZING → DISCONNECTED    : initialize 失败
CONNECTED → HEALTH_CHECK       : 健康检查定时器触发
HEALTH_CHECK → CONNECTED       : ping 成功
HEALTH_CHECK → RECONNECTING    : ping 失败
CONNECTED → DISCONNECTING      : shutdown() 调用
RECONNECTING → CONNECTING      : 指数退避后重连
RECONNECTING → DISCONNECTED    : 重连次数耗尽
DISCONNECTING → DISCONNECTED   : 传输断开完成
```

---

## 6. 配置

```yaml
lifepilot:
  mcp:
    enabled: true                    # MCP 支持总开关
    server:
      enabled: false                 # MCP Server 模式（反向桥接）
    servers:                         # MCP Server 连接列表
      - name: filesystem
        command: npx
        args: ["-y", "@modelcontextprotocol/server-filesystem", "/home/user/documents"]
        transport: stdio
        auto-connect: true
        reconnect: true
        timeout: 60s
        health-check-interval: 30s
      - name: github
        url: http://localhost:3001/mcp
        transport: streamable-http
        auto-connect: true
```

---

## 7. 测试策略

### 7.1 单元测试
- **JsonRpcMessage**：往返序列化属性、工厂方法正确性
- **McpToolAdapter**：风险等级推断（所有 annotations 组合）、ID 生成格式、预算分配
- **McpServerConfig**：配置校验（缺少 command/url 时抛异常）、默认值填充
- **McpServerState**：isAvailable() 方法
- **SkillToMcpBridge**：工具筛选（仅 exportable）、RiskLevel → annotations 映射
- **McpTool**：execute() 成功/失败路径

### 7.2 集成测试
- **McpServerRegistry**：连接 → 工具注册 → 断开 → 工具注销 完整流程（使用 Mock McpClient）
- **McpAutoConfiguration**：条件装配验证（enabled=true/false）
- **McpServerEndpoint**：HTTP 端点请求/响应（@WebMvcTest）

### 7.3 不测试
- StdioTransport 的实际子进程启动（依赖外部 MCP Server 进程）
- StreamableHttpTransport 的实际 HTTP 通信（依赖外部服务）
- SseTransport（骨架实现）

---

## 8. 数据库迁移

本 spec 不新增数据库表。MCP Server 连接状态为运行时内存状态，不持久化。

---

## 9. 验收标准与正确性属性映射

| 正确性属性 | 测试方式 | 覆盖的验收标准 |
|-----------|---------|--------------|
| CP-1 工具 ID 唯一性 | 单元测试：不同 serverName+toolName 生成不同 ID | AC-10.2 |
| CP-2 风险等级推断一致性 | 单元测试：穷举 annotations 组合 | AC-10.3~10.6 |
| CP-3 连接状态机完整性 | 单元测试：验证合法状态转换 | AC-9.3 |
| CP-4 重连指数退避 | 单元测试：验证延迟计算公式 | AC-9.6 |
| CP-5 JsonRpcMessage 往返 | 单元测试：序列化→反序列化等价 | AC-5.5 |
| CP-6 反向桥接安全约束 | 单元测试：仅返回 exportable 工具 | AC-12.1 |

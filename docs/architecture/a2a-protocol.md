# A2A 协议支持架构设计

> **模块编号**：22（Phase 6）
> **依赖模块**：多 Agent 协作（模块 21）、Agent 引擎（模块 2）、Gateway（模块 13）、Web UI（模块 18）
> **最后更新**：2026-02-27
> **协议版本**：A2A Protocol v0.2.5（2025 年 4 月 Google 发布，2025 年 6 月捐赠 Linux Foundation）

---

## 1. 模块定位与职责边界

### 1.1 定位

A2A（Agent-to-Agent）协议支持模块为 ZhiWei 提供跨系统 Agent 互操作能力。ZhiWei 同时作为 A2A Server（暴露自身 Agent 能力供外部调用）和 A2A Client（发现并调用外部 A2A Agent），实现与其他 AI Agent 系统的标准化通信。

### 1.2 职责边界

| 职责 | 归属 | 说明 |
|------|------|------|
| A2A Server 端点暴露 | 本模块 | 暴露 Agent Card、消息处理、任务管理端点 |
| A2A Client 远程调用 | 本模块 | 发现远程 Agent、发送消息、管理任务 |
| Agent Card 生成 | 本模块 | 从 AgentRegistry 生成标准 Agent Card |
| A2A Task 生命周期管理 | 本模块 | 管理 A2A Task 状态机（submitted → working → completed） |
| Agent 注册与执行 | 多 Agent 模块（模块 21） | AgentRegistry、AgentExecutor、HandoffTool |
| HTTP 端点基础设施 | Web 模块（模块 18） | Spring MVC、CORS、SSE |
| 认证与安全 | Gateway 模块（模块 13） | API Key 验证、速率限制 |

### 1.3 与 MCP 的关系

A2A 和 MCP 是互补协议，解决不同层次的问题：

| 维度 | MCP（模块 4 已实现） | A2A（本模块） |
|------|---------------------|--------------|
| 连接对象 | Agent ↔ Tool | Agent ↔ Agent |
| 通信模式 | 同步工具调用 | 异步任务管理 |
| 发现机制 | Tool Schema | Agent Card |
| 状态管理 | 无状态 | Task 生命周期 |
| 适用场景 | 调用外部工具/数据源 | 跨系统 Agent 协作 |

---

## 2. 核心概念与术语

### 2.1 A2A 协议核心概念

| 术语 | 定义 |
|------|------|
| Agent Card | Agent 能力声明文档（JSON），描述 Agent 的身份、技能、端点和认证要求 |
| Task | 工作单元，具有生命周期状态管理（submitted → working → completed/failed） |
| Message | Agent 间交换信息的基本单元，包含多个 Part（TextPart / FilePart / DataPart） |
| Part | 消息内容片段，支持文本、文件（base64 或 URI）、结构化数据 |
| Artifact | Task 产出物，包含一个或多个 Part |
| Skill | Agent Card 中声明的能力单元（与 ZhiWei 的 Skill 概念不同，此处指 A2A 协议的 skill 字段） |

### 2.2 角色定义

| 角色 | 说明 |
|------|------|
| A2A Server | 暴露 Agent Card 和消息处理端点，接收并执行任务 |
| A2A Client | 发现远程 Agent Card，发送消息，管理任务 |
| ZhiWei 主 Agent | 作为 A2A Client 调用远程 Agent；同时通过 A2A Server 暴露自身能力 |

### 2.3 Task 状态机

```
[*] → submitted → working → completed
                         → failed
                         → canceled
                → input_required → working
                → auth_required → working
                → rejected
```

---

## 3. 架构设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ZhiWei A2A 模块                           │
│                                                                     │
│  ┌──────────────────────────┐    ┌──────────────────────────┐      │
│  │     A2A Server 层         │    │     A2A Client 层         │      │
│  │                          │    │                          │      │
│  │  AgentCardController     │    │  A2aClientService        │      │
│  │  A2aMessageController    │    │    ├─ discoverAgent()    │      │
│  │  A2aTaskController       │    │    ├─ sendMessage()      │      │
│  │                          │    │    └─ getTask()          │      │
│  │  AgentCardGenerator      │    │                          │      │
│  │  A2aAgentExecutor        │    │  RemoteAgentRegistry     │      │
│  │  A2aTaskStore            │    │    ├─ 远程 Agent 注册     │      │
│  │                          │    │    └─ Agent Card 缓存     │      │
│  └──────────┬───────────────┘    └──────────┬───────────────┘      │
│             │                               │                       │
│  ┌──────────▼───────────────────────────────▼───────────────┐      │
│  │                    共享基础设施                             │      │
│  │                                                          │      │
│  │  A2aDataModel（AgentCard / Task / Message / Part 等）     │      │
│  │  A2aProperties（配置属性）                                 │      │
│  │  A2aAutoConfiguration（自动装配）                          │      │
│  └──────────────────────────────────────────────────────────┘      │
│             │                               │                       │
└─────────────┼───────────────────────────────┼───────────────────────┘
              │                               │
    ┌─────────▼─────────┐          ┌─────────▼─────────┐
    │  多 Agent 模块     │          │  Web / Gateway     │
    │  AgentRegistry     │          │  Spring MVC        │
    │  AgentExecutor     │          │  CORS / Auth       │
    │  AgentDefinition   │          │  SSE               │
    └───────────────────┘          └───────────────────┘
```

### 3.2 A2A Server 层

#### 3.2.1 AgentCardGenerator — Agent Card 生成器

从 AgentRegistry 中的 AgentDefinition 生成标准 A2A Agent Card JSON。

```java
public class AgentCardGenerator {
    /**
     * 生成 ZhiWei 主 Agent 的 Agent Card。
     * 将 AgentRegistry 中所有已注册 Agent 映射为 A2A AgentSkill。
     */
    public A2aAgentCard generateCard();
}
```

映射规则：
- `AgentDefinition.id` → `AgentSkill.id`
- `AgentDefinition.name` → `AgentSkill.name`
- `AgentDefinition.description` → `AgentSkill.description`
- ZhiWei 整体作为一个 A2A Agent 暴露，内部的多个 AgentDefinition 映射为 skills

#### 3.2.2 A2aAgentExecutor — A2A 请求执行器

接收 A2A 消息，路由到对应的内部 Agent 执行。

```java
public class A2aAgentExecutor {
    /**
     * 执行 A2A 消息请求。
     * 1. 解析消息中的 skill 指向（如果有）
     * 2. 路由到对应的 AgentExecutor 或主 AgentLoop
     * 3. 将执行结果转换为 A2A Task + Artifact
     */
    public A2aTask execute(A2aMessage message, @Nullable String skillId);
}
```

#### 3.2.3 A2aTaskStore — Task 状态存储

管理 A2A Task 的生命周期状态，支持查询和取消。

```java
public class A2aTaskStore {
    private final ConcurrentHashMap<String, A2aTask> tasks;

    public A2aTask create(A2aMessage message);
    public Optional<A2aTask> find(String taskId);
    public A2aTask updateStatus(String taskId, A2aTaskState newState);
    public boolean cancel(String taskId);
    public List<A2aTask> listByContextId(String contextId);
}
```

设计决策：Task 存储在内存中（ConcurrentHashMap），不持久化到 SQLite。理由：
1. A2A Task 是短期会话状态，不需要跨重启保留
2. ZhiWei 是单用户本地应用，并发量低
3. 避免增加 Flyway 迁移脚本复杂度

#### 3.2.4 REST 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/.well-known/agent.json` | GET | 返回 Agent Card（标准发现路径） |
| `/api/a2a/agent-card` | GET | 返回 Agent Card（备用路径） |
| `/api/a2a/message/send` | POST | 接收 A2A 消息（JSON-RPC 2.0 格式） |
| `/api/a2a/message/stream` | POST | 接收 A2A 消息并返回 SSE 流 |
| `/api/a2a/tasks/{id}` | GET | 查询 Task 状态 |
| `/api/a2a/tasks/{id}/cancel` | POST | 取消 Task |

### 3.3 A2A Client 层

#### 3.3.1 A2aClientService — 远程 Agent 调用服务

```java
public class A2aClientService {
    /**
     * 发现远程 Agent 的能力。
     * 从指定 URL 获取 Agent Card。
     */
    public A2aAgentCard discoverAgent(String agentUrl);

    /**
     * 向远程 Agent 发送消息。
     * 使用 HTTP+JSON 传输（REST 模式）。
     */
    public A2aTask sendMessage(String agentUrl, A2aMessage message);

    /**
     * 查询远程 Task 状态。
     */
    public Optional<A2aTask> getTask(String agentUrl, String taskId);

    /**
     * 取消远程 Task。
     */
    public boolean cancelTask(String agentUrl, String taskId);
}
```

传输协议选择：使用 HTTP+JSON/REST 传输（非 JSON-RPC 2.0）。理由：
1. Spring Boot 原生支持 REST，无需额外依赖
2. ZhiWei 是单用户本地应用，不需要 gRPC 的高性能
3. REST 调试友好，与现有 Web 模块风格一致
4. A2A Java SDK 已支持 REST 传输（`a2a-java-sdk-reference-rest`）

#### 3.3.2 RemoteAgentRegistry — 远程 Agent 注册表

```java
public class RemoteAgentRegistry {
    /**
     * 注册远程 Agent（通过 URL 发现）。
     * 缓存 Agent Card，定期刷新。
     */
    public void register(String agentUrl);

    /**
     * 注销远程 Agent。
     */
    public void unregister(String agentUrl);

    /**
     * 列出所有已注册的远程 Agent。
     */
    public List<A2aAgentCard> listAll();

    /**
     * 查找远程 Agent。
     */
    public Optional<A2aAgentCard> findByUrl(String agentUrl);
}
```

#### 3.3.3 RemoteAgentTool — 远程 Agent 工具桥接

将远程 A2A Agent 注册为 BuiltinTool，使主 AgentLoop 可以通过 Function Call 调用远程 Agent。

```java
public class RemoteAgentToolFactory {
    /**
     * 为远程 Agent 创建 BuiltinTool。
     * 工具 ID 格式：a2a_remote_{agentName}
     * 执行时调用 A2aClientService.sendMessage()。
     */
    public BuiltinTool createRemoteTool(A2aAgentCard remoteCard);
}
```

### 3.4 数据模型

采用自定义 record 实现 A2A 数据模型，不直接依赖 A2A Java SDK。理由：
1. A2A Java SDK（`io.github.a2asdk`）基于 Quarkus 参考实现，与 Spring Boot 集成需要额外适配
2. A2A 协议数据模型简单（约 15 个核心类型），自行实现成本低
3. 避免引入 Quarkus 相关传递依赖
4. 使用 Java 22 record + sealed interface，与 ZhiWei 编码风格一致

```java
// Agent Card
public record A2aAgentCard(
    String name,
    String description,
    String url,
    String version,
    @Nullable String protocolVersion,
    List<A2aAgentSkill> skills,
    A2aAgentCapabilities capabilities,
    List<String> defaultInputModes,
    List<String> defaultOutputModes,
    @Nullable Map<String, Object> securitySchemes
) {}

// Task
public record A2aTask(
    String id,
    String contextId,
    A2aTaskStatus status,
    @Nullable List<A2aMessage> history,
    @Nullable List<A2aArtifact> artifacts,
    @Nullable Map<String, Object> metadata
) {}

// Task 状态
public record A2aTaskStatus(
    A2aTaskState state,
    @Nullable A2aMessage message,
    @Nullable String timestamp
) {}

// Task 状态枚举
public enum A2aTaskState {
    SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED,
    CANCELED, FAILED, REJECTED, AUTH_REQUIRED
}

// Message
public record A2aMessage(
    String messageId,
    A2aRole role,
    List<A2aPart> parts,
    @Nullable String taskId,
    @Nullable String contextId,
    @Nullable Map<String, Object> metadata
) {}

// Part 密封接口
public sealed interface A2aPart permits
    A2aPart.Text, A2aPart.File, A2aPart.Data {

    record Text(String text, @Nullable Map<String, Object> metadata) implements A2aPart {}
    record File(A2aFileContent file, @Nullable Map<String, Object> metadata) implements A2aPart {}
    record Data(Map<String, Object> data, @Nullable Map<String, Object> metadata) implements A2aPart {}
}

// Artifact
public record A2aArtifact(
    String artifactId,
    List<A2aPart> parts,
    @Nullable String name,
    @Nullable String description
) {}
```

---

## 4. 关键设计决策

### 4.1 自定义数据模型 vs 依赖 A2A Java SDK

**决策**：自定义 record 实现。

| 方案 | 优势 | 劣势 |
|------|------|------|
| A2A Java SDK (`a2a-java`) | 官方实现，协议更新自动跟进 | 基于 Quarkus，Spring Boot 集成需适配；引入大量传递依赖 |
| A2A4J (`a2a4j`) | 社区 Spring Boot 实现 | 成熟度不足，API 不稳定 |
| Spring AI A2A (`spring-ai-a2a`) | Spring 生态原生集成 | 依赖 Spring AI 2.0 + Spring Boot 4.0，ZhiWei 使用 Spring AI 1.1.2 |
| 自定义 record | 零额外依赖；Java 22 record 风格一致；完全可控 | 需要手动跟进协议更新 |

A2A 协议数据模型约 15 个核心类型，使用 record + sealed interface 实现代码量约 200 行，维护成本可控。协议版本 0.2.5 已相对稳定，核心类型（AgentCard / Task / Message / Part）不太可能大幅变更。

### 4.2 ZhiWei 作为单一 A2A Agent vs 多 Agent 暴露

**决策**：ZhiWei 整体作为一个 A2A Agent 暴露，内部 Agent 映射为 A2A skills。

理由：
1. A2A 协议中一个 Agent Card 对应一个服务端点，ZhiWei 是单 JAR 部署
2. 外部调用者不需要了解 ZhiWei 内部的 Agent 拓扑
3. 内部 Agent（writer / life-coach / planner）作为 skills 暴露，外部可通过 skill ID 指定
4. 简化认证——一个端点一套认证，而非每个 Agent 独立认证

### 4.3 传输协议选择

**决策**：HTTP+JSON/REST（非 JSON-RPC 2.0）。

A2A 协议支持三种传输：JSON-RPC 2.0、gRPC、HTTP+JSON/REST。选择 REST 的理由：
1. 与 ZhiWei 现有 Web 模块（Spring MVC REST Controller）风格一致
2. 调试友好，curl / Postman 可直接测试
3. SSE 流式响应复用现有 SSE 基础设施
4. 单用户本地应用不需要 gRPC 的高吞吐

### 4.4 认证方案

**决策**：API Key 认证（`securitySchemes.apiKey`），复用 Gateway 模块的 Auth 中间件。

理由：
1. ZhiWei 是个人本地应用，OAuth 2.0 过于复杂
2. API Key 通过 `lifepilot.a2a.server.api-key` 配置，存储在环境变量中
3. 外部 Agent 调用时在 HTTP Header 中携带 API Key
4. 未来可扩展为 OAuth 2.0（当 ZhiWei 上云部署时）

### 4.5 Task 存储策略

**决策**：内存存储（ConcurrentHashMap），不持久化。

理由：
1. A2A Task 是短期会话状态（分钟级），不需要跨重启保留
2. 单用户场景并发量极低
3. 避免增加 Flyway 迁移脚本
4. 设置 TTL 自动清理过期 Task（默认 1 小时）

---

## 5. 与已有模块的集成点

| 集成模块 | 集成方式 | 说明 |
|---------|---------|------|
| 多 Agent（模块 21） | AgentRegistry 只读引用 | 生成 Agent Card 时读取已注册 Agent |
| 多 Agent（模块 21） | AgentExecutor 调用 | A2A Server 收到消息后委托 AgentExecutor 执行 |
| 多 Agent（模块 21） | DynamicToolRegistry 注册 | 远程 Agent 注册为 BuiltinTool |
| Web（模块 18） | Spring MVC Controller | A2A 端点作为 REST Controller 暴露 |
| Web（模块 18） | SSE 基础设施 | 流式响应复用 SseEmitter |
| Gateway（模块 13） | Auth 中间件 | API Key 验证 |
| Agent 引擎（模块 2） | AgentLoop | 主 Agent 通过远程 Agent 工具调用外部 Agent |
| LLM Router（模块 1） | 模型路由 | 远程 Agent 调用不经过本地 LLM |

---

## 6. 配置项

```yaml
lifepilot:
  a2a:
    enabled: true                                    # 是否启用 A2A 协议支持
    server:
      enabled: true                                  # 是否启用 A2A Server
      api-key: ${LIFEPILOT_A2A_API_KEY:}            # Server 端 API Key（空则不启用认证）
      agent-name: ZhiWei                          # Agent Card 中的名称
      agent-description: 个人生活助手                  # Agent Card 中的描述
      agent-version: 1.0.0                           # Agent Card 中的版本
      protocol-version: 0.2.5                        # A2A 协议版本
      streaming-enabled: true                        # 是否支持 SSE 流式响应
    client:
      enabled: true                                  # 是否启用 A2A Client
      remote-agents: []                              # 远程 Agent URL 列表
      connect-timeout-seconds: 10                    # 连接超时
      read-timeout-seconds: 60                       # 读取超时
      card-cache-ttl-minutes: 30                     # Agent Card 缓存 TTL
    task:
      ttl-minutes: 60                                # Task 内存存储 TTL
      max-history-length: 50                         # Task 历史消息最大长度
```

---

## 7. 调研参考

| 来源 | 核心洞察 | ZhiWei 采纳 |
|------|---------|---------------|
| [A2A Protocol Spec v0.2.5](https://github.com/google/A2A) | 开放协议，JSON-RPC 2.0 / gRPC / REST 三种传输，Agent Card 发现机制 | 采纳 REST 传输 + Agent Card 发现 |
| [A2A Java SDK](https://github.com/a2aproject/a2a-java) | 官方 Java SDK，Quarkus 参考实现，支持 JSON-RPC / gRPC / REST | 参考 API 设计，不直接依赖（避免 Quarkus 传递依赖） |
| [Spring AI A2A](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration) | Spring AI 社区项目，DefaultAgentExecutor 桥接 ChatClient | 参考集成模式，但不依赖（需 Spring AI 2.0 + Spring Boot 4.0） |
| [A2A4J](https://github.com/a2ap/a2a4j) | 社区 Java 实现，含 Spring Boot Starter | 参考数据模型设计 |
| [A2A Protocol Spec (Python)](https://a2aprotocol.ai/blog/a2a-protocol-specification-python) | 完整数据结构定义，Task 状态机，错误码 | 采纳数据模型和状态机设计 |
| [Shane Deconinck A2A Explainer](https://shanedeconinck.be/explainers/a2a) | Agent Card / Task / Auth 三大核心问题清晰解析 | 参考架构理解 |
| [ACP 合并入 A2A](https://dotsquarelab.com/resources/comparing-ai-agent-communication-protocols) | IBM ACP 已合并入 A2A，行业趋向统一 | 确认 A2A 是正确的协议选择 |
| [AWS Bedrock A2A 支持](https://aws.amazon.com/blogs/machine-learning/introducing-agent-to-agent-protocol-support-in-amazon-bedrock-agentcore-runtime/) | AWS 已支持 A2A，验证协议成熟度 | 确认协议生态可行性 |
| [A2A Java SDK 1.0.0.Alpha2](https://quarkus.io/blog/a2a-java-sdk-1-0-0-alpha2-released/) | Red Hat + Google 协作，SDK 趋向稳定 | 未来可考虑迁移到官方 SDK |

> 内容已重新组织表述以符合许可要求。参考来源均为 2025-2026 年发表的技术文章和开源项目。

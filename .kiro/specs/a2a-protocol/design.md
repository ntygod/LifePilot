# Design Document — A2A 协议支持

参考文档：
- 架构设计：#[[file:docs/architecture/a2a-protocol.md]]
- 特性设计：#[[file:docs/features/a2a-protocol.md]]
- 多 Agent 架构（依赖）：#[[file:docs/architecture/multi-agent-v2.md]]
- 需求文档：#[[file:.kiro/specs/a2a-protocol/requirements.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 集成检查规范：#[[file:.kiro/steering/integration-checklist.md]]

---

## Overview

A2A（Agent-to-Agent）协议支持模块（模块 22，Phase 6）为 LifePilot 提供跨系统 Agent 互操作能力。本模块实现 A2A Protocol v0.2.5 的 HTTP+JSON/REST 子集，使 LifePilot 同时作为 A2A Server（暴露自身 Agent 能力）和 A2A Client（调用外部 A2A Agent）。

核心设计决策：
- **自定义 record 数据模型**：不依赖 A2A Java SDK（`io.github.a2asdk`），避免 Quarkus 传递依赖，约 15 个核心类型用 Java 22 record + sealed interface 实现
- **HTTP+JSON/REST 传输**：与现有 Spring MVC 风格一致，复用 SseEmitter 实现流式响应
- **API Key 认证**：通过 `X-API-Key` Header 保护 `/api/a2a/**` 端点，`/.well-known/agent.json` 公开访问
- **内存 Task 存储**：ConcurrentHashMap + TTL 自动清理，不持久化到 SQLite
- **单一 Agent 暴露**：LifePilot 整体作为一个 A2A Agent，内部 Agent（writer / life-coach / planner）映射为 A2A skills

---

## Architecture

### 分层架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          A2A 模块 (com.lifepilot.a2a)                    │
│                                                                         │
│  ┌─────────────────────────────┐    ┌─────────────────────────────┐    │
│  │      Server 层 (.server)     │    │      Client 层 (.client)     │    │
│  │                             │    │                             │    │
│  │  AgentCardController        │    │  A2aClientService           │    │
│  │  A2aMessageController       │    │  RemoteAgentRegistry        │    │
│  │  A2aTaskController          │    │  RemoteAgentToolFactory     │    │
│  │  A2aApiKeyFilter            │    │                             │    │
│  │                             │    │                             │    │
│  │  AgentCardGenerator         │    │                             │    │
│  │  A2aAgentExecutor           │    │                             │    │
│  │  A2aTaskStore               │    │                             │    │
│  └──────────┬──────────────────┘    └──────────┬──────────────────┘    │
│             │                                   │                       │
│  ┌──────────▼───────────────────────────────────▼──────────────────┐   │
│  │                  共享层 (.model / .config)                       │   │
│  │                                                                 │   │
│  │  A2aAgentCard / A2aTask / A2aMessage / A2aPart (sealed)         │   │
│  │  A2aTaskState / A2aRole / A2aTaskStatus / A2aArtifact           │   │
│  │  A2aAgentSkill / A2aAgentCapabilities / A2aFileContent          │   │
│  │  A2aProperties / A2aAutoConfiguration                           │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└───────────────┬─────────────────────────────────┬─────────────────────┘
                │                                 │
      ┌─────────▼──────────┐            ┌─────────▼──────────┐
      │  多 Agent 模块 (21)  │            │  工具系统 (3)       │
      │  AgentRegistry      │            │  DynamicToolRegistry│
      │  AgentExecutor      │            │  BuiltinTool        │
      │  AgentDefinition    │            │  ToolContract       │
      └────────────────────┘            └────────────────────┘
```

### 包结构

```
com.lifepilot.a2a
├── model/                          # A2A 协议数据模型
│   ├── A2aAgentCard.java           # Agent Card record
│   ├── A2aAgentSkill.java          # Agent Skill record
│   ├── A2aAgentCapabilities.java   # Agent 能力声明 record
│   ├── A2aTask.java                # Task record
│   ├── A2aTaskStatus.java          # Task 状态 record
│   ├── A2aTaskState.java           # Task 状态枚举
│   ├── A2aMessage.java             # Message record
│   ├── A2aRole.java                # 角色枚举 (USER / AGENT)
│   ├── A2aPart.java                # Part sealed interface (Text / File / Data)
│   ├── A2aFileContent.java         # 文件内容 record
│   └── A2aArtifact.java            # Artifact record
├── server/                         # A2A Server 组件
│   ├── AgentCardGenerator.java     # Agent Card 生成器
│   ├── A2aAgentExecutor.java       # A2A 请求执行器
│   ├── A2aTaskStore.java           # Task 内存存储
│   ├── AgentCardController.java    # Agent Card REST 端点
│   ├── A2aMessageController.java   # 消息处理 REST 端点
│   ├── A2aTaskController.java      # Task 管理 REST 端点
│   └── A2aApiKeyFilter.java        # API Key 认证过滤器
├── client/                         # A2A Client 组件
│   ├── A2aClientService.java       # 远程 Agent 调用服务
│   ├── RemoteAgentRegistry.java    # 远程 Agent 注册表
│   └── RemoteAgentToolFactory.java # 远程 Agent 工具桥接
└── config/                         # 配置与自动装配
    ├── A2aProperties.java          # 配置属性
    └── A2aAutoConfiguration.java   # Spring Boot 自动装配
```


---

## Components and Interfaces

### Server 层组件

#### AgentCardGenerator — Agent Card 生成器

从 AgentRegistry 读取所有已注册 AgentDefinition，生成标准 A2A Agent Card。

```java
/**
 * A2A Agent Card 生成器。
 *
 * <p>从 AgentRegistry 读取已注册 Agent，映射为 A2aAgentSkill，
 * 结合 A2aProperties 中的配置生成完整的 A2aAgentCard。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class AgentCardGenerator {

    private final AgentRegistry agentRegistry;
    private final A2aProperties properties;

    /** 生成 LifePilot 主 Agent 的 Agent Card。 */
    public A2aAgentCard generateCard() {
        // 1. 从 AgentRegistry.listAll() 获取所有 AgentDefinition
        // 2. 映射：AgentDefinition.id → A2aAgentSkill.id
        //         AgentDefinition.name → A2aAgentSkill.name
        //         AgentDefinition.description → A2aAgentSkill.description
        // 3. 从 A2aProperties.server 读取 agentName / agentDescription / agentVersion / protocolVersion
        // 4. 构建 A2aAgentCapabilities（streaming = properties.server.streamingEnabled）
        // 5. defaultInputModes / defaultOutputModes = ["text"]
    }
}
```

映射规则：

| AgentDefinition 字段 | A2aAgentSkill 字段 | 说明 |
|---------------------|-------------------|------|
| `id` | `id` | 直接映射 |
| `name` | `name` | 直接映射 |
| `description` | `description` | 直接映射 |
| — | `inputModes` | 固定 `["text"]` |
| — | `outputModes` | 固定 `["text"]` |

#### A2aAgentExecutor — A2A 请求执行器

接收 A2A 消息，路由到内部 Agent 执行，管理 Task 生命周期。

```java
/**
 * A2A 请求执行器。
 *
 * <p>接收 A2aMessage，根据 skillId 路由到对应 AgentDefinition，
 * 通过 AgentExecutor 执行，管理 A2aTask 状态转换。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class A2aAgentExecutor {

    private final AgentRegistry agentRegistry;
    private final AgentExecutor agentExecutor;
    private final AgentLoop agentLoop;
    private final A2aTaskStore taskStore;

    /**
     * 同步执行 A2A 消息请求。
     *
     * <p>路由逻辑：
     * - 有 skillId → AgentRegistry.find(skillId) → AgentExecutor.execute()
     * - 无 skillId → AgentLoop.run()（主 Agent 处理）
     * </p>
     *
     * @param message A2A 消息
     * @param skillId 目标 Skill ID（可空）
     * @return 执行完成的 A2aTask
     */
    public A2aTask execute(A2aMessage message, @Nullable String skillId);

    /**
     * 流式执行 A2A 消息请求。
     *
     * <p>在 Virtual Thread 中异步执行，通过 Consumer 回调推送状态更新。</p>
     *
     * @param message  A2A 消息
     * @param skillId  目标 Skill ID（可空）
     * @param listener 状态更新回调
     */
    public void executeStreaming(A2aMessage message,
                                @Nullable String skillId,
                                Consumer<A2aTask> listener);
}
```

执行流程：

```
A2aMessage 到达
    │
    ├─ 检查 message.taskId 是否引用已有 Task
    │   ├─ 是 → taskStore.find(taskId) → 追加 history → 继续执行
    │   └─ 否 → taskStore.create(message) → 新建 Task（SUBMITTED）
    │
    ├─ taskStore.updateStatus(taskId, WORKING)
    │
    ├─ 路由执行
    │   ├─ skillId 非空 → agentRegistry.find(skillId)
    │   │   ├─ 找到 → agentExecutor.execute(definition, task, context, parentState)
    │   │   └─ 未找到 → taskStore.updateStatus(taskId, FAILED, "未知 Skill: " + skillId)
    │   └─ skillId 为空 → agentLoop.run(request)
    │
    ├─ 执行成功 → 封装 A2aArtifact → taskStore.updateStatus(taskId, COMPLETED)
    └─ 执行异常 → taskStore.updateStatus(taskId, FAILED, errorMessage)
```

#### A2aTaskStore — Task 内存存储

```java
/**
 * A2A Task 内存存储。
 *
 * <p>使用 ConcurrentHashMap 存储 A2aTask，支持 TTL 自动清理。
 * 不持久化到 SQLite（A2A Task 是短期会话状态）。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class A2aTaskStore {

    private final ConcurrentHashMap<String, A2aTask> tasks = new ConcurrentHashMap<>();
    private final A2aProperties properties;

    /** 创建新 Task（初始状态 SUBMITTED）。 */
    public A2aTask create(A2aMessage message);

    /** 按 ID 查找 Task。 */
    public Optional<A2aTask> find(String taskId);

    /** 按 contextId 列出所有关联 Task。 */
    public List<A2aTask> listByContextId(String contextId);

    /**
     * 更新 Task 状态。
     *
     * @param taskId   Task ID
     * @param newState 新状态
     * @param message  状态消息（可空）
     * @return 更新后的 Task
     */
    public A2aTask updateStatus(String taskId, A2aTaskState newState, @Nullable String message);

    /**
     * 向 Task 添加 Artifact。
     *
     * @param taskId   Task ID
     * @param artifact 产出物
     * @return 更新后的 Task
     */
    public A2aTask addArtifact(String taskId, A2aArtifact artifact);

    /**
     * 向 Task 追加 history 消息。
     *
     * <p>超过 maxHistoryLength 时移除最早的消息。</p>
     */
    public A2aTask appendHistory(String taskId, A2aMessage message);

    /**
     * 取消 Task。
     *
     * <p>仅 SUBMITTED / WORKING 状态可取消，终态 Task 返回 false。</p>
     */
    public boolean cancel(String taskId);

    /** 清理过期 Task（由定时任务调用）。 */
    public int cleanupExpired();
}
```

Task 状态机约束：

| 当前状态 | 允许转换到 |
|---------|-----------|
| SUBMITTED | WORKING, CANCELED, REJECTED |
| WORKING | COMPLETED, FAILED, CANCELED, INPUT_REQUIRED |
| INPUT_REQUIRED | WORKING, CANCELED |
| COMPLETED | （终态） |
| FAILED | （终态） |
| CANCELED | （终态） |
| REJECTED | （终态） |

#### REST Controllers

```java
/**
 * Agent Card REST 端点。
 *
 * @author zsg
 * @since 2026-02-28
 */
@RestController
public class AgentCardController {

    /** GET /.well-known/agent.json — 标准发现路径（无需认证）。 */
    @GetMapping(value = "/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public A2aAgentCard getAgentCard();

    /** GET /api/a2a/agent-card — 备用路径。 */
    @GetMapping(value = "/api/a2a/agent-card", produces = MediaType.APPLICATION_JSON_VALUE)
    public A2aAgentCard getAgentCardAlternate();
}

/**
 * A2A 消息处理 REST 端点。
 *
 * @author zsg
 * @since 2026-02-28
 */
@RestController
@RequestMapping("/api/a2a")
public class A2aMessageController {

    /** POST /api/a2a/message/send — 同步消息处理。 */
    @PostMapping(value = "/message/send", produces = MediaType.APPLICATION_JSON_VALUE)
    public A2aTask sendMessage(@RequestBody A2aMessage message,
                               @RequestParam(required = false) String skillId);

    /** POST /api/a2a/message/stream — SSE 流式消息处理。 */
    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@RequestBody A2aMessage message,
                                    @RequestParam(required = false) String skillId);
}

/**
 * A2A Task 管理 REST 端点。
 *
 * @author zsg
 * @since 2026-02-28
 */
@RestController
@RequestMapping("/api/a2a/tasks")
public class A2aTaskController {

    /** GET /api/a2a/tasks/{id} — 查询 Task 状态。 */
    @GetMapping("/{id}")
    public ResponseEntity<A2aTask> getTask(@PathVariable String id);

    /** POST /api/a2a/tasks/{id}/cancel — 取消 Task。 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<A2aTask> cancelTask(@PathVariable String id);
}
```

#### A2aApiKeyFilter — API Key 认证过滤器

```java
/**
 * A2A API Key 认证过滤器。
 *
 * <p>拦截 /api/a2a/** 路径的请求，校验 X-API-Key Header。
 * /.well-known/agent.json 不拦截（公开发现端点）。
 * api-key 配置为空时不启用认证。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class A2aApiKeyFilter extends OncePerRequestFilter {

    private final A2aProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // /.well-known/agent.json 不拦截
        // api-key 为空时不拦截
    }
}
```

### Client 层组件

#### A2aClientService — 远程 Agent 调用服务

```java
/**
 * A2A Client 远程调用服务。
 *
 * <p>使用 Spring RestClient 进行 HTTP 调用，支持 Agent 发现、
 * 消息发送、Task 查询和取消。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class A2aClientService {

    private final RestClient restClient;
    private final A2aProperties properties;

    /**
     * 发现远程 Agent 能力。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @return Agent Card（获取失败返回空 Optional）
     */
    public Optional<A2aAgentCard> discoverAgent(String agentUrl);

    /**
     * 向远程 Agent 发送消息。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @param message  A2A 消息
     * @return A2aTask（调用失败返回包含 FAILED 状态的 Task）
     */
    public A2aTask sendMessage(String agentUrl, A2aMessage message);

    /**
     * 查询远程 Task 状态。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @param taskId   Task ID
     * @return Task（查询失败返回空 Optional）
     */
    public Optional<A2aTask> getTask(String agentUrl, String taskId);

    /**
     * 取消远程 Task。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @param taskId   Task ID
     * @return 是否取消成功
     */
    public boolean cancelTask(String agentUrl, String taskId);
}
```

RestClient 配置：
- `connectTimeout` = `A2aProperties.client.connectTimeoutSeconds` × 1000 ms
- `readTimeout` = `A2aProperties.client.readTimeoutSeconds` × 1000 ms
- 远程 Agent Card 获取路径：`{agentUrl}/.well-known/agent.json`
- 远程消息发送路径：`{agentUrl}/api/a2a/message/send`
- 远程 Task 查询路径：`{agentUrl}/api/a2a/tasks/{taskId}`
- 远程 Task 取消路径：`{agentUrl}/api/a2a/tasks/{taskId}/cancel`

#### RemoteAgentRegistry — 远程 Agent 注册表

```java
/**
 * 远程 A2A Agent 注册表。
 *
 * <p>管理已配置的远程 Agent URL 列表，缓存 Agent Card，
 * 支持 TTL 过期重新获取。应用启动时自动发现配置的远程 Agent。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class RemoteAgentRegistry {

    /** 远程 Agent 缓存条目。 */
    private record CacheEntry(A2aAgentCard card, Instant fetchedAt) {}

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final A2aClientService clientService;
    private final A2aProperties properties;

    /** 注册远程 Agent（自动获取并缓存 Agent Card）。 */
    public boolean register(String agentUrl);

    /** 注销远程 Agent。 */
    public boolean unregister(String agentUrl);

    /** 列出所有已注册远程 Agent 的 Agent Card。 */
    public List<A2aAgentCard> listAll();

    /** 按 URL 查找远程 Agent。 */
    public Optional<A2aAgentCard> findByUrl(String agentUrl);

    /** 启动时自动发现配置的远程 Agent。 */
    public void discoverConfiguredAgents();

    /** 检查缓存是否过期，过期则重新获取。 */
    private Optional<A2aAgentCard> getOrRefresh(String agentUrl);
}
```

#### RemoteAgentToolFactory — 远程 Agent 工具桥接

```java
/**
 * 远程 A2A Agent 工具桥接工厂。
 *
 * <p>为每个已注册的远程 Agent 创建 BuiltinTool 实例，
 * 通过 DynamicToolRegistry 注册/注销。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class RemoteAgentToolFactory {

    private static final String TOOL_ID_PREFIX = "a2a_remote_";

    private final A2aClientService clientService;
    private final DynamicToolRegistry toolRegistry;

    /**
     * 为远程 Agent 创建并注册 BuiltinTool。
     *
     * <p>工具 ID 格式：a2a_remote_{agentName}（agentName 转 snake_case）。
     * inputSchema：task（必填 String）+ context（选填 String）。
     * 执行时调用 A2aClientService.sendMessage()。</p>
     */
    public void registerRemoteTool(String agentUrl, A2aAgentCard card);

    /** 注销远程 Agent 对应的 BuiltinTool。 */
    public void unregisterRemoteTool(String agentName);

    /** 将 Agent Card name 转为 snake_case 工具 ID。 */
    static String toToolId(String agentName);
}
```

BuiltinTool 构建示例：

```java
BuiltinTool.builder()
    .id("a2a_remote_data_analyst")
    .name("远程 Agent: Data Analyst")
    .description(card.description())
    .inputSchema(JsonSchema.of(Map.of(
        "type", "object",
        "properties", Map.of(
            "task", Map.of("type", "string", "description", "委托任务描述"),
            "context", Map.of("type", "string", "description", "可选的额外上下文信息")
        ),
        "required", List.of("task")
    )))
    .riskLevel(RiskLevel.MEDIUM)  // 远程调用风险为 MEDIUM
    .idempotent(false)
    .executor(input -> {
        String task = (String) input.parameters().get("task");
        String context = (String) input.parameters().get("context");
        // 构建 A2aMessage → clientService.sendMessage() → 提取 Artifact 文本
    })
    .build();
```


---

## Data Models

### 核心数据模型（com.lifepilot.a2a.model）

所有数据模型使用 Java 22 record 定义，Jackson 注解控制 JSON 序列化。

#### A2aAgentCard — Agent 能力声明

```java
/**
 * A2A Agent Card — Agent 能力声明文档。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aAgentCard(
    String name,
    String description,
    String url,
    String version,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String protocolVersion,
    List<A2aAgentSkill> skills,
    A2aAgentCapabilities capabilities,
    List<String> defaultInputModes,
    List<String> defaultOutputModes,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> securitySchemes
) {
    public A2aAgentCard {
        skills = skills == null ? List.of() : List.copyOf(skills);
        defaultInputModes = defaultInputModes == null ? List.of("text") : List.copyOf(defaultInputModes);
        defaultOutputModes = defaultOutputModes == null ? List.of("text") : List.copyOf(defaultOutputModes);
    }
}
```

#### A2aAgentSkill — Agent 技能声明

```java
/**
 * A2A Agent Skill — Agent Card 中的技能单元。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aAgentSkill(
    String id,
    String name,
    String description,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) List<String> inputModes,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) List<String> outputModes
) {}
```

#### A2aAgentCapabilities — Agent 能力声明

```java
/**
 * A2A Agent 能力声明。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aAgentCapabilities(
    boolean streaming
) {}
```

#### A2aTask — 工作单元

```java
/**
 * A2A Task — 工作单元，具有生命周期状态管理。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aTask(
    String id,
    String contextId,
    A2aTaskStatus status,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) List<A2aMessage> history,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) List<A2aArtifact> artifacts,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
) {}
```

#### A2aTaskStatus — Task 状态

```java
/**
 * A2A Task 状态。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aTaskStatus(
    A2aTaskState state,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) A2aMessage message,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String timestamp
) {}
```

#### A2aTaskState — Task 状态枚举

```java
/**
 * A2A Task 状态枚举。
 *
 * <p>JSON 序列化为小写字符串（如 "submitted"、"working"）。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum A2aTaskState {
    @JsonProperty("submitted") SUBMITTED,
    @JsonProperty("working") WORKING,
    @JsonProperty("input_required") INPUT_REQUIRED,
    @JsonProperty("completed") COMPLETED,
    @JsonProperty("canceled") CANCELED,
    @JsonProperty("failed") FAILED,
    @JsonProperty("rejected") REJECTED,
    @JsonProperty("auth_required") AUTH_REQUIRED;

    /** 是否为终态（不可再转换）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
    }
}
```

#### A2aMessage — 消息

```java
/**
 * A2A Message — Agent 间交换信息的基本单元。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aMessage(
    String messageId,
    A2aRole role,
    List<A2aPart> parts,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String taskId,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String contextId,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
) {
    public A2aMessage {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
```

#### A2aRole — 角色枚举

```java
/**
 * A2A 消息角色枚举。
 *
 * @author zsg
 * @since 2026-02-28
 */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum A2aRole {
    @JsonProperty("user") USER,
    @JsonProperty("agent") AGENT
}
```

#### A2aPart — 消息内容片段（sealed interface）

```java
/**
 * A2A Part — 消息内容片段。
 *
 * <p>使用 sealed interface + @JsonTypeInfo 实现多态 JSON 序列化。
 * type 字段区分 "text" / "file" / "data" 三种子类型。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = A2aPart.Text.class, name = "text"),
    @JsonSubTypes.Type(value = A2aPart.File.class, name = "file"),
    @JsonSubTypes.Type(value = A2aPart.Data.class, name = "data")
})
public sealed interface A2aPart permits A2aPart.Text, A2aPart.File, A2aPart.Data {

    /** 文本内容片段。 */
    record Text(
        String text,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
    ) implements A2aPart {}

    /** 文件内容片段。 */
    record File(
        A2aFileContent file,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
    ) implements A2aPart {}

    /** 结构化数据片段。 */
    record Data(
        Map<String, Object> data,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
    ) implements A2aPart {}
}
```

#### A2aFileContent — 文件内容

```java
/**
 * A2A 文件内容。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aFileContent(
    @Nullable String name,
    @Nullable String mimeType,
    @Nullable String bytes,   // base64 编码
    @Nullable String uri
) {}
```

#### A2aArtifact — Task 产出物

```java
/**
 * A2A Artifact — Task 产出物。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aArtifact(
    String artifactId,
    List<A2aPart> parts,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String name,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String description
) {
    public A2aArtifact {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
```

### Jackson 序列化配置要点

1. **A2aPart 多态序列化**：使用 `@JsonTypeInfo(property = "type")` + `@JsonSubTypes`，JSON 中通过 `"type": "text"` / `"type": "file"` / `"type": "data"` 区分子类型
2. **枚举小写序列化**：`A2aTaskState` 和 `A2aRole` 使用 `@JsonProperty` 注解指定小写值
3. **可空字段**：使用 `@JsonInclude(JsonInclude.Include.NON_NULL)` 避免序列化 null 字段
4. **防御性拷贝**：所有集合字段在紧凑构造器中使用 `List.copyOf()` / `Map.copyOf()`

### 配置属性

```java
/**
 * A2A 模块配置属性。
 *
 * @author zsg
 * @since 2026-02-28
 */
@ConfigurationProperties(prefix = "lifepilot.a2a")
public class A2aProperties {

    private boolean enabled = true;
    private Server server = new Server();
    private Client client = new Client();
    private Task task = new Task();

    // getter / setter 省略

    public static class Server {
        private boolean enabled = true;
        private String apiKey = "";
        private String agentName = "LifePilot";
        private String agentDescription = "个人生活助手";
        private String agentVersion = "1.0.0";
        private String protocolVersion = "0.2.5";
        private boolean streamingEnabled = true;
        // getter / setter 省略
    }

    public static class Client {
        private boolean enabled = true;
        private List<String> remoteAgents = List.of();
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 60;
        private int cardCacheTtlMinutes = 30;
        // getter / setter 省略
    }

    public static class Task {
        private int ttlMinutes = 60;
        private int maxHistoryLength = 50;
        // getter / setter 省略
    }
}
```

对应 `application.yml` 配置：

```yaml
lifepilot:
  a2a:
    enabled: true
    server:
      enabled: true
      api-key: ${LIFEPILOT_A2A_API_KEY:}
      agent-name: LifePilot
      agent-description: 个人生活助手
      agent-version: 1.0.0
      protocol-version: 0.2.5
      streaming-enabled: true
    client:
      enabled: true
      remote-agents: []
      connect-timeout-seconds: 10
      read-timeout-seconds: 60
      card-cache-ttl-minutes: 30
    task:
      ttl-minutes: 60
      max-history-length: 50
```

### 自动装配

```java
/**
 * A2A 模块 Spring Boot 自动装配。
 *
 * <p>根据配置条件注册 Server / Client 相关 Bean。
 * 依赖 MultiAgentAutoConfiguration 提供的 AgentRegistry 和 AgentExecutor。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@AutoConfiguration(after = MultiAgentAutoConfiguration.class)
@EnableConfigurationProperties(A2aProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.a2a", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class A2aAutoConfiguration {

    // ── Server Bean（lifepilot.a2a.server.enabled=true）──

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AgentCardGenerator agentCardGenerator(AgentRegistry agentRegistry,
                                                  A2aProperties properties);

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aTaskStore a2aTaskStore(A2aProperties properties);

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aAgentExecutor a2aAgentExecutor(AgentRegistry agentRegistry,
                                              AgentExecutor agentExecutor,
                                              AgentLoop agentLoop,
                                              A2aTaskStore taskStore);

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AgentCardController agentCardController(AgentCardGenerator generator);

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aMessageController a2aMessageController(A2aAgentExecutor executor,
                                                      A2aProperties properties);

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aTaskController a2aTaskController(A2aTaskStore taskStore);

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aApiKeyFilter a2aApiKeyFilter(A2aProperties properties);

    // ── Client Bean（lifepilot.a2a.client.enabled=true）──

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.client", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public A2aClientService a2aClientService(A2aProperties properties);

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.client", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public RemoteAgentRegistry remoteAgentRegistry(A2aClientService clientService,
                                                    A2aProperties properties);

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.a2a.client", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public RemoteAgentToolFactory remoteAgentToolFactory(A2aClientService clientService,
                                                          DynamicToolRegistry toolRegistry,
                                                          RemoteAgentRegistry remoteAgentRegistry);

    // ── 启动后初始化 ──

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        // 1. 如果 client.enabled，调用 RemoteAgentRegistry.discoverConfiguredAgents()
        // 2. 如果 server.enabled，启动 A2aTaskStore TTL 清理定时任务
    }
}
```

### 依赖接口验证

| 接口 | 源码位置 | 验证状态 |
|------|---------|---------|
| `AgentRegistry.register(AgentDefinition)` | `com.lifepilot.multiagent.registry.AgentRegistry` | ✅ 已核对：返回 boolean |
| `AgentRegistry.find(String)` | `com.lifepilot.multiagent.registry.AgentRegistry` | ✅ 已核对：返回 Optional<AgentDefinition> |
| `AgentRegistry.listAll()` | `com.lifepilot.multiagent.registry.AgentRegistry` | ✅ 已核对：返回 List<AgentDefinition> |
| `AgentExecutor.execute(AgentDefinition, String, String, AgentState)` | `com.lifepilot.multiagent.execution.AgentExecutor` | ✅ 已核对：返回 Action.SubAgentResult |
| `AgentDefinition(id, name, description, systemPrompt, allowedTools, canDelegate, budget, preferredProvider, source, metadata)` | `com.lifepilot.multiagent.model.AgentDefinition` | ✅ 已核对：10 个字段 |
| `DynamicToolRegistry.registerBuiltinTool(ToolContract)` | `com.lifepilot.tool.registry.DynamicToolRegistry` | ✅ 已核对：参数为 ToolContract |
| `DynamicToolRegistry.unregisterBuiltinTool(String)` | `com.lifepilot.tool.registry.DynamicToolRegistry` | ✅ 已核对：返回 boolean |
| `BuiltinTool.builder()` | `com.lifepilot.tool.BuiltinTool` | ✅ 已核对：Builder 模式，含 id/name/description/inputSchema/executor 等字段 |
| `AgentLoop.run(AgentRequest)` | `com.lifepilot.agent.AgentLoop` | ✅ 已核对：返回 AgentResponse |
| `MultiAgentAutoConfiguration` | `com.lifepilot.multiagent.config.MultiAgentAutoConfiguration` | ✅ 已核对：注册 AgentRegistry / AgentExecutor Bean |
| `JsonSchema.of(Map<String, Object>)` | `com.lifepilot.tool.schema.JsonSchema` | ✅ 已核对：静态工厂方法 |


---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: A2aAgentCard 序列化 round-trip

*For any* valid `A2aAgentCard` 实例（包含任意数量的 skills、任意 capabilities 配置、任意可空字段组合），序列化为 JSON 后再反序列化应产生与原始实例相等的对象。

**Validates: Requirements 2.4**

### Property 2: A2aTask 序列化 round-trip

*For any* valid `A2aTask` 实例（包含任意 A2aTaskState、任意 history 消息列表、任意 artifacts 列表、任意 metadata），序列化为 JSON 后再反序列化应产生与原始实例相等的对象。

**Validates: Requirements 2.5**

### Property 3: A2aMessage 序列化 round-trip

*For any* valid `A2aMessage` 实例（包含任意 A2aRole、任意 A2aPart 子类型组合——Text / File / Data、任意可空字段），序列化为 JSON 后再反序列化应产生与原始实例相等的对象。

**Validates: Requirements 2.6**

### Property 4: A2aPart JSON type 鉴别器

*For any* `A2aPart` 实例（Text / File / Data），序列化为 JSON 后，JSON 对象中应包含 `"type"` 字段，且值分别为 `"text"` / `"file"` / `"data"`。

**Validates: Requirements 2.2**

### Property 5: A2aTaskState 小写序列化

*For any* `A2aTaskState` 枚举值，序列化为 JSON 字符串后应为全小写（或 snake_case），且与协议规范定义的字符串一致（submitted / working / input_required / completed / canceled / failed / rejected / auth_required）。

**Validates: Requirements 2.3**

### Property 6: AgentCardGenerator skill 映射保持身份

*For any* 一组已注册的 `AgentDefinition`（任意数量、任意 id / name / description），`AgentCardGenerator.generateCard()` 生成的 `A2aAgentCard` 应包含与每个 `AgentDefinition` 一一对应的 `A2aAgentSkill`，且 skill.id == definition.id、skill.name == definition.name、skill.description == definition.description。

**Validates: Requirements 3.1, 3.2**

### Property 7: AgentCardGenerator 反映注册表当前状态

*For any* AgentRegistry 状态变更序列（注册 / 注销操作），每次调用 `generateCard()` 后生成的 Agent Card 的 skills 列表应与 `AgentRegistry.listAll()` 的当前快照一致（数量相等、ID 集合相同）。

**Validates: Requirements 3.5**

### Property 8: Task 创建初始状态为 SUBMITTED

*For any* `A2aMessage`，通过 `A2aTaskStore.create(message)` 创建的 `A2aTask` 的 `status.state` 应为 `SUBMITTED`，且 `id` 非空、`contextId` 与消息的 contextId 一致。

**Validates: Requirements 5.3**

### Property 9: TaskStore 存取 round-trip

*For any* 通过 `A2aTaskStore.create()` 创建的 Task，`A2aTaskStore.find(task.id)` 应返回非空 Optional，且返回的 Task 与创建时的 Task 相等。

**Validates: Requirements 7.2**

### Property 10: TaskStore contextId 列表完整性

*For any* 一组共享相同 `contextId` 的 Task（通过 create 创建），`A2aTaskStore.listByContextId(contextId)` 返回的列表应包含所有这些 Task，且不包含其他 contextId 的 Task。

**Validates: Requirements 7.3**

### Property 11: TaskStore 取消仅对非终态 Task 生效

*For any* `A2aTask`，`A2aTaskStore.cancel(taskId)` 返回 `true` 当且仅当 Task 当前状态为 SUBMITTED 或 WORKING。对于终态 Task（COMPLETED / FAILED / CANCELED / REJECTED），cancel 应返回 `false` 且 Task 状态不变。

**Validates: Requirements 7.4, 7.5**

### Property 12: TaskStore history 长度限制

*For any* Task 和任意数量的 `appendHistory` 调用，Task 的 history 列表长度不应超过 `A2aProperties.task.maxHistoryLength`。当超出限制时，最早的消息应被移除。

**Validates: Requirements 7.7**

### Property 13: TaskStore TTL 清理

*For any* 一组 Task，调用 `cleanupExpired()` 后，所有创建时间超过 `A2aProperties.task.ttlMinutes` 的 Task 应被移除，未过期的 Task 应保留。

**Validates: Requirements 7.6**

### Property 14: RemoteAgentRegistry 注册/查找/注销 round-trip

*For any* 远程 Agent URL 和对应的 Agent Card，注册后 `findByUrl(url)` 应返回该 Card；注销后 `findByUrl(url)` 应返回空 Optional。

**Validates: Requirements 10.1, 10.2, 10.4**

### Property 15: RemoteAgentToolFactory 工具 ID 命名规范

*For any* Agent Card name（包含空格、大写字母、特殊字符），`RemoteAgentToolFactory.toToolId(name)` 生成的工具 ID 应以 `a2a_remote_` 为前缀，后缀为 name 的 snake_case 转换，且整个 ID 仅包含小写字母、数字和下划线。

**Validates: Requirements 11.1**

### Property 16: API Key 过滤器拒绝无效请求

*For any* 对 `/api/a2a/**` 路径的 HTTP 请求，当 `A2aProperties.server.apiKey` 配置为非空值时，请求未携带 `X-API-Key` Header 或 Header 值与配置不匹配，应返回 HTTP 401 Unauthorized。

**Validates: Requirements 12.1, 12.2**


---

## Error Handling

### Server 端错误处理

| 错误场景 | 处理方式 | HTTP 状态码 |
|---------|---------|------------|
| 请求 JSON 解析失败 | 返回错误响应，包含解析错误描述 | 400 Bad Request |
| A2aMessage 缺少必填字段（messageId / role / parts） | 返回错误响应，包含字段校验错误 | 400 Bad Request |
| skillId 指向不存在的 Agent | Task 状态设为 FAILED，返回 Task | 200（Task 内含 FAILED 状态） |
| Agent 执行超时 | Task 状态设为 FAILED，message 包含超时描述 | 200（Task 内含 FAILED 状态） |
| Agent 执行异常 | Task 状态设为 FAILED，message 包含异常描述 | 200（Task 内含 FAILED 状态） |
| Task ID 不存在 | 返回 404 | 404 Not Found |
| 取消终态 Task | 返回 409 | 409 Conflict |
| API Key 无效 | 返回 401 | 401 Unauthorized |
| streaming-enabled=false 时请求 stream 端点 | 返回 405 | 405 Method Not Allowed |

### Client 端错误处理

| 错误场景 | 处理方式 |
|---------|---------|
| 远程 Agent 网络不可达 | 记录 WARN 日志，返回空 Optional / FAILED Task |
| 远程 Agent 响应超时 | 记录 WARN 日志，返回空 Optional / FAILED Task |
| 远程 Agent 返回非 2xx | 记录 WARN 日志，返回空 Optional / FAILED Task |
| 远程 Agent Card JSON 解析失败 | 记录 WARN 日志，返回空 Optional |
| 启动时远程 Agent 发现失败 | 记录 WARN 日志，跳过该 Agent，不阻塞启动 |

### 错误响应格式

Server 端错误统一使用以下 JSON 格式：

```json
{
  "error": {
    "code": 400,
    "message": "A2aMessage 缺少必填字段: messageId"
  }
}
```

---

## Testing Strategy

### 测试框架

- **单元测试**：JUnit 5
- **属性测试**：jqwik（Java 属性测试库）
- **集成测试**：`@SpringBootTest` + MockMvc + WireMock（模拟远程 Agent）
- **测试方法名**：中文（遵循编码规范）

### 属性测试（Property-Based Testing）

每个 Correctness Property 对应一个 jqwik 属性测试，最少 100 次迭代。

| Property | 测试类 | 生成器 |
|----------|--------|--------|
| P1: AgentCard round-trip | `A2aSerializationPropertyTest` | 随机 A2aAgentCard（随机 skills 列表、随机 capabilities） |
| P2: Task round-trip | `A2aSerializationPropertyTest` | 随机 A2aTask（随机状态、随机 history、随机 artifacts） |
| P3: Message round-trip | `A2aSerializationPropertyTest` | 随机 A2aMessage（随机 role、随机 A2aPart 子类型组合） |
| P4: Part type 鉴别器 | `A2aSerializationPropertyTest` | 随机 A2aPart（Text / File / Data） |
| P5: TaskState 小写 | `A2aSerializationPropertyTest` | 所有 A2aTaskState 枚举值 |
| P6: Skill 映射 | `AgentCardGeneratorPropertyTest` | 随机 AgentDefinition 列表 |
| P7: 注册表状态反映 | `AgentCardGeneratorPropertyTest` | 随机注册/注销操作序列 |
| P8: Task 创建状态 | `A2aTaskStorePropertyTest` | 随机 A2aMessage |
| P9: TaskStore 存取 | `A2aTaskStorePropertyTest` | 随机 A2aMessage |
| P10: contextId 列表 | `A2aTaskStorePropertyTest` | 随机 contextId + 随机 Task 数量 |
| P11: 取消非终态 | `A2aTaskStorePropertyTest` | 随机 A2aTaskState |
| P12: history 限制 | `A2aTaskStorePropertyTest` | 随机消息序列（长度 > maxHistoryLength） |
| P13: TTL 清理 | `A2aTaskStorePropertyTest` | 随机 Task + 随机创建时间 |
| P14: Registry round-trip | `RemoteAgentRegistryPropertyTest` | 随机 URL + 随机 AgentCard |
| P15: 工具 ID 命名 | `RemoteAgentToolFactoryPropertyTest` | 随机 Agent name（含空格、大写、特殊字符） |
| P16: API Key 过滤 | `A2aApiKeyFilterPropertyTest` | 随机 API Key + 随机请求路径 |

属性测试标签格式：
```java
// Feature: a2a-protocol, Property 1: A2aAgentCard 序列化 round-trip
@Property(tries = 100)
void agentCard_序列化反序列化_round_trip(@ForAll A2aAgentCard card) { ... }
```

### 单元测试

| 测试类 | 覆盖范围 |
|--------|---------|
| `A2aTaskStateTest` | 枚举值完整性（1.3）、isTerminal() 判断 |
| `A2aAgentCardTest` | 字段完整性（1.4）、防御性拷贝 |
| `AgentCardGeneratorTest` | 配置字段填充（3.3）、streaming 能力声明（3.4） |
| `A2aAgentExecutorTest` | skillId 路由（5.1, 5.2）、成功执行流（5.4+5.5）、失败执行流（5.6）、history 追加（5.7） |
| `A2aApiKeyFilterTest` | well-known 路径免认证（12.3）、空 api-key 不启用认证（12.4） |
| `A2aClientServiceTest` | 发现失败返回空 Optional（8.3）、调用失败返回错误结果（9.4） |
| `RemoteAgentToolFactoryTest` | inputSchema 结构（11.3）、工具执行调用 sendMessage（11.4） |

### 集成测试

| 测试类 | 覆盖范围 |
|--------|---------|
| `A2aServer_集成测试` | REST 端点可达性（4.1-4.6）、端到端消息处理、SSE 流式响应（6.1-6.3）、streaming-disabled 返回 405（6.4） |
| `A2aAutoConfiguration_集成测试` | server.enabled=false 不注册端点（4.7）、client.enabled=false 不注册 Client Bean（8.5）、enabled=false 不注册任何 Bean（14.6） |
| `A2aClient_WireMock_集成测试` | 远程 Agent 发现（8.1）、消息发送（9.1）、Task 查询（9.2）、Task 取消（9.3）、超时配置（8.2） |
| `RemoteAgentRegistry_集成测试` | 启动自动发现（10.6）、发现失败跳过（10.7）、TTL 缓存刷新（10.5） |
| `RemoteAgentToolFactory_集成测试` | 注册时自动创建工具（11.6）、注销时自动删除工具（11.7） |
| `A2aProperties_集成测试` | 配置绑定（13.2-13.5）、默认值验证 |

### 测试优先级

1. **P0（必须）**：序列化 round-trip（P1-P3）、TaskStore 核心操作（P8-P11）、API Key 过滤（P16）
2. **P1（重要）**：AgentCardGenerator 映射（P6-P7）、TaskStore 边界（P12-P13）、工具 ID 命名（P15）
3. **P2（补充）**：序列化格式细节（P4-P5）、RemoteAgentRegistry round-trip（P14）

# 多 Agent 协作架构设计（v2 — 四层能力模型重构）

> **模块编号**：21（Phase 6）
> **依赖模块**：Agent 引擎（模块 2）、工具系统（模块 3）、Skill 系统（模块 10）、LLM Router（模块 1）
> **最后更新**：2026-02-27
> **版本说明**：本文档是 `multi-agent.md` 的重构版本，基于 Skill/Agent 职责重叠问题的深度分析，
> 引入四层能力模型（Tool → Skill → Agent → Orchestrator），重新定义 Skill 和 Agent 的边界。

---

## 1. 问题诊断：Skill 与 Agent 的职责重叠

### 1.1 现状分析

当前 LifePilot 的 Skill 系统（模块 10）存在一个根本性的架构问题：**Skill 同时承担了两个不同抽象层次的职责**。

**L1 确定性工作流**：`SkillAction` sealed interface 定义了四种确定性动作（HttpAction / ShellAction / ChainAction / TemplateAction），这些动作不经过 LLM 推理，是纯粹的工作流编排。

**L2 自主推理实体**：`SubAgentFactory.activate()` 直接调用 `AgentLoop.run()`，创建拥有独立 System Prompt、独立 Budget、独立上下文的 SubAgent 实例。这本质上是一个完整的 Agent，而非 Skill。

关键证据：`SubAgentFactory.activate()` 的执行路径**完全绕过了 `SkillAction`**。它不走 HttpAction / ShellAction / ChainAction / TemplateAction 中的任何一条路径，而是直接调用 `agentLoop.run()`。这证明 SubAgent 激活模式从一开始就不属于 Skill 的动作体系。

```
当前架构的混淆：

SkillDefinition
├── SkillAction（L1 确定性工作流）
│   ├── HttpAction    → 调用 HTTP API
│   ├── ShellAction   → 执行 Shell 命令
│   ├── ChainAction   → 串联多个 Skill
│   └── TemplateAction → 模板渲染
│
└── SubAgentFactory.activate()（L2 自主推理）
    └── agentLoop.run()  ← 完全绕过 SkillAction！
```

### 1.2 行业验证：Microsoft Semantic Kernel 的教训

Microsoft 在 Semantic Kernel v1.0 中将 "Skills" 重命名为 "Plugins"，原因正是 Skills 和 Plugins 本质上是同一个抽象，命名混淆导致了开发者困惑。这个决策的核心洞察是：**当两个概念的实现路径完全不同时，它们不应该共享同一个抽象**。

参考来源：[Microsoft DevBlog — From Skills to Plugins](https://devblogs.microsoft.com/semantic-kernel/skills-to-plugins-fully-embracing-the-openai-plugin-spec-in-semantic-kernel/)

### 1.3 行业共识：四层能力模型

2025-2026 年 AI Agent 领域逐渐形成了一个四层能力模型的共识：

| 层次 | 名称 | 本质 | 是否经过 LLM 推理 | 示例 |
|------|------|------|------------------|------|
| L0 | Tool（工具） | 原子操作 | 否 | HTTP 调用、数据库查询、文件读写 |
| L1 | Skill（技能） | 确定性工作流 | 否 | HTTP→解析→模板渲染、多步骤串联 |
| L2 | Agent（智能体） | 自主推理实体 | **是** | 拥有独立 System Prompt + 工具集 + Budget |
| L3 | Orchestrator（编排器） | 协调多个 Agent | 是 | 主 AgentLoop，决定何时委托 |

参考来源：[The AI Agent Ecosystem: Understanding the Four Layers](https://cenrax.substack.com/p/the-ai-agent-ecosystem-understanding)

### 1.4 各厂商的实践印证

| 厂商/框架 | Skill 定位 | Agent 定位 | 关键区别 |
|-----------|-----------|-----------|---------|
| Anthropic Claude Code | 知识+指令注入包（上下文增强） | 独立 AI 实例（隔离上下文窗口） | Skill 不推理，Agent 推理 |
| Google ADK | — | Agent-as-Tool（无状态事务调用）vs Sub-Agent（有推理链） | 按是否有推理链区分 |
| Spring AI (2026.01) | Markdown 文件夹（指令/脚本/资源） | TaskTool/SubAgent（独立上下文窗口专家） | Skill 是静态资源，Agent 是运行时实体 |
| OpenAI Swarm/Agents SDK | — | Handoff 模式（Agent 通过工具调用委托） | 纯 Agent 层，无 Skill 概念 |

参考来源：
- [Anthropic — Skills vs Subagent](https://www.eesel.ai/blog/skills-vs-subagent)
- [Google Cloud — Sub-Agents vs Agents-as-Tools](https://cloud.google.com/blog/topics/developers-practitioners/where-to-use-sub-agents-versus-agents-as-tools)
- [Spring AI — Agent Skills](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills)
- [Spring AI — Task SubAgents](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents)

---

## 2. 重构方案：四层能力模型

### 2.1 目标架构

```
┌─────────────────────────────────────────────────────────────────────┐
│  L3 Orchestrator（编排器）— 主 AgentLoop                             │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  接收用户请求 → LLM 推理 → 选择工具/Skill/Agent → 汇总响应   │    │
│  └─────────────────────────────────────────────────────────────┘    │
│       │                    │                    │                    │
│       ▼                    ▼                    ▼                    │
│  ┌─────────┐    ┌──────────────────┐    ┌──────────────────┐       │
│  │ L0 Tool │    │    L1 Skill      │    │    L2 Agent       │       │
│  │ 原子操作 │    │  确定性工作流     │    │  自主推理实体     │       │
│  │         │    │                  │    │                  │       │
│  │BuiltinTool│  │ HttpAction       │    │ 独立 System Prompt│       │
│  │ McpTool  │    │ ShellAction      │    │ 独立工具白名单    │       │
│  │ SkillTool│    │ ChainAction      │    │ 独立 Budget      │       │
│  │         │    │ TemplateAction   │    │ 独立上下文窗口    │       │
│  │         │    │                  │    │ agentLoop.run()  │       │
│  │ 不推理  │    │ 不推理           │    │ **经过 LLM 推理** │       │
│  └─────────┘    └──────────────────┘    └──────────────────┘       │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心变更

| 变更项 | 现状 | 目标 |
|--------|------|------|
| SubAgent 激活 | `SubAgentFactory` 在 Skill 模块中 | 迁移到多 Agent 模块，由 `AgentExecutor` 负责 |
| `SkillDefinition.preferredProviderId` | Skill 可指定 LLM 偏好 | 移除（Skill 不使用 LLM，无需模型偏好） |
| Skill 定位 | "Agent 能力单元"（含 SubAgent 激活） | 纯 L1 确定性工作流（不含 LLM 推理） |
| Agent 定位 | 不存在独立概念 | L2 自主推理实体，YAML 声明式定义 |
| HandoffTool | 不存在 | LLM 通过 Function Call 委托给 Agent |
| `SkillToToolBridge` | 将 Skill 包装为工具（含 SubAgent 激活） | 仅桥接 L1 确定性 Skill |
| 热加载 | Skill YAML 热加载已实现 | Agent YAML 热加载（复用 Skill 热加载机制） |
| 自扩展 | Skill 自扩展（GapDetector + Generator） | 保留 Skill 自扩展，Agent 不支持自扩展 |

### 2.3 不变的部分

以下现有机制保持不变，多 Agent 模块直接复用：

- `AgentLoop.run()` — 核心控制循环
- `AgentState.forSubAgent()` — 创建隔离子状态
- `Action.SubAgentResult` — 子 Agent 结果 Action 类型
- `StateReducer` — 处理 SubAgentResult 的状态转换
- `Budget` — 三维预算模型
- `DynamicToolRegistry` — HandoffTool 注册为 Java 原生工具
- `TraceRecorder` — 子 Agent 轨迹自动记录
- `LlmRouter` — 子 Agent 通过 preferredProvider 指定模型

---

## 3. 数据模型

### 3.1 AgentDefinition — Agent 蓝图

```java
@Builder(toBuilder = true)
public record AgentDefinition(
    String id,                          // Agent 唯一标识，如 "writer"
    String name,                        // 显示名称，如 "写作专家"
    String description,                 // 能力描述，用于 LLM 发现和 HandoffTool 描述
    String systemPrompt,                // Agent 专属 System Prompt
    List<String> allowedTools,          // 工具白名单（toolId 列表）
    boolean canDelegate,                // 是否允许嵌套委托（拥有 HandoffTool）
    AgentBudget budget,                 // 独立预算约束
    @Nullable String preferredProvider, // 偏好 LLM Provider ID
    AgentSource source,                 // 来源类型
    Map<String, String> metadata        // 扩展元数据
) {}
```

与 `SkillDefinition` 的关键区别：

| 字段 | AgentDefinition | SkillDefinition |
|------|----------------|-----------------|
| `systemPrompt` | ✅ 定义 Agent 人格 | ✅ 保留（用于 Discovery 阶段描述） |
| `preferredProvider` | ✅ Agent 需要 LLM | ❌ 移除（Skill 不使用 LLM） |
| `canDelegate` | ✅ 控制嵌套委托 | ❌ 不适用 |
| `memoryAccess` | ❌ 不需要（Agent 通过工具访问记忆） | ✅ 保留（声明式记忆访问控制） |
| `execution` | ❌ 不需要（Agent 有自己的推理循环） | ✅ 保留（确定性执行策略） |
| `version` | ❌ 不需要 | ✅ 保留 |

### 3.2 AgentSource — 来源类型

```java
public sealed interface AgentSource permits
    AgentSource.Builtin,
    AgentSource.YamlDefined {

    /** 内置 Agent — Java 代码注册。 */
    record Builtin() implements AgentSource {}

    /** 用户定义 Agent — YAML 声明式，支持热加载。 */
    record YamlDefined(
        String filePath,
        @Nullable Instant lastModified
    ) implements AgentSource {}
}
```

**设计决策**：不支持 `AutoGenerated` 来源。Agent 定义需要审慎设计（System Prompt 质量直接影响推理质量），不适合自动生成。Skill 自扩展（GapDetector + Generator）处理的是 L1 确定性工作流的能力缺口，这是合理的自动化范围。

### 3.3 AgentBudget — 独立预算

```java
public record AgentBudget(
    int maxTokens,
    int maxSteps,
    int timeoutSeconds
) {
    public static final AgentBudget DEFAULT = new AgentBudget(16000, 15, 180);
    public static final AgentBudget LIGHTWEIGHT = new AgentBudget(4000, 8, 60);
    public static final AgentBudget HEAVYWEIGHT = new AgentBudget(32000, 25, 300);

    /** 转换为 Agent 引擎的 Budget 对象。 */
    public Budget toAgentBudget() {
        return Budget.builder()
                .maxTokens(maxTokens).tokensUsed(0).tokensReserved(0)
                .maxSteps(maxSteps)
                .maxDuration(Duration.ofSeconds(timeoutSeconds))
                .elapsed(Duration.ZERO)
                .build();
    }
}
```

与 `SkillBudget` 的区别：不含 `maxCostCents`（成本控制在 LlmRouter 层统一管理）。

---

## 4. 核心组件

### 4.1 AgentRegistry — Agent 注册中心

```java
public class AgentRegistry {
    private final ConcurrentHashMap<String, AgentDefinition> agents;

    boolean register(AgentDefinition definition);
    boolean unregister(String agentId);
    Optional<AgentDefinition> find(String agentId);
    List<AgentDefinition> listAll();
    int unregisterBySource(Class<? extends AgentSource> sourceType);
}
```

**与 SkillRegistry 的关系**：AgentRegistry 是独立的注册中心，不扩展 SkillRegistry。理由：
1. Agent 和 Skill 是不同抽象层次，混合管理会导致概念模糊
2. AgentRegistry 不需要语义搜索（Agent 通过 HandoffTool 描述被 LLM 发现）
3. AgentRegistry 不需要 SkillDefinitionValidator 的校验逻辑

### 4.2 AgentExecutor — Agent 执行器

```java
public class AgentExecutor {
    private final AgentLoop agentLoop;
    private final DynamicToolRegistry toolRegistry;
    private final AgentConfigProperties config;

    /**
     * 执行 Agent 委托任务。
     *
     * 流程：
     * 1. 检查委托深度（不超过 maxDelegationDepth）
     * 2. 创建独立 Budget（从 AgentBudget 转换）
     * 3. 创建隔离 AgentState（depth+1，独立 traceId）
     * 4. 选择 LLM Provider（preferredProvider 或默认）
     * 5. 构建工具白名单（Agent 级过滤）
     * 6. 执行 agentLoop.run()
     * 7. 捕获异常，返回 SubAgentResult
     */
    public SubAgentResult execute(AgentDefinition definition,
                                  String task,
                                  @Nullable String context,
                                  AgentState parentState);
}
```

**与 SubAgentFactory 的关系**：`AgentExecutor` 替代 `SubAgentFactory` 中的 SubAgent 激活职责。`SubAgentFactory` 将被标记为 `@Deprecated`，其功能分两部分迁移：
- SubAgent 激活 → `AgentExecutor`（多 Agent 模块）
- Skill 确定性执行 → 保留在 Skill 模块（但不再调用 `agentLoop.run()`）

### 4.3 HandoffTool — 委托工具

每个注册的 Agent 对应一个 HandoffTool 实例，工具 ID 格式：`handoff_to_{agentId}`。

```json
{
  "id": "handoff_to_writer",
  "name": "委托写作专家",
  "description": "将写作相关任务委托给写作专家 Agent。擅长：周报、邮件、文案、总结。",
  "parameters": {
    "task": { "type": "string", "description": "委托任务描述" },
    "context": { "type": "string", "description": "可选的额外上下文信息" }
  }
}
```

HandoffTool 注册为 `BuiltinTool`，通过 `DynamicToolRegistry.registerBuiltinTool()` 注册。当 Agent 在 AgentRegistry 中注册/注销时，对应的 HandoffTool 自动注册/注销。

### 4.4 AgentYamlLoader — YAML 加载与热加载

```java
public class AgentYamlLoader {
    /**
     * 扫描指定目录下的 YAML Agent 定义文件。
     * 支持 .yml 和 .yaml 后缀。
     */
    List<AgentDefinition> loadFromDirectory(Path directory);

    /**
     * 解析单个 YAML 文件为 AgentDefinition。
     */
    Optional<AgentDefinition> loadFromFile(Path file);
}
```

**热加载机制**：复用 Skill 系统已有的文件监控模式（`WatchService` 或定时扫描 `lastModified`），当 `~/.lifepilot/agents/` 目录下的 YAML 文件变更时，自动重新加载并更新 AgentRegistry。

### 4.5 AgentToToolBridge — Agent 工具桥接

```java
public class AgentToToolBridge {
    /**
     * 监听 AgentRegistered 事件，创建 HandoffTool 并注册到 DynamicToolRegistry。
     */
    @EventListener
    void onAgentRegistered(AgentRegistryEvent.AgentRegistered event);

    /**
     * 监听 AgentUnregistered 事件，注销对应 HandoffTool。
     */
    @EventListener
    void onAgentUnregistered(AgentRegistryEvent.AgentUnregistered event);
}
```

模式与 `SkillToToolBridge` 完全一致，但桥接的是 Agent 而非 Skill。

---

## 5. 架构交互

### 5.1 委托执行序列

```
用户 → 主 AgentLoop → LLM 决策 → handoff_to_writer(task="写周报")
                                        │
                                        ▼
                                  HandoffTool.execute()
                                        │
                                        ▼
                                  AgentRegistry.find("writer")
                                        │
                                        ▼
                                  AgentExecutor.execute(definition, task, ctx, parentState)
                                        │
                                        ├─ 1. 检查深度 ≤ maxDelegationDepth
                                        ├─ 2. 创建独立 Budget
                                        ├─ 3. 创建隔离 AgentState (depth+1)
                                        ├─ 4. 选择 LLM Provider
                                        ├─ 5. 构建工具白名单
                                        └─ 6. agentLoop.run()
                                                │
                                                ▼
                                          子 AgentLoop 执行
                                          （独立上下文、独立预算）
                                                │
                                                ▼
                                          AgentResponse
                                                │
                                                ▼
                                  Action.SubAgentResult 返回主 AgentLoop
                                                │
                                                ▼
                                  StateReducer 处理 → 主 Agent 继续推理
```

### 5.2 agents-as-tools 模式

选择 agents-as-tools 而非完全 handoff 的理由：

| 维度 | agents-as-tools（选择） | 完全 handoff |
|------|----------------------|-------------|
| 控制权 | 主 Agent 保持 | 转移给子 Agent |
| 结果汇总 | 主 Agent 可汇总多个子 Agent 结果 | 需要额外协调机制 |
| 上下文连贯 | 主 Agent 维护完整对话 | 子 Agent 需要完整对话历史 |
| 实现复杂度 | 低（复用 Action.SubAgentResult） | 高（需要对话状态迁移） |
| 适用场景 | 任务委托、专家咨询 | 完全领域切换 |

LifePilot 是个人助手，用户与主 Agent 保持持续对话，专家 Agent 是辅助角色。agents-as-tools 模式与现有 `Action.SubAgentResult` 完全兼容，无需修改 AgentLoop 核心循环。

### 5.3 与现有模块的集成点

| 集成模块 | 集成方式 | 说明 |
|---------|---------|------|
| AgentLoop | 复用 | AgentExecutor 调用 `AgentLoop.run()` 执行子 Agent |
| Action.SubAgentResult | 复用 | 子 Agent 结果通过现有 Action 类型返回 |
| AgentState.forSubAgent() | 复用 | 创建隔离子状态 |
| Budget | 复用 | 独立预算分配 |
| DynamicToolRegistry | 复用 | HandoffTool 注册为 Java 原生工具 |
| LlmRouter | 复用 | 子 Agent 通过 preferredProvider 指定模型 |
| TraceRecorder | 复用 | 子 Agent 轨迹自动记录（traceId 层级格式） |
| SkillRegistry | 只读引用 | Agent 的 allowedTools 可引用 Skill 工具 |

---

## 6. Skill 系统重构影响

### 6.1 需要变更的部分

| 组件 | 变更内容 | 理由 |
|------|---------|------|
| `SkillDefinition.preferredProviderId` | 移除 | Skill 不使用 LLM，无需模型偏好 |
| `SubAgentFactory` | 标记 `@Deprecated`，功能迁移到 `AgentExecutor` | SubAgent 激活属于 Agent 层 |
| `SkillToToolBridge` | 移除 SubAgent 激活路径 | Skill 工具桥接只处理 L1 确定性执行 |
| `SkillLifecycleManager` | 移除 SubAgent 激活委托 | 生命周期管理只管理 L1 Skill |
| `SkillBudget` | 移除 `maxCostCents`（可选） | L1 Skill 不调用 LLM，成本可忽略 |

### 6.2 保持不变的部分

| 组件 | 说明 |
|------|------|
| `SkillAction` (HttpAction/ShellAction/ChainAction/TemplateAction) | L1 确定性动作，保持不变 |
| `SkillRegistry` | Skill 注册中心，保持不变 |
| `SkillGapDetector` + `SkillGenerator` | Skill 自扩展，保持不变（只生成 L1 Skill） |
| `MemoryAccessPolicy` + `MemoryAccessEnforcer` | 声明式记忆访问控制，保持不变 |
| `SkillSearchIndex` | 语义搜索索引，保持不变 |
| `SkillDefinitionValidator` | 校验逻辑，保持不变 |
| YAML Skill 热加载 | 文件监控机制，保持不变 |

### 6.3 迁移策略

采用**渐进式迁移**，分两步完成：

**步骤 1（模块 21 实现）**：
- 创建 `AgentDefinition`、`AgentRegistry`、`AgentExecutor`、`HandoffTool`、`AgentToToolBridge`
- 创建 `AgentYamlLoader` + 热加载
- 预设专家 Agent（writer / analyst / researcher）
- `SubAgentFactory` 标记 `@Deprecated`，但保留功能

**步骤 2（模块 21 后续清理）**：
- 移除 `SkillDefinition.preferredProviderId`
- 重构 `SkillToToolBridge`，移除 SubAgent 激活路径
- 重构 `SkillLifecycleManager`，移除 SubAgent 委托
- 最终移除 `SubAgentFactory`

这种渐进式迁移确保模块 21 可以独立完成和测试，不阻塞 Skill 系统的正常运行。

---

## 7. YAML Agent 定义格式

### 7.1 完整示例

```yaml
# ~/.lifepilot/agents/writer.yml
id: writer
name: 写作专家
description: 擅长撰写周报、邮件、文案、总结等文字内容
system-prompt: |
  你是一位专业的中文写作助手。
  你的任务是根据用户提供的素材和要求，撰写高质量的文字内容。
  注意：保持简洁、专业、有条理。
allowed-tools:
  - memory-search
  - knowledge-search
can-delegate: false
budget:
  max-tokens: 16000
  max-steps: 15
  timeout-seconds: 180
preferred-provider: deepseek-chat
metadata:
  category: writing
  icon: ✍️
```

### 7.2 字段说明

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `id` | 是 | String | Agent 唯一标识，kebab-case |
| `name` | 是 | String | 显示名称 |
| `description` | 是 | String | 能力描述，用于 HandoffTool 的工具描述 |
| `system-prompt` | 是 | String | Agent 专属 System Prompt |
| `allowed-tools` | 否 | List<String> | 工具白名单，空列表表示无工具 |
| `can-delegate` | 否 | boolean | 是否允许嵌套委托，默认 false |
| `budget.max-tokens` | 否 | int | Token 预算，默认 16000 |
| `budget.max-steps` | 否 | int | 步骤预算，默认 15 |
| `budget.timeout-seconds` | 否 | int | 超时秒数，默认 180 |
| `preferred-provider` | 否 | String | 偏好 LLM Provider ID |
| `metadata` | 否 | Map | 扩展元数据 |

---

## 8. 预设专家 Agent

模块 21 提供以下预设专家 Agent（YAML 定义，可由用户修改或扩展）：

| Agent ID | 名称 | 职责 | 偏好模型 | 预算级别 | canDelegate |
|----------|------|------|---------|---------|-------------|
| writer | 写作专家 | 周报、邮件、文案、总结 | deepseek-chat | DEFAULT | false |
| analyst | 分析专家 | 数据分析、趋势解读、对比评估 | deepseek-chat | DEFAULT | false |
| researcher | 调研专家 | 信息检索、资料整理、知识汇总 | deepseek-chat | HEAVYWEIGHT | false |

预设 Agent 以 `AgentSource.Builtin` 来源注册，用户自定义 Agent 以 `AgentSource.YamlDefined` 来源注册。Builtin 来源不允许被 YamlDefined 覆盖（与 SkillRegistry 行为一致）。

---

## 9. 配置项

```yaml
lifepilot:
  agent:
    multi-agent:
      enabled: true                              # 是否启用多 Agent 协作
      max-delegation-depth: 2                    # 最大委托深度
      agent-definitions-path: ~/.lifepilot/agents/ # 用户自定义 Agent YAML 目录
      register-handoff-tools: true               # 是否自动注册 HandoffTool
      hot-reload:
        enabled: true                            # 是否启用热加载
        scan-interval-seconds: 5                 # 文件扫描间隔
      budget:
        default-max-tokens: 16000                # 默认 Token 预算
        default-max-steps: 15                    # 默认步骤预算
        default-timeout-seconds: 180             # 默认超时秒数
```

---

## 10. 关键设计决策总结

### 10.1 为什么 Agent 不支持自扩展

Skill 自扩展（GapDetector + Generator）适用于 L1 确定性工作流：生成的 YAML 定义包含 HttpAction / ShellAction 等确定性动作，执行路径可预测，三重验证（格式 + 安全 + 沙箱）可以有效保障安全。

Agent 自扩展不适用的原因：
1. Agent 的核心是 System Prompt，其质量直接决定推理质量，LLM 生成的 System Prompt 质量不可控
2. Agent 拥有 LLM 推理能力，执行路径不可预测，沙箱验证无法覆盖所有场景
3. Agent 的工具白名单决定了其能力边界，自动生成的白名单可能过宽或过窄
4. 用户自定义 Agent（YAML + 热加载）已经提供了足够的扩展性

### 10.2 为什么独立 AgentRegistry 而非扩展 SkillRegistry

1. **概念清晰**：Agent（L2 推理实体）和 Skill（L1 确定性工作流）是不同抽象层次
2. **发现机制不同**：Skill 通过语义搜索发现（SkillSearchIndex），Agent 通过 HandoffTool 描述被 LLM 发现
3. **校验逻辑不同**：Skill 需要校验 SkillAction 合法性，Agent 需要校验 System Prompt 非空
4. **生命周期不同**：Skill 有 Discovery → Activation → Execution 三阶段，Agent 直接通过 HandoffTool 调用

### 10.3 为什么 agents-as-tools 而非完全 handoff

见 §5.2。核心理由：LifePilot 是个人助手，主 Agent 需要保持对话连贯性和控制权。与现有 `Action.SubAgentResult` 完全兼容，零改动复用 AgentLoop。

### 10.4 委托深度限制

复用现有 `AgentState.depth` 机制，最大深度可配置（默认 2）。

- 深度 0 = 主 Agent（Orchestrator）
- 深度 1 = 专家 Agent
- 深度 2 = 专家 Agent 的嵌套委托（仅当 `canDelegate=true`）

---

## 11. 调研参考

| 来源 | 核心洞察 | LifePilot 采纳 |
|------|---------|---------------|
| [Microsoft Semantic Kernel v1.0](https://devblogs.microsoft.com/semantic-kernel/skills-to-plugins-fully-embracing-the-openai-plugin-spec-in-semantic-kernel/) | Skills 重命名为 Plugins，消除概念混淆 | 采纳：明确区分 Skill（L1）和 Agent（L2） |
| [四层能力模型](https://cenrax.substack.com/p/the-ai-agent-ecosystem-understanding) | Tool → Skill → Agent → Orchestrator | 采纳：作为架构重构的理论基础 |
| [Anthropic Claude Code](https://www.eesel.ai/blog/skills-vs-subagent) | Skills = 上下文注入，Subagent = 独立推理 | 采纳：Skill 不推理，Agent 推理 |
| [Google ADK](https://cloud.google.com/blog/topics/developers-practitioners/where-to-use-sub-agents-versus-agents-as-tools) | Agent-as-Tool vs Sub-Agent | 采纳：agents-as-tools 模式 |
| [Spring AI Agent Skills](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills) | Skill = Markdown 文件夹，渐进式发现 | 已在 Skill 系统中采纳 |
| [Spring AI Task SubAgents](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents) | 独立上下文 + 工具白名单 + 多模型路由 | 采纳：AgentExecutor 设计 |
| [OpenAI Swarm](https://github.com/openai/swarm) | Handoff 模式，Agent 通过工具调用委托 | 采纳：HandoffTool 命名和模式 |
| [AstrBot](https://github.com/Soulter/AstrBot) | `transfer_to_<name>` 工具模式 | 采纳：`handoff_to_{id}` 命名 |

> 内容已重新组织表述以符合许可要求。参考来源均为 2025-2026 年发表的技术文章和开源项目。

# 多 Agent 协作架构设计

> **模块编号**：21（Phase 6）
> **依赖模块**：Agent 引擎（模块 2）、工具系统（模块 3）、Skill 系统（模块 10）、LLM Router（模块 1）
> **最后更新**：2026-02-27

---

## 1. 问题诊断：Skill 与 Agent 的职责重叠

### 1.1 现状分析

当前 ZhiWei 的 Skill 系统（模块 10）存在一个根本性的架构问题：**Skill 同时承担了两个不同抽象层次的职责**。

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
| Agent 定位 | 不存在独立概念 | L2 自主推理实体，Markdown + YAML Frontmatter 声明式定义 |
| HandoffTool | 不存在 | LLM 通过 Function Call 委托给 Agent |
| `SkillToToolBridge` | 将 Skill 包装为工具（含 SubAgent 激活） | 仅桥接 L1 确定性 Skill |
| 热加载 | Skill YAML 热加载已实现 | Agent Markdown 热加载（复用 Skill 热加载机制） |
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
    AgentSource.MarkdownDefined {

    /** 内置 Agent — Java 代码注册。 */
    record Builtin() implements AgentSource {}

    /** 用户定义 Agent — Markdown + YAML Frontmatter 声明式，支持热加载。 */
    record MarkdownDefined(
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

### 4.4 AgentMarkdownLoader — Markdown 加载与热加载

```java
public class AgentMarkdownLoader {
    /**
     * 扫描指定目录下的 Markdown Agent 定义文件（.md 后缀）。
     * 解析 YAML Frontmatter 提取结构化字段，Markdown 正文作为 System Prompt。
     */
    List<AgentDefinition> loadFromDirectory(Path directory);

    /**
     * 解析单个 Markdown 文件为 AgentDefinition。
     * 文件格式：YAML Frontmatter（---分隔）+ Markdown 正文（System Prompt）。
     */
    Optional<AgentDefinition> loadFromFile(Path file);
}
```

**热加载机制**：复用 Skill 系统已有的文件监控模式（`WatchService` 或定时扫描 `lastModified`），当 `~/.zhiwei/agents/` 目录下的 Markdown 文件变更时，自动重新加载并更新 AgentRegistry。

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

ZhiWei 是个人助手，用户与主 Agent 保持持续对话，专家 Agent 是辅助角色。agents-as-tools 模式与现有 `Action.SubAgentResult` 完全兼容，无需修改 AgentLoop 核心循环。

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
- 创建 `AgentMarkdownLoader` + 热加载
- 预设专家 Agent（writer / life-coach / planner）
- `SubAgentFactory` 标记 `@Deprecated`，但保留功能

**步骤 2（模块 21 后续清理）**：
- 移除 `SkillDefinition.preferredProviderId`
- 重构 `SkillToToolBridge`，移除 SubAgent 激活路径
- 重构 `SkillLifecycleManager`，移除 SubAgent 委托
- 最终移除 `SubAgentFactory`

这种渐进式迁移确保模块 21 可以独立完成和测试，不阻塞 Skill 系统的正常运行。

---

## 7. Markdown Agent 定义格式（Markdown + YAML Frontmatter）

### 7.1 完整示例

```markdown
---
id: writer
name: 写作专家
description: 擅长撰写周报、邮件、文案、总结等文字内容，注重结构清晰和表达精准
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
  category: creation
  icon: ✍️
---

你是一位专业的中文写作助手。你的核心能力是将零散的素材和模糊的意图转化为结构清晰、表达精准的文字。

## 写作原则

- 先理解目的和受众，再组织结构
- 开门见山，避免冗余铺垫
- 使用具体数据和事实支撑观点
- 保持语气一致，匹配场景（正式/轻松/专业）
- 段落之间有清晰的逻辑过渡

## 交互规则

如果用户提供了素材但未明确格式，主动询问：目的是什么？给谁看？期望的篇幅和风格？
```

文件路径：`~/.zhiwei/agents/writer.md`

### 7.2 字段说明

| 字段 | 位置 | 必填 | 类型 | 说明 |
|------|------|------|------|------|
| `id` | Frontmatter | 是 | String | Agent 唯一标识，kebab-case |
| `name` | Frontmatter | 是 | String | 显示名称 |
| `description` | Frontmatter | 是 | String | 能力描述，用于 HandoffTool 的工具描述 |
| _(Markdown 正文)_ | Body | 是 | String | Agent 专属 System Prompt（Markdown 格式） |
| `allowed-tools` | Frontmatter | 否 | List<String> | 工具白名单，空列表表示无工具 |
| `can-delegate` | Frontmatter | 否 | boolean | 是否允许嵌套委托，默认 false |
| `budget.max-tokens` | Frontmatter | 否 | int | Token 预算，默认 16000 |
| `budget.max-steps` | Frontmatter | 否 | int | 步骤预算，默认 15 |
| `budget.timeout-seconds` | Frontmatter | 否 | int | 超时秒数，默认 180 |
| `preferred-provider` | Frontmatter | 否 | String | 偏好 LLM Provider ID |
| `metadata` | Frontmatter | 否 | Map | 扩展元数据 |

注意：`system-prompt` 不再是 YAML 字段，而是 Markdown 正文内容。这使得 System Prompt 可以充分利用 Markdown 的格式化能力（标题、列表、代码块等），编辑体验远优于 YAML 多行字符串。

---

## 8. 预设专家 Agent

### 8.1 设计原则：何时值得创建专家 Agent

Anthropic 在 2026 年 1 月发表的多 Agent 系统实践指南中，总结了三个 sub-agent 优于 single-agent 的场景：
（1）上下文污染——子任务产生大量与主任务无关的上下文；
（2）并行化——独立任务可同时执行；
（3）专业化——不同任务需要不同的工具集或 System Prompt。

参考来源：[Anthropic — Building multi-agent systems: When and how to use them](https://website.claude.com/blog/building-multi-agent-systems-when-and-how-to-use-them)

同时，Anthropic 明确警告：许多团队花数月构建复杂多 Agent 架构，最终发现改进单 Agent 的 Prompt 就能达到同等效果。多 Agent 实现通常消耗 3-10 倍 Token。

参考来源：[Multi-agent error amplification research](https://www.amitkoth.com/multi-agent-orchestration-complexity/)（95% 单步可靠性在 20 步后仅剩 36% 成功率）

因此，ZhiWei 的预设 Agent 必须通过以下「L2 准入测试」：

| 准入条件 | 说明 | 不满足则 |
|---------|------|---------|
| System Prompt 专业化增益 | 专用 System Prompt 的输出质量显著优于主 Agent 通用 Prompt | 不设 Agent，主 Agent 直接处理 |
| 人格/语气差异 | 任务需要与主 Agent 截然不同的交互风格（如教练式提问 vs 任务执行） | 不设 Agent |
| 上下文隔离收益 | 任务会产生大量中间上下文，污染主 Agent 对话 | 可选加分项 |
| 工具集差异 | 任务需要的工具集与主 Agent 显著不同 | 可选加分项 |

### 8.2 原方案评审：writer / analyst / researcher

| Agent | 准入测试结果 | 结论 |
|-------|------------|------|
| writer（写作专家） | ✅ System Prompt 增益显著（写作风格、结构、修辞需要专门优化）；✅ 人格差异（创作者 vs 执行者） | **保留** |
| analyst（分析专家） | ❌ "数据分析"过于宽泛，主 Agent 配合相同工具即可完成；❌ 无明确人格差异 | **移除** |
| researcher（调研专家） | ❌ 搜索+摘要是主 Agent 的核心能力；❌ 搜索结果上下文量小，无隔离收益 | **移除** |

### 8.3 新方案：基于 ZhiWei 产品定位的预设 Agent

ZhiWei 是个人助手（todo / schedule / habit / memory / knowledge），核心用户场景是日常管理。
基于 L2 准入测试和产品定位，重新设计预设 Agent 列表：

| Agent ID | 名称 | 职责 | 准入理由 | 偏好模型 | 预算级别 | canDelegate |
|----------|------|------|---------|---------|---------|-------------|
| writer | 写作专家 | 周报、邮件、文案、总结、润色 | System Prompt 增益：写作质量需要专门优化的 Prompt；人格差异：创作者视角 vs 主 Agent 的任务执行视角 | deepseek-chat | DEFAULT | false |
| life-coach | 生活教练 | 周/月回顾、习惯分析、目标复盘、行为模式洞察 | System Prompt 增益：教练式 Socratic 提问法、动机访谈技术；人格差异：引导式提问者 vs 主 Agent 的直接回答者；上下文隔离：回顾分析需要检索大量历史记忆数据 | deepseek-chat | HEAVYWEIGHT | false |
| planner | 规划专家 | 日/周规划、时间块分配、优先级排序、冲突检测 | System Prompt 增益：时间管理方法论（Eisenhower 矩阵、时间块法、能量管理）；上下文隔离：规划需要拉取完整的 todo/schedule/habit 数据进行综合分析 | deepseek-chat | DEFAULT | false |

### 8.4 各 Agent 详细设计

#### writer（写作专家）

核心价值：将用户的素材和意图转化为高质量的文字输出。主 Agent 的 System Prompt 优化方向是「理解意图 → 选择工具 → 执行任务」，而 writer 的 System Prompt 优化方向是「理解素材 → 组织结构 → 打磨表达」。

文件路径：`~/.zhiwei/agents/writer.md`

#### life-coach（生活教练）

核心价值：帮助用户进行结构化的自我反思和行为模式洞察。这是与主 Agent 人格差异最大的 Agent——主 Agent 是「你说什么我做什么」的执行者，life-coach 是「通过提问帮你发现答案」的引导者。

参考来源：
- [Personal Development with AI in 2026](https://www.upskillist.com/blog/personal-development-with-ai-daily-wins-that-compound/)（AI 作为 accountability partner 的趋势）
- [Resolution Coach](https://www.producthunt.com/products/resolution-coach)（AI 教练产品的交互模式）

文件路径：`~/.zhiwei/agents/life-coach.md`

#### planner（规划专家）

核心价值：将用户的 todo、schedule、habit 数据综合分析，生成结构化的日/周规划。主 Agent 可以逐条处理 todo 和 schedule，但缺乏「全局视角下的优先级排序和时间块分配」的专业 Prompt。

文件路径：`~/.zhiwei/agents/planner.md`

### 8.5 为什么不预设更多 Agent

以下是评估后决定不预设的候选 Agent：

| 候选 | 评估 | 结论 |
|------|------|------|
| researcher（调研专家） | 搜索+摘要是主 Agent 核心能力，无 System Prompt 增益 | 不预设 |
| analyst（分析专家） | 过于宽泛，主 Agent + 工具即可完成 | 不预设 |
| translator（翻译专家） | System Prompt 增益存在，但翻译是低频场景，且主 Agent 翻译质量已足够 | 不预设（作为用户自定义示例） |
| coder（编程助手） | ZhiWei 是个人助手，编程不在核心场景内 | 不预设 |
| health-advisor（健康顾问） | 涉及医疗建议的法律风险，不适合预设 | 不预设 |

用户可通过 Markdown 热加载机制自定义任意 Agent。文档 §3.3 提供了 translator 的完整 Markdown 示例。

### 8.6 注册策略

预设 Agent 以 `AgentSource.Builtin` 来源注册，用户自定义 Agent 以 `AgentSource.MarkdownDefined` 来源注册。Builtin 来源不允许被 MarkdownDefined 覆盖（与 SkillRegistry 行为一致）。

如果用户希望修改预设 Agent 的行为（如调整 writer 的 System Prompt），可以在 `~/.zhiwei/agents/` 下创建同 ID 的 Markdown 文件，此时 MarkdownDefined 版本将覆盖 Builtin 版本。这是一个有意的设计决策：预设 Agent 提供合理的默认值，但用户始终拥有最终控制权。

---

## 9. 配置项

```yaml
lifepilot:
  agent:
    multi-agent:
      enabled: true                              # 是否启用多 Agent 协作
      max-delegation-depth: 2                    # 最大委托深度
      agent-definitions-path: ~/.zhiwei/agents/  # 用户自定义 Agent Markdown 目录
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
4. 用户自定义 Agent（Markdown + 热加载）已经提供了足够的扩展性

### 10.2 为什么独立 AgentRegistry 而非扩展 SkillRegistry

1. **概念清晰**：Agent（L2 推理实体）和 Skill（L1 确定性工作流）是不同抽象层次
2. **发现机制不同**：Skill 通过语义搜索发现（SkillSearchIndex），Agent 通过 HandoffTool 描述被 LLM 发现
3. **校验逻辑不同**：Skill 需要校验 SkillAction 合法性，Agent 需要校验 System Prompt 非空
4. **生命周期不同**：Skill 有 Discovery → Activation → Execution 三阶段，Agent 直接通过 HandoffTool 调用

### 10.3 为什么 agents-as-tools 而非完全 handoff

见 §5.2。核心理由：ZhiWei 是个人助手，主 Agent 需要保持对话连贯性和控制权。与现有 `Action.SubAgentResult` 完全兼容，零改动复用 AgentLoop。

### 10.4 委托深度限制

复用现有 `AgentState.depth` 机制，最大深度可配置（默认 2）。

- 深度 0 = 主 Agent（Orchestrator）
- 深度 1 = 专家 Agent
- 深度 2 = 专家 Agent 的嵌套委托（仅当 `canDelegate=true`）

### 10.5 为什么 Agent 用 Markdown + YAML Frontmatter 而 Skill 用纯 YAML

Agent 和 Skill 采用不同的定义文件格式，核心原则是**格式跟随内容本质**：

| 维度 | Agent 定义 | Skill 定义 |
|------|-----------|-----------|
| 核心内容 | System Prompt（自然语言，Agent 的灵魂） | execution（SkillAction 结构化配置） |
| 内容占比 | 自然语言 > 80%，结构化配置 < 20% | 结构化配置 > 80%，自然语言 < 20% |
| 编辑体验 | Markdown 原生支持标题/列表/代码块，编辑器预览友好 | YAML 结构化字段，IDE 补全友好 |
| 文件格式 | `.md`（Markdown + YAML Frontmatter） | `.yml`（纯 YAML） |

行业趋势验证：
- [Claude Code Agent Spec](https://lattice.uptownhr.com/claude-code-agents/agent-spec)：Agent 定义为 Markdown + YAML Frontmatter
- [Open Agent Format (OAF) v0.8](https://openagentformat.com/)：`AGENTS.md` 使用 YAML Frontmatter + Markdown 指令
- [Spring AI Agent Skills](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills)：`SKILL.md` 使用 YAML Frontmatter + Markdown 指令
- [从 YAML 到 Markdown 的范式转移](https://jimmysong.io/blog/from-yaml-to-markdown-devops-vs-collabops/)：AI-native 时代从云原生 YAML 配置转向 Markdown 自然语言定义

核心洞察：当文件的主要内容是自然语言时，Markdown 是最自然的载体；当文件的主要内容是结构化配置时，YAML 是最自然的载体。Agent 的灵魂是 System Prompt（自然语言），Skill 的灵魂是 execution（结构化工作流）。

---

## 11. 调研参考

| 来源 | 核心洞察 | ZhiWei 采纳 |
|------|---------|---------------|
| [Microsoft Semantic Kernel v1.0](https://devblogs.microsoft.com/semantic-kernel/skills-to-plugins-fully-embracing-the-openai-plugin-spec-in-semantic-kernel/) | Skills 重命名为 Plugins，消除概念混淆 | 采纳：明确区分 Skill（L1）和 Agent（L2） |
| [四层能力模型](https://cenrax.substack.com/p/the-ai-agent-ecosystem-understanding) | Tool → Skill → Agent → Orchestrator | 采纳：作为架构重构的理论基础 |
| [Anthropic — Building multi-agent systems](https://website.claude.com/blog/building-multi-agent-systems-when-and-how-to-use-them) | 三个 sub-agent 优于 single-agent 的场景；多 Agent 消耗 3-10x Token | 采纳：作为预设 Agent「L2 准入测试」的理论基础 |
| [Anthropic Claude Code](https://www.eesel.ai/blog/skills-vs-subagent) | Skills = 上下文注入，Subagent = 独立推理 | 采纳：Skill 不推理，Agent 推理 |
| [Claude Code Sub-Agents](https://www.implicator.ai/claudes-ai-sub-agents-turn-one-assistant-into-a-team-of-specialists/) | 每个 sub-agent 在独立上下文窗口中运行 | 采纳：上下文隔离设计 |
| [Google ADK](https://cloud.google.com/blog/topics/developers-practitioners/where-to-use-sub-agents-versus-agents-as-tools) | Agent-as-Tool vs Sub-Agent | 采纳：agents-as-tools 模式 |
| [Multi-agent error amplification](https://www.amitkoth.com/multi-agent-orchestration-complexity/) | 95% 单步可靠性在 20 步后仅剩 36% 成功率 | 采纳：严格控制预设 Agent 数量 |
| [Spring AI Agent Skills](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills) | Skill = Markdown 文件夹 | 已在 Skill 系统中采纳 |
| [Spring AI Task SubAgents](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents) | 独立上下文 + 工具白名单 + 多模型路由 | 采纳：AgentExecutor 设计 |
| [OpenAI Swarm](https://github.com/openai/swarm) | Handoff 模式 | 采纳：HandoffTool 命名和模式 |
| [AstrBot](https://github.com/Soulter/AstrBot) | `transfer_to_<name>` 工具模式 | 采纳：`handoff_to_{id}` 命名 |
| [Personal Development with AI 2026](https://www.upskillist.com/blog/personal-development-with-ai-daily-wins-that-compound/) | AI 作为 accountability partner | 采纳：life-coach Agent 的教练式交互模式 |
| [Claude Code Agent Spec](https://lattice.uptownhr.com/claude-code-agents/agent-spec) | Agent 定义为 Markdown + YAML Frontmatter | 采纳：Agent 定义格式选型依据 |
| [Open Agent Format (OAF) v0.8](https://openagentformat.com/) | AGENTS.md 使用 YAML Frontmatter + Markdown 指令 | 采纳：Agent 定义格式选型依据 |

> 内容已重新组织表述以符合许可要求。参考来源均为 2025-2026 年发表的技术文章和开源项目。
# 多 Agent 协作架构设计

> **模块编号**：21（Phase 6）
> **依赖模块**：Agent 引擎（模块 2）、Skill 系统（模块 10）、LLM Router（模块 1）
> **最后更新**：2026-02-27

---

## 1. 模块定位与职责边界

### 1.1 定位

多 Agent 协作模块在现有单 Agent + Skill 激活架构之上，引入**命名 Agent 定义**和**HandoffTool 委托模式**，使主 Agent 能够将特定领域任务委托给具有独立身份、独立上下文、独立预算和差异化模型的专家 Agent。

### 1.2 与 Skill 系统的关系

当前 Skill 系统已实现 SubAgent 激活（`SubAgentFactory.activate()`），但存在以下局限：

| 维度 | 现有 Skill SubAgent | 多 Agent 协作 |
|------|---------------------|--------------|
| 身份 | 匿名，由 Skill 定义驱动 | 命名 Agent，有独立 AgentDefinition |
| 委托方式 | 代码层直接调用 `SubAgentFactory` | LLM 通过 HandoffTool 自主决策委托 |
| 模型选择 | 继承父 Agent 或 Skill 指定 | AgentDefinition 声明 preferredProvider |
| 工具范围 | Skill 白名单 | Agent 级工具白名单 + 可嵌套 HandoffTool |
| 上下文 | 隔离，仅传入 input 字符串 | 隔离，但可选择性继承父上下文摘要 |
| 注册发现 | SkillRegistry 管理 | AgentRegistry 管理，LLM 通过工具描述发现 |

多 Agent 协作模块**不替代** Skill 系统，而是在其上层提供更高级的 Agent 级抽象。Skill 仍然是工具和能力的基本单元，Agent 是拥有独立决策能力的高级实体。

### 1.3 职责边界

**本模块负责**：
- AgentDefinition 数据模型定义
- AgentRegistry 注册中心（注册、查找、列举）
- HandoffTool 委托工具（LLM 可调用的 Function Call）
- AgentExecutor 执行器（创建隔离上下文、执行 AgentLoop、收集结果）
- 预设专家 Agent 定义（YAML 声明式）
- AgentBudget 独立预算管理

**本模块不负责**：
- AgentLoop 核心循环（复用现有实现）
- LLM 路由策略（复用 LlmRouter，通过 preferredProvider 指定）
- 工具注册（复用 DynamicToolRegistry）
- 跨系统 Agent 互操作（A2A 协议，模块 22）

---

## 2. 核心概念与术语

| 术语 | 定义 |
|------|------|
| AgentDefinition | Agent 的完整蓝图，包含身份、System Prompt、工具白名单、预算、模型偏好等 |
| AgentRegistry | Agent 定义的注册中心，支持运行时注册/注销/查找 |
| HandoffTool | 委托工具，LLM 通过 Function Call 调用，将任务委托给指定 Agent |
| AgentExecutor | 执行器，负责创建隔离上下文并驱动 AgentLoop 执行委托任务 |
| 主 Agent (Primary Agent) | 直接处理用户请求的 Agent，拥有 HandoffTool |
| 专家 Agent (Expert Agent) | 被委托执行特定领域任务的 Agent |
| 委托深度 (Delegation Depth) | HandoffTool 嵌套调用的层数，受 maxDepth 限制 |

---

## 3. 架构设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                     用户请求                              │
└──────────────────────┬──────────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────────┐
│                  主 AgentLoop                            │
│  ┌─────────────────────────────────────────────────┐    │
│  │ LLM 决策：调用 handoff_to_writer(task="写周报")   │    │
│  └──────────────────────┬──────────────────────────┘    │
│                         ▼                                │
│  ┌─────────────────────────────────────────────────┐    │
│  │ HandoffTool.execute()                            │    │
│  │  1. AgentRegistry.find("writer")                 │    │
│  │  2. AgentExecutor.execute(definition, task, ctx) │    │
│  └──────────────────────┬──────────────────────────┘    │
│                         ▼                                │
│  ┌─────────────────────────────────────────────────┐    │
│  │ AgentExecutor                                    │    │
│  │  1. 创建独立 Budget                               │    │
│  │  2. 创建隔离 AgentState (depth+1)                 │    │
│  │  3. 构建工具白名单（Agent 级）                      │    │
│  │  4. 选择 LLM Provider（preferredProvider）         │    │
│  │  5. 执行 AgentLoop.run()                          │    │
│  └──────────────────────┬──────────────────────────┘    │
│                         ▼                                │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Action.SubAgentResult 返回主 AgentLoop            │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

### 3.2 HandoffTool 委托模式

借鉴 OpenAI Swarm / AstrBot 的 `transfer_to_<agent>` 模式，但做了关键调整：

**OpenAI Swarm 模式**：完全控制权转移，主 Agent 暂停，子 Agent 接管对话。
**LifePilot 模式**：委托执行后返回，主 Agent 保持控制权（agents-as-tools 模式）。

选择 agents-as-tools 而非完全 handoff 的理由：
1. 主 Agent 需要汇总多个专家 Agent 的结果
2. 主 Agent 保持对话连贯性和上下文
3. 预算控制更精确（主 Agent 统一管理）
4. 与现有 SubAgentResult Action 类型天然兼容

### 3.3 组件交互序列

```
用户 → 主AgentLoop → LLM → HandoffTool → AgentRegistry → AgentExecutor → 子AgentLoop → LLM
                                                                              ↓
用户 ← 主AgentLoop ← StateReducer ← Action.SubAgentResult ← AgentExecutor ←─┘
```

### 3.4 与现有模块的集成点

| 集成模块 | 集成方式 | 说明 |
|---------|---------|------|
| AgentLoop | 复用 | AgentExecutor 调用 `AgentLoop.run()` 执行子 Agent |
| Action.SubAgentResult | 复用 | 子 Agent 结果通过现有 Action 类型返回 |
| AgentState.forSubAgent() | 复用 | 创建隔离子状态 |
| Budget | 复用 | 独立预算分配 |
| DynamicToolRegistry | 复用 | HandoffTool 注册为 Java 原生工具 |
| LlmRouter | 复用 | 子 Agent 通过 preferredProvider 指定模型 |
| TraceRecorder | 复用 | 子 Agent 轨迹自动记录（traceId 层级格式） |
| SkillRegistry | 只读引用 | Agent 可引用 Skill 的工具白名单 |

---

## 4. 数据模型

### 4.1 AgentDefinition

```java
@Builder(toBuilder = true)
public record AgentDefinition(
    String id,                      // Agent 唯一标识，如 "writer"
    String name,                    // 显示名称，如 "写作专家"
    String description,             // 能力描述，用于 LLM 发现
    String systemPrompt,            // Agent 专属 System Prompt
    List<String> allowedTools,      // 工具白名单（toolId 列表）
    boolean canDelegate,            // 是否允许嵌套委托（拥有 HandoffTool）
    AgentBudget budget,             // 独立预算约束
    @Nullable String preferredProvider, // 偏好 LLM Provider ID
    AgentSource source,             // 来源类型
    Map<String, String> metadata    // 扩展元数据
) {}
```

### 4.2 AgentSource

```java
public sealed interface AgentSource permits
    AgentSource.Builtin,
    AgentSource.YamlDefined {

    record Builtin() implements AgentSource {}
    record YamlDefined(String filePath, @Nullable Instant lastModified) implements AgentSource {}
}
```

暂不支持 AutoGenerated 来源（与 Skill 自扩展不同，Agent 定义需要更审慎的设计）。

### 4.3 AgentBudget

```java
public record AgentBudget(
    int maxTokens,
    int maxSteps,
    int timeoutSeconds
) {
    public static final AgentBudget DEFAULT = new AgentBudget(16000, 15, 180);
    public static final AgentBudget LIGHTWEIGHT = new AgentBudget(4000, 8, 60);
    public static final AgentBudget HEAVYWEIGHT = new AgentBudget(32000, 25, 300);

    public Budget toAgentBudget() { ... }
}
```

### 4.4 HandoffTool 工具描述

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

---

## 5. 关键设计决策

### 5.1 agents-as-tools vs 完全 handoff

| 维度 | agents-as-tools（选择） | 完全 handoff |
|------|----------------------|-------------|
| 控制权 | 主 Agent 保持 | 转移给子 Agent |
| 结果汇总 | 主 Agent 可汇总多个子 Agent 结果 | 需要额外协调机制 |
| 上下文连贯 | 主 Agent 维护完整对话 | 子 Agent 需要完整对话历史 |
| 实现复杂度 | 低（复用 SubAgentResult） | 高（需要对话状态迁移） |
| 适用场景 | 任务委托、专家咨询 | 完全领域切换 |

**决策理由**：LifePilot 是个人助手，用户与主 Agent 保持持续对话，专家 Agent 是辅助角色。agents-as-tools 模式与现有 `Action.SubAgentResult` 完全兼容，无需修改 AgentLoop 核心循环。

### 5.2 AgentRegistry vs 扩展 SkillRegistry

| 方案 | 优势 | 劣势 |
|------|------|------|
| 独立 AgentRegistry（选择） | 职责清晰、Agent 和 Skill 概念分离 | 新增注册中心 |
| 扩展 SkillRegistry | 复用现有基础设施 | 概念混淆、SkillDefinition 膨胀 |

**决策理由**：Agent 和 Skill 是不同层次的抽象。Skill 是能力单元（工具集合），Agent 是决策实体（拥有独立推理能力）。混合管理会导致概念模糊。

### 5.3 YAML 声明式 Agent 定义

借鉴 Spring AI `spring-ai-agent-utils` 的 Markdown Agent 配置和 AstrBot 的 YAML SubAgent 定义，采用 YAML 格式声明 Agent：

```yaml
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
```

### 5.4 委托深度限制

复用现有 `AgentState.depth` 机制，最大深度从 `SubAgentFactory.MAX_ACTIVATION_DEPTH = 2` 提升为可配置值（默认 2）。

深度 0 = 主 Agent，深度 1 = 专家 Agent，深度 2 = 专家 Agent 的嵌套委托（仅当 `canDelegate=true`）。

---

## 6. 预设专家 Agent

模块 21 提供以下预设专家 Agent（YAML 定义，可由用户修改或扩展）：

| Agent ID | 名称 | 职责 | 偏好模型 | 预算级别 |
|----------|------|------|---------|---------|
| writer | 写作专家 | 周报、邮件、文案、总结 | deepseek-chat | DEFAULT |
| analyst | 分析专家 | 数据分析、趋势解读、对比评估 | deepseek-chat | DEFAULT |
| researcher | 调研专家 | 信息检索、资料整理、知识汇总 | deepseek-chat | HEAVYWEIGHT |

预设 Agent 以 `AgentSource.Builtin` 来源注册，用户自定义 Agent 以 `AgentSource.YamlDefined` 来源注册。

---

## 7. 配置项

```yaml
lifepilot:
  agent:
    multi-agent:
      enabled: true
      max-delegation-depth: 2
      agent-definitions-path: ~/.lifepilot/agents/
      register-handoff-tools: true
      budget:
        default-max-tokens: 16000
        default-max-steps: 15
        default-timeout-seconds: 180
```

---

## 8. 调研参考

### 8.1 前沿理论与框架

| 来源 | 核心洞察 | LifePilot 采纳 |
|------|---------|---------------|
| [OpenAI Swarm / Agents SDK](https://github.com/openai/swarm) | Handoff 模式：Agent 通过工具调用委托，共享对话历史 | 采纳 HandoffTool 模式，但选择 agents-as-tools 而非完全 handoff |
| [Spring AI Agent Utils (TaskTool)](https://spring.io/blog/2025/06/18/spring-ai-agentic-patterns-part-4) | 层级 SubAgent：主 Agent 通过 Task 工具委托，子 Agent 独立上下文和工具集 | 采纳独立上下文 + 工具白名单隔离 |
| [AstrBot SubAgent](https://github.com/Soulter/AstrBot) | `transfer_to_<name>` 工具模式，SubAgent 有独立 Provider 覆盖 | 采纳 `handoff_to_{id}` 命名和 preferredProvider 机制 |

### 8.2 开源项目对比

| 项目 | Agent 协作模式 | 优势 | 局限 |
|------|--------------|------|------|
| CrewAI | 角色团队，顺序/并行执行 | 快速搭建团队 | 灵活性不足，难以动态调整 |
| LangGraph | 有状态图，精确控制流 | 控制力强 | 学习曲线陡峭 |
| AutoGen (已维护模式) | 对话式多 Agent | 自然交互 | 已被 Microsoft Agent Framework 替代 |
| OpenClaw | 单 Agent + Tool Use 循环 | 简洁高效 | 无多 Agent 支持 |

### 8.3 A2A 协议（模块 22 预留）

Google A2A (Agent-to-Agent) 协议提供跨系统 Agent 互操作能力，包括 Agent Card 能力声明、HTTP+JSON 消息传递。本模块不实现 A2A，但 AgentDefinition 的 `metadata` 字段预留了未来转换为 Agent Card 的扩展空间。

> 内容已重新组织表述以符合许可要求。参考来源均为 2025-2026 年发表的技术文章和开源项目。

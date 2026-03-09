# Agent Skills 技能系统架构设计

> **文档性质**：深度架构设计文档（Developer-Facing）
> **目标读者**：核心开发者、架构评审者
> **模块归属**：`com.lifepilot.skill`
> **最后更新**：2026-03
> **从属关系**：本文档从 [ARCHITECTURE.md](../ARCHITECTURE.md) §4 拆分而来，聚焦 Agent Skills 技能系统的完整设计。

---

## 目录

- [1. 设计哲学与原则](#1-设计哲学与原则)
- [2. SkillDefinition — 技能定义数据模型](#2-skilldefinition--技能定义数据模型)
- [3. 四种 Skill 来源](#3-四种-skill-来源)
- [4. SkillRegistry — 技能注册中心](#4-skillregistry--技能注册中心)
- [5. Skill 激活机制 — 上下文注入](#5-skill-激活机制--上下文注入)
- [6. Skill 文件夹结构与 SKILL.md 格式](#6-skill-文件夹结构与-skillmd-格式)
- [7. Skill 自扩展机制](#7-skill-自扩展机制)
- [8. SkillToToolBridge — 技能发现与激活入口](#8-skilltoolbridge--技能发现与激活入口)
- [9. 安全与验证](#9-安全与验证)
- [10. SQLite Schema 与 Flyway 迁移](#10-sqlite-schema-与-flyway-迁移)
- [11. 配置参考](#11-配置参考)
- [12. 与 Agent / Tool / MCP 的关系](#12-与-agent--tool--mcp-的关系)
- [13. 测试策略](#13-测试策略)

---

## 1. 设计哲学与原则

### 1.1 核心定位：Skill = 程序性知识包（Procedural Knowledge Package）


2025-2026 年 AI Agent 领域形成了一个关键共识：**MCP 提供"专业工具"（Agent 能做什么），Skill 提供"操作手册"（Agent 应该怎么做）**。

Skill 不是独立的执行实体，不是 SubAgent，也不是工具的别名。Skill 是一个**程序性知识包**——包含领域专业指令、参考资料、脚本和资产文件。当 Skill 被激活时，它的指令被注入到 Agent 的上下文中，Agent 使用 LLM 按照这些指令完成任务。

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    能力抽象的三层模型                                      │
│                                                                         │
│  Layer 1: Tool / MCP（做什么）                                           │
│    └─ 原子操作：searchMemory, createDocument, queryKnowledgeBase        │
│    └─ 每个 Tool 是一个函数，有明确的输入/输出 Schema                      │
│    └─ MCP 是 Tool 的标准化协议                                           │
│                                                                         │
│  Layer 2: Skill（怎么做）                                                │
│    └─ 程序性知识包：指令 + 参考资料 + 脚本 + 资产                         │
│    └─ 激活时注入 Agent 上下文，指导 Agent 如何组合 Tool 完成任务           │
│    └─ 类比：医生的"诊疗规范"，不是医生本人                                │
│                                                                         │
│  Layer 3: Agent（谁来做）                                                │
│    └─ 独立推理实体：有自己的 LLM 调用、上下文窗口、预算                    │
│    └─ 需要独立人格和独立决策能力的角色（写作助手、健身教练）               │
│    └─ 由多 Agent 协作模块（模块 21）的 AgentDefinition 定义               │
└─────────────────────────────────────────────────────────────────────────┘
```

这个三层模型的关键区分：

| 维度 | Tool / MCP | **Skill** | Agent |
|------|-----------|-----------|-------|
| **本质** | 原子操作 | 程序性知识包 | 独立推理实体 |
| **执行方式** | 函数调用 | 指令注入 Agent 上下文 | 独立 LLM 调用循环 |
| **有无 LLM** | 无 | 无（借用宿主 Agent 的 LLM） | 有（独立 LLM 调用） |
| **上下文** | 无 | 注入宿主 Agent 上下文 | 独立上下文窗口 |
| **预算** | 无 | 共享宿主 Agent 预算 | 独立预算 |
| **记忆** | 无 | 共享宿主 Agent 记忆 | 可独立记忆空间 |
| **生命周期** | 调用即结束 | 注入→任务完成→卸载 | 启动→多轮推理→终止 |
| **适用场景** | 查天气、读文件 | 写周报、做财务分析 | 写作助手、健身教练 |

### 1.2 前沿研究基础

#### 1.2.1 Anthropic Agent Skills 开放标准 — 业界事实标准

2025 年 12 月，Anthropic 发布 Agent Skills 开放标准，48 小时内被 Microsoft、OpenAI 采纳，随后 Vercel、Weaviate 等跟进。截至 2026 年 3 月，已被 30+ 产品采用（Claude Code、VS Code、GitHub、OpenAI Codex、Cursor、Gemini CLI 等），成为事实上的行业标准。

核心设计：

- **SKILL.md 文件**：Markdown 格式的指令文件，YAML Frontmatter 声明元数据
- **文件夹结构**：`SKILL.md` + `scripts/` + `references/` + `assets/`
- **渐进式发现（Progressive Disclosure）**：三阶段加载，极大降低上下文消耗
- **关键理念**：Skill 是指令包，不是执行实体；激活 = 将指令注入 Agent 上下文

```
渐进式发现（Progressive Disclosure）：

阶段 1: Discovery（发现）
  只加载 name + description → 30-50 Token/Skill
  50 个 Skill 仅消耗 ~2000 Token

阶段 2: Activation（激活）
  加载完整指令 + 工具建议列表 → 500-2000 Token（仅被选中的 Skill）
  指令注入 Agent 的 System Prompt 或上下文

阶段 3: Execution（执行）
  Agent 按照注入的指令，使用 LLM 推理 + 调用 Tool 完成任务
  Skill 本身不执行任何操作，Agent 是执行者
```

ZhiWei 对 Anthropic 标准的映射：

| Anthropic Agent Skills 标准 | ZhiWei 实现 |
|---------------------------|---------------|
| SKILL.md 文件 | `SkillDefinition.instructions()` — 领域专业指令 |
| YAML Frontmatter 元数据 | `SkillDefinition` record 的结构化字段 |
| 文件夹结构（scripts/ + references/ + assets/） | Skill 文件夹：`SKILL.md` + 子目录 |
| Discovery 阶段 | `SkillRegistry.listSummaries()` — 30-50 Token/Skill |
| Activation 阶段 | `SkillActivator.activate()` — 将指令注入 Agent 上下文 |
| Execution 阶段 | Agent 使用 LLM 按指令推理执行 |

#### 1.2.2 Spring AI Agentic Patterns — Skill 与 SubAgent 的明确区分

2026 年 1 月，Spring AI 团队发布 Agentic Patterns 系列博客，明确区分了两个概念：

- **Part 1: Agent Skills** — Skill 是模块化的指令/脚本/资源文件夹，与 LLM 无关。`SkillsTool` 作为发现和激活的统一入口。Skill 的指令在激活时才注入上下文。
- **Part 4: Task SubAgents** — SubAgent 是独立的推理实体，有独立上下文窗口、独立预算、可路由到不同模型。这是完全不同于 Skill 的概念。

这个区分对 ZhiWei 的影响：需要独立推理能力的角色（写作助手、健身教练、财务分析师）应该是 `AgentDefinition`（模块 21），而不是 `SkillDefinition`。

#### 1.2.3 SkillRL — 自动技能发现与递归进化

2026 年 2 月 [SkillRL 论文](https://arxiv.org/abs/2602.08234) 提出从执行轨迹中自动提取可复用技能模式，技能库随 Agent 使用不断进化。

| SkillRL 概念 | ZhiWei 实现 |
|-------------|---------------|
| 自动技能发现 | `SkillGapDetector` — 检测现有 Skill 无法处理的请求 |
| 技能生成 | `SkillGenerator` — LLM 生成 SKILL.md 定义 |
| 递归进化 | `SkillMetricsTracker` — 跟踪 Skill 使用效果 |

#### 1.2.4 Self-Tooling Agent (STA) — 动态工具合成

[Self-Tooling Agent](https://openreview.net/forum?id=VnMcTvEqhd) 提出 Agent 动态合成新工具的思想。ZhiWei 的 Skill 自扩展机制借鉴了这一理念：当现有 Skill 无法处理请求时，`SkillGenerator` 自动生成新的 SKILL.md。

### 1.3 核心设计原则

#### 原则 1：指令注入，非独立执行

Skill 激活 = 将指令注入 Agent 上下文。Agent 使用自己的 LLM 按照指令推理执行。Skill 本身没有 LLM 调用能力，没有独立的执行循环。

```
激活前：Agent 上下文 = [System Prompt] + [用户消息] + [工具列表]
激活后：Agent 上下文 = [System Prompt] + [Skill 指令] + [用户消息] + [工具列表 + Skill 建议工具]
```

#### 原则 2：渐进式发现 — 按需加载

50 个 Skill 全量加载需要 ~25,000 Token。渐进式发现将消耗降至 ~3,500 Token（节省 86%）。

#### 原则 3：工具建议，非工具隔离

Skill 可以声明 `suggestedTools` 列表，建议 Agent 优先使用哪些工具。但这是建议而非强制隔离——Agent 仍然可以使用所有已注册的工具。工具权限隔离是 Agent 级别的概念（由 `AgentDefinition.allowedTools()` 控制）。

#### 原则 4：社区兼容

ZhiWei 的 Skill 格式必须与 Anthropic Agent Skills 开放标准兼容。社区发布的 SKILL.md 文件夹可以直接放入 `~/.zhiwei/skills/` 目录使用，无需任何转换。

#### 原则 5：自扩展安全 — 三重验证 + 用户确认

Agent 自动生成的 Skill 必须通过格式验证 → 安全验证 → 沙箱验证三重管线，首次激活需用户确认。


### 1.4 与旧设计的关键差异

| 维度 | 旧设计（已废弃） | 新设计（对齐业界标准） |
|------|----------------|---------------------|
| Skill 定位 | Agent 能力单元，激活后成为 SubAgent | 程序性知识包，激活时注入 Agent 上下文 |
| 执行方式 | SkillActionDispatcher 确定性执行 | Agent LLM 按指令推理执行 |
| 预算 | SkillBudget 独立三维预算 | 共享宿主 Agent 预算（预算隔离是 Agent 概念） |
| 记忆访问 | MemoryAccessPolicy 声明式隔离 | 共享宿主 Agent 记忆（记忆隔离是 Agent 概念） |
| 执行策略 | ExecutionStrategy（maxSteps/timeout/retry） | 无独立执行策略（执行由 Agent 控制） |
| 工具权限 | allowedTools 强制白名单 | suggestedTools 建议列表（非强制） |
| 类比 | Skill = Class, SubAgent = Object | Skill = 操作手册, Agent = 执行者 |
| 写作助手/健身教练 | SkillDefinition | AgentDefinition（模块 21） |

---

## 2. SkillDefinition — 技能定义数据模型

### 2.1 核心设计

`SkillDefinition` 是 Skill 的结构化表示。它描述了一个程序性知识包的元数据和指令内容。

设计要点：
- **不可变性**：使用 Java 22 `record`，`toBuilder()` 创建新实例
- **轻量化**：移除 SubAgent 相关字段（SkillBudget、MemoryAccessPolicy、ExecutionStrategy），这些属于 AgentDefinition
- **社区兼容**：字段与 Anthropic Agent Skills YAML Frontmatter 对齐

### 2.2 数据模型

```java
@Builder(toBuilder = true)
public record SkillDefinition(
    // === 基本信息（对应 YAML Frontmatter） ===
    String id,              // 唯一标识，如 "writing-assistant"
    String name,            // 显示名称，如 "写作助手"
    String description,     // 能力描述，供 Discovery 阶段使用
    String version,         // 语义化版本号，如 "1.0.0"
    SkillSource source,     // 来源类型

    // === 指令内容（对应 SKILL.md Body） ===
    String instructions,    // 领域专业指令（Markdown 格式）

    // === 工具建议（非强制） ===
    List<String> suggestedTools,  // 建议 Agent 优先使用的工具列表

    // === 元数据 ===
    Map<String, String> metadata  // 扩展元数据（tags, category, author 等）
) {
    public SkillDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Skill ID 不能为空");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Skill 名称不能为空");
        if (instructions == null || instructions.isBlank()) throw new IllegalArgumentException("Skill 指令不能为空");
        suggestedTools = List.copyOf(suggestedTools);
        metadata = Map.copyOf(metadata);
    }

    /** Discovery 阶段摘要（~40 Token）。 */
    public String toDiscoverySummary() {
        return id + ": " + description;
    }
}
```

### 2.3 字段说明

| 字段 | 对应 SKILL.md | 说明 |
|------|-------------|------|
| `id` | Frontmatter `id` | 全局唯一标识，kebab-case |
| `name` | Frontmatter `name` | 显示名称，中文 |
| `description` | Frontmatter `description` | 一句话描述，供 LLM Discovery |
| `version` | Frontmatter `version` | 语义化版本号 |
| `source` | 运行时确定 | Builtin / UserDefined / AutoGenerated / Marketplace |
| `instructions` | Markdown Body | 完整的领域专业指令 |
| `suggestedTools` | Frontmatter `suggested_tools` | 建议工具列表（非强制白名单） |
| `metadata` | Frontmatter 其余字段 | tags, category, author, dependencies 等 |

### 2.4 与旧 SkillDefinition 的字段映射

| 旧字段 | 新字段 | 变更说明 |
|--------|--------|---------|
| `systemPrompt` | `instructions` | 重命名：不再是 System Prompt，而是注入上下文的指令 |
| `allowedTools` | `suggestedTools` | 语义变更：从强制白名单变为建议列表 |
| `execution` | 移除 | ExecutionStrategy 是 Agent 概念，不属于 Skill |
| `memoryAccess` | 移除 | MemoryAccessPolicy 是 Agent 概念，不属于 Skill |
| `budget` | 移除 | SkillBudget 是 Agent 概念，不属于 Skill |
| `metadata` (Map) | `metadata` (Map) | 保留，扩展为承载更多 Frontmatter 字段 |

---

## 3. 四种 Skill 来源

### 3.1 SkillSource sealed interface

```java
public sealed interface SkillSource permits
        SkillSource.Builtin,
        SkillSource.UserDefined,
        SkillSource.AutoGenerated,
        SkillSource.Marketplace {

    /** 内置来源 — Java 代码提供的 Skill 指令。 */
    record Builtin() implements SkillSource {}

    /** 用户定义来源 — SKILL.md 文件夹。 */
    record UserDefined(String folderPath, @Nullable Instant lastModified) implements SkillSource {}

    /** 自动生成来源 — Agent 运行时生成。 */
    record AutoGenerated(
        String generatorTraceId,
        @Nullable Instant generatedAt,
        @Nullable String triggerRequest,
        boolean userConfirmed
    ) implements SkillSource {}

    /** 市场来源 — 从 Skill 市场安装。 */
    record Marketplace(String packageId, String indexSourceUrl, Instant installedAt) implements SkillSource {}

    default int priority() {
        return switch (this) {
            case Builtin _ -> 3;
            case UserDefined _, Marketplace _ -> 2;
            case AutoGenerated _ -> 1;
        };
    }
}
```

### 3.2 来源对比

| 来源 | 定义方式 | 加载时机 | 适用场景 |
|------|---------|---------|---------|
| Builtin | Java 代码提供指令文本 | 系统启动时注册 | 核心能力（待办管理、日程管理等） |
| UserDefined | SKILL.md 文件夹 | 运行时热加载 | 用户自定义 + 社区 Skill |
| AutoGenerated | Agent LLM 生成 | 安全验证后注册 | Agent 自动学习新能力 |
| Marketplace | 从市场安装 | 安装时注册 | 第三方 Skill 分发 |

---

## 4. SkillRegistry — 技能注册中心

### 4.1 职责

- 存储所有已注册的 `SkillDefinition`
- 提供 Discovery 阶段的摘要列表（`listSummaries()`）
- 支持按 ID 精确查找和语义搜索
- 发布注册/注销/更新事件

### 4.2 核心接口

```java
public class SkillRegistry {
    private final ConcurrentHashMap<String, SkillDefinition> skills;

    /** Discovery 阶段：返回所有 Skill 的 id + description 摘要。 */
    public List<String> listSummaries() { ... }

    /** 精确查找。 */
    public Optional<SkillDefinition> find(String skillId) { ... }

    /** 语义搜索：根据用户请求匹配最相关的 Skill。 */
    public List<SkillDefinition> search(String query, int topK) { ... }

    /** 注册 Skill，发布 SkillRegistered 事件。 */
    public void register(SkillDefinition definition) { ... }

    /** 注销 Skill，发布 SkillUnregistered 事件。 */
    public void unregister(String skillId) { ... }
}
```

---

## 5. Skill 激活机制 — 上下文注入

### 5.1 核心流程

Skill 激活的本质是**将 Skill 的 instructions 注入到 Agent 的上下文中**。Agent 的 LLM 按照这些指令推理执行任务。

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Skill 激活流程                                        │
│                                                                         │
│  1. Agent 收到用户请求                                                   │
│     └─ "帮我写一篇关于本周工作进展的周报"                                 │
│                                                                         │
│  2. Agent 查询 SkillRegistry（Discovery 阶段）                           │
│     └─ 获取所有 Skill 摘要，LLM 选择最匹配的 Skill                       │
│     └─ 选中：writing-assistant                                          │
│                                                                         │
│  3. SkillActivator 激活 Skill（Activation 阶段）                         │
│     └─ 从 SkillRegistry 获取完整 SkillDefinition                        │
│     └─ 将 instructions 注入 Agent 上下文                                 │
│     └─ 将 suggestedTools 添加到 Agent 的工具优先列表                     │
│                                                                         │
│  4. Agent 按指令执行（Execution 阶段）                                   │
│     └─ LLM 按照注入的指令推理                                           │
│     └─ 调用 searchMemory 获取本周数据                                    │
│     └─ 调用 createDocument 生成周报                                      │
│     └─ 返回结果给用户                                                    │
│                                                                         │
│  5. Skill 卸载                                                          │
│     └─ 任务完成后，从 Agent 上下文中移除 Skill 指令                       │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.2 SkillActivator

```java
/**
 * Skill 激活器 — 将 Skill 指令注入 Agent 上下文。
 */
public class SkillActivator {

    private final SkillRegistry skillRegistry;
    private final SkillMetricsTracker metricsTracker;

    /**
     * 激活 Skill：从 Registry 获取定义，返回注入内容。
     *
     * @param skillId Skill ID
     * @return SkillActivation 包含注入指令和建议工具
     */
    public Optional<SkillActivation> activate(String skillId) {
        return skillRegistry.find(skillId)
            .filter(this::canActivate)
            .map(def -> new SkillActivation(
                def.id(),
                def.instructions(),
                def.suggestedTools()
            ));
    }

    private boolean canActivate(SkillDefinition def) {
        // 自生成 Skill 需要用户确认
        if (def.source() instanceof SkillSource.AutoGenerated ag && !ag.userConfirmed()) {
            return false;
        }
        return true;
    }
}

/** Skill 激活结果 — 包含注入 Agent 上下文的内容。 */
public record SkillActivation(
    String skillId,
    String instructions,       // 注入 Agent 上下文的指令
    List<String> suggestedTools // 建议优先使用的工具
) {}
```

### 5.3 与 ContextAssembler 的集成

`ContextAssembler` 在组装 Agent 上下文时，检查是否有激活的 Skill，如果有则将 Skill 指令插入到上下文的指定槽位：

```
Agent 上下文结构：
┌─────────────────────────┐
│ System Prompt            │ ← Agent 基础人格
├─────────────────────────┤
│ [Skill Instructions]     │ ← 激活的 Skill 指令（可选）
├─────────────────────────┤
│ Memory Context           │ ← 记忆检索结果
├─────────────────────────┤
│ Conversation History     │ ← 对话历史
├─────────────────────────┤
│ User Message             │ ← 当前用户消息
└─────────────────────────┘
```


---

## 6. Skill 文件夹结构与 SKILL.md 格式

### 6.1 标准文件夹结构

兼容 Anthropic Agent Skills 开放标准：

```
~/.zhiwei/skills/
├── writing-assistant/
│   ├── SKILL.md              # 指令文件（必需）
│   ├── scripts/              # 辅助脚本（可选）
│   │   └── outline-template.sh
│   ├── references/           # 参考资料（可选）
│   │   └── style-guide.md
│   └── assets/               # 资产文件（可选）
│       └── report-template.docx
├── fitness-coach/
│   ├── SKILL.md
│   └── references/
│       └── exercise-database.json
└── exchange-rate/
    └── SKILL.md
```

### 6.2 SKILL.md 格式

```markdown
---
id: writing-assistant
name: 写作助手
description: 擅长长文撰写、润色、翻译，能结合用户的知识库生成高质量内容
version: "1.0.0"
suggested_tools:
  - searchMemory
  - queryKnowledgeBase
  - createDocument
tags:
  - writing
  - content
category: productivity
author: lifepilot
---

# 写作助手

你是一个专业的写作助手。你的写作风格简洁有力，善于用数据说话。

## 工作流程

1. 先从用户的记忆和知识库中获取相关素材
2. 根据素材和用户需求拟定大纲
3. 按大纲逐段撰写
4. 完成后进行润色和校对

## 写作原则

- 数据驱动：用具体数据支撑观点
- 简洁有力：避免冗余表达
- 结构清晰：使用标题和列表组织内容
- 贴合用户：从用户的实际情况出发

## 格式要求

- 周报使用 Markdown 格式
- 包含"本周完成"、"下周计划"、"风险与问题"三个部分
- 每个部分列出 3-5 个要点
```

### 6.3 YAML Frontmatter 字段规范

| 字段 | 必需 | 类型 | 说明 |
|------|------|------|------|
| `id` | 是 | string | 全局唯一标识，kebab-case |
| `name` | 是 | string | 显示名称 |
| `description` | 是 | string | 一句话描述 |
| `version` | 否 | string | 语义化版本号，默认 "1.0.0" |
| `suggested_tools` | 否 | string[] | 建议工具列表 |
| `tags` | 否 | string[] | 标签 |
| `category` | 否 | string | 分类 |
| `author` | 否 | string | 作者 |
| `dependencies` | 否 | string[] | 依赖的其他 Skill ID |

### 6.4 MarkdownSkillParser

解析 SKILL.md 文件，提取 YAML Frontmatter 和 Markdown Body：

```java
public class MarkdownSkillParser {
    /**
     * 解析 SKILL.md 内容为 SkillDefinition。
     *
     * @param content SKILL.md 文件内容
     * @return 解析结果（成功返回 SkillDefinition，失败返回错误列表）
     */
    public ParseResult parse(String content) {
        // 1. 分离 YAML Frontmatter 和 Markdown Body
        // 2. 解析 Frontmatter 为 Map
        // 3. 映射为 SkillDefinition
        // 4. Markdown Body → instructions
    }
}
```

---

## 7. Skill 自扩展机制

### 7.1 工作流程

```
用户请求 → SkillGapDetector 检测 → SkillGenerator 生成 SKILL.md
→ SkillValidationPipeline 三重验证 → 用户确认 → SkillRegistry 注册
```

### 7.2 SkillGapDetector

检测现有 Skill 无法处理的请求。当 SkillRegistry 搜索结果为空或相关度低于阈值时触发。

### 7.3 SkillGenerator

使用 LLM 生成完整的 SKILL.md 文件内容（YAML Frontmatter + Markdown 指令）。

### 7.4 SkillValidationPipeline

三重验证管线：

1. **FormatValidator** — YAML 格式校验、必填字段检查、值范围校验
2. **SecurityValidator** — 工具建议列表检查、指令注入检测
3. **SandboxValidator** — 隔离环境模拟验证

---

## 8. SkillToToolBridge — 技能发现与激活入口

### 8.1 设计变更

旧设计中，`SkillToToolBridge` 将每个 Skill 包装为 `BuiltinTool`，executor 委托给 `SkillLifecycleManager.activate()` 执行。这意味着 Skill 绕过了 LLM 推理，直接通过 `SkillActionDispatcher` 确定性执行。

新设计中，`SkillToToolBridge` 注册一个统一的 `skills` 工具（类似 Spring AI 的 `SkillsTool`），提供两个操作：

- `list_skills` — 返回所有 Skill 的 Discovery 摘要
- `activate_skill` — 激活指定 Skill，将指令注入 Agent 上下文

```java
/**
 * Skill 工具桥接 — 提供统一的 Skill 发现和激活入口。
 *
 * <p>注册一个 "skills" 工具到 DynamicToolRegistry，
 * Agent 通过调用此工具发现和激活 Skill。</p>
 */
public class SkillToToolBridge {

    /** 注册 skills 工具。 */
    public void registerSkillsTool() {
        BuiltinTool skillsTool = BuiltinTool.builder()
            .id("skills")
            .name("Skill 管理")
            .description("发现和激活 Skill。list_skills 查看可用 Skill，activate_skill 激活指定 Skill。")
            .executor(input -> {
                String action = input.getRequiredParam("action", String.class);
                return switch (action) {
                    case "list_skills" -> listSkills();
                    case "activate_skill" -> activateSkill(input);
                    default -> ToolResult.error("未知操作: " + action);
                };
            })
            .build();
        toolRegistry.registerBuiltinTool(skillsTool);
    }
}
```

### 8.2 与旧设计的对比

| 维度 | 旧设计 | 新设计 |
|------|--------|--------|
| 工具数量 | 每个 Skill 一个工具（skill.{id}） | 一个统一的 skills 工具 |
| 执行方式 | executor 委托 SkillActionDispatcher | activate 返回指令，Agent LLM 执行 |
| LLM 参与 | 不参与（确定性执行） | 全程参与（按指令推理） |
| 上下文消耗 | N 个工具 Schema | 1 个工具 Schema |

---

## 9. 安全与验证

### 9.1 自生成 Skill 安全管线

```
FormatValidator → SecurityValidator → SandboxValidator → UserConfirmation
```

- **FormatValidator**：YAML Schema 校验、必填字段、值范围
- **SecurityValidator**：指令注入检测、危险工具建议检查
- **SandboxValidator**：隔离环境模拟验证
- **UserConfirmation**：首次激活需用户确认

### 9.2 Skill 指令安全

Skill 指令注入 Agent 上下文后，由 Agent 的 GuardrailEngine 统一管控。Skill 本身不绕过任何安全机制。

---

## 10. SQLite Schema 与 Flyway 迁移

Skill 注册信息持久化到 SQLite：

```sql
CREATE TABLE IF NOT EXISTS skills (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT NOT NULL,
    version     TEXT NOT NULL DEFAULT '1.0.0',
    source_type TEXT NOT NULL,  -- BUILTIN / USER_DEFINED / AUTO_GENERATED / MARKETPLACE
    source_json TEXT,           -- 来源详情 JSON
    instructions TEXT NOT NULL,
    suggested_tools_json TEXT NOT NULL DEFAULT '[]',
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
```

---

## 11. 配置参考

```yaml
lifepilot:
  skill:
    # Skill 文件夹路径
    skills-dir: ${user.home}/.lifepilot/skills
    # 热加载扫描间隔（秒）
    watch-interval: 5
    # 自扩展开关
    auto-generation:
      enabled: true
      # 自生成 Skill 存储路径
      output-dir: ${user.home}/.lifepilot/skills/auto
    # 最大注册 Skill 数量
    max-registered: 100
    # Discovery 搜索返回数量
    search-top-k: 5
```

---

## 12. 与 Agent / Tool / MCP 的关系

### 12.1 三层能力模型

```
┌─────────────────────────────────────────────┐
│  Agent（模块 21）                             │
│  独立推理实体，有自己的 LLM、上下文、预算      │
│  适用：写作助手、健身教练、财务分析师          │
│  定义：AgentDefinition                        │
├─────────────────────────────────────────────┤
│  Skill（模块 10）                             │
│  程序性知识包，注入 Agent 上下文               │
│  适用：周报写作规范、代码审查流程、数据分析方法 │
│  定义：SkillDefinition                        │
├─────────────────────────────────────────────┤
│  Tool / MCP（模块 3-4）                       │
│  原子操作，函数调用                            │
│  适用：searchMemory, createDocument, httpCall │
│  定义：ToolContract / MCP Server              │
└─────────────────────────────────────────────┘
```

### 12.2 何时用 Skill vs Agent vs Tool

| 需求 | 选择 | 原因 |
|------|------|------|
| 查天气 | Tool | 单次函数调用 |
| 写周报 | Skill | 需要领域指令指导，但不需要独立推理 |
| 写作助手（持续对话） | Agent | 需要独立人格、独立上下文、多轮推理 |
| 代码审查 | Skill | 需要审查规范指令，但执行由宿主 Agent 完成 |
| 健身教练 | Agent | 需要独立人格、记忆隔离、长期跟踪 |

### 12.3 内置 Skill 的重新定位

旧设计中的内置 Skill（Todo/Schedule/Habit/Memory）本质上是 Tool 的封装——它们通过 `SkillActionDispatcher` 确定性执行，不涉及 LLM 推理。在新设计中：

- **Todo/Schedule/Habit 管理**：应作为 Tool（原子操作），通过 `BuiltinTool` 直接注册
- **Memory 管理**：已有 `searchMemory` 等 Tool，无需额外 Skill
- **真正的内置 Skill**：应提供领域指令，如"待办管理最佳实践"、"日程规划方法论"

---

## 13. 测试策略

### 13.1 单元测试

- `MarkdownSkillParser` 解析各种 SKILL.md 格式
- `SkillRegistry` 注册/注销/搜索
- `SkillActivator` 激活逻辑
- `SkillValidationPipeline` 三重验证

### 13.2 集成测试

- Skill 文件夹热加载端到端流程
- Skill 激活后 Agent 上下文注入验证
- 自生成 Skill 完整管线（Gap 检测 → 生成 → 验证 → 注册）

### 13.3 社区兼容性测试

- 使用 Anthropic Agent Skills 标准的示例 SKILL.md 文件验证解析兼容性
- 验证社区 Skill 文件夹可直接放入 `~/.zhiwei/skills/` 使用

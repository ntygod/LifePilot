# 元能力系统架构设计

> **文档性质**：深度架构设计文档（Developer-Facing）
> **目标读者**：核心开发者、架构评审者
> **模块归属**：`com.lifepilot.meta`（新增）+ 跨模块集成
> **最后更新**：2026-03
> **从属关系**：本文档描述元能力系统的完整架构设计，与 [特性文档](../features/meta-capabilities.md) 配套。

---

## 目录

- [1. 模块定位与职责边界](#1-模块定位与职责边界)
- [2. 核心概念与术语](#2-核心概念与术语)
- [3. 架构设计](#3-架构设计)
- [4. 基础工具子系统](#4-基础工具子系统)
- [5. 系统自省子系统](#5-系统自省子系统)
- [6. Skill 发现与安装子系统](#6-skill-发现与安装子系统)
- [7. MCP 发现与配置子系统](#7-mcp-发现与配置子系统)
- [8. 引导 Agent](#8-引导-agent)
- [9. 统一工具权限模型](#9-统一工具权限模型)
- [10. 与已有模块的集成点](#10-与已有模块的集成点)
- [11. 关键设计决策](#11-关键设计决策)
- [12. 配置参考](#12-配置参考)
- [13. 调研参考](#13-调研参考)

---

## 1. 模块定位与职责边界

### 1.1 定位

元能力系统是 ZhiWei 的**自扩展基础设施层**，位于业务 Skill 之下、Agent 引擎之上。它不提供具体的业务功能（查天气、记待办），而是提供让系统获取新能力的能力 — 自省、发现、安装、配置。

类比操作系统：业务 Skill 是应用程序，元能力是应用商店 + 系统设置 + 帮助中心。

### 1.2 职责边界

**本模块负责**：
- 基础工具（Infrastructure Tools）— Agent 的基本感官（时间、计算、搜索、交互）
- 系统自省 — 聚合各注册中心信息，让 Agent 了解自身能力
- Skill 发现与安装 — 从 ZhiWei Marketplace + 开源生态搜索、安装 Skill
- MCP 发现与配置 — 从 MCP Registry / Smithery 搜索、配置 MCP Server
- 引导 Agent — 新用户引导、场景推荐
- 统一工具权限模型 — 三层权限解析（全局策略 → 调用者作用域 → 工具属性）

**本模块不负责**：
- Skill 自扩展（`SkillGenerator` / `SkillGapDetector`，已在 Skill 系统模块实现）
- MCP 协议实现（`McpClient`，已在 MCP 模块实现）
- 工具执行管线（`ToolExecutionPipeline`，已在工具系统模块实现）
- 护栏策略定义（`GuardrailPolicy`，已在可观测性模块实现）

### 1.3 与 Skill 自扩展的关系

| 维度 | Skill 发现（元能力） | Skill 自扩展（Skill 系统） |
|------|---------------------|--------------------------|
| 来源 | 外部市场 / 开源生态 | Agent 运行时 LLM 生成 |
| 质量 | 经过社区验证和安全审计 | 即时创建，三重验证 |
| 触发 | 用户主动搜索或 Agent 检测能力缺口 | Agent 检测到 Skill 发现无结果后触发 |
| 优先级 | 高（优先使用已验证的 Skill） | 低（市场没有时的兜底方案） |

---

## 2. 核心概念与术语

| 术语 | 定义 |
|------|------|
| Infrastructure Tool | 基础工具，Agent 的基本感官，不属于任何业务 Skill，始终可用 |
| Meta Skill | 元能力 Skill，以 Java 原生 `BuiltinSkillProvider` 形式实现的自省/发现/配置能力 |
| 一切皆 Tool | 核心原则：LLM 只能调用 `DynamicToolRegistry` 中的 Tool；Skill 和 Agent 通过桥接机制转换为 Tool |
| Caller Scope | 调用者作用域，每种调用者（Agent/Skill/Workflow）的工具白名单 |
| Infrastructure 豁免 | 标记为 `infrastructure` 的工具绕过 Layer 2 白名单过滤，始终对所有调用者可用 |
| 格式适配层 | 将外部 Skill 格式（Anthropic Agent Skills 标准等）转换为 ZhiWei Markdown Frontmatter 格式 |

---

## 3. 架构设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         用户自然语言                                  │
└──────────────────────────────┬──────────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      AgentLoop（主 Agent）                           │
│  LLM 从 DynamicToolRegistry 中选择工具调用                            │
└──────────┬──────────┬──────────┬──────────┬─────────────────────────┘
           │          │          │          │
           ▼          ▼          ▼          ▼
┌──────────────┐┌──────────┐┌──────────┐┌──────────────────────────┐
│ Infrastructure││ Meta     ││ Domain   ││ Dynamic Tools            │
│ Tools        ││ Skills   ││ Skills   ││ (Markdown Skill + MCP)   │
│              ││          ││          ││                          │
│ • datetime   ││ • 自省    ││ • Todo   ││ • 用户自定义 Skill        │
│ • calculate  ││ • Skill  ││ • Sched  ││ • MCP External Tools     │
│ • think      ││   发现   ││ • Habit  ││                          │
│ • search     ││ • MCP    ││ • Memory ││                          │
│ • fetch      ││   发现   ││ • Sync   ││                          │
│ • confirm    ││          ││          ││                          │
│ • choose     ││          ││          ││                          │
│ • input      ││          ││          ││                          │
├──────────────┤├──────────┤├──────────┤├──────────────────────────┤
│ tags:        ││ tags:    ││ tags:    ││ tags:                    │
│ infrastructure││ meta    ││ domain   ││ skill / mcp              │
└──────────────┘└──────────┘└──────────┘└──────────────────────────┘
           │          │          │          │
           └──────────┴──────────┴──────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    DynamicToolRegistry（统一注册中心）                 │
│  所有工具统一注册，AgentLoop 不关心工具来源                             │
├─────────────────────────────────────────────────────────────────────┤
│  权限过滤层（ToolBridgeAgentToolProvider）                           │
│  • Layer 2: allowedToolIds 白名单过滤                                │
│  • Infrastructure 豁免: tags 含 "infrastructure" 的工具始终通过       │
├─────────────────────────────────────────────────────────────────────┤
│  护栏层（GuardrailEngine）                                          │
│  • Layer 1: 全局策略检查（风险审批 / 预算限制 / 速率限制 / 内容安全）   │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 子系统划分

元能力系统由六个子系统组成，按依赖关系分层：

```
Layer 0（零依赖）: 基础工具（InfraToolProvider）
Layer 1（依赖注册中心）: 系统自省（SystemIntrospectionSkillProvider）
Layer 2（依赖市场/外部API）: Skill 发现 + MCP 发现
Layer 3（依赖自省+发现）: 引导 Agent
横切关注点: 统一工具权限模型（改动现有代码，非独立子系统）
```

### 3.3 包结构

```
com.lifepilot.meta/
├── infra/                          # 基础工具子系统
│   ├── InfraToolProvider.java      # BuiltinSkillProvider 实现
│   ├── DateTimeToolExecutor.java   # builtin.env.datetime
│   ├── UserProfileToolExecutor.java# builtin.env.user-profile
│   ├── WebSearchToolExecutor.java  # builtin.web.search
│   ├── WebFetchToolExecutor.java   # builtin.web.fetch
│   ├── ThinkToolExecutor.java      # builtin.reason.think
│   ├── CalculateToolExecutor.java  # builtin.reason.calculate
│   ├── ConfirmToolExecutor.java    # builtin.interact.confirm
│   ├── ChooseToolExecutor.java     # builtin.interact.choose
│   └── InputToolExecutor.java      # builtin.interact.input
├── introspection/                  # 系统自省子系统
│   ├── SystemIntrospectionSkillProvider.java
│   ├── CapabilityAggregator.java   # 聚合各注册中心信息
│   └── CapabilitySummary.java      # 能力摘要 record
├── discovery/                      # 发现子系统
│   ├── skill/                      # Skill 发现
│   │   ├── SkillDiscoverySkillProvider.java
│   │   ├── ExternalSkillSource.java       # 外部源接口
│   │   ├── LobeHubSkillSource.java        # LobeHub 适配
│   │   ├── SkillFormatAdapter.java        # 格式转换
│   │   └── SkillSearchResult.java         # 搜索结果 record
│   └── mcp/                        # MCP 发现
│       ├── McpDiscoverySkillProvider.java
│       ├── McpRegistryClient.java         # 注册中心客户端
│       ├── SmitheryRegistryClient.java    # Smithery 适配
│       └── McpServerInfo.java             # Server 信息 record
├── onboarding/                     # 引导 Agent
│   └── onboarding-guide.md         # Markdown Agent 定义
└── config/
    ├── MetaAutoConfiguration.java  # Spring 自动配置
    └── MetaProperties.java         # 配置属性
```

---

## 4. 基础工具子系统

### 4.1 设计目标

提供 Agent 正常运作所需的基础感官能力 — 时间感知、数学计算、网络搜索、用户交互。这些工具不属于任何业务 Skill，始终对所有调用者可用。

### 4.2 InfraToolProvider

`InfraToolProvider` 实现 `BuiltinSkillProvider` 接口，系统启动时通过 `MetaAutoConfiguration` 注册。与业务 SkillProvider（Todo/Schedule/Habit/Memory）同级，但 `tags` 统一为 `["infrastructure"]`。

```java
public class InfraToolProvider implements BuiltinSkillProvider {
    @Override
    public String skillId() { return "builtin.infrastructure"; }

    @Override
    public List<BuiltinTool> provideTools() {
        return List.of(
            dateTimeTool(),      // builtin.env.datetime
            userProfileTool(),   // builtin.env.user-profile
            webSearchTool(),     // builtin.web.search
            webFetchTool(),      // builtin.web.fetch
            thinkTool(),         // builtin.reason.think
            calculateTool(),     // builtin.reason.calculate
            confirmTool(),       // builtin.interact.confirm
            chooseTool(),        // builtin.interact.choose
            inputTool()          // builtin.interact.input
        );
    }
}
```

### 4.3 各工具实现要点

| 工具 | 实现类 | 核心逻辑 | 外部依赖 |
|------|--------|---------|---------|
| `datetime` | `DateTimeToolExecutor` | `ZonedDateTime.now()` + 用户时区配置 | 无 |
| `user-profile` | `UserProfileToolExecutor` | 从 `MetaProperties` + L3 语义记忆聚合 | MemoryRetriever（可选） |
| `web.search` | `WebSearchToolExecutor` | HTTP 调用搜索引擎 API（Google/Bing/DuckDuckGo 可配置） | 搜索引擎 API Key |
| `web.fetch` | `WebFetchToolExecutor` | Jsoup 解析 HTML，提取正文 + 结构化数据 | 无（Jsoup 内嵌） |
| `think` | `ThinkToolExecutor` | 接收思考内容，返回确认。不输出给用户，仅记录到 TraceContext | 无 |
| `calculate` | `CalculateToolExecutor` | `BigDecimal` 精确运算 + 日期差计算 | 无 |
| `confirm` | `ConfirmToolExecutor` | 通过 Channel 向用户展示确认对话框，阻塞等待响应 | Channel 适配器 |
| `choose` | `ChooseToolExecutor` | 通过 Channel 展示选项列表，阻塞等待用户选择 | Channel 适配器 |
| `input` | `InputToolExecutor` | 通过 Channel 请求用户输入特定信息 | Channel 适配器 |

### 4.4 Infrastructure 标签语义

所有基础工具的 `tags` 包含 `"infrastructure"`，这个标签在 `ToolBridgeAgentToolProvider` 的过滤逻辑中具有特殊含义：

```java
// ToolBridgeAgentToolProvider.getToolCallbacks() 中的过滤逻辑
tools.stream()
    .filter(t -> allowedToolIds.contains(t.id())
                 || t.tags().contains("infrastructure"))
    .toList();
```

效果：即使调用者的 `allowedToolIds` 白名单中没有列出基础工具，它们仍然可见。这确保了 Agent 的基本感官不会被意外剥夺。

---

## 5. 系统自省子系统

### 5.1 设计目标

聚合 ZhiWei 各注册中心的信息，让 Agent 能准确回答"你能做什么？"类问题。自省是元能力的入口 — 用户了解系统能力后，才会产生扩展需求。

### 5.2 CapabilityAggregator

核心聚合器，从五个注册中心拉取信息并统一为 `CapabilitySummary`：

```java
public record CapabilitySummary(
    List<SkillInfo> skills,       // SkillRegistry
    List<AgentInfo> agents,       // AgentRegistry
    List<ToolInfo> tools,         // DynamicToolRegistry
    List<WorkflowInfo> workflows, // WorkflowRegistry
    List<McpServerInfo> mcpServers // McpClient 连接池
) {}
```

聚合策略：
- **懒加载 + 缓存**：首次调用时聚合，缓存 TTL 可配置（默认 60s）
- **分类过滤**：支持按类型（skill/agent/mcp/workflow/tool）过滤
- **状态标注**：每个能力标注当前状态（active/inactive/error）

### 5.3 SystemIntrospectionSkillProvider

以 `BuiltinSkillProvider` 形式注册，提供四个工具：

| 工具 ID | 实现逻辑 |
|---------|---------|
| `system.list-capabilities` | 调用 `CapabilityAggregator.aggregate()`，按类型分组返回 |
| `system.explain` | 根据能力 ID 定位到具体注册中心，返回详细元数据 |
| `system.status` | 聚合 LLM 连接状态、MCP Server 状态、各注册中心计数、记忆统计 |
| `system.suggest` | 接收用户需求描述，在已有能力中做语义匹配（基于描述文本相似度） |

### 5.4 与引导 Agent 的协作

引导 Agent（§8）的 `allowedTools` 中包含 `system.*` 工具，使其能基于实时注册状态回答用户问题，而非依赖静态文本。

---

## 6. Skill 发现与安装子系统

### 6.1 设计目标

封装已有的 `MarketplaceService` 基础设施，并扩展外部开源 Skill 生态支持，让用户通过对话即可搜索、评估、安装 Skill。

### 6.2 ExternalSkillSource 接口

```java
public sealed interface ExternalSkillSource
    permits LifePilotMarketplaceSource, LobeHubSkillSource, GitHubSkillSource {

    /** 搜索 Skill。 */
    List<SkillSearchResult> search(String keyword, int limit);

    /** 获取 Skill 详情。 */
    Optional<SkillDetail> detail(String packageId);

    /** 下载 Skill 原始内容。 */
    Optional<RawSkillContent> download(String packageId);

    /** 来源标识。 */
    String sourceId();
}
```

### 6.3 SkillFormatAdapter

将外部 Skill 格式转换为 ZhiWei Markdown Frontmatter 格式：

```
外部 Skill（Anthropic Agent Skills 标准等）
    │
    ▼
SkillFormatAdapter.adapt(RawSkillContent)
    │
    ▼
ZhiWei SKILL.md（YAML Frontmatter + Markdown 指令体）
    │
    ▼
SkillValidationPipeline（格式 → 安全 → 沙箱）
    │
    ▼
MarkdownSkillLoader 加载 → SkillRegistry 注册
```

适配层尽量薄 — 只做元数据映射和格式转换，不修改 Skill 的指令内容。

### 6.4 SkillDiscoverySkillProvider

以 `BuiltinSkillProvider` 形式注册，提供五个工具（`skill-discovery.search/detail/install/uninstall/check-updates`）。

安装流程：
1. `search` → 多源聚合搜索结果，按相关度排序
2. `detail` → 展示详细信息（功能、工具列表、安全评级）
3. `install` → `download` → `SkillFormatAdapter.adapt()` → `SkillValidationPipeline` → 写入用户 Skill 目录 → `SkillFileWatcher` 自动检测并加载

### 6.5 安全约束

- 外部来源 Skill 首次安装标记为 HIGH 风险，触发 `builtin.interact.confirm` 请求用户确认
- 所有外部 Skill 必须经过 `SkillValidationPipeline` 三重验证（格式 → 安全 → 沙箱）
- `SecurityValidator` 校验 Skill 的 `allowedTools` 不含 HIGH/CRITICAL 风险工具
- 安装记录持久化到 `installed_skills` 表，支持回溯和卸载

---

## 7. MCP 发现与配置子系统

### 7.1 设计目标

让用户通过对话搜索、安装、配置 MCP Server，无需手动编辑 `application.yml`。

### 7.2 McpRegistryClient 接口

```java
public sealed interface McpRegistryClient
    permits OfficialMcpRegistryClient, SmitheryRegistryClient {

    /** 搜索 MCP Server。 */
    List<McpServerInfo> search(String keyword, int limit);

    /** 获取安装指令。 */
    Optional<McpInstallInstruction> installInstruction(String serverId);

    /** 来源标识。 */
    String registryId();
}
```

### 7.3 McpDiscoverySkillProvider

以 `BuiltinSkillProvider` 形式注册，提供五个工具（`mcp-discovery.search/install/list/remove/test`）。

安装流程：
1. `search` → 查询 MCP 官方 Registry + Smithery
2. `install` → 检查前置条件（npx/uv/Docker）→ 引导用户配置环境变量 → 写入 `application.yml` → `McpClient.initialize()` → `listTools()` 确认连接
3. 连接测试失败时自动回滚配置变更

### 7.4 安全约束

- MCP Server 安装为 HIGH 风险操作，需用户确认
- API Key 等敏感信息引导用户通过环境变量设置，不写入配置文件
- 安装前检查前置条件可用性
- 配置变更支持原子回滚（写入前备份，失败时恢复）

---

## 8. 引导 Agent

### 8.1 设计目标

新用户首次使用 ZhiWei 时，引导 Agent 主动介绍系统能力，根据用户场景推荐 Skill 组合和配置方案。

### 8.2 实现方式

Markdown 定义的预设 Agent（`onboarding-guide.md`），放置在 `src/main/resources/preset-agents/` 目录，系统启动时通过 `AgentMarkdownLoader` 自动加载注册到 `AgentRegistry`。

Markdown Agent 定义结构：

```yaml
---
id: onboarding-guide
name: 引导助手
description: 新用户引导，介绍系统能力并推荐个性化配置
system-prompt: |
  你是 ZhiWei 的引导助手。你的职责是...
allowed-tools:
  - system.list-capabilities
  - system.explain
  - system.suggest
  - system.status
  - skill-discovery.search
  - mcp-discovery.search
  - builtin.interact.choose
can-delegate: false
preferred-provider: null
budget:
  max-steps: 20
  max-tokens: 8000
---
```

### 8.3 触发机制

- **首次启动**：检测到用户无历史对话记录时，自动激活引导 Agent
- **手动触发**：用户说"帮我重新设置"或"重新引导"时，通过 `handoff_to_onboarding-guide` 工具委托
- **能力缺口**：Agent 检测到用户需求无法满足时，推荐引导 Agent 协助发现新能力

### 8.4 引导流程

```
首次启动
    │
    ▼
自我介绍 + 询问使用场景
    │
    ▼
调用 system.list-capabilities 获取当前能力
    │
    ▼
根据场景推荐 Skill/Agent/Workflow 组合
    │
    ▼
调用 builtin.interact.choose 让用户选择
    │
    ▼
引导安装推荐的扩展（Skill 发现 / MCP 发现）
    │
    ▼
确认配置完成，切回主 Agent
```

---

## 9. 统一工具权限模型

### 9.1 设计目标

将分散在各模块的权限机制统一为三层模型，确保权限只能收窄、不能扩大，同时保证基础工具始终可用。

### 9.2 三层模型详解

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: 全局策略（GuardrailEngine）                        │
│  所有调用者都必须经过，不可绕过                                │
│  • ToolRiskPolicy — 风险等级 → 审批模式                      │
│  • BudgetLimitPolicy — Token 预算限制                        │
│  • RateLimitPolicy — 速率限制                                │
│  • ContentSafetyPolicy — 内容安全                            │
│  • DataRedactionPolicy — 数据脱敏                            │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: 调用者作用域（ToolBridgeAgentToolProvider）         │
│  每种调用者类型有自己的工具白名单                               │
│  • Agent: AgentDefinition.allowedTools                       │
│  • Skill: SkillDefinition.allowedTools                       │
│  • Workflow: 步骤级 required-tools                           │
│  • SubAgent: parentScope ∩ selfScope                         │
├─────────────────────────────────────────────────────────────┤
│  Layer 3: 工具自身属性（ToolContract.tags/riskLevel）         │
│  • tags: "infrastructure" → 绕过 Layer 2 白名单              │
│  • riskLevel → 决定 Layer 1 审批模式                         │
│  • tags: "handoff" → 受 canDelegate 控制                     │
└─────────────────────────────────────────────────────────────┘
```

### 9.3 各调用者的有效工具集

| 调用者 | 有效工具集公式 | 实现位置 |
|--------|--------------|---------|
| 主 Agent | `(allowedTools ∪ infrastructure) ∩ 全局策略` | `ToolBridgeAgentToolProvider` |
| SubAgent | `(parentScope ∩ selfScope ∪ infrastructure) ∩ 全局策略` | `AgentExecutor.buildAllowedToolIds()` |
| Skill | `(allowedTools ∪ infrastructure) ∩ 全局策略` | `SkillLifecycleManager.activate()` |
| Workflow | `(required-tools ∪ infrastructure) ∩ 全局策略` | `WorkflowEngine`（待增强） |

### 9.4 Infrastructure 豁免的实现

当前 `ToolBridgeAgentToolProvider.getToolCallbacks()` 按 `allowedToolIds` 过滤工具。需要增加 infrastructure 标签豁免：

```java
// 改动前（当前代码）
tools = tools.stream()
    .filter(t -> allowedToolIds.contains(t.id()))
    .toList();

// 改动后
tools = tools.stream()
    .filter(t -> allowedToolIds.contains(t.id())
                 || t.tags().contains("infrastructure"))
    .toList();
```

### 9.5 SubAgent 作用域约束

当前 `AgentExecutor.buildAllowedToolIds()` 只做存在性检查和自递归排除。需要增加父 Agent 作用域交集约束：

```java
// 改动：增加 parentAllowedToolIds 参数
private List<String> buildAllowedToolIds(
        AgentDefinition definition, Set<String> parentScope) {
    // ... 现有逻辑 ...
    // 新增：如果 parentScope 非空，取交集
    if (parentScope != null && !parentScope.isEmpty()) {
        result.retainAll(parentScope);
    }
    // infrastructure 工具始终保留
    toolRegistry.getToolSnapshot().stream()
        .filter(t -> t.tags().contains("infrastructure"))
        .map(ToolContract::id)
        .forEach(result::add);
    return List.copyOf(result);
}
```

### 9.6 权限解析流程

```
工具调用请求
    │
    ▼
┌─────────────────────┐
│ 1. 工具存在性检查     │ ── 不存在 → 返回错误
│    DynamicToolRegistry│
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 2. 调用者作用域检查   │ ── 不在白名单且非 infrastructure → 不可见
│    (Layer 2)         │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 3. 全局策略检查       │ ── Blocked → 拦截
│    GuardrailEngine   │ ── NeedsConfirmation → 请求确认
│    (Layer 1)         │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 4. 执行工具          │
│    ToolContract.exec │
└─────────────────────┘
```

### 9.7 改动影响评估

| 改动位置 | 改动内容 | 影响范围 |
|---------|---------|---------|
| `ToolBridgeAgentToolProvider` | 过滤逻辑增加 infrastructure 标签豁免 | Agent 工具可见性 |
| `AgentExecutor.buildAllowedToolIds()` | 增加父 Agent 作用域交集约束 | SubAgent 工具范围 |
| `WorkflowEngine` | 步骤执行前校验 required-tools | Workflow 工具权限 |
| `GuardrailEngine` | infrastructure 工具跳过审计日志 | 审计日志量 |

---

## 10. 与已有模块的集成点

### 10.1 集成矩阵

| 元能力子系统 | 集成模块 | 集成方式 | 说明 |
|-------------|---------|---------|------|
| InfraToolProvider | DynamicToolRegistry | Bean 注入 + `registerBuiltinTool()` | 启动时注册基础工具 |
| InfraToolProvider | Channel 适配器 | 接口调用 | 交互工具通过 Channel 与用户通信 |
| SystemIntrospection | SkillRegistry | Bean 注入 + `getAll()` | 聚合 Skill 信息 |
| SystemIntrospection | AgentRegistry | Bean 注入 + `getAll()` | 聚合 Agent 信息 |
| SystemIntrospection | DynamicToolRegistry | Bean 注入 + `getToolSnapshot()` | 聚合 Tool 信息 |
| SystemIntrospection | WorkflowRegistry | Bean 注入 + `listAll()` | 聚合 Workflow 信息 |
| SkillDiscovery | MarketplaceService | Bean 注入 | 封装已有市场搜索/安装能力 |
| SkillDiscovery | SkillValidationPipeline | Bean 注入 | 外部 Skill 安装前验证 |
| SkillDiscovery | SkillFileWatcher | 文件系统事件 | 安装后自动检测加载 |
| McpDiscovery | McpClient | Bean 注入 + `initialize()` | 安装后初始化连接 |
| McpDiscovery | McpConfigProperties | 配置文件读写 | 写入 MCP Server 配置 |
| OnboardingAgent | AgentRegistry | 启动时自动注册 | Markdown Agent 加载 |
| 权限模型 | ToolBridgeAgentToolProvider | 代码改动 | 增加 infrastructure 豁免 |
| 权限模型 | AgentExecutor | 代码改动 | 增加父作用域约束 |

### 10.2 Spring Bean 注册

`MetaAutoConfiguration` 负责注册所有元能力 Bean：

```java
@AutoConfiguration
@EnableConfigurationProperties(MetaProperties.class)
public class MetaAutoConfiguration {

    @Bean
    InfraToolProvider infraToolProvider(MetaProperties properties,
                                        DynamicToolRegistry registry) { ... }

    @Bean
    CapabilityAggregator capabilityAggregator(
            SkillRegistry skillRegistry,
            AgentRegistry agentRegistry,
            DynamicToolRegistry toolRegistry,
            WorkflowRegistry workflowRegistry) { ... }

    @Bean
    SystemIntrospectionSkillProvider systemIntrospectionSkillProvider(
            CapabilityAggregator aggregator) { ... }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.meta.skill-discovery.enabled",
                           havingValue = "true", matchIfMissing = true)
    SkillDiscoverySkillProvider skillDiscoverySkillProvider(
            List<ExternalSkillSource> sources,
            SkillValidationPipeline pipeline) { ... }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.meta.mcp-discovery.enabled",
                           havingValue = "true", matchIfMissing = true)
    McpDiscoverySkillProvider mcpDiscoverySkillProvider(
            List<McpRegistryClient> clients,
            McpClient mcpClient) { ... }
}
```

### 10.3 事件交互

| 事件 | 发布者 | 消费者 | 说明 |
|------|--------|--------|------|
| `SkillRegistryEvent.SkillRegistered` | SkillRegistry | SkillToToolBridge | 新安装的 Skill 自动桥接为 Tool |
| `McpToolsChangedEvent` | McpClient | DynamicToolRegistry | MCP Server 连接后工具自动注册 |

元能力子系统本身不发布新事件，复用已有的事件机制。

---

## 11. 关键设计决策

### 11.1 为什么基础工具不作为 Skill 实现？

**决策**：基础工具通过独立的 `InfraToolProvider` 直接注册到 `DynamicToolRegistry`，不包装为 Skill。

**理由**：
- Skill 的 SubAgent 模式会为每次调用创建独立 AgentLoop，对 `datetime` 这种高频低延迟工具来说开销过大
- 基础工具需要 `infrastructure` 标签的特殊豁免语义，Skill 桥接后的 `skill.*` 工具无法携带此标签
- 基础工具是 Agent 的"感官"，应该像原生能力一样零开销调用

### 11.2 为什么不引入独立的 PermissionService？

**决策**：权限检查分散在 `ToolBridgeAgentToolProvider`（Layer 2）和 `GuardrailEngine`（Layer 1）中，不抽取为独立服务。

**理由**：
- 两层的执行点已经存在且职责清晰，引入独立服务会增加调用链复杂度
- Layer 2 是工具可见性过滤（在 LLM 选择工具之前），Layer 1 是执行前策略检查（在工具执行之前），两者时机不同
- 保持现有架构，在各执行点增加少量逻辑即可满足需求

### 11.3 为什么 Infrastructure 工具不可禁用？

**决策**：`infrastructure` 标签的工具始终对所有调用者可见，不能通过白名单机制排除。

**理由**：
- `datetime`、`think`、`confirm` 是 Agent 正常运作的基本前提
- 一个不知道当前时间的 Agent 无法处理日程请求
- 一个不能请求用户确认的 Agent 无法安全执行高风险操作
- 这些工具风险等级为 LOW，不存在安全隐患

### 11.4 为什么 Skill 发现优先于 Skill 自扩展？

**决策**：Agent 检测到能力缺口时，先调用 `skill-discovery.search` 搜索市场，无结果再触发 `SkillGenerator` 自动生成。

**理由**：
- 市场 Skill 经过社区验证和安全审计，质量更可靠
- 自生成 Skill 是即时创建的，可能存在边界情况未覆盖
- 搜索市场的延迟（网络请求）远低于 LLM 生成 Skill 的延迟和 Token 消耗

### 11.5 为什么引导 Agent 用 Markdown 定义而非 Java 原生？

**决策**：引导 Agent 使用 Markdown Agent 定义文件，放在 `preset-agents/` 目录。

**理由**：
- 引导 Agent 的核心是 system prompt（引导话术），不需要自定义执行逻辑
- Markdown 定义可以快速迭代引导话术，无需重新编译
- 复用已有的 `AgentMarkdownLoader` 加载机制，零额外开发成本

---

## 12. 配置参考

```yaml
lifepilot:
  meta:
    # 基础工具配置
    infra:
      web-search:
        provider: duckduckgo          # google / bing / duckduckgo
        api-key: ${SEARCH_API_KEY:}   # Google/Bing 需要 API Key
        max-results: 5                # 每次搜索返回结果数
      web-fetch:
        max-content-length: 50000     # 提取正文最大字符数
        timeout-seconds: 10           # HTTP 请求超时
      user-profile:
        cache-ttl-seconds: 300        # 用户画像缓存 TTL

    # 系统自省配置
    introspection:
      cache-ttl-seconds: 60           # 能力聚合缓存 TTL

    # Skill 发现配置
    skill-discovery:
      enabled: true
      sources:
        - type: lifepilot-marketplace
          enabled: true
        - type: lobehub
          enabled: true
          base-url: https://lobehub.com/api
        - type: github
          enabled: false
          token: ${GITHUB_TOKEN:}
      search-limit: 10               # 每个来源的搜索结果上限

    # MCP 发现配置
    mcp-discovery:
      enabled: true
      registries:
        - type: official
          base-url: https://registry.modelcontextprotocol.io
        - type: smithery
          base-url: https://smithery.ai/api
      search-limit: 10

    # 引导 Agent 配置
    onboarding:
      auto-trigger: true              # 首次启动自动触发
      agent-definition: preset-agents/onboarding-guide.md
```

---

## 13. 调研参考

### 理论与标准

- [Anthropic Agent Skills 开放标准](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)（2025.12 发布，30+ 产品采纳）— 定义了 Agent Skill 的标准目录结构和元数据格式
- [MCP 官方 Registry](https://modelcontextprotocol.info/tools/registry/)（2025.9 上线）— Anthropic/OpenAI/Google/Microsoft/AWS 共同维护的 MCP Server 注册中心

### 开源项目

- [LobeHub Skills Marketplace](https://lobehub.com/skills)（2900+ Skills）— 社区最活跃的 Agent Skill 市场，含 `find-skills` / `install-skills` 元 Skill 设计
- [AgentSkillsHub](https://agentskillshub.dev/)（458+ Skills）— 提供安全评级（A-F）的 Skill 目录
- [Smithery Registry](https://smithery.ai/)（2000+ MCP Servers）— 社区最活跃的 MCP Server 目录
- [mcp-installer](https://github.com/anaisbetts/mcp-installer)（GitHub）— MCP Server 自安装模式的经典实现，通过 MCP 工具安装其他 MCP Server

### 竞品参考

- **Claude Desktop** — 内置 MCP Server 管理，支持通过 UI 配置 MCP Server
- **Cursor** — Agent 模式下的工具发现和自动安装机制
- **LobeChat** — 插件市场 + 一键安装 + 运行时热加载的完整实现

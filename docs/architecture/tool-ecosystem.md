# 工具系统 — 架构设计

> **文档性质**：架构设计文档
> **模块归属**：`com.lifepilot.tool`
> **最后更新**：2026-04

## 1. 模块概述

工具系统是知微 Agent 与外部世界交互的桥梁，定义了统一的工具契约（ToolContract），支持两种工具来源（Java 原生内置工具、MCP 外部工具），并通过执行管道提供护栏检查、幂等控制、超时重试等保障。

> **重要变更**：原三层架构中的 `SkillTool`（SKILL_DECLARATIVE 层）已在渐进式披露重构中移除。
> Skill 不再注册为独立工具，而是通过 `load_skill` / `generate_skill` 两个 BuiltinTool 按需加载。

## 2. 架构图

```mermaid
graph TB
    subgraph "Agent 引擎"
        AGENT["ReactAgentLoop"]
        BRIDGE["ToolBridgeAgentToolProvider<br/>工具回调桥接 + 延迟加载过滤"]
    end

    subgraph "工具注册"
        REG["DynamicToolRegistry<br/>动态工具注册表"]
        BUILTIN_REG["BuiltinToolRegistrar<br/>内置工具注册"]
    end

    subgraph "工具搜索"
        SEARCH["ToolSearchIndex<br/>向量语义搜索引擎"]
    end

    subgraph "工具契约（sealed interface）"
        BUILTIN["BuiltinTool<br/>Java 内置工具"]
        MCP_TOOL["McpTool<br/>MCP 外部工具"]
    end

    subgraph "执行管道"
        PIPE["ToolExecutionPipeline<br/>护栏→幂等→超时→重试→执行"]
        IDEM["IdempotencyManager<br/>幂等控制"]
    end

    subgraph "工具模型"
        CONTRACT["ToolContract"]
        INPUT["ToolInput<br/>类型安全输入"]
        RESULT["ToolResult<br/>结构化结果"]
        BUDGET["ToolBudget<br/>超时/重试/成本"]
        LAYER["ToolLayer<br/>层次优先级"]
        SCHEMA["JsonSchema<br/>输入输出 Schema"]
    end

    AGENT --> BRIDGE --> REG
    AGENT -->|"发现工具 ID"| BRIDGE
    BUILTIN_REG --> REG
    SEARCH --> REG
    REG --> BUILTIN
    REG --> MCP_TOOL
    BRIDGE --> PIPE
    PIPE --> IDEM
    PIPE --> CONTRACT
    CONTRACT --> INPUT
    CONTRACT --> RESULT
    CONTRACT --> BUDGET
    CONTRACT --> LAYER
    CONTRACT --> SCHEMA
```

## 3. 核心组件

### 3.1 ToolContract（sealed interface）

- 职责：工具生态的核心抽象，所有工具必须实现此接口
- 两个 permits：`BuiltinTool`（Java 内置）、`McpTool`（MCP 外部）
- 关键属性：`id()`、`name()`、`description()`、`riskLevel()`、`idempotent()`、`budget()`、`layer()`、`inputSchema()`、`outputSchema()`、`executionSemantics()`、`schedulingMode()`、`tags()`、`category()`、`exportable()`、`composable()`
- 关键方法：`execute(ToolInput)` → `ToolResult`

### 3.2 DynamicToolRegistry

- 职责：运行时工具注册表，支持两种来源的工具动态注册和查询
- 关键接口：`registerBuiltinTool()`、`registerMcpTools()`
- 工具层次优先级：BuiltinTool > McpTool，同 ID 高优先级覆盖低优先级

### 3.3 ToolExecutionPipeline

- 职责：工具执行管道，串联护栏检查、幂等控制、超时控制、重试逻辑
- 执行流程：护栏预检 → 幂等查重 → 超时包装 → 重试（指数退避） → 实际执行
- 关键接口：`execute(toolId, parameters, context)` → `ToolResult`

### 3.4 ToolBridgeAgentToolProvider

- 职责：将 ToolContract 转换为 Spring AI 的 ToolCallback，桥接 Agent 引擎和工具系统
- 延迟加载过滤：当 `alwaysLoadedToolIds` 非空时，`getToolCallbacks()` 仅向 LLM 暴露三类工具：始终加载的核心工具集（由 `lifepilot.meta.deferred-tool-loading.always-loaded-tool-ids` 配置）、本轮已发现的工具（`ReactAgentState.discoveredToolIds`）、带 `infrastructure` 标签的工具。其余工具定义不发送给 LLM，减少每次调用的 token 开销
- 工具发现闭环：`ReactAgentLoop` 在每轮工具执行后检测 `meta.search_tools` 和 `load_skill` 的输出，提取发现的工具 ID 并通过 `state.addDiscoveredToolIds()` 扩展可见集，下一轮迭代自动包含新工具的完整定义

### 3.5 ToolSearchIndex

- 职责：基于向量相似度的工具语义搜索引擎，为延迟工具发现提供搜索能力
- 索引构建：启动时对所有工具的 `name + description` 做向量化（通过 `EmbeddingRouter`），懒构建，首次搜索时触发
- 搜索策略：优先使用余弦相似度（cosine similarity）匹配，`EmbeddingRouter` 不可用时回退到子串分词匹配
- 关键接口：`buildIndex(List<ToolContract>)` 构建索引、`search(query, maxResults, minScore, excludeIds)` 搜索、`invalidate()` 使索引失效

## 4. 核心流程

```mermaid
sequenceDiagram
    participant A as ReactAgentLoop
    participant B as ToolBridge
    participant R as DynamicToolRegistry
    participant P as ToolExecutionPipeline
    participant I as IdempotencyManager
    participant T as ToolContract

    A->>B: 获取工具回调列表
    B->>R: 查询所有已注册工具
    R-->>B: List<ToolContract>
    B-->>A: List<ToolCallback>
    A->>P: execute(toolId, params)
    P->>R: 查找工具
    R-->>P: ToolContract
    P->>P: 护栏预检（RiskLevel）
    P->>I: 幂等查重
    alt 已有缓存结果
        I-->>P: ToolResult（缓存）
    else 首次执行
        P->>T: execute(ToolInput)
        T-->>P: ToolResult
        P->>I: 记录执行结果
    end
    P-->>A: ToolResult
```

## 5. 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 工具抽象 | sealed interface ToolContract | 编译期穷举两种工具类型，新增类型时编译器强制处理 |
| 层次优先级 | BuiltinTool > McpTool | 内置工具最可靠，MCP 外部工具优先级较低 |
| 执行管道 | Pipeline 模式 | 护栏、幂等、超时、重试等横切关注点解耦，可独立配置 |
| 输入验证 | JsonSchema + ToolInput.validate() | 工具执行前自动校验参数，防止无效调用 |

## 6. 集成点

- **Agent 引擎**（`agent`）：通过 ToolBridgeAgentToolProvider 提供工具回调；ReactAgentLoop 检测 `meta.search_tools` / `load_skill` 输出并扩展可见工具集
- **MCP 协议**（`mcp`）：McpTool 桥接 MCP 服务器提供的外部工具
- **Skill 系统**（`skill`）：通过 `load_skill` / `generate_skill` BuiltinTool 实现渐进式 Skill 发现与激活
- **护栏系统**（`guardrail` / `observability`）：执行管道中集成风险等级检查
- **可观测性**（`observability`）：工具执行轨迹记录
- **Embedding 路由**（`embedding`）：ToolSearchIndex 通过 EmbeddingRouter 做工具描述向量化，支持语义工具发现

## 7. 配置参考

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.tool.enabled` | `true` | 是否启用工具系统 |
| `lifepilot.tool.pipeline.*` | — | 执行管道配置（超时、重试等） |
| `lifepilot.meta.deferred-tool-loading.always-loaded-tool-ids` | `[web.search, ...]` | 始终加载的核心工具 ID 列表（每次 LLM 调用都包含完整定义） |
| `lifepilot.meta.deferred-tool-loading.max-search-results` | `5` | `meta.search_tools` 搜索最大返回数量 |
| `lifepilot.meta.deferred-tool-loading.min-score-threshold` | `0.3` | `meta.search_tools` 最低相似度阈值（0.0-1.0） |

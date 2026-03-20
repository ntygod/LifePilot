# 元能力系统 — 架构设计

> **文档性质**：架构设计文档
> **模块归属**：`com.lifepilot.meta`
> **最后更新**：2026-03

## 1. 模块概述

元能力系统（Meta Capabilities）为 Agent 提供通用执行基础设施和系统自省能力。模块分为两大子系统：**基础工具集**（Infra）提供 30+ 个内置工具覆盖环境感知、Web 信息获取、推理辅助、Shell 执行、浏览器自动化、代码执行、文件系统操作和用户交互控制；**便利层**（Convenience）提供系统自省和 Skill 发现能力。内置 MCP 服务器（mcp-installer、desktop-control 等）通过 JSON 配置文件由 `McpServerDiscovery` 统一发现和管理。元能力模块是 Agent 执行循环中最底层的工具供给者，所有工具通过 `BuiltinSkillProvider` 机制注册到 `DynamicToolRegistry`。

## 2. 架构图

```mermaid
graph TB
    subgraph meta["com.lifepilot.meta"]
        subgraph convenience["convenience — 便利层"]
            CA["CapabilityAggregator<br/>能力聚合器"]
            ISP["IntrospectionSkillProvider<br/>系统自省 Skill"]
            SDR["SkillDiscoveryRegistrar<br/>find-skills 提取器"]
        end

        subgraph infra["infra — 基础工具集"]
            ITP["InfraToolProvider<br/>基础工具提供者"]
            subgraph tools["30+ 个内置工具"]
                ENV["环境感知<br/>datetime / user-profile / system-info"]
                WEB["Web 信息<br/>web-search / web-fetch"]
                REASON["推理辅助<br/>think / calculate"]
                SHELL["Shell 执行<br/>shell-exec"]
                BROWSER["浏览器自动化<br/>navigate / click / input / screenshot"]
                CODE["代码执行<br/>code-execute"]
                FILE["文件系统<br/>read / write / list / search"]
                INTERACT["交互控制<br/>confirm / choose / input / notify"]
            end
            IB["InteractionBridge<br/>交互桥接器"]
            BSM["BrowserSessionManager<br/>浏览器会话管理"]
        end
    end

    subgraph external["外部依赖"]
        SR["SkillRegistry"]
        AR["AgentRegistry"]
        DTR["DynamicToolRegistry"]
        WR["WorkflowRegistry"]
        SB["SandboxBooter"]
        SSE["SseSessionManager"]
    end

    CA --> SR
    CA --> AR
    CA --> DTR
    CA --> WR
    ISP --> CA
    ISP --> DTR
    ITP --> DTR
    ITP --> SB
    ITP --> IB
    ITP --> BSM
    IB --> SSE
    INTERACT --> IB
    CODE --> SB

```

## 3. 核心组件

### 3.1 InfraToolProvider — 基础工具提供者

- 职责：实现 `BuiltinSkillProvider` 接口，注册 30+ 个内置工具到 `DynamicToolRegistry`
- Skill ID：`builtin.infrastructure`
- 工具按功能域分为 5 类：环境感知（datetime/user-profile/system-info）、Web 信息（web-search/web-fetch）、推理辅助（calculate）、Shell 执行（shell-exec）、文件系统（file-read/file-write/file-list/file-search/file-copy/file-move/file-delete/file-append/file-patch/file-info）、交互控制（confirm/choose/input/notify）、浏览器自动化（navigate/click/input/screenshot/scroll/hover/keyboard/select/wait/tab/storage/accessibility）、代码执行（code-execute）
- 可选依赖：`SandboxBooter`（代码执行）、`InteractionBridge`（交互控制）、`BrowserSessionManager`（浏览器自动化，需 Playwright）

### 3.2 InteractionBridge — 交互桥接器

- 职责：管理 Agent 与用户之间的交互请求/响应生命周期
- 核心机制：工具 Executor 调用 `request()` 发起阻塞式交互 → 通过 SSE 或 CLI Channel 推送 → `CompletableFuture.get()` 等待用户响应 → 外部调用 `resolve()` 完成 Future
- 支持两种 Channel：SSE（Web，优先）和 CLI（降级）
- 超时控制：`lifepilot.meta.infra.interaction.response-timeout-seconds`（默认 120s）
- 非阻塞通知：`notify()` 方法仅推送消息，不等待响应

### 3.3 BrowserSessionManager — 浏览器会话管理

- 职责：管理 Playwright 浏览器实例的生命周期
- 条件注册：仅在 `com.microsoft.playwright.Playwright` 类可用时注册（`@ConditionalOnClass`）
- 支持无头模式、空闲超时自动关闭、安装超时控制

### 3.4 CapabilityAggregator — 能力聚合器

- 职责：从 SkillRegistry、AgentRegistry、DynamicToolRegistry、WorkflowRegistry 四个注册中心拉取信息，统一为 `CapabilitySummary`
- 缓存策略：首次调用聚合并缓存，TTL 由 `lifepilot.meta.introspection.cache-ttl-seconds` 控制（默认 60s）
- 事件驱动失效：监听 `SkillRegistryEvent`、`ToolRegistryEvent`、`AgentRegistryEvent`，触发防抖缓存失效（窗口 500ms）
- 支持按类型过滤：skill / agent / tool / workflow / mcp

### 3.5 IntrospectionSkillProvider — 系统自省 Skill

- 职责：注册 4 个自省工具，让 Agent 能够查询和了解自身能力
- Skill ID：`builtin.introspection`
- 工具列表：
  - `system.list-capabilities` — 列出所有已注册能力，支持按类型过滤
  - `system.explain` — 按 ID 查看能力详情，支持类型路由
  - `system.status` — 系统状态概览（各注册中心计数 + 工具层次分布 + JVM 内存）
  - `system.suggest` — 关键词匹配 + 语义搜索推荐能力

### 3.6 SkillDiscoveryRegistrar — find-skills 提取器

- 职责：启动时将内置 `find-skills` SKILL.md 从 classpath 提取到用户 Skill 目录
- 提取路径：`~/.zhiwei/skills/builtin.find-skills/SKILL.md`
- 不覆盖策略：目标文件已存在时跳过，保留用户自定义内容
- 后续由 `MarkdownSkillLoader` 在 `ApplicationReadyEvent` 时作为 UserDefined Skill 加载

### 3.7 内置 MCP 服务器（JSON 发现机制）

- 职责：通过 `classpath:builtin-mcp/servers.json` 定义内置 MCP 服务器（mcp-installer、desktop-control 等）
- 启动时由 `McpServerDiscovery.seedBuiltinServers()` 将内置配置合并到用户目录 `~/.zhiwei/mcp/servers.json`
- 合并策略：仅添加新条目，不覆盖用户已有配置（保留用户自定义修改）
- `McpServerDiscovery` 扫描用户目录 JSON 作为最高优先级发现路径
- 发现的 MCP 服务器通过 `McpServerRegistry` → `DynamicToolRegistry` → Agent 链路注册

## 4. 核心流程

### 4.1 交互请求流程

```mermaid
sequenceDiagram
    participant Tool as 交互工具 Executor
    participant IB as InteractionBridge
    participant SSE as SseSessionManager
    participant User as 用户（Web/CLI）

    Tool->>IB: request(InteractionRequest)
    IB->>IB: 生成 interactionId
    IB->>IB: 创建 CompletableFuture
    IB->>SSE: sendEvent(sessionId, "interaction", request)
    SSE->>User: SSE 事件推送
    Note over IB: 阻塞等待（默认 120s）
    User->>SSE: 用户响应
    SSE->>IB: resolve(interactionId, response)
    IB->>IB: 完成 CompletableFuture
    IB-->>Tool: InteractionResponse
```

### 4.2 能力聚合与自省流程

```mermaid
sequenceDiagram
    participant Agent as Agent Loop
    participant ISP as system.list-capabilities
    participant CA as CapabilityAggregator
    participant SR as SkillRegistry
    participant AR as AgentRegistry
    participant TR as DynamicToolRegistry
    participant WR as WorkflowRegistry

    Agent->>ISP: 调用 list-capabilities
    ISP->>CA: aggregate()
    alt 缓存命中且未过期
        CA-->>ISP: 返回缓存 CapabilitySummary
    else 缓存未命中或已过期
        CA->>SR: listAll()
        CA->>AR: listAll()
        CA->>TR: getToolSnapshot()
        CA->>WR: listAll()
        CA->>CA: 映射为 CapabilityInfo 列表
        CA->>CA: 缓存结果（TTL 60s）
        CA-->>ISP: CapabilitySummary
    end
    ISP-->>Agent: 格式化能力列表
```

## 5. 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 工具注册方式 | BuiltinSkillProvider 接口 | 与 Skill 系统统一注册机制，工具自动纳入 DynamicToolRegistry 管理 |
| 交互模型 | CompletableFuture 阻塞等待 | Agent 工具执行是同步模型，阻塞等待最简单直接 |
| 能力聚合缓存 | ConcurrentHashMap + TTL + 事件防抖 | 避免每次自省都遍历四个注册中心，防抖合并短时间内的多次注册事件 |
| 浏览器自动化 | Playwright + 条件注册 | Playwright 是可选重依赖，通过 @ConditionalOnClass 避免强制引入 |
| find-skills 提取 | classpath → 用户目录 | 提取后作为 UserDefined Skill 加载，用户可查看和编辑 |
| 内置 MCP 服务器 | JSON 配置 + 启动时 seed | 内置 MCP 定义在 classpath JSON 中，启动时合并到用户目录，由 McpServerDiscovery 统一发现 |

## 6. 集成点

| 集成模块 | 方向 | 说明 |
|---------|------|------|
| tool（DynamicToolRegistry） | meta → tool | 注册 21 + 4 = 25 个内置工具 |
| skill（SkillRegistry） | meta → skill | 注册 2 个 BuiltinSkill（infrastructure + introspection） |
| skill（SkillRegistry） | meta ← skill | 自省时查询 Skill 列表和语义搜索 |
| multiagent（AgentRegistry） | meta ← multiagent | 自省时查询 Agent 列表 |
| workflow（WorkflowRegistry） | meta ← workflow | 自省时查询工作流列表 |
| mcp（McpServerRegistry） | meta → mcp | 内置 MCP 服务器通过 JSON 发现机制注册（McpServerDiscovery） |
| sandbox（SandboxBooter） | meta ← sandbox | 代码执行工具委托沙箱执行 |
| interaction（SseSessionManager） | meta → interaction | 交互请求通过 SSE 推送到 Web 前端 |

## 7. 配置参考

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.meta.infra.web-search.provider` | `tavily` | 搜索引擎提供商（当前固定为 Tavily） |
| `lifepilot.meta.infra.web-search.max-results` | `5` | 搜索最大返回数 |
| `lifepilot.meta.infra.web-search.search-depth` | `basic` | Tavily 搜索深度（basic / advanced） |
| `lifepilot.meta.infra.web-search.topic` | `general` | Tavily 搜索主题（general / news / finance） |
| `lifepilot.meta.infra.web-search.include-answer` | `true` | 是否附带 Tavily answer 摘要 |
| `lifepilot.meta.infra.web-fetch.max-content-length` | `50000` | Web 抓取最大字符数 |
| `lifepilot.meta.infra.web-fetch.timeout-seconds` | `10` | HTTP 请求超时 |
| `lifepilot.meta.infra.shell.timeout-seconds` | `30` | Shell 命令执行超时 |
| `lifepilot.meta.infra.shell.max-output-length` | `50000` | Shell 输出最大字符数 |
| `lifepilot.meta.infra.browser.enabled` | `true` | 浏览器功能开关 |
| `lifepilot.meta.infra.browser.headless` | `true` | 无头模式 |
| `lifepilot.meta.infra.browser.idle-timeout-seconds` | `300` | 浏览器空闲超时 |
| `lifepilot.meta.infra.code-execute.enabled` | `true` | 代码执行开关 |
| `lifepilot.meta.infra.code-execute.default-language` | `python` | 默认执行语言 |
| `lifepilot.meta.infra.file.max-read-size` | `1048576` | 文件最大读取字节数（1MB） |
| `lifepilot.meta.infra.interaction.response-timeout-seconds` | `120` | 用户交互响应超时 |
| `lifepilot.meta.introspection.cache-ttl-seconds` | `60` | 能力聚合缓存 TTL |
| `lifepilot.meta.introspection.debounce-millis` | `500` | 事件防抖窗口 |
| `lifepilot.meta.skill-discovery.enabled` | `true` | find-skills 提取开关 |
| `lifepilot.meta.onboarding.auto-trigger` | `true` | 引导 Agent 自动触发 |

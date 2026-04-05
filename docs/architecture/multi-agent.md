# 多 Agent 协作 — 架构设计

> **文档性质**：架构设计文档
> **模块归属**：`com.lifepilot.multiagent`
> **最后更新**：2026-03-23

## 1. 当前定位

模块保留两类能力：
- `spawn_workers`：把一个任务拆成多个并行 Worker 执行。
- Agent 定义与注册：用于管理预设 / 用户自定义 / 市场安装 Agent，并供 A2A、管理后台、能力发现等模块使用。

## 2. 核心组件

### 2.1 AgentDefinition

Agent 蓝图，包含：
- `id`、`name`、`description`
- `systemPrompt`
- `allowedTools`
- `budget`
- `preferredProvider`
- `source`
- `metadata`

### 2.2 AgentRegistry

运行时 Agent 注册表。负责注册、覆盖、注销、查询，并发布 `AgentRegistryEvent`。

### 2.3 AgentMarkdownParser / AgentMarkdownLoader / AgentMarkdownSerializer

负责从 Markdown 定义文件加载 Agent、热更新，以及序列化回磁盘。

### 2.4 SpawnWorkersToolFactory

提供 `spawn_workers` 工具，负责：
- 校验并行任务列表
- 通过 `SubAgentBudgetAllocator` 从父预算剩余额度派生 Worker 池预算，支持 per-task 预算覆盖上限
- 直接调用 `AgentOrchestrator.run()` 为每个 Worker 分配预算并并行执行（不经过 AgentExecutor）
- Worker 继承父 Agent 完整工具集，但排除 `spawn_workers` 自身（防止递归）
- 聚合结果返回主 Agent

### 2.5 SubAgentBudgetAllocator

负责 Worker 预算分配：
- 从父请求剩余额度切分 Worker 池预算
- 支持任务级预算覆盖上限（per-task budget override）

### 2.6 ToolDiscoveryService

列出当前所有可用工具，并结合上下文做能力过滤。

## 3. 核心流程

### 3.1 Agent 加载

1. 启动时加载 `preset-agents/*.md`
2. 加载用户目录中的自定义 Agent
3. 热加载监听目录变更并同步 `AgentRegistry`

### 3.2 并行 Worker 执行

1. 主 Agent 调用 `spawn_workers`
2. 工具读取父请求上下文中的预算、深度、trace 信息
3. `SubAgentBudgetAllocator` 按剩余额度切分 Worker 池预算，应用 per-task 覆盖上限
4. 通过 `AgentOrchestrator.run()` 并发执行多个 Worker（Worker 继承完整工具集但排除 `spawn_workers`）
5. 聚合输出并返回主 Agent

## 4. 设计约束

- 不再自动把 AgentDefinition 桥接成工具
- `spawn_workers` 必须受父预算剩余额度约束
- Agent 自身的工具白名单仍然有效

## 5. 配置参考

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.agent.multi-agent.enabled` | `true` | 多 Agent 模块总开关 |
| `lifepilot.agent.multi-agent.max-delegation-depth` | `2` | 最大执行深度 |
| `lifepilot.agent.multi-agent.agent-definitions-path` | `~/.zhiwei/agents/` | Agent Markdown 定义目录 |
| `lifepilot.agent.multi-agent.hot-reload.enabled` | `true` | 热加载开关 |
| `lifepilot.agent.multi-agent.hot-reload.scan-interval-seconds` | `5` | 热加载扫描间隔（秒） |
| `lifepilot.agent.multi-agent.budget.default-max-tokens` | `16000` | 默认 Token 上限 |
| `lifepilot.agent.multi-agent.budget.default-max-steps` | `15` | 默认步骤上限 |
| `lifepilot.agent.multi-agent.budget.default-timeout-seconds` | `180` | 默认超时（秒） |
| `lifepilot.agent.multi-agent.parallel-worker.max-parallel-workers` | `5` | 最大并行 Worker 数 |
| `lifepilot.agent.multi-agent.parallel-worker.worker-budget-ratio` | `0.7` | 父预算中分给 Worker 池的比例 |

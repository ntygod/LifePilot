# 多 Agent 协作 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.multiagent`
> **最后更新**：2026-03-23

## 1. 功能概述

当前多 Agent 模块的核心能力是：
- 管理 Agent 定义（内置 / 自定义 / 市场安装）
- 提供 `spawn_workers` 并行执行能力
- 为 A2A、管理后台、能力发现提供 Agent 注册信息

## 2. 核心特性

### 2.1 Markdown 声明式 Agent 定义

使用 Markdown 文件定义 Agent 蓝图，包含 System Prompt、工具白名单、预算、模型偏好等。支持启动时加载和热加载。

### 2.2 并行 Worker 执行

主 Agent 可调用 `spawn_workers` 将任务拆成多个独立子任务并行执行，适合：
- 并行调研
- 批量比对
- 多目标检查

`SpawnWorkersToolFactory` 直接调用 `AgentOrchestrator.run()` 执行 Worker（不经过 AgentExecutor）。Worker 继承父 Agent 完整工具集，但排除 `spawn_workers` 自身以防止递归。

### 2.3 剩余额度预算裁剪

`SubAgentBudgetAllocator` 负责预算分配。Worker 预算不再从固定默认值派生，而是从父请求剩余额度派生，并支持任务级预算覆盖上限（per-task budget override）。

### 2.4 Agent 资产独立管理

Agent 仍然作为独立资产存在，可被：
- A2A Server 路由
- 管理后台查看与编辑
- Marketplace 安装 / 卸载
- 系统能力发现工具列出

## 3. 使用场景

用户要求“并行调研 3 家产品并汇总对比结论”，主 Agent 可调用 `spawn_workers` 同时派发 3 个 Worker，各自完成一个调研子任务，最后把结果聚合回主 Agent。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.agent.multi-agent.enabled` | `true` | 多 Agent 模块总开关 |
| `lifepilot.agent.multi-agent.max-delegation-depth` | `2` | 最大执行深度 |
| `lifepilot.agent.multi-agent.agent-definitions-path` | `~/.zhiwei/agents/` | Agent 定义文件目录 |
| `lifepilot.agent.multi-agent.hot-reload.enabled` | `true` | 热加载开关 |
| `lifepilot.agent.multi-agent.hot-reload.scan-interval-seconds` | `5` | 扫描间隔（秒） |
| `lifepilot.agent.multi-agent.budget.default-max-tokens` | `16000` | 默认 Token 上限 |
| `lifepilot.agent.multi-agent.budget.default-max-steps` | `15` | 默认步骤上限 |
| `lifepilot.agent.multi-agent.budget.default-timeout-seconds` | `180` | 默认超时（秒） |
| `lifepilot.agent.multi-agent.parallel-worker.max-parallel-workers` | `5` | 最大并行 Worker 数 |
| `lifepilot.agent.multi-agent.parallel-worker.worker-budget-ratio` | `0.7` | Worker 池预算比例 |

## 5. 限制

- Worker 之间不能直接通信
- 聚合结果仍由主 Agent 负责整合
- `tokensReserved/returnFromSubAgent()` 还未接入真实父子记账链路

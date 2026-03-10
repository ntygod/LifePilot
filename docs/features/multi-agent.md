# 多 Agent 协作 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.multiagent`
> **最后更新**：2026-03

## 1. 功能概述

多 Agent 协作为知微提供专家分工能力，主 Agent 可将特定领域的子任务委托给专家 Agent（如写作、分析、调研），每个专家 Agent 拥有独立的 System Prompt、工具权限、预算约束和可选的差异化模型，实现更精准的任务处理。

## 2. 核心特性

### 2.1 HandoffTool 委托模式

主 Agent 通过调用 `handoff_to_{agentId}` 工具将子任务委托给专家 Agent。委托参数包含任务描述（必填）和附加上下文（选填）。子 Agent 执行完成后，结果通过 ToolResult 返回给主 Agent。委托深度可配置（默认 2 层），防止无限递归。

### 2.2 Markdown 声明式 Agent 定义

使用 Markdown 文件定义 Agent 蓝图，包含身份标识、System Prompt、工具白名单、预算约束、模型偏好等。文件存放在可配置的目录中（默认 `~/.zhiwei/agents/`），支持热加载——新增、修改、删除 `.md` 文件后自动同步到注册表。

### 2.3 独立预算与差异化模型

每个子 Agent 拥有独立的 Token、步骤、超时预算，不与父 Agent 共享。提供三个预设预算级别（DEFAULT / LIGHTWEIGHT / HEAVYWEIGHT）。支持通过 `preferredProvider` 为不同 Agent 指定不同的 LLM 提供商，实现成本与能力的差异化配置。

### 2.4 工具权限隔离

子 Agent 的有效工具集取其白名单与父 Agent 作用域的交集，防止通过委托实现权限提升。自递归防护始终排除指向自身的 handoff 工具。Infrastructure 工具（标记为 "infrastructure" 标签）始终保留，不受交集约束。

### 2.5 事件驱动桥接

Agent 注册/注销事件通过 Spring Event 自动触发 HandoffTool 的创建/销毁，AgentRegistry 与 DynamicToolRegistry 完全解耦。热加载检测到文件变更时，自动更新注册表并同步桥接。

### 2.6 三种 Agent 来源

- Builtin：内置预设专家 Agent（写作 / 分析 / 调研）
- MarkdownDefined：用户通过 Markdown 文件自定义
- Marketplace：从插件市场安装

## 3. 使用场景

用户向知微提出复杂任务（如"帮我写一篇关于 AI 趋势的分析报告"），主 Agent 识别任务需要调研和写作两个阶段，先调用 `handoff_to_researcher` 委托调研 Agent 收集资料，再调用 `handoff_to_writer` 委托写作 Agent 基于调研结果撰写报告。每个专家 Agent 使用各自的 System Prompt 和工具集独立完成子任务，结果逐级返回给主 Agent 整合。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.agent.multi-agent.enabled` | `true` | 多 Agent 协作总开关 |
| `lifepilot.agent.multi-agent.max-delegation-depth` | `2` | 最大委托深度 |
| `lifepilot.agent.multi-agent.agent-definitions-path` | `~/.zhiwei/agents/` | Agent 定义文件目录 |
| `lifepilot.agent.multi-agent.register-handoff-tools` | `true` | 自动注册 HandoffTool |
| `lifepilot.agent.multi-agent.hot-reload.enabled` | `true` | 热加载开关 |
| `lifepilot.agent.multi-agent.hot-reload.scan-interval-seconds` | `5` | 扫描间隔（秒） |
| `lifepilot.agent.multi-agent.budget.default-max-tokens` | `16000` | 默认 Token 上限 |
| `lifepilot.agent.multi-agent.budget.default-max-steps` | `15` | 默认步骤上限 |
| `lifepilot.agent.multi-agent.budget.default-timeout-seconds` | `180` | 默认超时（秒） |

## 5. 限制与未来方向

当前限制：
- 子 Agent 之间不能直接通信，只能通过父 Agent 中转
- 合成轨迹步骤无法获取子 Agent 内部真实轨迹
- 委托执行为同步阻塞，不支持并行委托多个子 Agent

未来方向：
- 支持子 Agent 间直接消息传递
- 并行委托执行（多个子 Agent 同时工作）
- Agent 能力自动匹配（根据任务自动选择最合适的专家 Agent）

# Agent 引擎 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.agent`
> **最后更新**：2026-03

## 1. 功能概述

Agent 引擎是知微的智能推理核心，接收用户自然语言输入，经过意图理解、任务规划、工具执行、反思评估等阶段，最终生成结构化响应。支持非流式和 SSE 流式两种输出模式，内置预算控制防止资源过度消耗。

## 2. 核心特性

### 2.1 六阶段生命周期

Agent 执行遵循 Understanding → Planning → Executing → Reflecting → Responding → Terminated 六个阶段，每个阶段有明确的职责和合法转换路径。阶段转换由 StateReducer 纯函数驱动，确保状态机行为可预测。

### 2.2 不可变状态机

所有状态通过不可变 record（AgentState）表示，每次状态转换生成新实例。这种设计天然支持轨迹回放、并发安全和调试追溯。

### 2.3 九种动作类型

LLM 输出和系统事件被解析为 9 种动作类型（sealed interface）：意图理解、计划生成、工具结果、反思完成、响应生成、预算耗尽、护栏阻断、错误恢复、子 Agent 结果。编译器强制穷举处理所有动作类型。

### 2.4 三维预算控制

支持 Token 数量、执行时间、步骤数三个维度的预算限制。预算耗尽时自动生成降级响应，汇总已完成的步骤结果返回给用户。

### 2.5 SSE 流式输出

支持 Server-Sent Events 流式响应，LLM 生成的 Token 实时推送到前端。流式模式下支持推理过程事件、工具调用事件、UI 组件事件等多种事件类型。

### 2.6 上下文智能组装

ContextAssembler 根据当前阶段动态组装 LLM 上下文，包括系统 Prompt、记忆检索结果、对话历史、知识库内容。支持 Token 预算动态分配和对话压缩。

### 2.7 子 Agent 委托

通过 SubAgentResult 动作类型支持多 Agent 协作场景，子 Agent 拥有独立预算和上下文，执行结果回传到父 Agent 状态。

## 3. 使用场景

用户通过 CLI 或 Web UI 发送消息，Agent 引擎自动理解意图、规划执行步骤、调用所需工具（如查询待办、设置提醒、搜索知识库），并在反思阶段评估结果质量。如果结果不满意，Agent 会自动调整计划重新执行。整个过程对用户透明，用户只需等待最终响应。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.agent.max-iterations` | — | 单次循环最大迭代次数 |
| `lifepilot.agent.budget.max-tokens` | — | Token 预算上限 |
| `lifepilot.agent.budget.max-duration` | — | 时间预算上限 |
| `lifepilot.agent.budget.max-steps` | — | 步数预算上限 |
| `lifepilot.agent.context.mode` | — | 上下文组装模式（basic / full） |

## 5. 限制与未来方向

- 当前 ActionParser 依赖 LLM 输出格式的稳定性，格式偏差可能导致解析失败（内置重试机制）
- 反思阶段的评估质量依赖 LLM 能力，简单模型可能无法有效反思
- 未来计划：优化首字响应时间（TTFT），引入更细粒度的执行策略

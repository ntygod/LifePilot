# 多 Agent 协作功能说明

> **模块编号**：21（Phase 6）
> **最后更新**：2026-02-27

---

## 1. 功能概述

### 1.1 核心变化

本次重构解决了 ZhiWei 中 Skill 和 Agent 职责重叠的根本问题，建立清晰的四层能力模型：

| 层次 | 名称 | 职责 | 是否经过 LLM 推理 |
|------|------|------|------------------|
| L0 | Tool | 原子操作（HTTP、Shell、数据库） | 否 |
| L1 | Skill | 确定性工作流（多步骤编排） | 否 |
| L2 | Agent | 自主推理实体（独立人格+工具+预算） | **是** |
| L3 | Orchestrator | 主 AgentLoop，协调一切 | 是 |

**之前**：Skill 系统同时承担 L1（确定性工作流）和 L2（SubAgent 激活 → agentLoop.run()）两个层次的职责，概念混淆。

**之后**：Skill 回归纯 L1 确定性工作流定位，所有 LLM 推理委托统一由 Agent（L2）通过 HandoffTool 完成。

### 1.2 用户价值

- **更高质量的专业输出**：写作、回顾、规划等任务由专门优化的 Agent 处理
- **成本优化**：轻量任务使用低成本模型，复杂任务使用高能力模型
- **可扩展性**：用户可通过 Markdown 文件自定义专家 Agent，支持热加载，无需重启
- **透明可控**：委托过程在 Trace 中完整记录，用户可回放查看
- **架构清晰**：Skill 和 Agent 职责分明，不再混淆

---

## 2. 核心特性

### 2.1 Agent 注册与管理

- AgentRegistry 管理所有 Agent 定义，支持运行时注册/注销/查找
- 内置预设专家 Agent（写作、生活教练、规划），开箱即用
- 用户可在 `~/.zhiwei/agents/` 目录下添加 Markdown Agent 定义（`.md` 文件）
- 支持热加载：修改 Markdown 文件后自动重新注册，无需重启

### 2.2 HandoffTool 委托

- 每个注册的 Agent 自动生成对应的 `handoff_to_{agentId}` 工具
- 主 Agent 的 LLM 通过 Function Call 自主决策何时委托
- 委托参数：`task`（任务描述）+ 可选 `context`（额外上下文）
- 委托结果通过 `Action.SubAgentResult` 返回主 Agent
- 主 Agent 保持控制权（agents-as-tools 模式），可汇总多个专家结果

### 2.3 独立预算与上下文隔离

- 每个专家 Agent 拥有独立的 Token / 步骤 / 时间预算
- 子 Agent 上下文完全隔离，不共享主 Agent 的对话历史
- 子 Agent 预算耗尽不影响主 Agent 执行
- 委托深度限制（默认 2 层），防止无限递归

### 2.4 差异化模型路由

- AgentDefinition 可指定 `preferredProvider`，如 `deepseek-chat`、`ollama-qwen`
- 未指定时使用 LlmRouter 默认路由策略
- 支持成本优化场景：轻量 Agent 使用本地模型，重要 Agent 使用云端模型

### 2.5 预设专家 Agent

预设 Agent 经过严格的「L2 准入测试」筛选——只有当专用 System Prompt 的输出质量显著优于主 Agent 通用 Prompt 时，才值得创建独立 Agent。

| Agent | 职责 | 典型场景 | 为什么需要独立 Agent |
|-------|------|---------|-------------------|
| 写作专家 (writer) | 文字创作与润色 | "帮我写一份本周工作周报"、"润色这封邮件" | 写作质量需要专门优化的 Prompt（结构、修辞、风格），与主 Agent 的任务执行 Prompt 截然不同 |
| 生活教练 (life-coach) | 周/月回顾、习惯分析、目标复盘 | "帮我做一下这周的回顾"、"分析我最近的习惯完成情况" | 需要教练式引导提问（Socratic 方法），而非主 Agent 的直接回答模式；回顾分析需要检索大量历史数据，上下文隔离收益高 |
| 规划专家 (planner) | 日/周规划、时间块分配、优先级排序 | "帮我规划明天的日程"、"这周有哪些重要的事要做" | 需要时间管理方法论（Eisenhower 矩阵、能量曲线）的专业 Prompt；需要综合拉取 todo/schedule/habit 全量数据 |

用户可通过 Markdown 热加载机制自定义任意 Agent（如翻译专家、编程助手等），无需重启。

---

## 3. 使用场景

### 3.1 自动委托

用户发送消息 → 主 Agent 理解意图 → 判断需要专家协助 → 调用 HandoffTool → 专家 Agent 执行 → 结果返回主 Agent → 主 Agent 整合回复用户。

```
用户: 帮我写一份本周工作周报，重点包括完成了可观测性模块开发
主Agent: [调用 handoff_to_writer(task="撰写工作周报，重点：可观测性模块开发")]
写作专家: [执行写作任务，使用 memory-search 检索本周活动]
主Agent: 这是为您撰写的周报：...
```

### 3.2 多专家协作

主 Agent 可在一次对话中委托多个专家 Agent：

```
用户: 帮我回顾一下这周的情况，然后规划下周的重点
主Agent: [调用 handoff_to_life_coach(task="进行本周回顾，分析习惯完成和任务完成情况")]
生活教练: [检索本周记忆数据，分析行为模式，返回回顾报告和改进建议]
主Agent: [调用 handoff_to_planner(task="基于本周回顾制定下周规划", context=回顾报告)]
规划专家: [拉取 todo/schedule/habit 数据，生成下周结构化规划]
主Agent: 这是您的周回顾和下周规划：...
```

### 3.3 用户自定义 Agent

用户在 `~/.zhiwei/agents/translator.md` 创建翻译专家：

```markdown
---
id: translator
name: 翻译专家
description: 擅长中英文互译，保持原文风格和语气
allowed-tools: []
can-delegate: false
budget:
  max-tokens: 8000
  max-steps: 5
  timeout-seconds: 60
preferred-provider: deepseek-chat
---

你是一位专业的中英文翻译专家。

## 翻译原则

- 翻译时保持原文的风格、语气和专业术语
- 如果原文是中文则翻译为英文，反之亦然
```

保存文件后，热加载机制自动检测变更并注册新 Agent，无需重启。主 Agent 立即可以通过 `handoff_to_translator` 工具委托翻译任务。

### 3.4 Skill 与 Agent 的协作

Skill（L1）和 Agent（L2）可以在同一次对话中协作：

```
用户: 查一下今天的日程，然后帮我写一封请假邮件给老板
主Agent: [调用 skill.schedule-query（L1 确定性 Skill，不经过 LLM）]
日程Skill: [查询 → 返回 "14:00 团队周会, 16:00 客户演示"]
主Agent: [调用 handoff_to_writer(task="写请假邮件给老板", context="今日日程：14:00 团队周会, 16:00 客户演示")]
写作专家: [LLM 推理 → 生成请假邮件，提及需要安排同事代参加会议]
主Agent: 邮件已为您撰写好：...
```

这个例子清晰展示了四层模型的协作：
- L0 Tool：日程数据库查询
- L1 Skill：schedule-query 编排查询 + 格式化
- L2 Agent：writer 专家通过 LLM 推理撰写邮件
- L3 Orchestrator：主 AgentLoop 协调整个流程

---

## 4. Skill 系统变更说明

### 4.1 Skill 回归 L1 定位

| 维度 | 变更前 | 变更后 |
|------|--------|--------|
| 定位 | "Agent 能力单元"（含 SubAgent 激活） | 纯 L1 确定性工作流 |
| 执行路径 | SkillAction（确定性）+ SubAgentFactory（LLM 推理） | 仅 SkillAction（确定性） |
| `preferredProviderId` | 有（指定 LLM 偏好） | 移除（Skill 不使用 LLM） |
| 自扩展 | 保留 | 保留（GapDetector + Generator 只生成 L1 Skill） |
| 记忆访问控制 | 保留 | 保留（MemoryAccessPolicy） |
| 热加载 | 保留 | 保留 |

### 4.2 对用户的影响

- 现有 YAML Skill 定义（`~/.zhiwei/skills/*.yml`）**完全兼容**，无需修改
- 如果用户之前通过 Skill 的 SubAgent 模式使用专家能力，需要改为创建 Agent Markdown 定义
- Skill 自扩展（自动生成新 Skill）功能不受影响
- 内置 Skill（Todo / Schedule / Habit / Memory）不受影响

---

## 5. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.agent.multi-agent.enabled` | `true` | 是否启用多 Agent 协作 |
| `lifepilot.agent.multi-agent.max-delegation-depth` | `2` | 最大委托深度 |
| `lifepilot.agent.multi-agent.agent-definitions-path` | `~/.zhiwei/agents/` | 用户自定义 Agent Markdown 目录 |
| `lifepilot.agent.multi-agent.register-handoff-tools` | `true` | 是否自动注册 HandoffTool |
| `lifepilot.agent.multi-agent.hot-reload.enabled` | `true` | 是否启用热加载 |
| `lifepilot.agent.multi-agent.hot-reload.scan-interval-seconds` | `5` | 文件扫描间隔 |
| `lifepilot.agent.multi-agent.budget.default-max-tokens` | `16000` | 默认 Token 预算 |
| `lifepilot.agent.multi-agent.budget.default-max-steps` | `15` | 默认步骤预算 |
| `lifepilot.agent.multi-agent.budget.default-timeout-seconds` | `180` | 默认超时秒数 |

---

## 6. 限制与未来扩展

### 6.1 当前限制

- 仅支持 agents-as-tools 模式（委托后返回），不支持完全控制权转移
- 子 Agent 不共享主 Agent 对话历史（隔离设计）
- 不支持 Agent 间直接通信（需通过主 Agent 中转）
- 不支持并行委托（顺序执行）
- Agent 不支持自扩展（需用户手动创建 Markdown 定义）

### 6.2 未来扩展（模块 22+）

- **A2A 协议支持**（模块 22）：跨系统 Agent 互操作，Agent Card 能力声明
- **并行委托**：多个专家 Agent 并行执行，主 Agent 汇总结果
- **对话历史选择性共享**：子 Agent 可选择性继承父 Agent 的对话摘要
- **Agent 间直接通信**：专家 Agent 之间可以直接交换信息，无需主 Agent 中转
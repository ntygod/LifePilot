# 多 Agent 协作功能说明

> **模块编号**：21（Phase 6）
> **最后更新**：2026-02-27

---

## 1. 功能概述

多 Agent 协作为 ZhiWei 引入**专家 Agent 委托**能力。主 Agent 在处理用户请求时，可以自主判断是否需要将特定领域任务委托给专家 Agent 执行。每个专家 Agent 拥有独立的身份、System Prompt、工具集、预算和可选的差异化 LLM 模型。

### 用户价值

- **更高质量的专业输出**：写作、分析、调研等任务由专门优化的 Agent 处理
- **成本优化**：轻量任务使用低成本模型，复杂任务使用高能力模型
- **可扩展性**：用户可通过 YAML 文件自定义专家 Agent，无需编码
- **透明可控**：委托过程在 Trace 中完整记录，用户可回放查看

---

## 2. 核心特性

### 2.1 Agent 注册与管理

- AgentRegistry 管理所有 Agent 定义，支持运行时注册/注销/查找
- 内置预设专家 Agent（写作、分析、调研），开箱即用
- 用户可在 `~/.lifepilot/agents/` 目录下添加 YAML Agent 定义
- 支持热加载：修改 YAML 文件后自动重新注册

### 2.2 HandoffTool 委托

- 每个注册的 Agent 自动生成对应的 `handoff_to_{agentId}` 工具
- 主 Agent 的 LLM 通过 Function Call 自主决策何时委托
- 委托参数：`task`（任务描述）+ 可选 `context`（额外上下文）
- 委托结果通过 `Action.SubAgentResult` 返回主 Agent

### 2.3 独立预算与上下文隔离

- 每个专家 Agent 拥有独立的 Token / 步骤 / 时间预算
- 子 Agent 上下文完全隔离，不共享主 Agent 的对话历史
- 委托深度限制（默认 2 层），防止无限递归

### 2.4 差异化模型路由

- AgentDefinition 可指定 `preferredProvider`，如 `deepseek-chat`、`ollama-qwen`
- 未指定时使用 LlmRouter 默认路由策略
- 支持成本优化场景：轻量 Agent 使用本地模型，重要 Agent 使用云端模型

### 2.5 预设专家 Agent

| Agent | 职责 | 典型场景 |
|-------|------|---------|
| 写作专家 (writer) | 文字创作 | "帮我写一份本周工作周报"、"润色这封邮件" |
| 分析专家 (analyst) | 数据分析 | "分析我最近的习惯完成趋势"、"对比这两个方案" |
| 调研专家 (researcher) | 信息检索 | "调研 Spring AI 最新版本变化"、"整理这个主题的资料" |

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
用户: 帮我分析最近的习惯数据，然后写一份改进建议报告
主Agent: [调用 handoff_to_analyst(task="分析习惯完成数据")]
分析专家: [返回分析结果]
主Agent: [调用 handoff_to_writer(task="基于分析结果撰写改进建议", context=分析结果)]
写作专家: [返回报告]
主Agent: 这是您的习惯改进建议报告：...
```

### 3.3 用户自定义 Agent

用户在 `~/.lifepilot/agents/translator.yml` 创建翻译专家：

```yaml
id: translator
name: 翻译专家
description: 擅长中英文互译，保持原文风格和语气
system-prompt: |
  你是一位专业的中英文翻译专家。
  翻译时保持原文的风格、语气和专业术语。
  如果原文是中文则翻译为英文，反之亦然。
allowed-tools: []
can-delegate: false
budget:
  max-tokens: 8000
  max-steps: 5
  timeout-seconds: 60
preferred-provider: deepseek-chat
```

---

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.agent.multi-agent.enabled` | `true` | 是否启用多 Agent 协作 |
| `lifepilot.agent.multi-agent.max-delegation-depth` | `2` | 最大委托深度 |
| `lifepilot.agent.multi-agent.agent-definitions-path` | `~/.lifepilot/agents/` | 用户自定义 Agent YAML 目录 |
| `lifepilot.agent.multi-agent.register-handoff-tools` | `true` | 是否自动注册 HandoffTool |
| `lifepilot.agent.multi-agent.budget.default-max-tokens` | `16000` | 默认 Token 预算 |
| `lifepilot.agent.multi-agent.budget.default-max-steps` | `15` | 默认步骤预算 |
| `lifepilot.agent.multi-agent.budget.default-timeout-seconds` | `180` | 默认超时秒数 |

---

## 5. 限制与未来扩展

### 5.1 当前限制

- 仅支持 agents-as-tools 模式（委托后返回），不支持完全控制权转移
- 子 Agent 不共享主 Agent 对话历史（隔离设计）
- 不支持 Agent 间直接通信（需通过主 Agent 中转）
- 不支持并行委托（顺序执行）

### 5.2 未来扩展（模块 22+）

- **A2A 协议支持**（模块 22）：跨系统 Agent 互操作，Agent Card 能力声明
- **并行委托**：多个专家 Agent 并行执行，主 Agent 汇总结果
- **Agent 自扩展**：类似 Skill 自扩展，Agent 根据需求自动生成新专家定义
- **对话历史选择性共享**：子 Agent 可选择性继承父 Agent 的对话摘要

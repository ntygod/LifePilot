# 记忆系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.memory`
> **最后更新**：2026-03-20

## 1. 功能概述

记忆系统为 Agent 提供四层记忆能力，但当前真正落地的核心原则已经调整为：

- **当前对话连续性靠会话层**
- **L1 只做临时工作区**
- **跨会话对话靠 recall 工具**
- **长期事实和经验分别沉淀到 L3 / L4**

这让“最近聊了什么”“任务进行到哪一步”“以前别的会话里提过什么”“系统长期记住了哪些事实/偏好”四类信息各归其位，不再互相混用。

## 2. 核心特性

### 2.1 当前会话上下文改为“最近完整轮次”

- `ContextAssembler` 每次直接从会话层读取最近完整轮次，按时间正序拼接
- user 和 assistant 消息以完整 turn 保留，不再按 importance 打散
- 这意味着当前 session 的连续性不再依赖 L1，也不依赖 L2 flush

### 2.2 L1 变成真正的临时工作区

L1 现在不再保存聊天记录，只保存跨轮但临时的任务状态：

- `PendingDecisionItem`：等待用户确认
- `TaskStateItem`：任务挂起后的进度状态
- `WorkingSetItem`：下一轮还要继续使用的中间摘要

这些数据会落到 `session_workspace_items`，有 TTL 和清理任务，但不会进入长期记忆。

### 2.3 跨会话回忆改为 snippet recall

- `builtin.memory.recall` 不再返回零散单条消息
- 检索会先命中 `chat_messages_fts`
- 再回到 `chat_messages` 取命中前后完整轮次
- 最终返回片段级结果，天然更适合模型使用

同时会排除当前 session，避免把当前对话再次检索回来。

### 2.4 用户画像和经验继续自动注入

当前自动注入到 Prompt 的长期信息主要有两类：

- L3 的 `PREFERENCE / HABIT / GOAL`
- L3 的 `EXPERIENCE`

这保证模型仍然能感知长期偏好和过往经验，但不会自动把别的 session 原始对话混进主上下文。

### 2.5 记忆工具能力更清晰

当前记忆相关工具包括：

- `builtin.memory.search`：搜索知识实体
- `builtin.memory.recall`：回忆别的会话里的对话片段
- `builtin.knowledge.search`：搜索资料文档
- `builtin.memory.create` / `update` / `delete` / `tag`
- `builtin.memory.query-at-time`
- `builtin.memory.search-experience`

主上下文负责“当前会话连续性”，工具负责“按需回忆和检索”，职责比旧方案更清楚。

### 2.6 巩固与遗忘不再依赖 L1 flush

- 空闲巩固由 `checkIdleConsolidation()` 驱动
- 情景信息沉淀到 L3/L4 的链路不再依赖“先把 L1 flush 到 L2”
- 遗忘引擎仍然负责清理低价值实体和归档过期内容

## 3. 使用场景

### 3.1 普通连续对话

用户连续追问同一个话题时，Agent 直接依赖当前 session 最近完整轮次保持上下文，不需要额外回忆工具。

### 3.2 挂起后继续执行

如果某次执行需要用户确认高风险工具，系统会把“等待确认”写入 L1 工作区。用户下一轮回来时，Agent 能看到活跃工作区摘要，继续从上次中断点往下执行。

### 3.3 回忆别的会话

如果用户说“我之前提过旅游计划吗”，Agent 应调用 `builtin.memory.recall`，检索别的 session 里的相关片段，而不是自动在主上下文中混入跨会话历史。

### 3.4 复用历史经验

当任务与过去成功案例相似时，系统会自动注入少量经验实体；如果还需要更主动地查找历史策略，Agent 还可以调用 `builtin.memory.search-experience`。

## 4. 配置项

当前最常用的配置包括：

| 配置键 | 说明 |
|--------|------|
| `lifepilot.memory.enabled` | 记忆系统总开关 |
| `lifepilot.memory.workspace.enabled` | 是否启用临时工作区 |
| `lifepilot.memory.workspace.prompt-max-items` | Prompt 中最多注入多少条工作区摘要 |
| `lifepilot.memory.workspace.pending-decision-ttl-hours` | 待确认条目保留时长 |
| `lifepilot.memory.workspace.task-state-ttl-hours` | 任务状态保留时长 |
| `lifepilot.memory.workspace.working-set-ttl-hours` | 工作集保留时长 |
| `lifepilot.memory.workspace.cleanup-cron` | 工作区清理调度 |
| `lifepilot.memory.agentic-tool.*` | 记忆工具默认 TopK 等参数 |
| `lifepilot.memory.retrieval.*` | 用户画像和 L3/L4 检索参数 |
| `lifepilot.memory.consolidation.*` | 巩固触发与窗口参数 |
| `lifepilot.memory.experience.*` | 经验注入、反馈、合并与隔离参数 |

## 5. 当前限制

- `WorkingSetItem` 的业务写入场景还比较少，当前最成熟的是挂起/确认类工作区
- 知识库和跨会话 recall 仍然主要依赖工具调用，而不是自动注入
- 配置类里仍保留少量历史字段，但主链路已经不再按旧的 `WorkingMemory`/`flush` 模型运行

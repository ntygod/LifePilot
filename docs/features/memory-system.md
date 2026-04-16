# 记忆系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.memory`
> **最后更新**：2026-04-16

## 1. 功能概述

记忆系统为 Agent 提供四层记忆能力，但当前真正落地的核心原则已经调整为：

- **当前对话连续性靠会话层**
- **L1 只做临时工作区**
- **跨会话对话靠 recall 工具**
- **长期事实和经验分别沉淀到 L3 / L4**

这让“最近聊了什么”“任务进行到哪一步”“以前别的会话里提过什么”“系统长期记住了哪些事实/偏好”四类信息各归其位，不再互相混用。

## 2. 核心特性

### 2.1 当前会话上下文改为”四路并行检索 + 最近完整轮次”

- `ContextAssembler` 通过四路并行检索组装上下文（contextSnapshot + userProfile + experiences + relevantMemories），使用 `CompletableFuture` + Virtual Thread 并行化
- 最近完整轮次从会话层读取，按时间正序拼接；user 和 assistant 消息以完整 turn 保留，不再按 importance 打散
- `ContextAssembler` 在 system prompt 中注入 `<memory_metadata>` 标签，包含画像数、经验数和事实性记忆数的统计信息（5 分钟 TTL 缓存）
- 通过 `HybridRetriever` 检索与当前查询相关的记忆实体，注入到 `<memory_context>` 标签（排除已由画像和经验路径覆盖的类型）
- 当前 session 的连续性不再依赖 L1，也不依赖 L2 flush

### 2.2 L1 变成真正的临时工作区

L1 现在不再保存聊天记录，只保存跨轮但临时的任务状态：

- `PendingDecisionItem`：等待用户确认
- `TaskStateItem`：任务执行的进度状态（逐步记录每个工具调用的关键参数和结果摘要，超过 10 步时折叠早期步骤为统计汇总）
- `WorkingSetItem`：下一轮还要继续使用的中间摘要

这些数据会落到 `session_workspace_items`，有 TTL 和清理任务，但不会进入长期记忆。

`WorkingSetItem` 有三处主写入点：挂起/确认场景（`AgentPersistenceHandler`）、关键工具执行结果（`ToolExecutionCoordinator`，覆盖 `memory.create/update/tag`、`workflow.execute`、`code.execute`、`datastore.query`）、反思结论（`ReactAgentLoop`，反思触发后自动写入截断至 300 字符的结论摘要）。

### 2.3 跨会话回忆改为 snippet recall

- `memory.recall` 不再返回零散单条消息
- 检索会先命中 `chat_messages_fts`
- 再回到 `chat_messages` 取命中前后完整轮次
- 最终返回片段级结果，天然更适合模型使用

同时会排除当前 session，避免把当前对话再次检索回来。

### 2.4 用户画像、经验和相关记忆自动注入

当前自动注入到 Prompt 的长期信息包括三类：

- L3 的 `PREFERENCE / HABIT / GOAL`（用户画像）
- L3 的 `EXPERIENCE`（排除工具级经验，工具级经验由 `ToolExecutionCoordinator` 在工具执行前按 toolId 精准注入）
- 通过 `HybridRetriever` 检索的相关记忆实体（`memory_context`），排除已被画像和经验路径覆盖的类型

注入权重由 `lifepilot.memory.retrieval.injectionWeights` 控制（relevance/importance/recency，默认 0.4/0.3/0.3）。

这保证模型仍然能感知长期偏好、过往经验和相关事实记忆，但不会自动把别的 session 原始对话混进主上下文。

### 2.5 记忆工具能力更清晰

当前记忆相关工具包括：

- `memory.search`：搜索知识实体
- `memory.recall`：回忆别的会话里的对话片段
- `knowledge.search`：搜索资料文档
- `memory.create` / `update` / `delete` / `tag`
- `memory.query-at-time`
- `memory.search-experience`

主上下文负责”当前会话连续性”，工具负责”按需回忆和检索”，职责比旧方案更清楚。

`HybridRetriever` 支持可选的 `RerankRouter` 精排步骤、`knownEmpty` 短路优化和向量路径 pre-filter（当 MemoryReadFilter 限制 space/scope 时，先查合规实体 ID 集合做内存过滤，超过 1000 个时自动回退为后过滤）。

`EntityType` 枚举包含 12 种类型：PERSON、ORGANIZATION、PLACE、EVENT、PROJECT、TOPIC、PREFERENCE、HABIT、GOAL、SKILL、EXPERIENCE、CUSTOM。

### 2.6 巩固与遗忘不再依赖 L1 flush

- 空闲巩固由 `checkIdleConsolidation()` 驱动
- `ConsolidationPipeline` 顺序执行六步：语义巩固 → 程序巩固 → 偏好同步 → 经验合并 → 用户画像巩固 → 经验提升
- 情景信息沉淀到 L3/L4 的链路不再依赖”先把 L1 flush 到 L2”
- 遗忘引擎四维保护：按类型（PREFERENCE/HABIT/GOAL）、按重要度（≥0.9）、按高频访问（accessCount ≥ 10）、按近期访问（7 天内）
- 反思触发时 `ExperienceSummarizer.quickLearn()` 异步写入即时经验，缩短学习反馈周期

## 3. 使用场景

### 3.1 普通连续对话

用户连续追问同一个话题时，Agent 直接依赖当前 session 最近完整轮次保持上下文，不需要额外回忆工具。

### 3.2 挂起后继续执行

如果某次执行需要用户确认高风险工具，系统会把“等待确认”写入 L1 工作区。用户下一轮回来时，Agent 能看到活跃工作区摘要，继续从上次中断点往下执行。

### 3.3 回忆别的会话

如果用户说“我之前提过旅游计划吗”，Agent 应调用 `memory.recall`，检索别的 session 里的相关片段，而不是自动在主上下文中混入跨会话历史。

### 3.4 复用历史经验

当任务与过去成功案例相似时，系统会自动注入少量非工具级经验实体；工具级经验则在工具执行前由 `ToolExecutionCoordinator` 按 toolId 精准注入到 observation 中，帮助 Agent 理解结果或纠正后续调用。如果还需要更主动地查找历史策略，Agent 还可以调用 `memory.search-experience`。

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
| `lifepilot.memory.retrieval.injectionWeights` | 记忆注入权重（relevance/importance/recency，默认 0.4/0.3/0.3） |
| `lifepilot.memory.retrieval.memoryContextEnabled` | 是否启用 memory_context 注入（默认 true） |
| `lifepilot.memory.retrieval.memoryContextMaxEntities` | memory_context 最大实体数（默认 5） |
| `lifepilot.memory.retrieval.memoryContextTokenBudget` | memory_context token 预算（默认 800） |
| `lifepilot.memory.retrieval.memoryContextScoreThreshold` | memory_context 最低相关度阈值（默认 0.6） |
| `lifepilot.memory.procedural.templateEnabled` | 是否启用 L4 操作模板聚类（默认 true） |
| `lifepilot.memory.consolidation.*` | 巩固触发与窗口参数 |
| `lifepilot.memory.forgetting.recentAccessProtectionDays` | 近期访问保护天数（默认 7） |
| `lifepilot.memory.forgetting.highAccessCountProtection` | 高频访问保护阈值（默认 10） |
| `lifepilot.memory.experience.*` | 经验注入、反馈、合并与隔离参数 |

## 5. 当前限制

- `WorkingSetItem` 已在工具执行（关键工具结果）和反思结论两个场景形成主链路写入
- 知识库和跨会话 recall 仍然主要依赖工具调用，而不是自动注入（但相关记忆实体已通过 memory_context 自动注入）
- 配置类里仍保留少量历史字段，但主链路已经不再按旧的 `WorkingMemory`/`flush` 模型运行

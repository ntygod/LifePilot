# 记忆系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.memory`
> **最后更新**：2026-05-07（补充统一冷召回编排未来方向）

## 1. 功能概述

记忆系统为 Agent 提供四层记忆能力，但当前真正落地的核心原则已经调整为：

- **当前对话连续性靠会话层**
- **L1 只做临时工作区**
- **跨会话对话靠 recall 工具**
- **长期事实和经验分别沉淀到 L3 / L4**

这让“最近聊了什么”“任务进行到哪一步”“以前别的会话里提过什么”“系统长期记住了哪些事实/偏好”四类信息各归其位，不再互相混用。

## 2. 核心特性

### 2.1 当前会话上下文改为”热摘要单入口 + 最近完整轮次”

- `ContextAssembler` 通过 `ContextEngine` 读取当前 session 上下文，并通过一次 `HotMemoryDigestService.build(...)` 获取默认自动注入记忆
- 最近完整轮次从会话层读取，按时间正序拼接；user 和 assistant 消息以完整 turn 保留，不再按 importance 打散
- `ContextAssembler` 在各 context section 首行注入画像数、经验数和事实性记忆数统计（5 分钟 TTL 缓存）
- `<user_profile_context>` / `<experience_context>` / `<memory_context>` 均只消费同一份 `HotMemoryDigest` 快照，不再并行运行旧画像、经验或相关记忆检索
- 当前 session 的连续性不再依赖 L1，也不依赖 L2 flush

### 2.2 L1 变成真正的临时工作区

L1 现在不再保存聊天记录，只保存跨轮但临时的任务状态：

- `PendingDecisionItem`：等待用户确认
- `TaskStateItem`：任务执行的进度状态（逐步记录每个工具调用的关键参数和结果摘要，超过 10 步时折叠早期步骤为统计汇总）
- `WorkingSetItem`：下一轮还要继续使用的中间摘要

这些数据会落到 `session_workspace_items`，有 TTL 和清理任务，但不会进入长期记忆。

`WorkingSetItem` 有三处主写入点：挂起/确认场景（`AgentPersistenceHandler`）、关键工具执行结果（`ToolExecutionCoordinator`，覆盖 `memory.create/update/tag`、`code`）、反思结论（`ReactAgentLoop`，反思触发后自动写入截断至 300 字符的结论摘要）。

### 2.3 跨会话回忆改为 snippet recall

- `memory.recall` 不再返回零散单条消息
- 检索会先命中 `session_transcript_entries_fts`
- 再回到 `session_transcript_entries` 取命中前后完整轮次
- 最终返回片段级结果，天然更适合模型使用

同时会排除当前 session，避免把当前对话再次检索回来。

### 2.4 L3.5 热摘要单入口注入

当前自动注入到 Prompt 的长期信息统一经过 `HotMemoryDigestService` 收口：

- `USER_PROFILE`：来自 L3 可消费画像实体或 `__consolidated_profile`；高置信 L4 偏好规则也合并在这里，但必须带可消费 L3 `source_entity_id`
- `EXPERIENCE`：来自非工具级、任务级高价值经验；工具级经验仍由 `ProviderMessageBuilder` + `ToolTipResolver` 按 toolId 动态前置到工具输出之前，不写入 `Observation.output`
- `PROJECT_MEMORY / FACTS`：来自项目约定和常用事实，合并进 `<memory_context>`

热摘要只读取 `MemoryQualityPolicy.isPromptConsumable(entity) == true` 的 L3 实体，记录 `source_entity_ids`，并在注入前脱敏。热摘要为空或构建失败时，本轮不回退旧画像 / 经验 / 相关记忆检索路径；需要更多历史或事实时由 Agent 显式调用 `memory.search` / `memory.recall`。

这保证模型仍然能感知长期偏好、过往经验和相关事实记忆，但不会自动把别的 session 原始对话混进主上下文。

### 2.5 记忆工具能力更清晰

当前记忆相关工具包括：

- `memory(action=search)`：搜索知识实体
- `memory(action=recall)`：回忆别的会话里的对话片段
- `knowledge.search`：搜索资料文档（独立工具）
- `memory(action=create)` / `update` / `delete` / `tag`：实体 CRUD + 关系标记
- `memory(action=cancel)`：用户表达"取消 / 撤销 / 不再做 / 以后别提"等语义时调用，按 `query` 语义描述批量归档相关实体（默认 `GOAL / EXPERIENCE / HABIT`，可通过 `entityTypes` 覆盖到任意类型），默认最多归档 5 条、最小相关性阈值 0.5。与 `delete` 互补：`delete` 按已知 ID 精确删单条，`cancel` 按语义召回批量归档，覆盖"取消定时任务"这类需级联清理多个旧记忆的场景。仅 `create PREFERENCE` 不足以挡住后续对旧目标/经验的召回，取消语义下必须同时使用 `cancel`
- `memory(action=complete)`：标记 `GOAL / PROJECT` 已完成，驱动 lifecycle 状态机进入终态
- `memory(action=supersede)`：旧实体被新实体替代，在 lifecycle 上建立 superseded_by 关系
- `memory(action=query-at-time)`：时间点查询
- `memory(action=search-experience)`：主动检索执行经验

主上下文负责”当前会话连续性”，工具负责”按需回忆和检索”，职责比旧方案更清楚。

归档链路（`delete` / `cancel` / 巩固 / 遗忘引擎）统一走 `SemanticMemory.archive()`，除主库生命周期变更外，还会登记 `memory_projection_outbox` DELETE 投影任务，确保归档实体不再被向量检索召回，并保留失败补偿能力。

`HybridRetriever` 支持可选的 `RerankRouter` 精排步骤和向量路径 pre-filter（当 MemoryReadFilter 限制 space/scope 时，先查合规实体 ID 集合做内存过滤，超过 1000 个时自动回退为后过滤）。检索 miss 不能缓存成全局空库状态。

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

当任务与过去成功案例相似时，系统只通过热摘要注入少量非工具级经验；工具级经验则由 `ProviderMessageBuilder` 在构造 LLM 消息时通过 `ToolTipResolver` 按 toolId 动态前置到工具输出之前（装饰走呈现层），帮助 Agent 理解结果或纠正后续调用。`Observation.output` 本身保持纯 JSON，不被装饰文本污染，Skill 激活、Trace 回放、审计等下游解析都能拿到干净的工具原始输出。如果还需要更主动地查找历史策略，Agent 还可以调用 `memory.search-experience`。

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
| `lifepilot.memory.hot-digest.*` | L3.5 热摘要开关、分区预算和最大条目数 |
| `lifepilot.memory.agentic-tool.*` | 记忆工具默认 TopK 等参数 |
| `lifepilot.memory.retrieval.*` | `HybridRetriever` 与记忆搜索工具参数；不再控制默认上下文自动注入 |
| `lifepilot.memory.procedural.templateEnabled` | 是否启用 L4 操作模板聚类（默认 true） |
| `lifepilot.memory.consolidation.*` | 巩固触发与窗口参数 |
| `lifepilot.memory.forgetting.recentAccessProtectionDays` | 近期访问保护天数（默认 7） |
| `lifepilot.memory.forgetting.highAccessCountProtection` | 高频访问保护阈值（默认 10） |
| `lifepilot.memory.experience.*` | 经验注入、反馈、合并与隔离参数 |

## 5. 当前限制

- `WorkingSetItem` 已在工具执行（关键工具结果）和反思结论两个场景形成主链路写入
- 知识库、跨会话 recall 和冷 L3 检索仍然依赖工具调用，而不是默认自动注入
- `MemoryProperties` 中旧版 `WorkingMemory` 预算和空闲 flush 配置已清理；压缩前持久化仍作为 transcript compaction 的 L0 事件存在，不再代表 L1 到 L2 的主链路
- 热摘要当前是按需构建快照，尚未落独立表或接入 outbox 失效队列；下一步只有在需要跨实例复用或缓存时才应持久化

## 6. 未来演进方向

- 下一阶段重点不是继续扩大默认 Prompt 注入，而是建立统一冷召回编排：让 L2 历史对话、L3 长期记忆、知识库 chunk、知识库 / 记忆图和 trace 复盘都通过 `RetrievalOrchestrator` 形成结构化 `EvidenceBundle`
- 先做真实流式对话回归集和指标，再做 query 改写、source routing、多源并行召回、RRF 融合排序；GraphRAG / DRIFT 类复杂图检索和学习型重排放到后续阶段
- 简单查询保持直接检索；只有复杂、多约束或跨源问题才启用 query decomposition，避免为了技术堆叠牺牲延迟和可解释性

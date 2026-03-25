# Transcript 重构下的记忆能力守恒方案

> 文档性质：正式设计文档
> 关联文档：[openclaw-style-transcript-refactor-plan.md](./openclaw-style-transcript-refactor-plan.md)
> 最后更新：2026-03-23

## 1. 目标与结论

本次将会话系统重构为 OpenClaw 风格的 `transcript-first` 架构，不允许以“上下文更先进”为理由牺牲现有记忆系统能力。

本方案的核心结论是：

- `transcript` 只替换“会话事实源”和“上下文构建方式”。
- 现有 `semantic / procedural / experience / forgetting / workspace` 仍保留为长期记忆与派生能力层。
- `compaction / pruning` 不是“遗忘系统”，二者必须严格分离。
- 所有当前依赖 `chat_messages / chat_sessions / agent_sessions` 的记忆触发点，都要改为依赖统一事件流和 transcript 投影。
- 最终验收标准不是“新架构能跑”，而是“现有记忆能力一项不少，且在工具结果、Artifact、长会话场景下比现在更强”。

换句话说，这次不是“把记忆系统改成 OpenClaw”，而是“把会话层改成 OpenClaw 风格，同时保证记忆系统能力守恒并增强”。

---

## 2. 当前记忆系统能力盘点

当前项目的记忆系统不是单一模块，而是多层能力组合：

### 2.1 会话内工作记忆

- `SessionWorkspaceService`
- `PendingDecisionItem`
- `TaskStateItem`
- `WorkingSetItem`

职责：

- 保存当前会话的待确认事项
- 保存任务中间状态
- 保存短期工作产物

这部分属于“活动工作记忆”，不是长期知识记忆。

### 2.2 情景记忆

- [EpisodicMemory](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/episodic/EpisodicMemory.java)
- [CompressionService](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/compression/CompressionService.java)

职责：

- 读取会话历史
- 搜索历史消息与片段
- 提供会话级 recall
- 对旧消息做压缩

当前问题：它本质上已经退化成 `chat_sessions/chat_messages` 的读模型，而不是独立事实源。

### 2.3 语义记忆与实时自学习

- [SemanticMemory](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/semantic/SemanticMemory.java)
- [RealtimeExtractor](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/semantic/RealtimeExtractor.java)

职责：

- 从单轮用户输入和助手输出中实时提取实体
- 执行 AUDN：Add / Update / Delete / Noop
- 做冲突检测、版本化、归档

这部分是真正的“自学习”主链路之一。

### 2.4 巩固与模式提炼

- [ConsolidationPipeline](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/consolidation/ConsolidationPipeline.java)
- [EpisodicToSemanticConsolidator](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/consolidation/EpisodicToSemanticConsolidator.java)
- [EpisodicToProceduralConsolidator](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/consolidation/EpisodicToProceduralConsolidator.java)
- `PreferenceConsolidator`
- `ExperienceMerger`

职责：

- 从近期对话中提升高频实体的重要度
- 从长对话触发知识提取
- 从成功执行轨迹中提炼可复用程序模板
- 同步偏好规则
- 合并相似经验

这部分是“模式提炼”和“长期沉淀”的核心。

### 2.5 经验记忆

- [ExperienceSummarizer](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/experience/ExperienceSummarizer.java)
- [SubtaskReflector](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/experience/SubtaskReflector.java)
- [ContrastiveLearner](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/experience/ContrastiveLearner.java)
- [EffectivenessTracker](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/experience/EffectivenessTracker.java)

职责：

- 从完整 ReAct 轨迹提炼经验
- 从工具调用对中提炼子任务经验
- 对成功 / 失败案例做对比学习
- 跟踪注入经验是否真的有效

这是当前项目里最接近“元学习”的能力层。

### 2.6 用户反馈驱动学习

- [FeedbackProcessor](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/feedback/FeedbackProcessor.java)
- [InjectionRecordRepository](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/retrieval/InjectionRecordRepository.java)
- [MessageFeedbackRepository](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/interaction/web/repository/MessageFeedbackRepository.java)

职责：

- 根据用户 like / dislike 调整相关记忆实体的重要度
- 支持回滚旧反馈再应用新反馈

这是当前“在线强化信号”主入口。

### 2.7 遗忘系统

- [ForgettingEngine](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/memory/forgetting/ForgettingEngine.java)
- `HybridPolicy`
- `ReflectionSummaryPolicy`
- `EntityExpirationJob`

职责：

- 选择低价值记忆候选
- 进行归档或压缩
- 维护 `forgetting_log`

这是独立于会话压缩的认知遗忘系统。

---

## 3. 当前能力对底层存储的真实依赖

必须先分清哪些能力天然独立，哪些能力会在 transcript 改造时断掉。

## 3.1 与旧会话表弱耦合的能力

以下能力不依赖 `chat_messages` 作为最终事实源，迁移风险较低：

- `RealtimeExtractor`
  直接吃单轮 `userMessage + aiResponse`
- `ExperienceSummarizer`
  直接吃 `ReactAgentState`
- `SubtaskReflector`
  直接吃 `ReactStep`
- `ContrastiveLearner`
  主要吃 `SemanticMemory + VectorSearcher`
- `EffectivenessTracker`
  主要吃 `ReactAgentState + injection records`
- `ForgettingEngine`
  直接作用于 `SemanticMemory`
- `EpisodicToProceduralConsolidator`
  主要吃 `agent_traces + agent_trace_steps`

这些能力在新架构下只需要重接事件或来源标识，不需要推翻业务语义。

## 3.2 与旧会话表强耦合的能力

以下能力如果直接删掉旧会话表，会立即掉能力：

- `EpisodicMemory`
  当前直接读 `chat_sessions/chat_messages`
- `CompressionService`
  当前直接压缩旧消息表
- `MemoryAutoConfiguration.checkIdleConsolidation()`
  当前用 `SELECT MAX(created_at) FROM chat_messages`
- `EpisodicToSemanticConsolidator`
  当前依赖 `EpisodicMemory.getRecent(...)`
- `ChatController` 历史消息查询接口
  当前通过会话读模型返回消息
- `FeedbackProcessor`
  当前依赖 `messageId -> sessionId -> 注入记录`

结论：

- 真正危险的不是 `SemanticMemory`、`ForgettingEngine` 这些长期记忆模块。
- 真正危险的是“情景记忆读模型”和“依赖消息 ID 的反馈与压缩链路”。

---

## 4. 重构原则

## 4.1 能力守恒原则

所有现有记忆能力必须映射到新架构中的明确位置，不允许出现“这个先下掉，后面再补”的灰区。

## 4.2 事件驱动原则

当前很多记忆能力是“在代码里顺手调一下”。重构后必须改为稳定事件驱动：

- 由事实写入触发
- 不依赖某个 Controller 或某段 Persistence 代码的偶然顺序
- 能被重放和补算

## 4.3 长期记忆与上下文卫生分离原则

- `compaction` 负责长会话可运行
- `pruning` 负责单次请求的上下文裁剪
- `forgetting` 负责长期记忆容量治理

三者绝不混用。

## 4.4 可追溯原则

任何写入长期记忆的内容，都必须能追溯到：

- `session_id`
- `turn_id`
- `trace_id`
- `transcript_entry_id`
- `artifact_id`（如有）

否则后续无法调试、回滚、审计。

## 4.5 先建新投影，再删旧链路

虽然本次不考虑兼容性，但实现顺序仍要满足：

- 新的 transcript 事实源建立完成
- 基于 transcript 的 read model 和事件流能跑通
- 记忆能力迁移完成并验收
- 最后再彻底删除旧表和旧调用链

---

## 5. 目标架构中的记忆分层

重构后记忆系统分成五层：

### 5.1 Transcript Facts

职责：

- 保存会话发生过什么
- 保存 user / assistant / tool_call / tool_result / compaction_summary / artifact_ref / system_event

特点：

- 完整
- 追加写
- 是事实，不做业务推断

### 5.2 Session Read Model

职责：

- 为 Web UI 和情景记忆提供可读投影
- 提供消息列表、会话摘要、检索入口、分支视图

特点：

- 从 transcript 投影而来
- 可重建
- 不是事实源

### 5.3 Durable Memory

职责：

- 长期保留值得记住的内容
- 保存用户偏好、项目记忆、决策日志、长期事实

形式：

- `memory_documents`
- `memory_document_chunks`
- 结构化实体和规则

### 5.4 Structured Memory Index

职责：

- 提供 `SemanticMemory / ProceduralMemory / Experience` 这些高价值可检索结构
- 作为推理注入的高密度索引层

### 5.5 Workspace / Active Artifacts

职责：

- 提供当前任务活跃状态
- 保存中间产物、待确认事项、进行中的任务状态

---

## 6. 现有能力到新架构的逐项映射

| 现有能力 | 当前数据来源 | 当前触发点 | 新架构落点 | 新触发机制 | 守恒要求 |
| --- | --- | --- | --- | --- | --- |
| 会话历史检索 | `chat_sessions/chat_messages` | 读接口实时查询 | `TranscriptBackedEpisodicMemory` + `SessionReadModel` | `TranscriptEntryCommitted` 投影 | 历史搜索、片段召回、按会话读取能力不下降 |
| 旧消息压缩 | `CompressionService + EpisodicMemory` | 定时或手动压缩 | `CompactionEngine` + `compaction_summary` + transcript slice | `CompactionRequested` / overflow 触发 | 保留“摘要化旧历史”的能力，但改成 transcript 级 compaction |
| 实时实体提取 | 单轮 `userMessage + aiResponse` | `AgentPersistenceHandler.asyncPostProcess()` | `RealtimeExtractor` 保留 | `TurnCommitted` / `AssistantReplyCommitted` | 自学习不断流，且可读取工具结果补充上下文 |
| 高频实体提升 | `EpisodicMemory.getRecent()` | `ConsolidationPipeline` | `TranscriptWindowProjector` + `EpisodicToSemanticConsolidator` | `SessionIdle` / `ConsolidationRequested` | 仍能提升高频事实重要度 |
| 长对话知识提取 | 对话全文 | `EpisodicToSemanticConsolidator` | transcript slice + artifact refs | `ConsolidationRequested` | 长对话知识抽取能力不降级 |
| 程序模板提炼 | `agent_traces + agent_trace_steps` | `ConsolidationPipeline` | 维持不变，增加 transcript / artifact 辅助证据 | `TraceCompleted` / `ConsolidationRequested` | 模式提炼能力保留并增强 |
| 偏好同步 | 语义记忆实体 | `ConsolidationPipeline` | 保持 `PreferenceConsolidator` | `MemoryConsolidationRequested` | 偏好规则仍可从记忆中沉淀 |
| 经验提炼 | `ReactAgentState` | `asyncPostProcess` | `ExperienceSummarizer` 保留 | `TraceCompleted` | 主任务经验持续生成 |
| 子任务反思 | `ReactStep.ToolCall/Observation` | `asyncPostProcess` | `SubtaskReflector` 保留 | `TraceCompleted` | 工具级微经验继续生成 |
| 对比学习 | 语义经验实体 | `ExperienceSummarizer` 后续 | `ContrastiveLearner` 保留 | `ExperienceCreated` | 成败对照学习不断流 |
| 经验效果跟踪 | 注入记录 + trace | 注入时 / 结束后 | `EffectivenessTracker` 保留 | `MemoryInjected` + `TraceCompleted` | 低质量经验仍能淘汰 |
| 用户反馈学习 | `message_feedback + injection_records` | 点赞点踩 | `FeedbackProcessor` 保留，但改为 transcript entry 级绑定 | `MessageFeedbackReceived` | 在线强化学习能力保留 |
| 遗忘系统 | `SemanticMemory` | 定时任务 | `ForgettingEngine` 保留 | 定时 / 容量阈值 | 遗忘独立于 compaction |
| 工作区短期记忆 | `session_workspace_items` | suspend / task update | `WorkspaceService` 保留 | `TaskSuspended / ArtifactCommitted / TaskStateChanged` | 当前任务状态能力不变 |

---

## 7. 必须新增的统一事件模型

要保证能力守恒，必须引入统一记忆事件总线。建议最少包含以下事件：

- `SessionStarted`
- `TurnCommitted`
- `TranscriptEntryCommitted`
- `AssistantReplyCommitted`
- `ToolCallCommitted`
- `ToolResultCommitted`
- `ArtifactCommitted`
- `TraceCompleted`
- `MemoryInjected`
- `MessageFeedbackReceived`
- `SessionIdle`
- `CompactionRequested`
- `CompactionCommitted`
- `PreCompactionMemoryFlushRequested`
- `PreCompactionMemoryFlushCommitted`

事件总线的职责：

- 解耦 transcript 写入与记忆写入
- 允许失败重试
- 允许离线补算
- 允许未来引入批处理和回放

---

## 8. 各记忆能力的重构方案

## 8.1 自学习：RealtimeExtractor

### 保留结论

必须保留，而且迁移后应增强。

### 当前问题

当前只看：

- 用户消息
- 助手最终回复

看不到：

- 历史工具结果
- 中间 Artifact
- 结构化观察结果

### 新方案

- 仍保留 `RealtimeExtractor`
- 触发点从 `asyncPostProcess()` 改为 `TurnCommitted`
- 输入从 `userMessage + aiResponse` 升级为：
  - 当前 turn 的 `user_message`
  - 当前 turn 的 `assistant_message`
  - 当前 turn 关键 `tool_result` 摘要
  - 必要的 `artifact_ref` 摘要

### 额外增强

- 在 `PreCompactionMemoryFlush` 前再跑一次“强化抽取”
- 把即将被 prune 的高价值工具结果转成 durable memory 或结构化实体

这样会比现在更强，而不是更弱。

## 8.2 遗忘系统：ForgettingEngine

### 保留结论

必须完整保留，且不能与 compaction 混淆。

### 当前问题

没有架构问题，主要是概念边界容易在 transcript 改造时被误伤。

### 新方案

- `ForgettingEngine` 继续只作用于 `SemanticMemory / Experience / Procedure` 等长期记忆层
- 不对 transcript 做删除决策
- 不参与 provider 上下文 pruning
- `forgetting_log` 继续保留

### 硬性原则

- `compaction` 不能写 `forgetting_log`
- `forgetting` 不能改 transcript 原始事实

## 8.3 模式提炼：EpisodicToProceduralConsolidator

### 保留结论

必须保留，而且会得到更多高质量输入。

### 当前问题

现在主要只依赖 `agent_trace_steps.tool_id/tool_input_json`，缺失：

- tool result 的完整效果
- Artifact 结果
- 失败上下文与分支差异

### 新方案

- 继续以 `agent_traces + agent_trace_steps` 为主
- 补充读取关联的 transcript turn 和 artifact refs
- 聚类样本不再只是“工具调用序列”，而是：
  - 目标
  - 工具调用序列
  - 关键 tool result 摘要
  - 成功 / 失败结果

### 结果

模式提炼能力不仅保留，还会更接近真实操作模式，而不是只看调用顺序。

## 8.4 经验提炼：ExperienceSummarizer / SubtaskReflector / ContrastiveLearner / EffectivenessTracker

### 保留结论

这一组必须整体保留，不能拆散。

### 当前触发链

当前主要在 [AgentPersistenceHandler.java](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/agent/persistence/AgentPersistenceHandler.java) 的 `asyncPostProcess()` 串行调用：

- `ExperienceSummarizer`
- `EffectivenessTracker`
- `ContrastiveLearner`
- `SubtaskReflector`

### 新方案

把这条链改成事件化：

- `TraceCompleted` -> `ExperienceSummarizer`
- `TraceCompleted` -> `SubtaskReflector`
- `ExperienceCreated` -> `ContrastiveLearner`
- `MemoryInjected + TraceCompleted` -> `EffectivenessTracker`

### 关键增强

- `ExperienceSummarizer` 允许引用 transcript 中的 `tool_result` 和 `artifact_ref`
- `EffectivenessTracker` 不再依赖“消息 ID 间接关联”，而是直接依赖 `injection_id / trace_id / turn_id`

## 8.5 用户反馈驱动学习：FeedbackProcessor

### 保留结论

必须保留，但绑定对象要从 `messageId` 升级为 `assistant transcript entry`。

### 当前问题

当前反馈链路强绑定 `chat_messages.id`，如果旧消息表退位，反馈能力会直接断掉。

### 新方案

- 前端反馈目标改成：
  - `assistant_entry_id`
  - 或 `turn_id`
- 新建 `transcript_feedback` 或升级现有反馈表，使其绑定 transcript entry
- `InjectionRecordRepository` 改为记录：
  - `trace_id`
  - `turn_id`
  - `assistant_entry_id`
  - `entity_type`
  - `entity_ids`

### 效果

在线学习能力保留，且关联更稳定。

## 8.6 情景记忆：EpisodicMemory

### 保留结论

能力必须保留，但实现必须重写。

### 当前问题

当前 `EpisodicMemory` 已经不是“情景记忆事实层”，而是旧会话表的查询包装。

### 新方案

引入：

- `TranscriptBackedEpisodicMemory`
- `SessionReadModelProjector`

职责分离：

- `transcript` 保存事实
- `SessionReadModel` 保存 UI 和 recall 友好的投影
- `EpisodicMemory` 只做查询接口抽象，不再直接依赖旧表

### 必保能力

- `getRecent(...)`
- `search(...)`
- `searchSnippetsExcludingSession(...)`
- `getById(...)`
- `getMessagesBySessionId(...)`

这些能力都要在 transcript 投影层重建。

## 8.7 旧压缩链：CompressionService

### 保留结论

“压缩能力”要保留，“旧实现”要删除。

### 新方案

废弃旧 `CompressionService` 的“原消息压缩回写 messages 表”方式，改为：

- `CompactionEngine`
- `compaction_summary` transcript entry
- `PreCompactionMemoryFlush`

原有“压缩旧会话以降低上下文成本”的目标被保留，但表达方式从“覆盖旧消息”改为“追加摘要节点”。

---

## 9. 新增的 Durable Memory 设计

为了真正对齐 OpenClaw 风格，且不削弱项目现有记忆系统，必须新增“可读 durable memory 文档层”。

## 9.1 为什么必须新增

当前系统大多是：

- 结构化实体
- 规则
- 经验对象

缺少：

- 可直接阅读的长期记忆文档
- 项目级 / 日期级 / 决策级归档视图

这会导致 transcript 一旦 compaction，很多高价值上下文只能靠零散实体维持，表达力不够。

## 9.2 新的 memory documents

建议新增：

- `memory_documents`
- `memory_document_chunks`

建议文档类型：

- `daily_log`
- `long_term_memory`
- `project_memory`
- `decision_log`
- `user_profile`
- `tooling_notes`
- `artifact_digest`

## 9.3 与现有结构化记忆的关系

- `memory_documents` 是人类可读、模型可引用的长期记忆正文
- `SemanticMemory` 是结构化事实索引
- `ProceduralMemory` 是流程模板索引
- `Experience` 是经验索引

三者共存，不互相替代。

## 9.4 新增的 Pre-compaction Memory Flush

这是本次守恒方案的关键增强点。

当会话接近上下文阈值时：

1. 先触发一次静默 `memory flush`
2. 从近期 transcript、tool result、artifact 中抽取 durable memory
3. 再进行 compaction

这样可以避免：

- 工具结果被 prune 后信息丢失
- 长会话里重要决策只存在于即将压缩的上下文中

---

## 10. 新事件与新存储下的触发重连方案

## 10.1 当前触发点

当前关键触发点主要散落在：

- `AgentPersistenceHandler.asyncPostProcess()`
- `ChatController.submitFeedback()`
- `MemoryAutoConfiguration.checkIdleConsolidation()`
- 定时任务 `ForgettingEngine` / `EntityExpirationJob` / `EpisodicCleanupJob`

## 10.2 目标触发点

重构后统一改成：

- `TurnCommitted`
  - 实时实体提取
  - 会话读模型刷新
- `TraceCompleted`
  - 经验提炼
  - 子任务反思
  - 效果评估
- `ExperienceCreated`
  - 对比学习
- `MessageFeedbackReceived`
  - 反馈调权
- `SessionIdle`
  - 巩固管线
- `CompactionRequested`
  - 先 memory flush 再 compaction

## 10.3 事件调度要求

- 允许异步执行
- 允许失败重试
- 允许幂等
- 允许按 `session_id / trace_id` 回放

---

## 11. 实施顺序

这份文档只定义记忆能力守恒要求，但也必须给出落地顺序。

### 阶段 1：建立 transcript 与事件底座

- 新建 transcript store
- 新建 session store
- 新建 transcript projector
- 新建 memory event bus

### 阶段 2：重建情景记忆读模型

- 实现 `TranscriptBackedEpisodicMemory`
- 把 Web 历史消息读取迁到 transcript projection
- 把 `CompressionService` 替换为 `CompactionEngine`

### 阶段 3：迁移实时学习与经验链

- `RealtimeExtractor` 改接 `TurnCommitted`
- `ExperienceSummarizer`、`SubtaskReflector`、`EffectivenessTracker` 改接 `TraceCompleted`
- `ContrastiveLearner` 改接 `ExperienceCreated`

### 阶段 4：迁移反馈与注入链

- 注入记录改为绑定 `trace_id / turn_id / assistant_entry_id`
- 反馈改为绑定 transcript entry
- `FeedbackProcessor` 切到新链路

### 阶段 5：迁移巩固与长期文档

- 新建 `memory_documents`
- 加入 `PreCompactionMemoryFlush`
- `EpisodicToSemanticConsolidator` 改用 transcript window

### 阶段 6：删除旧事实源

- 删除 `chat_messages` 事实源职责
- 删除 `chat_sessions` 事实源职责
- 删除 `agent_sessions` 恢复职责
- 删除旧 `CompressionService` 写回链

---

## 12. 验收标准

只有满足以下条件，才能认为本次 transcript 重构没有损失记忆系统能力：

### 12.1 自学习验收

- 单轮对话后仍能自动写入和更新语义实体
- 工具结果中的高价值事实能进入长期记忆
- 长会话 compaction 后，重要事实不因 prune 而丢失

### 12.2 遗忘验收

- `ForgettingEngine` 仍能独立运行
- `forgetting_log` 正常写入
- compaction 不会错误触发 forgetting 统计

### 12.3 模式提炼验收

- 成功执行轨迹仍可提炼 `ProcedureTemplate`
- 提炼结果能引用工具结果与 artifact 摘要
- 提炼质量不低于旧实现

### 12.4 经验记忆验收

- `ExperienceSummarizer`、`SubtaskReflector`、`ContrastiveLearner`、`EffectivenessTracker` 全部可跑通
- 经验注入后仍能根据结果上调或淘汰

### 12.5 反馈学习验收

- 点赞点踩仍可回写相关记忆分数
- 反馈切换时仍可回滚上一次影响

### 12.6 情景记忆验收

- Web UI 历史消息读取正常
- recall 搜索与片段召回正常
- fork / branch 历史可读

---

## 13. 必补测试清单

### 后端单元测试

- `TranscriptBackedEpisodicMemoryTest`
- `MemoryEventBusDispatchTest`
- `RealtimeExtractor_事件驱动测试`
- `FeedbackProcessor_TranscriptEntry绑定测试`
- `PreCompactionMemoryFlushTest`

### 后端集成测试

- `TurnCommitted -> RealtimeExtractor -> SemanticMemory`
- `TraceCompleted -> ExperienceSummarizer/SubtaskReflector/EffectivenessTracker`
- `ExperienceCreated -> ContrastiveLearner`
- `MessageFeedbackReceived -> FeedbackProcessor`
- `SessionIdle -> ConsolidationPipeline`
- `CompactionRequested -> PreCompactionMemoryFlush -> CompactionCommitted`

### 回归测试

- 历史 recall 质量不下降
- 高频实体提升仍有效
- 经验注入有效性追踪仍有效
- forgetting 与 compaction 指标完全分离

---

## 14. 最终结论

如果只是把会话系统替换成 OpenClaw 风格 transcript，而不补这份文档中定义的事件流、投影层、durable memory 文档层和反馈重绑机制，那么现有记忆系统能力一定会掉。

但如果严格按本方案执行，结果会是：

- 自学习保留并增强
- 遗忘系统完整保留
- 模式提炼完整保留并获得更丰富输入
- 经验学习链完整保留
- 情景记忆从旧会话表耦合中解耦
- transcript、memory、workspace 三层职责终于清晰

因此，本次 OpenClaw 风格改造的正确目标不是“简化记忆系统”，而是：

> **把 transcript 变成统一事实源，把记忆系统变成真正独立、可追溯、可增强的长期学习层。**

# Agent 上下文消费与压缩机制 — 架构文档

> **文档性质**：Agent 上下文组装、记忆消费、对话压缩的**实际实现**说明（与代码对齐）
> **最后更新**：2026-06-12（context-management-optimization 特性对齐）
> **关联文档**：[memory-system.md](./memory-system.md) / [agent-learning.md](./agent-learning.md)

---

## 1. 核心规则（一句话）

| 规则 | 实现 |
|------|------|
| 当前会话历史自动进 Prompt | 按 token 预算从近往前选完整轮；早期轮由 compaction 摘要让位 |
| 跨会话历史不进 Prompt | Agent 通过 `memory(action="recall")` 按需召回 |
| 长期记忆和决策信号小预算注入 | L3.5 热摘要（`HotMemoryDigestService`）+ L4 决策信号（`AdaptiveDecisionEngine`） |
| 历史工具结果不进 Prompt（只留一行预览） | `SessionPruningEngine.formatToolResultPreview` 内联生成；完整原文经 `transcript.get` 召回 |
| 压缩只针对当前会话历史 | 唯一机制 `CompactionEngine`，追加 `compaction_summary` 条目 |

---

## 2. 信息距离分层

按 Agent 获取信息的"距离"分 5 层，距离越近越自动，越远越需主动召回。

```
距离 0 — 当前轮工具执行（完整 output，仅超长截断）
距离 1 — 当前会话历史（transcript 选轮 + compaction 摘要）
距离 2 — 跨会话对话（memory.recall）
距离 3 — 长期事实（L3 实体 / memory.search、L3.5 热摘要自动注入）
距离 4 — 派生知识（L4 决策信号自动注入 / memory.search-experience）
```

| 层 | 默认呈现 | 召回方式 | 压缩/裁剪策略 |
|---|---------|---------|---------|
| 0 当前轮 | 完整工具调用 + 结果 | — | 仅超长截断（web.search 走预览，失败输出 600 字截断） |
| 1 会话历史 | 按预算选完整轮 + `compaction_summary` 摘要 | — | CompactionEngine 摘要 + SessionPruningEngine 裁工具结果 |
| 2 跨会话 | 不注入 | `memory.recall` | 召回片段按 topK 截断 |
| 3 长期事实 | L3.5 热摘要（四 section 独立预算） | `memory.search` | 各 section token/条目上限 |
| 4 派生知识 | L4 决策信号（小预算） | `memory.search-experience` | 决策信号本身精简 |

---

## 3. 上下文组装管线（ContextAssembler）

`com.lifepilot.agent.context.ContextAssembler.assemble(state)` 每轮 ReAct 调用，**组装期不调 LLM（快速路径）**，内部用 Virtual Thread 并行加载会话快照与热记忆：

```
assemble(state)
  ├─ 并行① ContextEngine.load() ── transcript 选轮 + compaction 边界 + 工具结果裁剪 + 工作区 + artifact
  ├─ 并行② HotMemoryDigestService.build() ── L3.5 热摘要（user_profile / project / experience / facts）
  ├─ systemPrompt = role-definition + context-guide + react-system(tool_protocol/groundedness)
  │                 + skill catalog + mcp catalog + completion_contract(模板) + decision_context(L4)
  ├─ contextMessages = user_profile / workspace / artifact / experience / memory section
  ├─ userPrompt = runtime_context(时间/位置/天气/OS/channel) + loaded_skills + knowledge_binding + goal
  └─ ProviderMessageBuilder.build() ── 拼为 provider 消息序列 + ReAct 步骤 + 卫生化
```

- 提示词模板由 `PromptRegistry` 管理（`prompts/agent/*.st`，`{key}` 占位符渲染）。`completion_contract` 与模式规则已迁移为 `agent/completion-contract.st`、`agent/task-mode-{cron,heartbeat,interactive}.st`。
- 失败降级：组装异常回退最小提示词（`buildFallbackContext`），热摘要构建失败本轮跳过默认记忆注入。

### Token 估算（统一口径）

全链路统一使用 `com.lifepilot.memory.consumption.compression.TokenEstimator.estimate`：按 UTF-16 code unit，ASCII（`c<=0x7F`）0.25 token、非 ASCII 1.5 token，四舍五入取下限 1；null/空返回 0。上下文管理范围内不再有内联估算实现（`knowledge` 模块 RAG 分块与 `ReactAgentLoop` 读 provider 实际用量除外）。

---

## 4. 压缩机制（唯一：CompactionEngine）

`com.lifepilot.agent.context.CompactionEngine` 是**唯一**的对话压缩机制（旧 `CompressionService` 滑动窗口压缩已作为死代码删除，`session_transcript_compressions` 表由 V6 迁移 DROP）。

### 4.1 触发

`compactIfNeeded(sessionId, traceId, preferredProviderId)`，两个触发点经 **per-session ReentrantLock 串行化**（防并发写双摘要）：
- 轮中：`ReactAgentLoop.maybeCompactMidLoop`（仅非首轮兜底）
- 轮末：`AgentPersistenceHandler` 异步后处理（主路径）

触发阈值（`shouldCompact`）：
```
有效窗口 = (maxContextTokens<=0 ? provider窗口 : min(maxContextTokens, provider窗口))
transcript 可用窗口 = max(1024, 有效窗口 - fixedOverheadReserveTokens)   // 预留固定开销
阈值 = transcript 可用窗口 × triggerThresholdPercent(默认75%)
当活跃可见 transcript 条目 token 之和 ≥ 阈值，且完整轮数 ≥ minTurnCount(6) 时压缩
```
`fixedOverheadReserveTokens`（默认 8000）代表 systemPrompt + skill/mcp catalog + 自动注入（热摘要/决策信号/工作区/检查点）等非 transcript 固定开销，使压缩在真实逼近窗口时及时触发。

### 4.2 算法与产物

1. 解析上次 compaction 边界（`TranscriptCompactionBoundaryResolver`），取活跃条目。
2. 按完整轮分组，保留最近 `keepRecentTurns`(默认2) 轮，`firstKeptEntryId` 标记保留区起点。
3. 压缩前 `PreCompactionMemoryFlushEngine.flush` 把待让位条目刷入记忆文档（可经 `transcript.get` / memory 文档找回）。
4. LLM 生成 summary（`memory/compression-summary`）+ keyPoints（`memory/compression-keypoints`）+ 结构化 `TaskCheckpoint`。
5. 追加一条 `compaction_summary` transcript 条目，payload 含 `summary / firstKeptEntryId / keyPoints / checkpoint / memoryDocumentIds`，不改写旧条目。

读取上下文时（`ContextEngine`），只保留 `firstKeptEntryId` 之后的原始 transcript，并把 `<history_summary>` 与 `<task_checkpoint>` 作为消息注入历史头部。

### 4.3 工具结果裁剪（SessionPruningEngine）

历史 `tool_result` 不进完整原文，由 `formatToolResultPreview` 生成一行预览（`web.search`/`web.fetch` 有专门摘要）；按 `recentToolResultLimit`(4) 保留最近 N 条、失败结果 pin（`failedToolResultLimit`2）；软截断/硬清空由 `trimForPrompt` 控制。**不存在独立的 `ToolResultSummarizer` 类**。

### 4.4 LLM 不可用 / 降级

压缩为非关键路径，失败静默降级（下一轮重判）。组装快速路径永不调 LLM。

---

## 5. 工具结果的两种形态

| 形态 | 位置 | 用途 |
|------|------|------|
| **完整 raw output** | L0 `session_transcript_entries.tool_result` payload | 审计、回放、Agent 按需召回 |
| **一行预览** | Prompt 注入时由 `SessionPruningEngine.formatToolResultPreview` 内联生成 | 让 Agent 知道"这一步发生了什么"，省 token |

例外：当前轮工具结果保留 raw output（`web.search` 走结构化预览，失败输出截断 600 字）。

---

## 6. 跨会话 / 历史召回工具

| 工具 | 解决的问题 | 返回内容 | 实现 |
|------|----------|---------|------|
| `memory(action="recall")` | "我们之前聊过什么"（跨会话） | 对话片段（user/assistant 文本，**不含工具调用摘要**） | `MemoryToolProvider.executeRecall` → `EpisodicMemory.searchSnippetsExcludingSession` |
| `transcript.search` | "当前会话里那次工具调用" | tool_result 预览列表（含 entryId/callId） | `TranscriptToolProvider` |
| `transcript.get` | "那次工具调用的完整原文" | 单条 tool_result 完整 outputJson（默认上限 30000 字符） | `TranscriptToolProvider` |
| `memory(action="search")` | "我对 X 的偏好/事实" | L3 实体 | `MemoryToolProvider.executeSearch` + `HybridRetriever` |
| `memory(action="search-experience")` | "之前类似任务怎么做的" | EXPERIENCE 实体 | `MemoryToolProvider.executeSearchExperience` |

> 注：不存在 `memory.fetch-transcript` action；"按 entryId/callId 取历史工具结果原文"的能力由 `transcript.search` / `transcript.get` 提供。

---

## 7. 对话级预算（Budget）

`com.lifepilot.agent.model.Budget` 为对话级安全护栏，含三维：
- **steps / duration**：防失控循环，任一超限优雅终止。
- **token**：`maxTokens`/`tokensUsed`/`deductTokens` 作粗粒度安全上限与**用量遥测**（被 multiagent 子代理预算分配与可观测性 `AgentResponse.tokensUsed`/`TokenConsumptionStats`/trace 消费）。

> 历史上的"渐进降级阶梯"（`degradationLevel`/`DegradationLevel`/`AssembledContext.degrade`，按 token 使用率 0.80/0.90/0.95 分档裁剪）已删除——几乎不触发且名实不符。"上下文窗口装不下"由压缩链路（§4）与历史选轮兜底处理，与 token 遥测正交。

> `TokenBudget`（`com.lifepilot.agent.context.TokenBudget`，单次窗口六槽位分配/消耗记录）与对话级 `Budget` 是**两个不同概念**：前者服务每轮上下文核算与 observability/web 预览，后者服务对话级安全上限。

---

## 8. 不变量

- L0 transcript 永远完整保存（含工具完整结果），任何压缩只影响 Prompt 注入。
- 当前轮工具结果保留 raw output（仅超长截断）；历史轮工具结果在 Prompt 中只是一行预览。
- 对话压缩只有 `CompactionEngine` 一套机制，摘要由 LLM 生成、失败静默降级。
- 跨会话内容永远不自动注入，只能经 `memory.recall` 等工具召回。
- L3.5 热摘要 + L4 决策信号有独立小预算，不挤占会话历史预算。
- Token 估算全链路统一口径（`TokenEstimator`）。
- 组装快速路径永不调 LLM；会话快照与热记忆并行加载。

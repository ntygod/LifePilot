## ZhiWei Agent 记忆生命周期实现说明

> 本文是对 `AgentLoop` 与多层记忆系统当前「实际实现行为」的梳理，用于和设计文档 `agent-memory-lifecycle.md` / `memory-advanced.md` 对照。侧重点是：一次请求在代码里如何读/写 Session、L1 工作记忆、L2 情景记忆与混合检索。

---

### 1. 顶层概览：一次请求的真实链路

按时间顺序，一个请求从进入到写回记忆，大致经历以下步骤：

1. **入口**：`AgentLoop.run(..)` / `runStreaming(..)` 收到 `AgentRequest`
2. **会话加载 + L1 回灌**：
   - 用 `SessionManager.findSession` 读取 `SessionSnapshot`（来自 `agent_sessions`）
   - 用 `AgentState.fromSession(snapshot, request)` 恢复 `AgentState`
   - 用 `hydrateWorkingMemoryFromSnapshot(snapshot)` 把 `recentTurns` / L2 片段回灌到 L1 `WorkingMemory`
3. **主循环**（多轮 Action）：
   - 检查迭代次数与时间/Token 预算
   - `ContextAssembler.assemble` 组装上下文（读取 L1、调用 `HybridRetriever` 检索 L2/L3/L4）
   - 调用 LLM / 工具，解析为 `Action`，通过 `StateReducer.reduce` 更新 `AgentState`
4. **终止后异步后处理**（Virtual Thread）：
   - `SessionManager.saveSession` 写入/更新 `agent_sessions`（L0「快照层」）
   - `saveMessagesToMemory` 把本轮用户消息 + 最终回复写入 L1 `WorkingMemory`
5. **后台 flush**（定时任务）：
   - 调用 `WorkingMemory.cleanupIdleSessions` 找到空闲会话
   - 对每个空闲会话执行 `WorkingMemory.flush(sessionId)`：
     - 组装 `ConversationRecord` + `MessageRecord`，调用 `EpisodicMemory.save` 写入 L2
     - 清理 L1 对应 session 的 Slot 与 Token 计数
6. **下次请求**：
   - 再次从 `SessionSnapshot`（必要时再从 L2）回灌 L1，然后重复第 3–5 步。

---

### 2. 会话加载与 L1 回灌：`initState` + `hydrateWorkingMemoryFromSnapshot`

#### 2.1 `AgentLoop.initState`

入口 `run(..)` / `runStreaming(..)` 的前半段逻辑一致，都会先调用：

- `SessionManager.findSession(request.sessionId())`：
  - 命中时返回 `SessionSnapshot(sessionId, channelId, recentTurns, mentionedEntities, lastActiveAt, totalTurns, totalTokensUsed)`；
  - 未命中时返回 empty。
- 有快照时：
  - `AgentState.fromSession(snapshot, request)`：用 `recentTurns` 等恢复 `AgentState`；
  - `hydrateWorkingMemoryFromSnapshot(snapshot)`：负责把历史对话回灌到 L1。
- 没有快照时：
  - `AgentState.init(request)`：初始化全新的 `AgentState`，L1 初始为空。

#### 2.2 `SessionSnapshot` 与快照层持久化

`SessionSnapshot` 的职责是保存最近 N 轮对话与聚合信息：

- 字段：
  - `recentTurns`：`ConversationTurn` 列表（最近 N 轮，包含 user/assistant 文本）；
  - `mentionedEntities`：提到过的实体 ID；
  - `lastActiveAt` / `totalTurns` / `totalTokensUsed`：会话统计。
- 持久化由 `SessionManager.saveSession(AgentState state)` 完成，逻辑：
  - 从 `AgentState` 构造新的 `ConversationTurn`（使用 `state.goal()` 作为 user，`state.finalOutput()` 作为 assistant）；
  - 若已有 session：
    - 复制历史 `recentTurns`，追加本轮 `newTurn`；
    - 截断到配置的 `maxRecentTurns`（只保留最近 N 轮）；
    - 累加 `totalTurns` 与 `totalTokensUsed`。
  - 若无 session：
    - 新建 `recentTurns = [newTurn]`；
    - 初始化统计字段。

> 结论：**每次 AgentLoop 结束时都会写入/更新一次 `SessionSnapshot`**，它是恢复上下文时优先使用的数据源，不直接参与 Prompt 组装。

#### 2.3 `hydrateWorkingMemoryFromSnapshot`：L1 回灌逻辑

`hydrateWorkingMemoryFromSnapshot(SessionSnapshot snapshot)` 的职责是，在 **L1 对应 session 还没有任何 Slot 时**，用快照 / L2 数据补齐工作记忆：

- 前置检查：
  - 若 `workingMemory` 为空（Bean 未注入）则直接返回；
  - 调用 `workingMemory.getContext(sessionId)`：
    - 如已存在且非空，**直接返回**（避免重复回灌）。
- 优先使用 `SessionSnapshot.recentTurns`：
  - 遍历 `recentTurns` 中每个 `ConversationTurn`：
    - 若 `userMessage` 非空：
      - 使用 `estimateTokens(userMessage)` 估算 Token 数；
      - 构造 `ConversationSlot.userMessage(...)`，调用 `workingMemory.append(sessionId, slot)`；
    - 若 `agentResponse` 非空：
      - 同样估算 Token，构造 `ConversationSlot.assistantMessage(...)`，追加到 L1。
- 若 `recentTurns` 为空且 `EpisodicMemory` 非空：
  - 调用 `episodicMemory.getMessagesBySessionId(sessionId)` 获取该会话所有消息；
  - 仅取最近 20 条，根据 `role` 字段还原为 user/assistant `ConversationSlot`；
  - 按时间顺序写入 L1。

> 设计含义：**L1 被视为可丢弃的缓存层**。一旦 JVM 重启或 L1 被清空，可以通过 `SessionSnapshot.recentTurns`（优先）和 L2 `EpisodicMemory` 重新构建最近一段对话时间线。

---

### 3. 主循环与上下文组装：`ContextAssembler.assemble`

#### 3.1 主循环骨架

在 `run(..)` 中，核心循环结构大致为：

- 初始化：
  - `state = initState(request)`；
  - `limits = LoopLimits.from(config)`；
  - `counters = new LoopCounters()`；
  - `loopStart = Instant.now()`。
- 循环（`for (int iteration = 0; !state.isDone(); iteration++)`）：
  1. 迭代上限检查：`forceTerminateIfIterationLimitReached`；
  2. 预算更新与超限检查：`updateBudgetElapsed` + `forceTerminateIfBudgetExceeded`；
  3. **上下文组装**：`var assembled = assembleContext(request, state)`；
  4. LLM 调用与解析：`Action action = callLlmAndParseAction(request, state, assembled)`；
  5. 状态归约与记录：`state = reduceAndRecord(state, action, traceContext, loopStart)`；
  6. 连续异常/阻断保护：`counters.onAction(action, state, limits)`。
- 循环结束后调用 `asyncPostProcess(state)`，返回 `state.toResponse()`。

**与记忆系统直接相关的是第 3 步：**`assembleContext` → `ContextAssembler.assemble`。

#### 3.2 `ContextAssembler.assemble` 完整版行为

完整版 `ContextAssembler` 在非 basic 模式下，会做以下事情：

1. 根据 `state.phase()` 从 `MemoryRetrievalStrategy` 获取当前阶段的检索策略（是否跳过、Top-K、权重等）。
2. 调用 `hybridRetriever.retrieve(state.goal(), topK, weights)`：
   - 并行触发：
     - 向量检索：从 L3 `SemanticMemory` 中找相关实体；
     - FTS 检索：对对话文本/文档做全文搜索；
     - 图遍历：基于 SQLite 图索引做关系扩散；
   - 融合三路结果，打分排序，返回统一的 `RetrievalResult` 列表。
3. 调用 `workingMemory.getContext(state.sessionId())`：
   - 获取当前会话的所有 `WorkingMemorySlot`：
     - `ConversationSlot`：用户/助手对话；
     - `ToolResultSlot`：工具调用结果摘要；
     - `ReasoningSlot`：中间推理/计划文本。
4. 调用 `tokenBudgetAllocator.allocate(..)`：
   - 根据当前对话轮数、检索结果质量、模型上限，分配：
     - 检索结果可用 Token；
     - 工作记忆可用 Token；
     - 工具结果/推理/缓冲的 Token 预算。
5. 按预算截断：
   - `truncateByBudget(retrievalResults, retrievalBudget)` 保留少量高分记忆片段；
   - `truncateSlotsByBudget(slots, workingMemoryBudget)` 保留最近/高重要度的 Slot。
6. 格式化检索结果为字符串列表，例如：
   - `"【情景记忆】2025-02-10 用户提到：……"`；
   - `"【语义记忆】用户偏好：……"` 等。
7. 统计 WorkingMemory Token 使用量，并构造一份 `TokenBudget`。
8. 构建 Prompt：
   - `systemPrompt = buildSystemPrompt(state.phase())`；
   - `userPrompt = buildEnhancedUserPrompt(state, formattedMemories, truncatedSlots)`：
     - 包含：
       - `state.goal()`（本轮用户请求）；
       - “相关记忆”列表（来自 `HybridRetriever`）；
       - “对话历史”：从截断后的 `ConversationSlot` 中按时间顺序拼接；
       - 工具结果：从 `ToolResultSlot` 中提取；
       - 推理上下文：从 `ReasoningSlot` 中提取；
       - 已执行步骤、剩余预算等元信息。
9. 构建 `AssembledContext`，并记录日志 / 埋点（检索数量、最高分、Token 占用、是否降级等）。

> 结论：**L1 工作记忆中的对话槽位与混合检索的记忆片段，都已经实际参与 Prompt 构造**。对话历史以“对话历史”段注入，记忆检索结果以“相关记忆”段注入。

---

### 4. 写回记忆：`asyncPostProcess`、`SessionManager` 与 `WorkingMemory`

#### 4.1 异步后处理：`asyncPostProcess`

循环结束后，`AgentLoop` 会开启一个 Virtual Thread 做异步持久化：

- `sessionManager.saveSession(finalState)`：
  - 按前文 2.2 逻辑写入/更新 `agent_sessions`，维护：
    - 最新一轮 `ConversationTurn`；
    - 截断后的 `recentTurns`；
    - 聚合统计字段。
- 若 `workingMemory` 不为 null，则执行 `saveMessagesToMemory(finalState)`：
  - 负责把“本轮用户消息 + 最终助手回复”写入 L1。

#### 4.2 写回 L1：`saveMessagesToMemory`

`saveMessagesToMemory(AgentState state)` 的逻辑：

- 提取：
  - `sessionId = state.sessionId()`；
  - `userMessage = state.goal()`（本轮用户输入）；
  - `assistantResponse = state.finalOutput()`（最终回答）。
- 对每条非空消息：
  - 调 `estimateTokens` 估算 Token 数；
  - 构造对应的 `ConversationSlot.userMessage(..)` / `ConversationSlot.assistantMessage(..)`；
  - 通过 `workingMemory.append(sessionId, slot)` 追加到 L1。

> 注意：这里不会直接写 L2。**L2 的写入路径是通过 L1 的 `flush` 实现的**（见下一节）。

---

### 5. L1 工作记忆的行为：`WorkingMemory`

#### 5.1 数据结构

`WorkingMemory` 内部维护三份映射：

- `sessions: Map<sessionId, List<WorkingMemorySlot>>`：
  - 每个 session 对应一个同步列表，存储该会话所有 Slot。
- `tokenUsage: Map<sessionId, Integer>`：
  - 记录每个 session 当前使用的 Token 总数。
- `lastActivity: Map<sessionId, Instant>`：
  - 记录会话最后一次写入 Slot 的时间。

#### 5.2 写入与淘汰：`append`

`append(String sessionId, WorkingMemorySlot slot)` 的行为：

- 若 `sessions` 中不存在该 session：
  - 初始化 `Collections.synchronizedList(new ArrayList<>())`。
- 将 Slot 追加到列表尾部；
- 更新 `tokenUsage[sessionId] += slot.tokenCount()`；
- 更新 `lastActivity[sessionId] = now`。
- 从配置 `MemoryProperties.workingMemoryTokenBudget` 取出 L1 Token 预算；若超出：
  - 循环调用 `findEvictionCandidate(slots)` 找要淘汰的 Slot；
  - 按如下优先级淘汰：
    - 类型优先级：`ReasoningSlot(0) → ToolResultSlot(1) → ConversationSlot(2)`；
    - 同类型内：按 `importance` 升序（重要度低的先淘汰）；
    - `ConversationSlot` 若 `isPinned == true` 则不会被淘汰；
  - 从列表移除 `victim`，并从 `tokenUsage` 中扣减对应 Token。

> 结论：L1 是「有 Token 预算控制的短期工作区」，优先保留对话内容，其次工具结果，最后推理 Slot，且支持通过 `pinned` 标记保护关键对话。

#### 5.3 读取：`getContext`

`getContext(String sessionId)`：

- 若不存在对应列表，返回空 List；
- 否则在同步块中返回 `List.copyOf(slots)` 的不可变拷贝。

这是 `ContextAssembler` 读对话历史与工具结果的唯一入口。

#### 5.4 Flush 到 L2：`flush` 与 `cleanupIdleSessions`

`flush(String sessionId)` 用于将某个会话的对话时间线持久化到 L2：

- 读取 `sessions.get(sessionId)`，若为空或无 Slot，则直接返回；
- 遍历 Slot 列表，筛选出所有 `ConversationSlot`：
  - 为每条 Slot 创建一个 `MessageRecord`：
    - 字段包含：`messageId`、`conversationId`、`role`、`content`、`compressionLevel`（默认为 `ORIGINAL`）、`pinned`、`toolCallJson`、`tokenCount`、`createdAt` 等；
- 如消息列表非空：
  - 构造 `ConversationRecord`：
    - `conversationId` 为新的 UUID；
    - `sessionId` 与标题（例如 `"会话记录"`）；
    - 包含上述 `messages` 列表；
    - `createdAt` / `updatedAt` 为当前时间；
  - 调用 `episodicMemory.save(record)` 写入 L2。
- 最后清理：
  - 从 `sessions`、`tokenUsage`、`lastActivity` 中移除该 session。

`cleanupIdleSessions(Duration idleThreshold)` 则是触发 flush 的机制：

- 若 `idleThreshold` 为 null/非正数，则直接返回；
- 遍历 `lastActivity`：
  - 对于每个 session，计算 `Duration.between(lastActive, now)`；
  - 若大于 `idleThreshold`，加入待清理列表；
- 对每个 idle session 调用 `flush(sessionId)`；
- 在日志里记录被清理的 session 及其空闲时间。

> 设计语义：不是“每轮结束就 flush”，而是 **由空闲清理任务在会话长时间无活动后，将整段对话归档到 L2 并释放 L1 资源**。

---

### 6. 混合检索：`HybridRetriever` 与多层记忆的读取

`HybridRetriever.retrieve(String query, int topK, RetrievalWeights weights)` 的关键行为：

- 并行触发三路检索（通过 Virtual Thread Executor）：
  - **向量检索**：`vectorSearcher.searchEntities(query, topK, minScore)`，从 L3 `SemanticMemory` 的实体向量库中召回；
  - **全文检索（FTS）**：`ftsSearcher.search(query, topK)`，从对话文本/文档索引中召回；
  - **图遍历**：`graphTraverser.traverse(query, topK)`，沿图结构进行关系扩散与匹配。
- 可选 L4 程序记忆意图匹配：
  - 若 `intentMatcher` 不为空，调用 `intentMatcher.match(query)`：
    - 命中时生成一条 `ReasoningSlot`，记录「操作模板建议」及其匹配度、成功率、步骤数等信息；
    - 目前该 `ReasoningSlot` 存在 `HybridRetriever` 内部（`lastProcedureSlot`），需要显式取出并注入到 L1 / Prompt 才会生效。
- 对三路结果进行：
  - 归一化评分；
  - 自适应权重融合与 RRF；
  - 时间衰减与重要度加成；
  - 去重、排序、Top-K 截断。
- 对最终命中的语义实体，调用 `semanticMemory.incrementAccessCount` 批量更新访问计数。

在整体生命周期中，`HybridRetriever` 的输出通过 `ContextAssembler.assemble` 的“相关记忆”段参与 Prompt 构造，实现 L2/L3/L4 对当前推理的增强。

---

### 7. 代码级生命周期总结

根据上述分析，可以将一次请求的代码级生命周期总结为「五个阶段」：

1. **会话加载阶段**
   - `SessionManager.findSession` → `SessionSnapshot`；
   - `AgentState.fromSession` → 恢复 `AgentState`；
   - `hydrateWorkingMemoryFromSnapshot` → 快照/L2 → L1 回灌。
2. **推理阶段（主循环）**
   - `ContextAssembler.assemble`：
     - 从 L1 读取对话历史/工具结果/推理 Slot；
     - 调用 `HybridRetriever` 检索 L2/L3/L4 记忆；
     - 在 Token 预算内构造 Prompt。
   - LLm / 工具调用 → `StateReducer.reduce` → 新的 `AgentState`。
3. **写回阶段**
   - `asyncPostProcess`：
     - `SessionManager.saveSession` 写入 `agent_sessions`（L0 快照）；
     - `saveMessagesToMemory` 写入 L1 `WorkingMemory`。
4. **归档阶段**
   - 定时任务调用 `WorkingMemory.cleanupIdleSessions`：
     - 对空闲 session 执行 `flush`；
     - 调用 `EpisodicMemory.save` 把对话时间线落盘到 L2。
5. **恢复阶段**
   - 当下次请求到来且 L1 中无该 session Slot 时：
     - `hydrateWorkingMemoryFromSnapshot` 先从 `SessionSnapshot.recentTurns` 回灌；
     - 如果快照没有 recentTurns 且 L2 有记录，再从 `EpisodicMemory` 加载最近若干条消息回灌。

这条链路与设计文档中的“L1 工作记忆 / L2 情景记忆 / Session 快照层 / 混合检索”分工基本一致，可作为后续做「设计 vs 实现差异清单」时的基础参考。

---

### 8. 用途与后续工作建议

- **用途**
  - 作为阅读代码时的导览：快速定位「某一步记忆是在哪个类/方法里读写的」；
  - 作为和 `agent-memory-lifecycle.md` 的对照文件：检查实现是否严格遵循设计意图。
- **后续工作建议**
  - 可以基于本文再写一份「差异清单」：
    - 每个设计步骤对应到具体方法；
    - 标出「已实现 / 未实现 / 行为有差异」；
    - 为每一项差异列出修复建议与优先级。


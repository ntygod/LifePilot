## ZhiWei 记忆系统：实现 vs 设计对照笔记

> 目的：梳理当前 `com.lifepilot.memory` 相关代码与《memory-system.md》等设计文档的差异，作为后续逐条拆解与改造的工作底稿。

### 1. 总览结论

- **整体架构基本对齐**：包结构 `working / episodic / semantic / procedural / retrieval / consolidation / forgetting / config` 与文档一致，L1–L4 四层记忆、混合检索、遗忘引擎都有较完整实现。
- **主要差异点集中在两类**：
  - 有些功能**已经有接口/数据模型，但逻辑简化或未完全接入生命周期**；
  - 有些属于**设计文档中的“横切能力 / 未来扩展模块”，目前实现缺失或只实现了一部分**（例如 ConversationViewService、数据脱敏、完整的抽取/压缩管线）。

后续讨论可以围绕下面各节逐条拆解。

---

### 2. 包结构与核心类

**设计目标**

- 记忆子系统按功能分层：  
  `working / episodic / semantic / procedural / retrieval / extraction / consolidation / forgetting / compression / config`。
- 每层有清晰的核心类和职责边界。

**当前实现情况**

- 已存在并与设计高度一致的包/类（示例）：
  - `working`: `WorkingMemory`, `WorkingMemorySlot`, `ConversationSlot`, `ToolResultSlot`, `ReasoningSlot`, `TokenBudgetAllocator`, `BudgetAllocation` 等
  - `episodic`: `EpisodicMemory`, `ConversationRecord`, `MessageRecord`, `CompressionLevel`
  - `semantic`: `SemanticMemory`, `TemporalEntity`, `TemporalRelation`, `ConflictDetector`, `VersionMerger`, `EntityType`, `RelationTypes`, `ConflictResolution`
  - `procedural`: `ProceduralMemory`, `ProcedureTemplate`, `TemplateStep`, `StrategyPattern`, `PreferenceRule`, `IntentMatcher`
  - `retrieval`: `HybridRetriever`, `VectorSearcher`, `FtsSearcher`, `GraphTraverser`, `RetrievalResult`, `RetrievalWeights`, `VectorSearchResult`, `RankedItem`
  - `consolidation`: `ConsolidationPipeline`, `EpisodicToSemanticConsolidator`, `EpisodicToProceduralConsolidator`, `ConsolidationStats`
  - `forgetting`: `ForgettingEngine` 以及一系列策略类 `HybridPolicy`, `FifoPolicy`, `LruPolicy`, `PriorityDecayPolicy`, `ReflectionSummaryPolicy`, `ForgettingPriority`
  - `config`: `MemoryAutoConfiguration`, `MemoryProperties`

- **设计中有明确提及但当前未见或实现缺失的模块**：
  - `extraction` 包：`KnowledgeExtractionPipeline`, `EntityExtractor`, `RelationExtractor`, `ExtractionResult`
  - `compression` 包：`CompressionService`, `CompressionStrategy`（目前压缩逻辑零散地出现在 Episodic/Forgetting 等处）
  - 检索层的隐私脱敏器 `DataRedactor`
  - 会话逻辑视图 `ConversationViewService` 及 `ConversationSessionView` / `ConversationTurnView`

**小结**

- 核心记忆层（L1–L4）和关键能力基本齐全；
- 抽取/压缩/隐私/会话视图等横切模块仍处于“文档设计 > 代码实现”的阶段。

---

### 3. L1 Working Memory（工作记忆）

**设计要点**

- 使用 `ConcurrentHashMap<String, List<WorkingMemorySlot>>` 按会话维护短期上下文。
- 有独立的 `TokenBudgetAllocator` + `SlotEvictionPolicy`：
  - 动态分配每个会话的 token 预算；
  - 淘汰策略是“重要度加权滑动窗口”，优先淘汰低重要度、未 pinned 的槽位。
- 生命周期：会话结束时应调用 `flush()` 将 L1 内容巩固到 L2 `EpisodicMemory`。

**当前实现**

- 与设计一致的部分：
  - `sessions` 使用 `ConcurrentHashMap<String, List<WorkingMemorySlot>>`。
  - 槽位类型 `ConversationSlot`, `ToolResultSlot`, `ReasoningSlot` 与 UML 设计一致。
  - 维护了 `tokenUsage` 和 `lastActivity`，支持基于 token 使用和空闲时间管理。
  - 在 `append()` 内部实现了简单的淘汰逻辑：
    - 超过 `MemoryProperties.workingMemoryTokenBudget` 时，调用内部 `findEvictionCandidate`；
    - 淘汰顺序上：优先淘汰低重要度的 `ReasoningSlot`，再是 `ToolResultSlot`，最后才是 `ConversationSlot`，并跳过 pinned。
  - 提供 `cleanupIdleSessions(Duration)`，按 `lastActivity` 超时后自动 `flush()` 到 L2 并清理。

- 与设计不完全一致 / 简化的部分：
  - `TokenBudgetAllocator` 和 `SlotEvictionPolicy` 抽象**尚未真正接入**：
    - 当前 token 预算和淘汰策略逻辑直接写在 `WorkingMemory` 内部方法中，属于“固化策略”，扩展性有限。
  - “会话结束 → flush” 触发点不够明确统一：
    - 实际上只有在：
      - 外部显式调用 `flush(sessionId)`，或者
      - 某个后台任务调用 `cleanupIdleSessions` 时才会触发；
    - 目前 `AgentLoop` 只负责往 L1 写入当前轮消息，并**没有在会话关闭时主动触发 `WorkingMemory.flush()`**，更多依赖空闲清理。

**小结（后续可拆解点）**

- 把预算分配与淘汰算法从 `WorkingMemory` 中抽离为策略接口；
- 补充“会话 lifecycle → L1 flush” 的明确挂载点，避免仅依赖 idle 清理。

---

### 4. L2 Episodic Memory（情景记忆）

**设计要点**

- 存储结构：`conversations` + `messages`，消息表支持 FTS5。
- 能力包括：
  - `save(record)`：事务性保存一整段对话；
  - `getRecent(duration)` / `getRecent(int limit)`：按时间或数量获取最近对话；
  - `search(query)`：FTS5 + BM25 全文检索；
  - `getByIntent(intentType)`：按意图维度抽取历史对话；
  - `compress(conversationId, level)`：按多级压缩策略（ORIGINAL → SUMMARY → KEYPOINTS）。
- 与会话系统物理隔离，通过 `ConversationViewService` 以逻辑视图的方式访问。

**当前实现**

- 已实现的能力：
  - `save(ConversationRecord)`：事务内写入 `conversations` 和 `messages` 表。
  - `getRecent(int limit)`：按 `created_at DESC` 限制数量，并使用 `loadMessages` 填充消息列表。
  - `search(String query)`：
    - 通过 `messages_fts` 执行 FTS5 MATCH；
    - 使用 `bm25(messages_fts)` 排序；
    - 将命中的 `conversation_id` 映射回完整的 `ConversationRecord`。
  - `getById(conversationId)`。
  - `getMessagesBySessionId(sessionId)`：按 `session_id` 取出相关对话，再 flatten 出所有 `MessageRecord`。
  - `MessageRecord` 中预留了 `compressedContent` 与 `compressionLevel(CompressionLevel)` 字段，为后续压缩策略做好数据模型准备。

- 缺失或未实现的部分：
  - 未提供 `getRecent(Duration duration)`、`getByIntent(intentType)` 等更语义化的查询接口。
  - 尚未实现对话级压缩 API `compress(conversationId, level)`：
    - 尽管有 `CompressionLevel` 和 `compressedContent` 字段，但没有统一的服务/策略类执行压缩与降级策略。
  - 设计中的 `ConversationViewService` 还未落地：
    - 当前 `AgentLoop` 在“从历史回灌工作记忆”时，直接调用 `EpisodicMemory.getMessagesBySessionId(sessionId)`，而不是通过一个抽象的“会话视图层”。

**小结（后续可拆解点）**

- 按时间窗口和意图扩展更丰富的查询接口；
- 引入对话压缩服务（策略模式），系统化使用 `CompressionLevel` 与 `compressedContent`；
- 把和会话系统的交互统一收敛到 `ConversationViewService` 上。

---

### 5. L3 Semantic Memory（语义记忆 / 时序知识图谱）

**设计要点**

- 使用 `TemporalEntity` / `TemporalRelation` 建模版本化、时间感知的知识图谱。
- 提供：
  - 版本感知 upsert（带冲突检测与版本合并）；
  - 时间旅行查询；
  - 变更历史查询；
  - 图遍历（基于当前有效关系）；
  - 实体归档；
  - 访问计数与 lastAccessedAt 维护；
  - sqlite-vec 等向量索引集成。

**当前实现**

- 与设计高度契合：
  - `upsertWithConflictDetection`：
    - 使用 `ConflictDetector.detectConflict(incoming)` 检测冲突；
    - 如有冲突：以 `VersionMerger.merge(existing, incoming, conversationId)` 合并，关闭旧版本（`is_current=0, valid_to=now`），插入新版本并更新向量；
    - 无冲突：新建版本 `version=1`，插入并更新向量。
  - `queryAtTime(Instant point)`：根据 `valid_from`/`valid_to` 实现时间旅行。
  - `getChangeHistory(name, type)`：按 `version ASC` 返回所有版本。
  - `findRelated(entityId, maxDepth)`：用递归 CTE 遍历图，遵循“当前有效关系 + 深度限制”。
  - `archive(entity)`：关闭当前实体及所有相关关系。
  - `incrementAccessCount`, `addRelation`, `findAllCurrent()` 等方法支撑遗忘策略和检索。
  - `updateVector` 通过 `VectorSearcher.upsertEntityVector` 集成向量索引，异常时不影响主事务。

**小结**

- L3 是当前实现与设计**最为贴合的一层**；
- 版本化、冲突检测、时间维度与向量检索都已经落地。

---

### 6. L4 Procedural Memory + 混合检索 + 遗忘引擎

**设计要点**

- Procedural Memory：
  - 使用 `ProcedureTemplate`, `TemplateStep`, `StrategyPattern`, `PreferenceRule` 建模程序性知识；
  - 基于对话历史自动挖掘用户常用操作序列与偏好规则；
  - `IntentMatcher` 根据当前查询匹配合适的模板。
- `HybridRetriever`：
  - 向量检索 + FTS + 图遍历 + 程序记忆（L4）四路混合；
  - 使用加权 RRF + 时间衰减 + 重要度加成融合排序；
  - 程序记忆更多作为“执行建议”，而非直接参与 RRF 。
- `ForgettingEngine`：
  - 定时任务方式运行 MaRS 风格遗忘；
  - 综合 FIFO/LRU/优先级衰减/反思摘要等策略；
  - 支持基于 LLM 的压缩后归档。

**当前实现**

- HybridRetriever：
  - 使用 `CompletableFuture` + 虚拟线程并行执行：
    - `VectorSearcher.searchEntities(query, topK, ...)`
    - `FtsSearcher.search(query, topK)`
    - `GraphTraverser.traverse(query, topK)`
  - 三路结果经过加权 RRF 融合，并叠加：
    - 时间衰减因子（基于 `lastAccessedAt` 和 `recencyDecay`）；
    - 重要度加成（基于 `importanceScore` 和 `importanceBoost`）。
  - 数据去重后按 `fusedScore` 排序，截取 `topK`，并调用 `SemanticMemory.incrementAccessCount` 批量更新。
  - L4 程序记忆：
    - 如存在 `IntentMatcher`，并行执行 `match(query)`；
    - 若命中，构造一条 `ReasoningSlot.retrievalContext(...)` 存放在 `lastProcedureSlot`；
    - 不参与 RRF 排名，仅作为推理上下文建议输出。
  - 目前实现中的一个效率差异：
    - `convertVectorResults` 会在每次转换时调用 `semanticMemory.findAllCurrent()` 再在内存中过滤对应实体，这与理想中“直接按 id 查询实体详情”的路径略有偏离，规模扩大会有性能风险。

- ForgettingEngine：
  - `@Scheduled(cron = "${lifepilot.memory.forgetting.cron}")` 定时触发 `forget()`。
  - `forget()` 流程：
    - 使用 `semanticMemory.findAllCurrent()` 得到所有当前实体，按 `importance_score` / `access_count` 排序。
    - 过滤受保护实体：
      - 类型为 `PREFERENCE/HABIT/GOAL`；
      - 或 `importanceScore ≥ 0.9`。
    - 构造 `HybridPolicy(fifo, lru, decay, reflection)` 选出候选实体；
    - 对每个实体调用 `executeForgetAction`：
      - 若重要度在 `[minImportance, maxImportance)` 且 LLM 可用：
        - 调用 LLM（`LlmScene.MEMORY_COMPRESSION`）生成摘要；
        - 记录日志，并对原实体执行 `semanticMemory.archive(entity)`；
      - 其他情况或 LLM 调用失败：
        - 直接 `archive`；
    - 最后通过 `logForgetting` 写入 `forgetting_log`。

- Procedural Memory 本体：
  - 类与数据结构均已存在；
  - 它与 Agent/任务编排的实际集成程度需结合其他模块看（当前在 HybridRetriever 的 L4 通路有使用）。

**小结（后续可拆解点）**

- 优化 HybridRetriever 中 vector → entity 的映射路径，避免每次 `findAllCurrent()` 全量扫描；
- 补全从 L2 自动提炼程序模板的管线（抽取 → 评估 → 存入 ProceduralMemory）；
- 进一步完善遗忘策略的可配置性与可观测性。

---

### 7. 会话视图 / ConversationViewService 与 Agent 集成

**设计要点**

- 记忆系统**不直接依赖 Web 会话数据库表**，只通过：
  - `ConversationSessionView`
  - `ConversationTurnView`
  - `ConversationViewService`（`getSession/getRecentTurns/getFullTimeline`）
 访问会话相关信息。
- 其他模块（记忆抽取 job、评估、巩固等）也统一通过该服务，避免横向依赖底层表结构。

**当前实现**

- 目前代码中未找到 `ConversationViewService` 及对应的 `*View` 类型实现。
- `AgentLoop` 与记忆系统的关系：
  - 初始化阶段：
    - 从 `SessionManager` 获取 `SessionSnapshot`；
    - 若 snapshot 内已有 `recentTurns`，则调用 `hydrateWorkingMemoryFromSnapshot` 将其回灌至 L1；
    - 若 `recentTurns` 为空且存在 EpisodicMemory，则直接用 `episodicMemory.getMessagesBySessionId(sessionId)` 回灌。
  - 对话轮次结束时：
    - 在 `asyncPostProcess` 中将本轮用户与 AI 消息写入 L1 `WorkingMemory`（不直接落盘 L2）。
- 这意味着当前实现是“Agent → SessionManager/EpisodicMemory”直连，而非通过统一的会话视图服务协调。

**小结（后续可拆解点）**

- 设计和实现之间，`ConversationViewService` 层是一个明显的缺口；
- 可以考虑将 `SessionSnapshot + EpisodicMemory` 聚合封装成统一的会话视图服务，并让 Agent/抽取/评估等统一依赖它。

---

### 8. 其他横切能力 / 管线现状

**设计中强调的横切能力**

- 抽取管线：`KnowledgeExtractionPipeline`, `EntityExtractor`, `RelationExtractor`，从 L2 对话抽取实体与关系进入 L3；
-. 压缩管线：`CompressionService`, `CompressionStrategy`，统一管理对话/实体压缩策略；
- 隐私与脱敏：`DataRedactor` 对检索结果和回显内容进行敏感信息处理；
- 记忆巩固：`ConsolidationPipeline` 定期将 L1/L2 信息沉淀到 L3/L4；
- 监控与评估：记忆命中率、遗忘效果、抽取准确度等指标。

**当前实现概览**

- 巩固相关：
  - `ConsolidationPipeline`, `EpisodicToSemanticConsolidator`, `EpisodicToProceduralConsolidator` 等类已经存在；
  - 但其触发点（定时任务 / 会话结束 hook / 手动运维）在当前阅读范围内尚未看到完整接入。
- 抽取与压缩：
  - 数据模型中已有 `CompressionLevel`, `compressedContent` 等准备；
  - L3 遗忘引擎中存在基于 LLM 的压缩逻辑；  
  - 但缺少统一的 `compression` 包和抽取管线实现。
- 隐私脱敏：
  - 尚未见到 `DataRedactor` 或等价实现，相关逻辑应该仍在规划阶段。

---

### 9. 后续拆解建议（供下一步详细设计用）

为了后续逐条深入，建议优先从以下几个主题开始拆解（每个主题可以单开文档或章节）：

1. **WorkingMemory 策略解耦与生命周期对齐**
   - 抽象并接入 `TokenBudgetAllocator` / `SlotEvictionPolicy`；
   - 明确“会话结束 → L1 flush → L2 save” 的统一触发机制。
2. **ConversationViewService 抽象与落地**
   - 设计 `ConversationSessionView` / `ConversationTurnView` 的精简接口；
   - 将 Agent / 抽取 / 巩固 / 评估 等对会话的访问统一收敛到该服务。
3. **EpisodicMemory 高级接口与压缩管线**
   - 完善 `getRecent(Duration)`、`getByIntent` 等查询能力；
   - 引入 `CompressionService`，贯通 `CompressionLevel` 与具体策略。
4. **HybridRetriever 性能与可观测性优化**
   - 优化向量结果到实体详情的映射路径；
   - 增加 RRF 融合与各通路贡献的 metric/trace。
5. **抽取 / 巩固 / 遗忘的闭环**
   - 明确从 L2 对话 → L3/L4 知识的抽取与巩固触发点；
   - 结合 `ForgettingEngine` 的遗忘策略形成完整生命周期闭环。

> 接下来可以从任一主题开始，逐条细化需求、设计接口，并输出具体改造计划与实现步骤。


# 知微现状基线 — 记忆 / 学习 / 主动智能（2026-06 回归快照）

> **文档性质**：长期未跟进开发后，基于**实际源码核实**（非文档）重建的权威现状快照。
> **核实分支**：`feature/memory-learning-split`（记忆模块物理拆分刚完成，`mvn compile` 通过）
> **核实方法**：包结构目录遍历 + 关键类源码阅读 + grep 接线验证 + Spring `AutoConfiguration.imports` 核对
> **用途**：作为"我现在到底在哪个阶段"的对账基线；与 [memory-system.md](./memory-system.md) / [agent-learning.md](./agent-learning.md) / [context-and-compression.md](./context-and-compression.md) 的描述做差异核对。

---

## 0. 一句话定位

知微已经**跨过"把记忆做深"，踏入"主动智能"第一阶段**：

- **记忆能力**：成体系、分层完整、刚完成模块化拆分 → 成熟
- **学习系统**：八个子系统全部有实现 → 基本完整，但"巩固调度解耦"仅有未接线骨架
- **主动智能**：分两层落地 —— 智能层（感知/决策信号）**已上线默认开启**；主动发起层（自主思考/主动对话）**已有第一版但默认关闭**

---

## 1. 真实包结构（已核实）

### 1.1 记忆模块 `com.lifepilot.memory.*`

| 子模块 | 包 | 职责 | 关键类 |
|--------|----|----|--------|
| 存储基座 | `memory.store.{entity,episodic,procedural,scope,vector,projection,event,workspace,support,document}` | L0/L1/L3/L4 物理存储 + 项目隔离 + 向量/图投影 | SemanticMemory, EpisodicMemory, ProceduralMemory, SessionWorkspaceService, VectorSearcher, MemorySpaceRepository |
| 检索 | `memory.retrieval.*`（+`orchestrator`,`config`）| 混合检索与编排 | HybridRetriever, FtsSearcher, GraphTraverser, VectorSearcher, QueryRefiner, QueryRewriter, RetrievalOrchestrator（默认关）|
| 消费 | `memory.consumption.{hot,quality,compression,episodic,config}` | 热摘要 / 质量 / 压缩 / 清理 | HotMemoryDigestService, MemoryQualityPolicy, EpisodicCleanupJob |
| 治理 | `memory.governance.{policy,security,audit,lifecycle,server,config}` | 读写边界 / 注入检测 / 生命周期 / 记忆 MCP Server | MemoryAccessPolicy, MemoryInjectionDetector, PromptInjectionPatternScanner |
| 残留值类型 | `memory.semantic`、`memory.episodic` | 仅剩 record/enum（AudnDecision、TemporalRelation、ConversationSnippetRecord 等），服务类已迁出 | — |
| 聚合壳（待删）| `memory.config` | `MemoryAutoConfiguration` 仍存在 | — |

### 1.2 学习模块 `com.lifepilot.agent.learning.*`

| 子包 | 职责 | 关键类 |
|------|------|--------|
| `extraction` | 对话实时提取 | RealtimeExtractor, ExtractionValidator, MemoryExtractionCandidateRepository |
| `experience` | 经验提炼与效果追踪 | ExperienceSummarizer, SubtaskReflector, ContrastiveLearner（+ContrastiveInsight）, EffectivenessTracker, TrajectoryQualityAssessor, ToolTipResolver |
| `consolidation` | 巩固管线（+`association` REM）| ConsolidationPipeline（在用）, **ConsolidationScheduler（骨架，未接线）**, 5 个 Consolidator, EntityDeduplicator |
| `forgetting` | MaRS 六策略遗忘 | ForgettingEngine, Fifo/Lru/PriorityDecay/ReflectionSummary/RandomDrop/Hybrid Policy |
| `conflict` | 语义冲突 LLM 裁决 | ConflictResolutionService, ConflictResolutionRepository |
| `staleness` | 新事实写入后邻居老化 | StalenessCoordinator, VectorBasedStaleConflictDetector, StalenessMarker, NeighborRefreshService |
| `feedback` | 用户反馈处理 | FeedbackProcessor |
| `config` | 装配 | AgentLearningAutoConfiguration, AgentLearningProperties |

### 1.3 主动智能 `com.lifepilot.agent.{intelligence,initiative}`

| 层 | 包 | 关键类 | 默认开关 |
|----|----|----|--------|
| 智能层 | `agent.intelligence`（+`model`,`config`）| CapabilityAssessor, EnvironmentPerceptor, AdaptiveDecisionEngine, DecisionSignal | **enabled=true（开）** |
| 主动发起层 | `agent.initiative.{pool,gate,thinker,express,execute,signal,model,config}` | ThoughtPool, Gatekeeper, DefaultThinker, ConversationInitiator, ActionExecutor, ExecutionPermission, InitiativeEngine | **enabled=false（关）** |

两层均已注册进 `META-INF/spring/...AutoConfiguration.imports`（`IntelligenceAutoConfiguration` / `InitiativeAutoConfiguration`）。

---

## 2. 数据流（已核实接线）

### 2.1 记忆写入路径
```
对话结束 → RealtimeExtractor(LLM AUDN) → 候选表(memory_extraction_candidates)
        → ExtractionValidator 质量门控 → SemanticMemory.upsertWithConflictDetection()
        → afterCommit 登记 memory_projection_outbox → 向量/图派生索引
```

### 2.2 记忆读取/消费路径
```
ContextAssembler.assemble()
  ├─ 会话历史（最近 K 轮 + rolling summary）
  ├─ L1 工作区摘要（SessionWorkspaceService）
  ├─ L3.5 热摘要（HotMemoryDigestService：USER_PROFILE/PROJECT_MEMORY/EXPERIENCE/FACTS）
  └─ <decision_context>（AdaptiveDecisionEngine，见 §2.4）
冷召回走工具：memory.recall(L2) / memory.search(L3) / memory.search-experience / knowledge.search
```

### 2.3 学习闭环
```
ReactAgentLoop.asyncPostProcess（Virtual Thread）
  → ExperienceSummarizer（任务级经验）/ SubtaskReflector（工具级经验）
  → ContrastiveLearner（对比增强）/ EffectivenessTracker（有效性反馈调 importanceScore）
巩固：ConsolidationPipeline.consolidate() 串行跑 7 阶段（cron 0 0 3 * * *）
遗忘：ForgettingEngine（cron 0 0 4 * * SUN）
```

### 2.4 智能层接线（已上线）
```
ToolExecutionCoordinator 每次工具执行后 → capabilityAssessor.recordExecution(toolId, success, latency, error)
ContextAssembler.buildDecisionSignalSection() → adaptiveDecisionEngine.buildDecisionSignal(goal, toolIds)
   → 有不健康工具/环境提示时，注入 <decision_context> 到系统提示词（无信号则返回 null 不注入）
```

---

## 3. 完成度矩阵（真实 vs 文档）

| 能力 | 真实状态 | 证据 / 说明 |
|------|---------|------------|
| 记忆四子模块拆分 | ✅ 完成 | 5 个 AutoConfiguration 全在；包已物理迁移；编译通过 |
| MemoryProperties 删除 | ✅ 完成 | grep `class MemoryProperties` 无匹配 |
| `MemoryAutoConfiguration` 聚合壳删除 | ❌ 未做 | `memory/config/MemoryAutoConfiguration.java` 仍在 |
| 循环依赖事件解耦（EntityWrittenEvent）| ❌ 未做 | `setWriteCallback` 仍在 SemanticMemory/EpisodicMemory 使用 |
| 模块边界测试（ArchUnit）| ❌ 未做 | 未找到依赖边界测试 |
| 学习子系统（提取/经验/遗忘/冲突/老化/反馈）| ✅ 实现 | `agent.learning.*` 八子包齐全 |
| **巩固调度解耦（EVENT/CRON/IDLE）** | ❌ **骨架未接线** | `ConsolidationScheduler` 四方法全转发 `pipeline.consolidate(false)`；全仓无 `new ConsolidationScheduler` |
| 智能层（感知 + 决策信号）| ✅ 上线，默认开 | ContextAssembler + ToolExecutionCoordinator 已接线 |
| 主动发起层（思考/主动对话/自主执行）| 🟡 第一版，默认关 | 代码 + AgentOrchestrator 集成 + 迁移脚本已有，`initiative.enabled=false` |
| 统一检索编排 RetrievalOrchestrator | 🟡 可选接口，默认关 | `orchestrator.enabled=false` |

---

## 4. 文档漂移清单（需修正）

1. **`agent-learning.md` §1.1 / §2.x 源码路径**：仍写 `memory.semantic` / `memory.experience` / `memory.lifecycle.staleness` 等旧路径，实际已迁至 `agent.learning.*`。（本轮已修正）
2. **`agent-learning.md` §3.2**：把巩固解耦写成"规划中"，但未说明 `ConsolidationScheduler` 已存在为**未接线骨架**。
3. **`context-and-compression.md`**：描述的 `ToolResultSummarizer` / `SessionRollingCompressor` / `memory.fetch-transcript` 在其 §7 自标"不存在"——是**未实施设计稿**，需确认落地或仅存档。该文件当前**未提交**（git untracked）。
4. **`memory-refactoring/tasks.md`**：勾选框此前全空，已于本轮回填真实状态。

---

## 5. 下一步方向（已选定 a：巩固调度解耦）

目标：把 `ConsolidationScheduler` 从"转发壳"做成**真正按阶段独立触发**的调度器，打通学习闭环（经验写入后秒级可被检索，而非等 24h cron）。详见后续 spec。

收尾项（合并 `feature/memory-learning-split` 到 develop 前）：
- [ ] 决策 EntityWrittenEvent 解耦：补做 or 接受 setter 注入并更新 design
- [ ] 删除 `MemoryAutoConfiguration` 聚合壳（或说明保留原因）
- [ ] 补模块边界测试 or 显式 defer
- [ ] `mvn test` 全量回归

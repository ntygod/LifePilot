# LifePilot 模块依赖图评估 — 问题清单

> 评估时间：2026-02-25
> 评估范围：模块依赖图设计合理性、代码与依赖图对齐、文档覆盖度、代码质量
> 状态标记：⬜ 待处理 / 🔧 处理中 / ✅ 已解决

---

## A. 依赖图结构问题

### A1. 模块 7 和模块 8 完成状态标记不准确
- **严重度**：中
- **状态**：✅ 已解决
- **描述**：依赖图中模块 7（ContextAssembler 完整版）和模块 8（知识库管理）均标记为未开始，但实际代码已部分实现。
  - 模块 7：`ContextAssembler.java` 已包含完整版构造器（注入 HybridRetriever、WorkingMemory、TokenBudgetAllocator、MemoryRetrievalStrategy），`AgentAutoConfiguration` 中已有 `fullContextAssembler` 和 `basicContextAssembler` 两个条件 Bean。缺少 `DialogCompressor`。
  - 模块 8：`com.lifepilot.knowledge` 包已有 chunking/parser/repository/model/config 子包，`KnowledgeBaseManager` CRUD 已实现。缺少 `DocumentIngester`、高级分块策略、检索集成。
- **处理方案**：
  1. 检查 `.kiro/specs/context-assembler-full/tasks.md` 和 `.kiro/specs/knowledge-base/tasks.md` 的实际完成状态
  2. 在依赖图中将已部分实现的模块标记为 🔧
  3. 确认剩余工作量，决定是补完当前 spec 还是拆分新 spec

### A2. 模块 7 依赖描述不精确
- **严重度**：低
- **状态**：✅ 已解决
- **描述**：依赖图写"依赖语义记忆"，但实际 ContextAssembler 完整版依赖的是：
  - `HybridRetriever`（来自 semantic-memory spec）
  - `WorkingMemory` + `TokenBudgetAllocator`（来自 memory-foundation spec）
  - `MemoryRetrievalStrategy`（来自 agent 模块自身的 context 子包）
- **处理方案**：修正为"依赖记忆系统基础 + 语义记忆"

### A3. 模块 11（CLI 交互层）依赖不完整
- **严重度**：中
- **状态**：✅ 已解决
- **描述**：依赖图写"依赖 Agent 引擎"，但 CLI 层实际还需要：
  - Skill 系统（快捷命令 `todo/schedule/habit` 需要调用内置技能）
  - MCP 系统（快捷命令 `mcp` 需要管理 MCP Server）
  - LLM 系统（快捷命令 `llm` 需要管理 Provider）
- **处理方案**：修正为"依赖 Agent 引擎 + Skill 系统 + MCP + LLM"

### A4. 模块 12（主动推理引擎）缺少 Skill 系统依赖
- **严重度**：中
- **状态**：✅ 已解决
- **描述**：ProactiveReasoner 的两阶段推理中，LLM 精细判断后需要触发具体动作（发送提醒、创建待办等），这些动作需要通过 Skill 系统执行。依赖图写"依赖 Agent 引擎 + 记忆系统"。
- **处理方案**：补充"+ Skill 系统"

### A5. 模块 14（多模态能力）位置可以提前
- **严重度**：低
- **状态**：⬜ 待处理
- **描述**：`ProviderCapability` 枚举已包含 `VISION`，LLM Router 层面已为多模态预留扩展点。多模态能力的核心工作（LlmRouter 多模态路由、MediaProcessor、Apache Tika）不依赖 Phase 3 的任何模块。
- **处理方案**：考虑将模块 14 提前到 Phase 2 末尾或 Phase 3 初期，与 Phase 3 并行

### A6. 模块 8（知识库管理）与 HybridRetriever 的集成路径不清晰
- **严重度**：高
- **状态**：⬜ 待处理
- **描述**：当前 `HybridRetriever` 检索的是 `SemanticMemory` 中的实体（`TemporalEntity`），而知识库的 `DocumentChunk` 是独立的数据模型。依赖图中模块 8 写"依赖语义记忆"，但实际集成需要明确：
  - 方案 A：扩展 `HybridRetriever` 支持文档分块来源（新增文档来源过滤条件）
  - 方案 B：知识库有独立的检索路径（独立的向量检索 + FTS5 检索）
  - 方案 C：将 DocumentChunk 转换为 TemporalEntity 存入语义记忆（统一数据模型）
- **处理方案**：在 knowledge-base spec 的 design 文档中明确集成方案

---

## B. 代码质量问题

### B1. AgentLoop 硬编码业务可调参数
- **严重度**：中（规范违反）
- **状态**：✅ 已解决
- **文件**：`src/main/java/com/lifepilot/agent/AgentLoop.java`
- **描述**：`MAX_LOOP_ITERATIONS = 50` 和 `MAX_CONSECUTIVE_BLOCKS = 3` 是硬编码的业务可调参数，违反 coding-standards.md §12 配置外部化规范。
- **处理方案**：迁移到 `AgentConfigProperties`，在 `application.yml` 中声明默认值

### B2. AgentLoop.agentToolProvider 字段未使用
- **严重度**：低（Warning）
- **状态**：✅ 已解决
- **文件**：`src/main/java/com/lifepilot/agent/AgentLoop.java`
- **描述**：构造函数接收 `AgentToolProvider` 但循环体内没有调用 `getToolCallbacks()`。工具执行实际走的是 `ToolExecutionPipeline`，`AgentToolProvider` 只是桥接层。
- **处理方案**：
  - 方案 A：在 AgentLoop 的 EXECUTING 阶段集成 `agentToolProvider.getToolCallbacks()` 调用
  - 方案 B：如果工具调用完全由外部驱动（ActionParser 解析出 ToolCall → 外部执行），则移除该字段
  - 需要确认 AgentLoop 与工具系统的集成设计意图

### B3. WorkingMemory.allocator 字段未使用
- **严重度**：低（Warning）
- **状态**：✅ 已解决
- **文件**：`src/main/java/com/lifepilot/memory/working/WorkingMemory.java`
- **描述**：构造函数注入了 `TokenBudgetAllocator` 但未在任何方法中使用。
- **处理方案**：确认是否需要在 `append()` 方法中使用 allocator 进行预算检查，或者移除该字段

### B4. WorkingMemory 的 Integer::sum null safety 警告
- **严重度**：低（Warning）
- **状态**：✅ 已解决
- **文件**：`src/main/java/com/lifepilot/memory/working/WorkingMemory.java`
- **描述**：`tokenUsage.merge(sessionId, slot.tokenCount(), Integer::sum)` 产生 null safety 警告，因为 `ConcurrentHashMap.merge()` 的 BiFunction 参数可能接收 null。
- **处理方案**：改用 `tokenUsage.compute(sessionId, (k, v) -> (v == null ? 0 : v) + slot.tokenCount())` 或保持现状（ConcurrentHashMap 不允许 null value，实际不会触发）

### B5. ContextAssembler @Nullable 字段 null 警告
- **严重度**：低（Warning，逻辑上安全）
- **状态**：✅ 已解决
- **文件**：`src/main/java/com/lifepilot/agent/context/ContextAssembler.java`
- **描述**：`hybridRetriever`、`workingMemory`、`tokenBudgetAllocator`、`retrievalStrategy` 标注 `@Nullable`，在 `isFullMode()` 检查后使用仍产生编译器警告。
- **处理方案**：
  - 方案 A：在使用前添加 `assert` 或 `Objects.requireNonNull()` 消除警告
  - 方案 B：重构为两个独立类（`BasicContextAssembler` / `FullContextAssembler`）消除 null 字段
  - 方案 C：保持现状，添加 `@SuppressWarnings("null")` 注释

---

## C. 文档缺失

### C1. 缺少独立架构/特性文档的模块
- **严重度**：中
- **状态**：⬜ 待处理
- **描述**：以下模块在依赖图中规划但缺少独立的架构或特性文档：

| 模块编号 | 模块名 | 缺失文档 | 优先级 | 备注 |
|---------|--------|---------|--------|------|
| 11 | CLI 交互层 | 架构 + 特性 | 中 | 开始 spec 前需要 |
| 14 | 多模态能力 | 架构 + 特性 | 中 | ROADMAP.md §3.4 有初步方案 |
| 15 | 工作流/自动化编排 | 架构 + 特性 | 高 | ROADMAP.md §3.6 有初步方案，复杂模块需独立设计 |
| 16 | 代码执行沙箱 | 架构 + 特性 | 中 | ROADMAP.md §3.7 有初步方案 |
| 17 | 外部数据源同步 | 架构 + 特性 | 中 | ROADMAP.md §3.5 有初步方案 |
| 18-19 | Web UI | 架构 + 特性 | 高 | ROADMAP.md §3.3 有初步方案，前端技术栈引入需要设计 |
| 21 | 多 Agent 协作 | 架构 + 特性 | 低 | ROADMAP.md §3.8 有初步方案 |
| 22 | A2A 协议 | 架构 + 特性 | 低 | 远期 |
| 23 | 记忆系统进阶 | 独立文档 | 低 | 部分内容在 memory-system.md 中 |

- **处理方案**：
  - 近期需要的（模块 11、15、18-19）：在开始 spec 前创建独立文档
  - 中期的（模块 14、16、17）：可在 spec 规划时从 ROADMAP.md 提取
  - 远期的（模块 21-23）：暂不创建，等到 Phase 5-6 时再补充

### C2. ROADMAP.md 完成状态需要更新
- **严重度**：低
- **状态**：✅ 已解决
- **描述**：ROADMAP.md §1.1 的已实现能力矩阵和 §1.2 的 Spec 完成进度表未反映最新状态：
  - 内置技能（builtin-skills spec）已完成，但矩阵中标记为"❌ 未实现"
  - Skill 系统（skill-system spec）已完成，但矩阵中未体现
  - 知识库管理部分已实现（基础 CRUD + 解析器），但标记为"❌ 未实现"
- **处理方案**：更新 ROADMAP.md 的状态标记

### C3. FEATURES.md 路线图状态需要更新
- **严重度**：低
- **状态**：✅ 已解决
- **描述**：FEATURES.md §六 功能路线图中，MCP 协议、Agent Skills、内置 Skills 等已完成的功能仍标记为"📋 规划中"。
- **处理方案**：更新 FEATURES.md 的状态标记

---

## D. 依赖图修正建议汇总

> ✅ 以下修正已全部应用到 `.kiro/steering/spec-workflow.md` §1.1

1. ~~模块 7 标记从空白改为 🔧~~ → 已标记为 ✅
2. ~~模块 8 标记从空白改为 🔧~~ → 已标记为 ✅
3. ~~模块 7 依赖修正为"依赖记忆系统基础 + 语义记忆"~~ → ✅ 已修正
4. ~~模块 11 依赖修正为"依赖 Agent 引擎 + Skill 系统 + MCP + LLM"~~ → ✅ 已修正
5. ~~模块 12 依赖补充"+ Skill 系统"~~ → ✅ 已修正
6. 模块 14 考虑提前到 Phase 2 末尾或 Phase 3 初期 — ⬜ 待下次规划时决定

---

## 处理优先级排序

### 第一批（立即处理 — 依赖图准确性）
1. **A1** — 确认模块 7/8 的 tasks.md 状态，更新依赖图标记
2. **A2 + A3 + A4** — 修正依赖图中的依赖描述
3. **D** — 一次性应用依赖图修正

### 第二批（代码质量 — 规范合规）
4. **B1** — AgentLoop 硬编码参数外部化
5. **B2** — AgentLoop.agentToolProvider 未使用问题
6. **B3** — WorkingMemory.allocator 未使用问题
7. **B4** — WorkingMemory null safety 警告
8. **B5** — ContextAssembler @Nullable 警告

### 第三批（文档更新）
9. **C2** — 更新 ROADMAP.md 状态
10. **C3** — 更新 FEATURES.md 状态

### 第四批（后续规划时处理）
11. **A5** — 模块 14 位置调整（下次规划时决定）
12. **A6** — 知识库与 HybridRetriever 集成方案（knowledge-base spec design 时决定）
13. **C1** — 缺失文档补充（各模块 spec 开始前创建）

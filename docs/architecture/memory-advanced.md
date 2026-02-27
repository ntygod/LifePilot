> 本文档为模块 23「记忆系统进阶」的架构设计文档。
> 基于 `docs/architecture/memory-system.md` 已有的四层认知记忆架构，扩展 L4 程序记忆、记忆巩固管线、MaRS 认知遗忘策略，并对两个待评估点（Agentic GraphRAG、Idle-Driven 记忆巩固）给出深度分析和决策。

# 记忆系统进阶架构设计

---

## 1. 模块定位与职责边界

### 1.1 模块定位

模块 23 是记忆系统的「演化层」——在 Phase 2 已完成的 L1-L3 记忆存储与检索基础上，补全记忆的**生命周期管理**能力：

- **L4 程序记忆**：从重复的成功执行模式中自动提炼操作模板和用户偏好规则
- **记忆巩固管线**：实现情景记忆→语义记忆、情景记忆→程序记忆的双向巩固流程
- **MaRS 认知遗忘**：基于 MaRS 论文的 Hybrid 遗忘策略框架，实现预算约束下的智能遗忘

### 1.2 职责边界

| 属于本模块 | 不属于本模块 |
|-----------|------------|
| L4 程序记忆的数据模型、存储、检索 | L1-L3 记忆层的核心实现（Phase 2 已完成） |
| 情景→语义巩固管线 | 知识提取管线 KnowledgeExtractionPipeline（Phase 2 已完成） |
| 情景→程序巩固管线 | ContextAssembler 完整版（Phase 2 已完成） |
| Hybrid 遗忘策略引擎 | 对话压缩 CompressionService（Phase 2 已完成） |
| 巩固/遗忘的调度触发机制 | HybridRetriever 三路检索（Phase 2 已完成，本模块仅扩展 L4 检索路径） |

### 1.3 前置依赖

| 依赖模块 | 依赖内容 |
|---------|---------|
| 模块 5 记忆系统基础 ✅ | L1 WorkingMemory、L2 EpisodicMemory、SqliteVecStore |
| 模块 6 语义记忆 ✅ | L3 SemanticMemory、TemporalEntity、HybridRetriever、GraphTraverser |
| 模块 7 ContextAssembler ✅ | Token 预算分配、记忆检索槽位填充 |
| 模块 1 LLM Router ✅ | 巩固管线需要 LLM 调用进行知识提炼 |

---

## 2. 前沿研究基础

### 2.1 核心参考研究

本模块的设计综合了 2024-2026 年 Agent 记忆领域的最新研究成果，在 `memory-system.md` §1.2 已引用的六项基础研究之上，进一步纳入以下前沿工作：

#### 2.1.1 记忆巩固与层次化组织

| 研究 | 核心贡献 | LifePilot 采纳 |
|------|---------|---------------|
| **TiMem** ([arXiv 2601.02845](https://arxiv.org/abs/2601.02845)) | 提出时序记忆树（Temporal Memory Tree, TMT），将对话流组织为五层层次结构，从原始对话片段逐层抽象为人格画像。在 LoCoMo 基准上达到 75.30% 准确率，同时减少 52.20% 的召回记忆长度 | 巩固管线的层次化抽象策略：L2 情景记忆→L3 语义记忆的渐进提炼流程 |
| **HiMem** ([arXiv 2601.06377](https://arxiv.org/abs/2601.06377)) | 提出冲突感知的记忆再巩固（Conflict-aware Memory Reconsolidation），基于检索反馈修订和补充已存储知识，实现记忆的持续自我演化 | 巩固管线中的冲突检测与版本化更新策略 |
| **SimpleMem** ([arXiv 2601.02553](https://arxiv.org/abs/2601.02553)) | 提出语义无损压缩三阶段管线：过滤冗余→合并相关记忆→动态调整检索深度。F1 提升 26.4%，Token 消耗降低 30 倍 | 巩固管线中的记忆合并策略，避免重复记忆累积 |
| **TeleMem** ([arXiv 2601.06037](https://arxiv.org/abs/2601.06037)) | 将记忆组织为结构化可演化的语义轨迹，通过批量摘要→检索相关记忆→语义聚类→LLM 巩固的写入管线，摊销写入成本 | 巩固管线的批量处理策略，避免逐条巩固的 LLM 调用开销 |
| **MemGAS** ([arXiv 2505.19549](https://arxiv.org/abs/2505.19549)) | 使用高斯混合模型（GMM）对新记忆与历史记忆进行聚类和关联，实现多粒度记忆单元的自适应选择和检索 | 情景→程序巩固中的执行轨迹聚类策略 |

#### 2.1.2 认知遗忘与记忆管理

| 研究 | 核心贡献 | LifePilot 采纳 |
|------|---------|---------------|
| **MaRS** ([arXiv 2512.12856](https://arxiv.org/abs/2512.12856)) | 将记忆形式化为元组 A=(M,P,B,π)，提出 6 种遗忘策略，Hybrid 策略综合评分 ≈0.911。300 次评估实验验证了预算约束下的遗忘有效性 | Hybrid 遗忘策略的完整实现框架 |
| **FadeMem** ([arXiv 2601.18642](https://arxiv.org/abs/2601.18642)) | 生物启发的主动遗忘机制，证明主动遗忘低价值信息可提升检索精度和推理质量 | 遗忘引擎的理论基础，隐私感知遗忘的设计依据 |
| **Intelligent Decay** ([arXiv 2509.25250](https://arxiv.org/abs/2509.25250)) | 基于复合评分（recency + relevance + user-specified utility）的主动衰减机制，用于长时运行 Agent 的记忆修剪和巩固 | Priority Decay 策略的复合评分公式设计 |

#### 2.1.3 图检索与知识图谱

| 研究 | 核心贡献 | LifePilot 采纳 |
|------|---------|---------------|
| **Synapse** ([arXiv 2601.02744](https://arxiv.org/abs/2601.02744)) | 将记忆建模为动态图，通过扩散激活（Spreading Activation）+ 侧向抑制（Lateral Inhibition）+ 时间衰减动态高亮相关子图 | 评估 Agentic GraphRAG 的参考方案 |
| **GraphRAG-Bench** ([arXiv 2506.05690](https://arxiv.org/abs/2506.05690)) | 系统评估 GraphRAG vs 传统 RAG，发现 GraphRAG 在多跳推理上提升 4.5%，但在简单事实检索上准确率低 13.4%，延迟高 2.3 倍 | Agentic GraphRAG 待评估决策的关键依据 |
| **Engram** ([GitHub](https://github.com/engram-network/engram)) | 开源 Agent 记忆层，基于知识图谱 + 扩散激活 + 睡眠周期巩固（LLM 驱动的情景→语义蒸馏） | 巩固管线的睡眠周期触发模式参考 |

#### 2.1.4 开源项目与产品对比

| 项目/产品 | 核心特点 | 与 LifePilot 的差异 |
|----------|---------|-------------------|
| **EverMemOS** (EverMind, 2025-2026) | 四层记忆提取架构，LoCoMo 93.05% SOTA，100-300ms 延迟，Token 成本降低 70%。三阶段生命周期：MemCell 提取→MemScene 语义巩固→重构性回忆 | 云端 API 服务，非本地部署；LifePilot 采纳其 MemCell→MemScene 的两级巩固思路，但基于 SQLite 本地实现 |
| **Mem0** ([arXiv 2504.19413](https://arxiv.org/abs/2504.19413)) | 三阶段管线（提取→巩固→检索），Mem0ᵍ 图变体支持多会话关系推理。LoCoMo 上相对准确率提升 26%，p95 延迟降低 91% | 云端优先，图存储依赖 Neo4j；LifePilot 采纳其选择性记忆提取策略，但用 SQLite 递归 CTE 替代 Neo4j |
| **Engram** (2026) | 知识图谱 + 扩散激活检索 + 睡眠周期巩固。开源，支持 MCP 集成 | Python 生态，非 JVM；LifePilot 借鉴其睡眠周期巩固模式和扩散激活检索思路 |
| **LangChain Agent Builder** (2026) | 文件系统记忆架构，跨会话学习和适应 | 通用框架，无认知科学启发的层次化设计；LifePilot 的四层认知架构更精细 |

### 2.2 关键设计决策

基于上述调研，本模块做出以下关键设计决策：

| 决策 | 选择 | 理由 |
|------|------|------|
| 巩固触发模式 | 定时调度（Cron）为主，空闲触发为辅 | TiMem/EverMemOS 均采用批量巩固而非实时巩固，LLM 调用成本高，批量处理更经济。空闲触发作为补充（见 §7 待评估点） |
| 巩固粒度 | 对话级批量处理 | SimpleMem/TeleMem 证明批量摘要→聚类→巩固的管线比逐条处理效率高 30 倍 |
| 遗忘策略 | MaRS Hybrid 四阶段 | MaRS 实验证明 Hybrid 策略综合评分最高（0.911），且计算可控 |
| L4 程序记忆提炼 | 向量聚类 + LLM 模板提取 | MemGAS 的 GMM 聚类思路适合发现重复执行模式，LLM 提取变量占位符 |
| 图检索增强 | 暂不引入 Agentic GraphRAG | GraphRAG-Bench 显示简单查询场景下 GraphRAG 反而降低准确率，SQLite CTE 在当前数据规模下足够（见 §7.1 详细分析） |

---

## 3. L4 程序记忆实现架构

### 3.1 设计概述

L4 程序记忆的详细数据模型、IntentMatcher、ProceduralMemory 服务已在 `memory-system.md` §6 中完整定义。本模块负责**实现**这些设计，核心工作包括：

1. **数据库表创建**：`procedure_templates`、`template_steps`、`preference_rules`、`strategy_patterns` 四张表（Flyway 迁移脚本）
2. **ProceduralMemory 服务实现**：CRUD + 意图匹配 + 成功率追踪 + 过时模板淘汰
3. **IntentMatcher 实现**：基于 sqlite-vec 向量相似度的意图匹配
4. **HybridRetriever 扩展**：新增 L4 检索路径，在三路检索基础上增加程序记忆匹配

### 3.2 与已有设计的对齐

本模块严格遵循 `memory-system.md` §6 的数据模型设计，不做变更：

- `ProcedureTemplate` record：templateId, name, description, triggerIntent, steps, variables, successRate, useCount, lastUsedAt, sourceTraceIds, createdAt, updatedAt
- `TemplateStep` record：stepOrder, toolId, action, parameterTemplate, description, isOptional
- `PreferenceRule` record：ruleId, category, condition, preference, confidence, sourceConversationIds, createdAt, updatedAt
- `StrategyPattern` record：patternId, contextTrigger, strategy, rationale, successRate, useCount, createdAt

### 3.3 L4 检索集成

程序记忆的检索通过 `IntentMatcher` 实现，集成到 `ContextAssembler` 的检索流程中：

```
用户消息 → ContextAssembler
  ├── HybridRetriever.retrieve()  → L2/L3 检索结果
  └── IntentMatcher.match()       → L4 程序记忆匹配
       ├── 向量相似度匹配 triggerIntent
       ├── 过滤 isReliable() = true 的模板
       └── 注入 ReasoningSlot（模板步骤作为执行建议）
```

---

## 4. 记忆巩固管线架构

### 4.1 设计概述

巩固管线的整体架构已在 `memory-system.md` §9 中定义。本模块负责实现两个方向的巩固器和编排服务：

1. **EpisodicToSemanticConsolidator**：分析近期对话中的实体提及频率，高频实体触发知识提取或重要度提升
2. **EpisodicToProceduralConsolidator**：识别成功执行轨迹，向量聚类发现重复模式，LLM 提炼操作模板
3. **ConsolidationPipeline**：编排服务，定时调度（每日凌晨 3:00），顺序执行两个方向的巩固

### 4.2 巩固流程详细设计

#### 4.2.1 情景→语义巩固

```
@Scheduled(cron = "0 0 3 * * *")
│
├── Step 1: 获取最近 N 天对话（默认 7 天）
├── Step 2: 统计已有 L3 实体在对话中的提及频率
├── Step 3: 高频实体（≥ 3 次）→ 提升 importanceScore
├── Step 4: 未被 L3 覆盖的长对话 → 触发 KnowledgeExtractionPipeline
└── Step 5: 记录巩固日志到 memory_consolidation_log
```

关键设计点（受 TeleMem 启发）：
- **批量处理**：一次获取 N 天对话，统一分析，避免逐条 LLM 调用
- **增量窗口**：每次只处理最近增量，不重复分析已巩固的对话
- **幂等性**：重复执行不会产生副作用（importanceScore 有上限 1.0，已提取的对话不会重复提取）

#### 4.2.2 情景→程序巩固

```
@Scheduled(cron = "0 0 3 * * *")  // 在语义巩固之后执行
│
├── Step 1: 获取最近 N 天的成功执行轨迹（goal 非空 + 工具调用 ≥ 2 步）
├── Step 2: 对执行轨迹的工具调用序列进行向量化
├── Step 3: 余弦相似度聚类（阈值 ≥ 0.85）
├── Step 4: 聚类大小 ≥ 2 的组 → LLM 提炼操作模板
│     ├── 提取公共步骤序列
│     ├── 识别变量占位符（${variable}）
│     └── 生成模板名称和描述
├── Step 5: 与已有模板去重（triggerIntent 向量相似度 ≥ 0.9 视为同一模板）
│     ├── 已有模板 → 更新 successRate 和 useCount
│     └── 新模板 → 保存到 L4
└── Step 6: 记录巩固日志
```

关键设计点（受 MemGAS 启发）：
- **向量聚类**：使用 EmbeddingModel 将工具调用序列向量化，余弦相似度聚类发现重复模式
- **最小样本数**：至少 2 次相似执行才提炼模板，避免过早泛化
- **去重机制**：新模板与已有模板的 triggerIntent 向量比较，防止重复创建

### 4.3 巩固日志表

```sql
CREATE TABLE IF NOT EXISTS memory_consolidation_log (
    id                      TEXT PRIMARY KEY,
    consolidation_type      TEXT NOT NULL,  -- 'EPISODIC_TO_SEMANTIC' | 'EPISODIC_TO_PROCEDURAL'
    conversations_analyzed  INTEGER NOT NULL DEFAULT 0,
    entities_found          INTEGER NOT NULL DEFAULT 0,
    entities_boosted        INTEGER NOT NULL DEFAULT 0,
    extractions_triggered   INTEGER NOT NULL DEFAULT 0,
    templates_created       INTEGER NOT NULL DEFAULT 0,
    templates_updated       INTEGER NOT NULL DEFAULT 0,
    elapsed_ms              INTEGER NOT NULL DEFAULT 0,
    created_at              TEXT NOT NULL DEFAULT (datetime('now'))
);
```

---

## 5. MaRS 认知遗忘策略架构

### 5.1 设计概述

遗忘策略引擎的完整设计已在 `memory-system.md` §10 中定义，包括 6 种遗忘策略的类型体系（`ForgettingPolicy` sealed interface）和 `ForgettingEngine` 服务。本模块负责实现这些设计。

### 5.2 Hybrid 四阶段遗忘流程

Hybrid 策略是 MaRS 论文中综合评分最高的策略（≈0.911），采用分阶段渐进式遗忘：

```
@Scheduled(cron = "0 0 4 * * SUN")  // 每周日凌晨 4:00（在巩固管线之后）
│
├── 阶段 1: FIFO — 淘汰超过最大保留期限的实体（默认 365 天）
│     └── 直接归档，不考虑重要度
│
├── 阶段 2: LRU — 淘汰长期未访问的实体
│     └── last_accessed_at > 90 天 且 access_count = 0
│
├── 阶段 3: Priority Decay — 优先级衰减淘汰
│     ├── effectivePriority = importanceScore × exp(-λ × daysSinceLastAccess)
│     ├── λ = 0.02（半衰期约 35 天）
│     └── effectivePriority < 0.2 的实体进入遗忘候选
│
└── 阶段 4: Reflection-Summary — 压缩而非删除
      ├── 中等重要度实体（importanceScore ∈ [0.3, 0.8]）
      ├── LLM 生成摘要替代原始 description
      └── 保留核心信息，释放存储空间
```

### 5.3 遗忘安全保障

| 保障机制 | 说明 |
|---------|------|
| 用户认知类实体保护 | `EntityType.isUserCognitive()` 为 true 的实体（偏好、习惯、目标）享有更高保留优先级 |
| 用户标记保护 | 用户手动标记为重要的实体永不遗忘 |
| 每阶段预算限制 | 每个阶段最多遗忘 budget/4 个实体，防止单次遗忘过多 |
| 隐私感知遗忘 | 包含 PII 标记的实体在遗忘优先级计算中获得额外权重，优先清理 |
| 遗忘日志 | 每次遗忘操作记录到 `forgetting_log` 表，可审计可追溯 |

### 5.4 遗忘日志表

```sql
CREATE TABLE IF NOT EXISTS forgetting_log (
    id                  TEXT PRIMARY KEY,
    entity_id           TEXT NOT NULL,
    entity_name         TEXT NOT NULL,
    strategy            TEXT NOT NULL,  -- 'FIFO' | 'LRU' | 'PRIORITY_DECAY' | 'REFLECTION_SUMMARY' | 'HYBRID'
    action_taken        TEXT NOT NULL,  -- 'ARCHIVED' | 'COMPRESSED' | 'DELETED'
    forgetting_priority REAL NOT NULL,
    reason              TEXT,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);
```

---

## 6. 调度与触发机制

### 6.1 定时调度策略

| 任务 | Cron 表达式 | 执行时间 | 说明 |
|------|-----------|---------|------|
| 巩固管线 | `0 0 3 * * *` | 每日凌晨 3:00 | 先执行情景→语义，再执行情景→程序 |
| 遗忘引擎 | `0 0 4 * * SUN` | 每周日凌晨 4:00 | 在巩固管线之后执行，确保新巩固的记忆不被误遗忘 |

### 6.2 调度实现

使用 Spring `@Scheduled` 注解实现定时调度，通过 `@ConfigurationProperties` 外部化 Cron 表达式：

```yaml
lifepilot:
  memory:
    consolidation:
      cron: "0 0 3 * * *"
      lookback-days: 7
      high-frequency-threshold: 3
      importance-boost-step: 0.1
      cluster-similarity-threshold: 0.85
      min-cluster-size: 2
    forgetting:
      cron: "0 0 4 * * SUN"
      max-retention-days: 365
      lru-threshold-days: 90
      priority-decay-rate: 0.02
      priority-decay-threshold: 0.2
      max-forget-per-run: 100
```

---

## 7. 待评估点深度分析与决策

### 7.1 Agentic GraphRAG

**评估问题**：是否用图检索 Skill 替代 SQL CTE 穷举遍历？

#### 7.1.1 现状分析

当前 LifePilot 的图遍历检索使用 SQLite 递归 CTE 实现（`GraphTraverser`），通过 `WITH RECURSIVE` 语句沿关系边扩展 N 跳。这种方案在 Phase 2 中已实现并验证。

#### 7.1.2 GraphRAG 的优势与局限

根据 GraphRAG-Bench（[arXiv 2506.05690](https://arxiv.org/abs/2506.05690)）的系统评估：

**优势**：
- 多跳推理准确率提升约 4.5%（HotpotQA 基准）
- 在需要层次化知识结构的复杂推理场景中表现更好
- 能发现通过关系链接的间接相关信息

**局限**：
- 简单事实检索准确率反而降低 13.4%（Natural Question 基准）
- 平均延迟增加 2.3 倍
- 时间敏感查询准确率下降 16.6%
- 图构建和维护成本高

#### 7.1.3 LifePilot 场景评估

| 维度 | SQLite CTE 现状 | Agentic GraphRAG |
|------|----------------|-----------------|
| 数据规模 | 个人 Agent，实体数量级 ~10K | GraphRAG 优势在 100K+ 实体时才显著 |
| 查询复杂度 | 2-3 跳关系遍历为主 | GraphRAG 在 5+ 跳深度推理时优势明显 |
| 延迟要求 | < 50ms（三路并行检索） | GraphRAG 引入额外 LLM 调用，延迟不可控 |
| 部署约束 | 单 JAR + SQLite，零外部依赖 | 需要图数据库或额外索引结构 |
| 维护成本 | SQL 迁移脚本，成熟工具链 | 图 schema 演化复杂 |

#### 7.1.4 决策

**暂不引入 Agentic GraphRAG**。理由：

1. LifePilot 是个人 Agent，实体规模在 SQLite CTE 的高效处理范围内（~10K 实体，2-3 跳遍历 < 10ms）
2. GraphRAG-Bench 的评估显示，在简单查询场景下 GraphRAG 反而降低准确率，而个人 Agent 的大部分查询属于简单事实检索
3. 引入 GraphRAG 会破坏「单 JAR + SQLite」的零依赖部署模型
4. 当前 HybridRetriever 的三路检索（向量 + FTS5 + CTE 图遍历）已覆盖大部分检索需求

**触发重新评估的条件**：
- 单用户实体数量超过 50K
- 用户反馈图遍历检索质量不足
- SQLite CTE 在深度遍历（5+ 跳）时出现性能瓶颈（> 100ms）

### 7.2 Idle-Driven 记忆巩固

**评估问题**：是否用空闲事件驱动替代定时触发巩固？

#### 7.2.1 概念

Idle-Driven 巩固的核心思想是：不在固定时间点执行巩固，而是在 Agent 空闲时（无用户交互）自动触发。类似人类大脑在睡眠期间进行记忆巩固。

Engram 项目的「睡眠周期巩固」（sleep-cycle consolidation）就是这一模式的实现——在 Agent 空闲时通过 LLM 处理将情景记忆蒸馏为语义知识。

#### 7.2.2 优劣分析

| 维度 | 定时调度（Cron） | Idle-Driven |
|------|----------------|------------|
| 可预测性 | ✅ 固定时间执行，行为可预测 | ⚠️ 执行时机不确定 |
| 资源利用 | ⚠️ 可能在用户活跃时执行 | ✅ 利用空闲时段，不影响交互延迟 |
| 实现复杂度 | ✅ Spring @Scheduled，简单可靠 | ⚠️ 需要 IdleDetector + 空闲状态机 |
| 及时性 | ⚠️ 最长等待 24 小时 | ✅ 空闲即触发，巩固更及时 |
| 测试难度 | ✅ 确定性行为，易于测试 | ⚠️ 依赖时间和用户行为，测试复杂 |

#### 7.2.3 决策

**Phase 6 采用定时调度为主，预留 Idle-Driven 扩展接口**。理由：

1. 定时调度实现简单、行为可预测、易于测试和调试
2. 个人 Agent 的使用模式通常有明确的活跃/空闲周期（白天使用、夜间空闲），定时调度（凌晨 3:00）天然利用了空闲时段
3. Idle-Driven 需要额外的 `IdleDetector` 组件和空闲状态机，增加系统复杂度
4. 巩固管线的 LLM 调用成本是主要瓶颈，无论定时还是空闲触发，单次巩固的成本相同

**预留扩展**：
- `ConsolidationPipeline` 的 `consolidate()` 方法设计为可被外部调用（不仅限于 @Scheduled）
- 未来可通过 `IdleDetector` 在检测到空闲时调用 `consolidate()`，无需修改巩固逻辑本身
- 配置项 `lifepilot.memory.consolidation.trigger-mode` 预留 `CRON` / `IDLE` / `HYBRID` 三种模式

---

## 8. 数据库迁移

本模块需要新增以下 Flyway 迁移脚本（版本号待确认，需在现有最大版本号之后）：

### 8.1 L4 程序记忆表

```sql
-- procedure_templates: 操作模板
-- template_steps: 模板步骤
-- preference_rules: 偏好规则
-- strategy_patterns: 策略模式
-- procedure_template_embeddings: 模板意图向量索引（sqlite-vec）
```

### 8.2 巩固与遗忘日志表

```sql
-- memory_consolidation_log: 巩固日志
-- forgetting_log: 遗忘日志
```

具体 DDL 参见 `memory-system.md` §8（L4 表结构）和本文档 §4.3、§5.4。

---

## 9. 配置项汇总

```yaml
lifepilot:
  memory:
    # L4 程序记忆
    procedural:
      max-templates: 200          # 最大模板保留数量
      min-reliability: 0.7        # 最低可靠性阈值（successRate）
      min-use-count: 2            # 最低使用次数（isReliable 判断）
      stale-days: 90              # 过时天数阈值
      match-threshold: 0.6        # 意图匹配最低分数
      default-importance: 0.5     # 默认重要度

    # 巩固管线
    consolidation:
      cron: "0 0 3 * * *"
      trigger-mode: CRON          # CRON | IDLE | HYBRID（预留）
      lookback-days: 7
      high-frequency-threshold: 3
      importance-boost-step: 0.1
      importance-boost-max: 0.3
      cluster-similarity-threshold: 0.85
      min-cluster-size: 2
      max-templates-per-run: 10
      min-execution-steps: 2      # 最少工具调用步数才视为有效执行轨迹

    # 遗忘引擎
    forgetting:
      cron: "0 0 4 * * SUN"
      max-retention-days: 365
      lru-threshold-days: 90
      priority-decay-rate: 0.02
      priority-decay-threshold: 0.2
      reflection-summary-min-importance: 0.3
      reflection-summary-max-importance: 0.8
      max-forget-per-run: 100
      privacy-aware-boost: 0.3    # PII 实体的遗忘优先级额外权重
```

---

## 10. 与已有模块的集成点

| 集成点 | 被集成模块 | 集成方式 |
|-------|----------|---------|
| L4 检索注入 | ContextAssembler（模块 7） | IntentMatcher 结果注入 ReasoningSlot |
| HybridRetriever 扩展 | 检索引擎（模块 6） | 新增 L4 检索路径 |
| 巩固管线调用知识提取 | KnowledgeExtractionPipeline（模块 6） | 高频未提取对话触发提取 |
| 巩固管线调用 EmbeddingModel | LLM Router（模块 1） | 执行轨迹向量化 |
| 遗忘引擎操作 SemanticMemory | 语义记忆（模块 6） | archive() / 更新 description |
| 遗忘引擎调用 LLM | LLM Router（模块 1） | Reflection-Summary 策略生成摘要 |

---

## 11. 调研参考汇总

### 理论来源
- MaRS: A Cognitive Memory Architecture and Benchmark for Privacy-Aware Generative Agents ([arXiv 2512.12856](https://arxiv.org/abs/2512.12856))
- FadeMem: Biologically-Inspired Forgetting for Efficient Agent Memory ([arXiv 2601.18642](https://arxiv.org/abs/2601.18642))
- TiMem: Temporal-Hierarchical Memory Consolidation ([arXiv 2601.02845](https://arxiv.org/abs/2601.02845))
- SimpleMem: Efficient Lifelong Memory for LLM Agents ([arXiv 2601.02553](https://arxiv.org/abs/2601.02553))
- HiMem: Hierarchical Long-Term Memory for LLM Agents ([arXiv 2601.06377](https://arxiv.org/abs/2601.06377))
- TeleMem: Building Long-Term and Multimodal Memory for Agentic AI ([arXiv 2601.06037](https://arxiv.org/abs/2601.06037))
- MemGAS: Multi-Granularity Association and Selection ([arXiv 2505.19549](https://arxiv.org/abs/2505.19549))
- Agent Memory Operations Survey ([arXiv 2505.00675](https://arxiv.org/abs/2505.00675))
- Synapse: Spreading Activation for Episodic-Semantic Memory ([arXiv 2601.02744](https://arxiv.org/abs/2601.02744))
- GraphRAG-Bench: Comprehensive Analysis for GraphRAG ([arXiv 2506.05690](https://arxiv.org/abs/2506.05690))
- Intelligent Decay for Long-Running Agents ([arXiv 2509.25250](https://arxiv.org/abs/2509.25250))

### 开源项目
- EverMemOS (EverMind): https://evermind.sh
- Mem0: https://github.com/mem0ai/mem0
- Engram: https://github.com/engram-network/engram

### 竞品产品
- EverMemOS Cloud Service (2026)
- Mem0 Cloud API
- LangChain Agent Builder Memory Architecture (2026)

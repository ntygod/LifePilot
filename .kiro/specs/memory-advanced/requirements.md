# 需求文档：记忆系统进阶 (memory-advanced)

## 简介

本模块（模块 23）是 LifePilot 记忆系统的「演化层」，在 Phase 2 已完成的 L1-L3 记忆存储与检索基础上，补全记忆的完整生命周期管理能力。核心包含三个子系统：

1. **L4 程序记忆**：从重复的成功执行模式中自动提炼操作模板、偏好规则和策略模式，让 Agent 从「每次都要想」进化为「记住怎么做」
2. **记忆巩固管线**：模拟人类睡眠期间的记忆巩固过程，实现情景记忆→语义记忆、情景记忆→程序记忆的双向巩固
3. **MaRS 认知遗忘引擎**：基于 MaRS 论文的 Hybrid 四阶段遗忘策略，在预算约束下智能清理低价值记忆

参考文档：
- 架构设计：#[[file:docs/architecture/memory-advanced.md]]
- 特性设计：#[[file:docs/features/memory-advanced.md]]
- 基础记忆架构：#[[file:docs/architecture/memory-system.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## 术语表

- **ConsolidationPipeline**：记忆巩固管线编排服务，定时调度并顺序执行两个方向的巩固器
- **EpisodicToSemanticConsolidator**：情景→语义巩固器，分析近期对话中的实体提及频率，高频实体触发知识提取或重要度提升
- **EpisodicToProceduralConsolidator**：情景→程序巩固器，识别成功执行轨迹，向量聚类发现重复模式，LLM 提炼操作模板
- **ForgettingEngine**：遗忘策略引擎，执行 MaRS Hybrid 四阶段遗忘流程
- **ForgettingPolicy**：遗忘策略 sealed interface，定义 6 种遗忘策略（FIFO、LRU、PriorityDecay、ReflectionSummary、RandomDrop、Hybrid）
- **ProceduralMemory**：L4 程序记忆服务，管理操作模板、偏好规则、策略模式的 CRUD 和检索
- **IntentMatcher**：意图匹配器，基于 sqlite-vec 向量相似度 + FTS5 关键词的双路匹配，为用户意图找到最佳操作模板
- **ProcedureTemplate**：操作模板 record，编码多步操作的可复用序列，包含步骤列表、变量定义、成功率追踪
- **TemplateStep**：模板步骤 record，操作模板中的单个执行步骤，支持 ${variable} 占位符参数
- **PreferenceRule**：偏好规则 record，从用户行为中学习到的个人偏好，按类别组织，带置信度评分
- **StrategyPattern**：策略模式 record，特定情境下推荐的高层次决策策略
- **MemoryProperties**：记忆系统配置属性类，通过 @ConfigurationProperties 外部化所有业务可调参数
- **ConsolidationStats**：巩固统计结果 record，记录每次巩固的分析对话数、发现实体数、提升实体数、触发提取数
- **ForgettingPriority**：遗忘优先级计算器，综合时间因子、访问因子、重要度因子和隐私因子计算遗忘紧迫程度
- **TemporalEntity**：时序知识图谱实体（L3 语义记忆，Phase 2 已实现）
- **HybridRetriever**：三路混合检索引擎（Phase 2 已实现），本模块扩展 L4 检索路径
- **KnowledgeExtractionPipeline**：知识提取管线（Phase 2 已实现），巩固管线在发现未提取的高频对话时调用

## 需求

### 需求 1：L4 程序记忆数据模型与持久化

**用户故事：** 作为 LifePilot 开发者，我希望 L4 程序记忆拥有完整的数据模型和数据库表结构，以便操作模板、偏好规则和策略模式能够被持久化存储和检索。

#### 验收标准

1. THE ProceduralMemory 服务 SHALL 使用 ProcedureTemplate record 存储操作模板，包含 templateId、name、description、triggerIntent、steps、variables、successRate、useCount、lastUsedAt、sourceTraceIds、createdAt、updatedAt 字段
2. THE ProceduralMemory 服务 SHALL 使用 TemplateStep record 存储模板步骤，包含 stepOrder、toolId、action、parameterTemplate、description、isOptional 字段，支持 ${variable} 占位符语法
3. THE ProceduralMemory 服务 SHALL 使用 PreferenceRule record 存储偏好规则，包含 ruleId、category、key、value、confidence、learnedFrom、observationCount、createdAt、updatedAt 字段
4. THE ProceduralMemory 服务 SHALL 使用 StrategyPattern record 存储策略模式，包含 patternId、situation、recommendedAction、successRate、applicationCount、createdAt 字段
5. THE Flyway 迁移脚本 SHALL 创建 procedure_templates、preference_rules、strategy_patterns 三张主表，以及 procedure_templates_fts（FTS5 全文索引）、procedure_intent_vec（sqlite-vec 向量索引）、strategy_situation_vec（sqlite-vec 向量索引）三张虚拟表
6. THE procedure_templates 表 SHALL 使用 TEXT 类型存储 UUID 主键、ISO 8601 时间戳，使用 TEXT + _json 后缀存储 JSON 数组和对象字段（steps_json、variables_json、source_trace_ids_json）

### 需求 2：ProceduralMemory 服务 CRUD 与成功率追踪

**用户故事：** 作为 LifePilot Agent，我希望能够创建、查询、更新和删除操作模板，并追踪模板的执行成功率，以便自动淘汰低质量模板。

#### 验收标准

1. THE ProceduralMemory 服务 SHALL 提供操作模板的完整 CRUD 操作（创建、按 ID 查询、更新、删除）
2. WHEN 操作模板执行成功时，THE ProceduralMemory 服务 SHALL 递增 useCount 并按加权平均更新 successRate
3. WHEN 操作模板执行失败时，THE ProceduralMemory 服务 SHALL 递增 useCount 并按加权平均降低 successRate
4. THE ProcedureTemplate 的 isReliable() 方法 SHALL 在 successRate ≥ 配置阈值（默认 0.7）且 useCount ≥ 配置最低使用次数（默认 2）时返回 true
5. THE ProcedureTemplate 的 isStale() 方法 SHALL 在 lastUsedAt 超过配置的过时天数阈值（默认 90 天）时返回 true
6. THE ProceduralMemory 服务 SHALL 提供偏好规则的 CRUD 操作，同一 category 和 key 组合下偏好规则唯一
7. WHEN 偏好规则被重复观察到时，THE ProceduralMemory 服务 SHALL 递增 observationCount 并提升 confidence 值
8. THE ProceduralMemory 服务 SHALL 提供策略模式的 CRUD 操作，支持按 situation 向量相似度检索

### 需求 3：IntentMatcher 意图匹配

**用户故事：** 作为 LifePilot Agent，我希望能够根据用户意图的语义相似度快速匹配最佳操作模板，以便复用之前成功的操作模式而非每次从头推理。

#### 验收标准

1. THE IntentMatcher SHALL 采用双路并行匹配策略：sqlite-vec 向量语义匹配（权重 0.7）和 FTS5 关键词匹配（权重 0.3）
2. WHEN 用户意图文本输入时，THE IntentMatcher SHALL 返回融合评分最高且分数 ≥ 配置阈值（默认 0.6）的操作模板，若无匹配则返回 Optional.empty()
3. THE IntentMatcher SHALL 仅返回 isReliable() 为 true 的操作模板作为匹配候选
4. THE IntentMatcher SHALL 使用 EmbeddingModel 将用户意图文本向量化，与 procedure_intent_vec 中的模板意图向量计算余弦相似度

### 需求 4：HybridRetriever L4 检索路径扩展

**用户故事：** 作为 LifePilot Agent，我希望在上下文组装时能够同时检索 L4 程序记忆，以便在回答用户时参考已有的操作模板和偏好规则。

#### 验收标准

1. THE HybridRetriever SHALL 在现有三路检索（向量 + FTS5 + 图遍历）基础上新增 L4 程序记忆检索路径
2. WHEN ContextAssembler 执行检索时，THE IntentMatcher 的匹配结果 SHALL 被注入到 ReasoningSlot 中，作为 Agent 的执行建议
3. THE L4 检索路径 SHALL 与现有三路检索并行执行，总检索延迟不因新增路径而显著增加


### 需求 5：情景→语义巩固器

**用户故事：** 作为 LifePilot 用户，我希望 Agent 能自动从日常对话中识别高频提及的实体并强化记忆，以便频繁讨论的人物、项目等被自动记住而无需手动整理。

#### 验收标准

1. THE EpisodicToSemanticConsolidator SHALL 获取最近 N 天（默认 7 天，可配置）的对话记录进行分析
2. THE EpisodicToSemanticConsolidator SHALL 统计已有 L3 实体在对话文本中的提及频率
3. WHEN 实体提及频率 ≥ 配置阈值（默认 3 次）时，THE EpisodicToSemanticConsolidator SHALL 提升该实体的 importanceScore，提升步长可配置（默认 0.1），单次巩固最大提升量可配置（默认 0.3），importanceScore 上限为 1.0
4. WHEN 对话文本长度 > 200 字符且尚未被知识提取覆盖时，THE EpisodicToSemanticConsolidator SHALL 触发 KnowledgeExtractionPipeline 进行知识提取
5. THE EpisodicToSemanticConsolidator SHALL 采用增量窗口策略，每次只处理最近增量，已巩固的对话不重复分析
6. THE EpisodicToSemanticConsolidator SHALL 在每次执行后记录巩固日志到 memory_consolidation_log 表，包含分析对话数、发现实体数、提升实体数、触发提取数、耗时

### 需求 6：情景→程序巩固器

**用户故事：** 作为 LifePilot 用户，我希望 Agent 能自动从重复的成功操作中提炼操作模板，以便下次遇到类似任务时直接复用而非从头推理。

#### 验收标准

1. THE EpisodicToProceduralConsolidator SHALL 获取最近 N 天（默认 7 天，可配置）包含工具调用的成功执行轨迹
2. THE EpisodicToProceduralConsolidator SHALL 仅处理工具调用步数 ≥ 配置阈值（默认 2 步）的执行轨迹
3. THE EpisodicToProceduralConsolidator SHALL 使用 EmbeddingModel 将执行轨迹的工具调用序列向量化，通过余弦相似度聚类（阈值 ≥ 配置值，默认 0.85）
4. WHEN 聚类大小 ≥ 配置最小样本数（默认 2）时，THE EpisodicToProceduralConsolidator SHALL 使用 LLM 从聚类中提炼操作模板，提取公共步骤序列和 ${variable} 变量占位符
5. THE EpisodicToProceduralConsolidator SHALL 将新模板与已有模板进行去重（triggerIntent 向量相似度 ≥ 0.9 视为同一模板），已有模板更新 successRate 和 useCount，新模板保存到 L4
6. THE EpisodicToProceduralConsolidator SHALL 每次执行最多提炼配置数量（默认 10）个新模板
7. THE EpisodicToProceduralConsolidator SHALL 在每次执行后记录巩固日志到 memory_consolidation_log 表，包含创建模板数、更新模板数、耗时

### 需求 7：ConsolidationPipeline 编排与调度

**用户故事：** 作为 LifePilot 系统管理员，我希望巩固管线能按配置的时间自动执行，并按正确顺序编排两个方向的巩固器。

#### 验收标准

1. THE ConsolidationPipeline SHALL 通过 Spring @Scheduled 注解按配置的 Cron 表达式（默认每日凌晨 3:00）定时执行
2. THE ConsolidationPipeline SHALL 先执行 EpisodicToSemanticConsolidator，再执行 EpisodicToProceduralConsolidator，保证语义巩固先于程序巩固
3. IF EpisodicToSemanticConsolidator 执行失败，THEN THE ConsolidationPipeline SHALL 记录错误日志并继续执行 EpisodicToProceduralConsolidator，单个巩固器的失败不阻塞另一个
4. THE ConsolidationPipeline 的 consolidate() 方法 SHALL 设计为可被外部调用（不仅限于 @Scheduled），为未来 Idle-Driven 触发模式预留扩展接口
5. THE ConsolidationPipeline SHALL 保证幂等性：对同一时间窗口重复执行巩固操作，结果与执行一次完全相同（importanceScore 有上限 1.0，已提取的对话不重复提取，已存在的模板不重复创建）
6. THE Cron 表达式 SHALL 通过 @ConfigurationProperties 外部化，配置键为 lifepilot.memory.consolidation.cron

### 需求 8：MaRS Hybrid 四阶段遗忘策略

**用户故事：** 作为 LifePilot 用户，我希望 Agent 的记忆系统能自动清理低价值信息，以便记忆保持精炼、检索质量不因记忆膨胀而下降。

#### 验收标准

1. THE ForgettingPolicy sealed interface SHALL 定义 6 种遗忘策略实现：FifoPolicy、LruPolicy、PriorityDecayPolicy、ReflectionSummaryPolicy、RandomDropPolicy、HybridPolicy
2. THE HybridPolicy SHALL 按四阶段顺序执行遗忘：FIFO（淘汰超过配置最大保留天数的实体，默认 365 天）→ LRU（淘汰超过配置天数未访问且 accessCount = 0 的实体，默认 90 天）→ Priority Decay（淘汰衰减后优先级低于配置阈值的实体，默认 0.2）→ Reflection-Summary（对中等重要度实体生成 LLM 摘要压缩）
3. THE PriorityDecayPolicy SHALL 使用指数衰减公式 effectivePriority = importanceScore × exp(-λ × daysSinceLastAccess)，其中 λ 可配置（默认 0.02，半衰期约 35 天）
4. THE ReflectionSummaryPolicy SHALL 仅选择 importanceScore 在配置范围内（默认 [0.3, 0.8)）的实体进行摘要压缩
5. THE HybridPolicy 的每个阶段 SHALL 有独立的预算限制（总预算 / 4），防止单阶段遗忘过多实体

### 需求 9：ForgettingEngine 遗忘引擎与安全保障

**用户故事：** 作为 LifePilot 用户，我希望遗忘引擎在清理低价值记忆的同时保护我的重要记忆，以便核心偏好和习惯永远不会被误删。

#### 验收标准

1. THE ForgettingEngine SHALL 通过 Spring @Scheduled 注解按配置的 Cron 表达式（默认每周日凌晨 4:00）定时执行，在巩固管线之后运行
2. THE ForgettingEngine SHALL 保护用户认知类实体（EntityType 为 PREFERENCE、HABIT、GOAL 的实体），这些实体永不被选为遗忘候选
3. THE ForgettingEngine SHALL 保护用户手动标记为重要的实体，这些实体永不被遗忘
4. THE ForgettingEngine SHALL 保护高重要度实体（importanceScore ≥ 配置阈值），这些实体永不被遗忘
5. THE ForgettingEngine SHALL 限制每次执行的最大遗忘数量（默认 100，可配置），防止单次遗忘过多
6. THE ForgettingEngine SHALL 对包含 PII 标记的实体在遗忘优先级计算中增加额外权重（默认 0.3，可配置），优先清理隐私敏感数据
7. THE ForgettingEngine SHALL 在每次遗忘操作后记录遗忘日志到 forgetting_log 表，包含实体 ID、实体名称、使用的策略、执行的动作（ARCHIVED / COMPRESSED / DELETED）、遗忘优先级、原因
8. THE ForgettingPriority 计算器 SHALL 使用综合评分公式 priority = (timeFactor × 0.3 + accessFactor × 0.3 + importanceFactor × 0.2) × privacyFactor，其中 timeFactor = daysSinceLastAccess / maxRetentionDays，accessFactor = 1.0 / (1 + accessCount)，importanceFactor = 1.0 - importanceScore，privacyFactor = containsPII ? 配置倍数 : 1.0
9. IF LLM 不可用，THEN THE ForgettingEngine SHALL 跳过 Reflection-Summary 阶段，继续执行其他阶段的遗忘操作


### 需求 10：数据库迁移脚本

**用户故事：** 作为 LifePilot 开发者，我希望 L4 程序记忆表和巩固/遗忘日志表通过 Flyway 迁移脚本自动创建，以便数据库 schema 版本化管理。

#### 验收标准

1. THE Flyway 迁移脚本 SHALL 使用版本号 V20（当前最大版本为 V19），脚本命名为 V20__create_memory_advanced_tables.sql
2. THE 迁移脚本 SHALL 创建 procedure_templates 表（TEXT 主键、TEXT 时间戳、REAL 成功率、INTEGER 使用次数、TEXT JSON 字段）
3. THE 迁移脚本 SHALL 创建 procedure_templates_fts 虚拟表（FTS5，tokenize='unicode61'，索引 name、description、trigger_intent）
4. THE 迁移脚本 SHALL 创建 preference_rules 表，包含 UNIQUE(category, key) 约束
5. THE 迁移脚本 SHALL 创建 strategy_patterns 表
6. THE 迁移脚本 SHALL 创建 memory_consolidation_log 表，记录巩固执行日志
7. THE 迁移脚本 SHALL 创建 forgetting_log 表，记录遗忘操作日志
8. THE sqlite-vec 向量索引虚拟表（procedure_intent_vec、strategy_situation_vec）SHALL 通过程序化创建（与现有 entity_embeddings 保持一致的创建方式），不在 Flyway 迁移脚本中创建

### 需求 11：配置外部化

**用户故事：** 作为 LifePilot 系统管理员，我希望记忆系统进阶的所有业务可调参数都通过 application.yml 配置，以便无需修改代码即可调整巩固和遗忘行为。

#### 验收标准

1. THE MemoryProperties 配置类 SHALL 通过 @ConfigurationProperties 外部化以下 L4 程序记忆参数：max-templates（默认 200）、min-reliability（默认 0.7）、min-use-count（默认 2）、stale-days（默认 90）、match-threshold（默认 0.6）、default-importance（默认 0.5）
2. THE MemoryProperties 配置类 SHALL 通过 @ConfigurationProperties 外部化以下巩固管线参数：cron（默认 "0 0 3 * * *"）、trigger-mode（默认 CRON，预留 IDLE 和 HYBRID）、lookback-days（默认 7）、high-frequency-threshold（默认 3）、importance-boost-step（默认 0.1）、importance-boost-max（默认 0.3）、cluster-similarity-threshold（默认 0.85）、min-cluster-size（默认 2）、max-templates-per-run（默认 10）、min-execution-steps（默认 2）
3. THE MemoryProperties 配置类 SHALL 通过 @ConfigurationProperties 外部化以下遗忘引擎参数：cron（默认 "0 0 4 * * SUN"）、max-retention-days（默认 365）、lru-threshold-days（默认 90）、priority-decay-rate（默认 0.02）、priority-decay-threshold（默认 0.2）、reflection-summary-min-importance（默认 0.3）、reflection-summary-max-importance（默认 0.8）、max-forget-per-run（默认 100）、privacy-aware-boost（默认 0.3）
4. THE 配置键 SHALL 使用 kebab-case 命名，前缀为 lifepilot.memory.procedural / lifepilot.memory.consolidation / lifepilot.memory.forgetting
5. THE application.yml SHALL 显式声明所有配置项及默认值

### 需求 12：LLM 降级与容错

**用户故事：** 作为 LifePilot 用户，我希望在 LLM 不可用时，不依赖 LLM 的记忆功能继续正常工作，以便系统具备基本的容错能力。

#### 验收标准

1. IF LLM 不可用，THEN THE EpisodicToSemanticConsolidator SHALL 正常执行实体频率统计和 importanceScore 提升，仅跳过需要 LLM 的知识提取步骤
2. IF LLM 不可用，THEN THE EpisodicToProceduralConsolidator SHALL 跳过模板提炼步骤，记录警告日志并返回 0 个新模板
3. IF LLM 不可用，THEN THE ForgettingEngine SHALL 正常执行 FIFO、LRU、Priority Decay 三个阶段，仅跳过 Reflection-Summary 阶段
4. WHEN LLM 调用失败时，THE ConsolidationPipeline SHALL 记录 WARN 级别日志，包含失败原因和跳过的步骤信息

### 需求 13：遗忘安全性属性测试

**用户故事：** 作为 LifePilot 开发者，我希望通过属性测试验证遗忘策略在任意输入下都满足安全性不变量，以便防止错误遗忘导致不可恢复的信息丢失。

#### 验收标准

1. FOR ALL 随机生成的实体列表和预算比例，THE HybridPolicy 选择的遗忘候选中 SHALL 不包含 EntityType 为 PREFERENCE、HABIT、GOAL 的用户认知实体
2. FOR ALL 随机生成的实体列表和预算比例，THE HybridPolicy 选择的遗忘候选中 SHALL 不包含 importanceScore ≥ 保护阈值的高重要度实体
3. FOR ALL 随机生成的实体列表和预算值，THE ForgettingPolicy 的所有实现 SHALL 返回的遗忘候选数量不超过预算值
4. FOR ALL 随机生成的实体列表，THE HybridPolicy 执行遗忘后，剩余实体的平均 importanceScore SHALL 不低于遗忘前的平均 importanceScore

### 需求 14：巩固幂等性属性测试

**用户故事：** 作为 LifePilot 开发者，我希望通过属性测试验证巩固操作的幂等性，以便定时调度的重复执行不会产生副作用。

#### 验收标准

1. FOR ALL 随机生成的对话记录和实体集合，THE EpisodicToSemanticConsolidator 对同一数据集执行两次巩固 SHALL 产生与执行一次相同的结果（importanceScore 不会无限增长，已提取的对话不重复提取）
2. FOR ALL 随机生成的执行轨迹集合，THE EpisodicToProceduralConsolidator 对同一数据集执行两次巩固 SHALL 不产生重复的操作模板（去重机制生效）
3. FOR ALL 随机生成的实体列表，THE ForgettingEngine 对同一数据集执行两次遗忘 SHALL 产生与执行一次相同的结果（已归档的实体不重复处理）

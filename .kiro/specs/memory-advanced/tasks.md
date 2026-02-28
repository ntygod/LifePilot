# Implementation Plan: 记忆系统进阶 (memory-advanced)

## Overview

基于 Phase 2 已完成的 L1-L3 记忆系统，实现 L4 程序记忆、记忆巩固管线和 MaRS 遗忘引擎三个子系统。按自底向上顺序：先数据模型与配置 → 核心服务 → 巩固管线 → 遗忘引擎 → 集成扩展。使用 Java 22（record、sealed interface、pattern matching），遵循 LifePilot 编码规范。

## Tasks

- [x] 1. Flyway V20 迁移脚本与 MemoryProperties 配置扩展
  - [x] 1.1 创建 Flyway V20 迁移脚本
    - 创建 `src/main/resources/db/migration/V20__create_memory_advanced_tables.sql`
    - 包含 procedure_templates 表（TEXT 主键、TEXT 时间戳、REAL 成功率、INTEGER 使用次数、TEXT JSON 字段）
    - 包含 procedure_templates_fts 虚拟表（FTS5，tokenize='unicode61'，索引 name/description/trigger_intent）+ 同步触发器
    - 包含 preference_rules 表（UNIQUE(category, key) 约束）
    - 包含 strategy_patterns 表
    - 包含 memory_consolidation_log 表
    - 包含 forgetting_log 表
    - sqlite-vec 向量索引虚拟表通过程序化创建，不在此脚本中
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8_

  - [x] 1.2 扩展 MemoryProperties 配置类
    - 在现有 `MemoryProperties` 中新增 `Procedural`、`Consolidation`、`Forgetting` 三个嵌套静态内部类
    - Procedural：maxTemplates(200)、minReliability(0.7f)、minUseCount(2)、staleDays(90)、matchThreshold(0.6f)、defaultImportance(0.5f)
    - Consolidation：cron、triggerMode、lookbackDays、highFrequencyThreshold、importanceBoostStep、importanceBoostMax、clusterSimilarityThreshold、minClusterSize、maxTemplatesPerRun、minExecutionSteps
    - Forgetting：cron、maxRetentionDays、lruThresholdDays、priorityDecayRate、priorityDecayThreshold、reflectionSummaryMinImportance、reflectionSummaryMaxImportance、maxForgetPerRun、privacyAwareBoost
    - 所有字段提供 getter/setter，配置键使用 kebab-case
    - _Requirements: 11.1, 11.2, 11.3, 11.4_

  - [x] 1.3 更新 application.yml 配置声明
    - 在 `lifepilot.memory` 下新增 procedural、consolidation、forgetting 三个配置块
    - 显式声明所有配置项及默认值
    - _Requirements: 11.5_

- [ ] 2. L4 程序记忆数据模型
  - [ ] 2.1 创建 ProcedureTemplate record
    - 创建 `com.lifepilot.memory.procedural.ProcedureTemplate`
    - 字段：templateId、name、description、triggerIntent、steps(List<TemplateStep>)、variables(Map<String,String>)、successRate、useCount、lastUsedAt、sourceTraceIds、createdAt、updatedAt
    - 实现 `isReliable(float minReliability, int minUseCount)` 方法
    - 实现 `isStale(int staleDays)` 方法
    - _Requirements: 1.1, 2.4, 2.5_

  - [ ]* 2.2 写属性测试：isReliable 阈值判断
    - **Property 3: isReliable 阈值判断正确性**
    - **Validates: Requirements 2.4**

  - [ ]* 2.3 写属性测试：isStale 时间判断
    - **Property 4: isStale 时间判断正确性**
    - **Validates: Requirements 2.5**

  - [ ] 2.4 创建 TemplateStep record
    - 创建 `com.lifepilot.memory.procedural.TemplateStep`
    - 字段：stepOrder、toolId、action、parameterTemplate(Map<String,String>)、description、isOptional
    - 实现 `resolveParameters(Map<String,String> variables)` 方法，替换 ${variable} 占位符
    - _Requirements: 1.2_

  - [ ]* 2.5 写属性测试：变量替换正确性
    - **Property 8: TemplateStep 变量替换正确性**
    - **Validates: Requirements 1.2**

  - [ ] 2.6 创建 PreferenceRule record
    - 创建 `com.lifepilot.memory.procedural.PreferenceRule`
    - 字段：ruleId、category、key、value、confidence、learnedFrom、observationCount、createdAt、updatedAt
    - 实现 `isHighConfidence()` 方法
    - _Requirements: 1.3_

  - [ ] 2.7 创建 StrategyPattern record
    - 创建 `com.lifepilot.memory.procedural.StrategyPattern`
    - 字段：patternId、situation、recommendedAction、successRate、applicationCount、createdAt
    - _Requirements: 1.4_

  - [ ] 2.8 创建 ConsolidationStats record
    - 创建 `com.lifepilot.memory.consolidation.ConsolidationStats`
    - 字段：consolidationType、conversationsAnalyzed、entitiesFound、entitiesBoosted、extractionsTriggered、templatesCreated、templatesUpdated、elapsedMs
    - _Requirements: 5.6, 6.7_

- [ ] 3. Checkpoint — 数据模型验证
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. ProceduralMemory 服务 CRUD 与成功率追踪
  - [ ] 4.1 实现 ProceduralMemory 服务 — 模板 CRUD
    - 创建 `com.lifepilot.memory.procedural.ProceduralMemory`
    - 构造函数注入 JdbcTemplate、VectorSearcher、MemoryProperties
    - 实现 save/findById/update/delete 四个模板 CRUD 方法
    - save 时通过 VectorSearcher 创建 triggerIntent 向量索引
    - 初始化时程序化创建 procedure_intent_vec、strategy_situation_vec 虚拟表（与现有 entity_embeddings 一致）
    - JSON 字段使用 ObjectMapper 序列化/反序列化 steps_json、variables_json、source_trace_ids_json
    - _Requirements: 2.1, 1.5, 1.6, 10.8_

  - [ ]* 4.2 写属性测试：模板 CRUD 往返一致性
    - **Property 1: 模板 CRUD 往返一致性**
    - **Validates: Requirements 2.1**

  - [ ] 4.3 实现 ProceduralMemory 服务 — 成功率追踪
    - 实现 `recordExecution(String templateId, boolean success)` 方法
    - 加权平均公式：newRate = (oldRate × oldCount + (success ? 1.0 : 0.0)) / (oldCount + 1)
    - 同时递增 useCount，更新 lastUsedAt
    - _Requirements: 2.2, 2.3_

  - [ ]* 4.4 写属性测试：成功率加权平均公式
    - **Property 2: 成功率加权平均公式正确性**
    - **Validates: Requirements 2.2, 2.3**

  - [ ] 4.5 实现 ProceduralMemory 服务 — 偏好规则 CRUD
    - 实现 savePreference/findPreference/getPreferences/reinforcePreference 方法
    - savePreference 使用 INSERT OR REPLACE 保证 (category, key) 唯一性
    - reinforcePreference 递增 observationCount 并提升 confidence
    - _Requirements: 2.6, 2.7_

  - [ ]* 4.6 写属性测试：偏好规则唯一性与强化
    - **Property 5: 偏好规则唯一性约束**
    - **Property 6: 偏好规则强化单调递增**
    - **Validates: Requirements 2.6, 2.7**

  - [ ] 4.7 实现 ProceduralMemory 服务 — 策略模式 CRUD
    - 实现 saveStrategy/findStrategiesBySituation 方法
    - findStrategiesBySituation 通过 strategy_situation_vec 向量相似度检索
    - _Requirements: 2.8_

  - [ ]* 4.8 写单元测试：ProceduralMemory 服务
    - 测试模板 CRUD 完整流程
    - 测试偏好规则唯一性约束
    - 测试策略模式向量检索
    - _Requirements: 2.1, 2.6, 2.8_

- [ ] 5. IntentMatcher 意图匹配器
  - [ ] 5.1 实现 IntentMatcher 双路匹配
    - 创建 `com.lifepilot.memory.procedural.IntentMatcher`
    - 构造函数注入 ProceduralMemory、VectorSearcher、FtsSearcher、LlmRouter、MemoryProperties
    - 创建 `TemplateMatch` 内部 record（template, score）
    - 实现 `match(String intentText)` 方法：
      1. LlmRouter.embed(intentText) 获取意图向量
      2. 并行执行 sqlite-vec 向量搜索 + FTS5 关键词搜索
      3. 加权融合 score = 0.7 × semanticScore + 0.3 × keywordScore
      4. 过滤 isReliable() 为 true 的候选
      5. 返回最高分且 ≥ matchThreshold 的模板
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [ ]* 5.2 写属性测试：IntentMatcher 仅返回可靠且超阈值模板
    - **Property 7: IntentMatcher 仅返回可靠且超阈值的模板**
    - **Validates: Requirements 3.2, 3.3**

  - [ ]* 5.3 写单元测试：IntentMatcher
    - 测试无匹配时返回 Optional.empty()
    - 测试不可靠模板被过滤
    - 测试融合评分低于阈值时返回空
    - _Requirements: 3.2, 3.3_

- [ ] 6. Checkpoint — L4 程序记忆核心完成
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. EpisodicToSemanticConsolidator 情景→语义巩固器
  - [ ] 7.1 实现 EpisodicToSemanticConsolidator
    - 创建 `com.lifepilot.memory.consolidation.EpisodicToSemanticConsolidator`
    - 构造函数注入 EpisodicMemory、SemanticMemory、@Nullable KnowledgeExtractionPipeline、JdbcTemplate、MemoryProperties
    - 实现 `consolidate()` 方法：
      1. 获取最近 lookbackDays 天对话（增量窗口，通过 memory_consolidation_log 记录上次巩固时间戳）
      2. 统计已有 L3 实体在对话文本中的提及频率
      3. 高频实体（≥ highFrequencyThreshold）提升 importanceScore（步长 importanceBoostStep，单次最大 importanceBoostMax，上限 1.0）
      4. 长对话（> 200 字符）且未被知识提取覆盖 → 触发 KnowledgeExtractionPipeline（LLM 不可用时跳过）
      5. 记录巩固日志到 memory_consolidation_log
    - 返回 ConsolidationStats
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 12.1_

  - [ ]* 7.2 写属性测试：importanceScore 提升有界
    - **Property 9: importanceScore 提升有界且不超上限**
    - **Validates: Requirements 5.3**

  - [ ]* 7.3 写属性测试：语义巩固幂等性
    - **Property 22: 语义巩固幂等性**
    - **Validates: Requirements 14.1**

  - [ ]* 7.4 写单元测试：EpisodicToSemanticConsolidator
    - 测试增量窗口策略（已巩固对话不重复分析）
    - 测试 LLM 不可用时跳过知识提取但正常执行频率统计
    - 测试 importanceScore 上限 1.0 不被突破
    - _Requirements: 5.5, 12.1_

- [ ] 8. EpisodicToProceduralConsolidator 情景→程序巩固器
  - [ ] 8.1 实现 EpisodicToProceduralConsolidator
    - 创建 `com.lifepilot.memory.consolidation.EpisodicToProceduralConsolidator`
    - 构造函数注入 JdbcTemplate、ProceduralMemory、LlmRouter、MemoryProperties
    - 实现 `consolidate()` 方法：
      1. 从 agent_traces + agent_trace_steps 查询最近 lookbackDays 天成功执行轨迹（工具调用步数 ≥ minExecutionSteps）
      2. LlmRouter.embed() 向量化工具调用序列，余弦相似度聚类（阈值 ≥ clusterSimilarityThreshold）
      3. 聚类大小 ≥ minClusterSize → LlmRouter.callEntity() 提炼操作模板（提取公共步骤 + ${variable} 变量）
      4. 与已有模板去重（triggerIntent 向量相似度 ≥ 0.9 视为同一模板）
      5. 每次最多提炼 maxTemplatesPerRun 个新模板
      6. 记录巩固日志到 memory_consolidation_log
    - LLM 不可用时跳过模板提炼，返回 0 个新模板
    - 返回 ConsolidationStats
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 12.2_

  - [ ]* 8.2 写属性测试：执行轨迹按最小步数过滤
    - **Property 10: 执行轨迹按最小步数过滤**
    - **Validates: Requirements 6.2**

  - [ ]* 8.3 写属性测试：单次巩固最大模板数限制
    - **Property 11: 单次巩固最大模板数限制**
    - **Validates: Requirements 6.6**

  - [ ]* 8.4 写属性测试：程序巩固幂等性
    - **Property 23: 程序巩固幂等性**
    - **Validates: Requirements 14.2**

- [ ] 9. ConsolidationPipeline 编排与调度
  - [ ] 9.1 实现 ConsolidationPipeline
    - 创建 `com.lifepilot.memory.consolidation.ConsolidationPipeline`
    - 构造函数注入 EpisodicToSemanticConsolidator、EpisodicToProceduralConsolidator、MemoryProperties
    - 实现 `@Scheduled(cron = "${lifepilot.memory.consolidation.cron}")` 定时入口
    - 实现 `consolidate()` 公开方法（为 Idle-Driven 预留）
    - 顺序执行：语义巩固 → 程序巩固
    - 单个巩固器异常不阻塞另一个（try-catch 隔离）
    - 记录 WARN 级别日志包含失败原因
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 12.4_

  - [ ]* 9.2 写属性测试：巩固管线故障隔离
    - **Property 12: 巩固管线故障隔离**
    - **Validates: Requirements 7.3**

  - [ ]* 9.3 写单元测试：ConsolidationPipeline
    - 测试正常顺序执行两个巩固器
    - 测试语义巩固器异常时程序巩固器仍执行
    - 测试 Cron 配置外部化
    - _Requirements: 7.2, 7.3, 7.6_

- [ ] 10. Checkpoint — 巩固管线完成
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 11. ForgettingPolicy sealed interface 与策略实现
  - [ ] 11.1 创建 ForgettingPolicy sealed interface 与 ForgettingPriority 计算器
    - 创建 `com.lifepilot.memory.forgetting.ForgettingPolicy` sealed interface
    - permits: FifoPolicy, LruPolicy, PriorityDecayPolicy, ReflectionSummaryPolicy, RandomDropPolicy, HybridPolicy
    - 定义 `selectForForgetting(List<TemporalEntity> candidates, int budget)` 和 `name()` 方法
    - 创建 `com.lifepilot.memory.forgetting.ForgettingPriority` 计算器
    - 实现综合评分公式：priority = (timeFactor × 0.3 + accessFactor × 0.3 + importanceFactor × 0.2) × privacyFactor
    - _Requirements: 8.1, 9.8_

  - [ ]* 11.2 写属性测试：ForgettingPriority 公式正确性
    - **Property 16: ForgettingPriority 公式正确性**
    - **Validates: Requirements 9.8**

  - [ ]* 11.3 写属性测试：PII 实体遗忘优先级更高
    - **Property 15: PII 实体遗忘优先级更高**
    - **Validates: Requirements 9.6**

  - [ ] 11.4 实现 FifoPolicy 和 LruPolicy
    - FifoPolicy：淘汰超过 maxRetentionDays 的实体
    - LruPolicy：淘汰超过 lruThresholdDays 未访问且 accessCount = 0 的实体
    - _Requirements: 8.2_

  - [ ] 11.5 实现 PriorityDecayPolicy
    - 指数衰减公式：effectivePriority = importanceScore × exp(-λ × daysSinceLastAccess)
    - 淘汰衰减后优先级低于 priorityDecayThreshold 的实体
    - _Requirements: 8.3_

  - [ ]* 11.6 写属性测试：Priority Decay 指数衰减公式
    - **Property 17: Priority Decay 指数衰减公式正确性**
    - **Validates: Requirements 8.3**

  - [ ] 11.7 实现 ReflectionSummaryPolicy
    - 仅选择 importanceScore 在 [reflectionSummaryMinImportance, reflectionSummaryMaxImportance) 范围内的实体
    - 使用 LLM 生成摘要压缩（LLM 不可用时跳过）
    - _Requirements: 8.4_

  - [ ]* 11.8 写属性测试：ReflectionSummary 重要度范围
    - **Property 18: ReflectionSummary 仅选择指定重要度范围的实体**
    - **Validates: Requirements 8.4**

  - [ ] 11.9 实现 RandomDropPolicy 和 HybridPolicy
    - RandomDropPolicy：随机选择候选实体
    - HybridPolicy：四阶段顺序执行 FIFO → LRU → PriorityDecay → ReflectionSummary
    - 每阶段独立预算限制（总预算 / 4）
    - _Requirements: 8.2, 8.5_

  - [ ]* 11.10 写属性测试：Hybrid 每阶段预算限制
    - **Property 19: Hybrid 每阶段预算限制**
    - **Validates: Requirements 8.5**

  - [ ]* 11.11 写属性测试：遗忘数量不超过预算
    - **Property 14: 遗忘数量不超过预算**
    - **Validates: Requirements 9.5, 13.3**

- [ ] 12. ForgettingEngine 遗忘引擎
  - [ ] 12.1 实现 ForgettingEngine
    - 创建 `com.lifepilot.memory.forgetting.ForgettingEngine`
    - 构造函数注入 SemanticMemory、@Nullable LlmRouter、JdbcTemplate、MemoryProperties
    - 实现 `@Scheduled(cron = "${lifepilot.memory.forgetting.cron}")` 定时入口
    - 实现 `forget()` 公开方法：
      1. 从 SemanticMemory.findAllCurrent() 获取所有当前实体
      2. 过滤受保护实体（PREFERENCE/HABIT/GOAL 类型、importanceScore ≥ 保护阈值、用户手动标记重要）
      3. 使用 HybridPolicy 执行四阶段遗忘
      4. 限制每次最大遗忘数量（maxForgetPerRun）
      5. 对遗忘候选执行动作：ARCHIVED（SemanticMemory.archive）/ COMPRESSED（LLM 摘要替换）/ DELETED
      6. 记录遗忘日志到 forgetting_log 表
    - LLM 不可用时跳过 Reflection-Summary 阶段
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.9, 12.3_

  - [ ]* 12.2 写属性测试：受保护实体永不被选中
    - **Property 13: 遗忘安全性 — 受保护实体永不被选中**
    - **Validates: Requirements 9.2, 9.3, 9.4, 13.1, 13.2**

  - [ ]* 12.3 写属性测试：遗忘提升平均重要度
    - **Property 20: 遗忘提升平均重要度**
    - **Validates: Requirements 13.4**

  - [ ]* 12.4 写属性测试：LLM 降级正常执行
    - **Property 21: LLM 降级 — 非 LLM 依赖操作正常完成**
    - **Validates: Requirements 9.9, 12.1, 12.2, 12.3**

  - [ ]* 12.5 写属性测试：遗忘幂等性
    - **Property 24: 遗忘幂等性**
    - **Validates: Requirements 14.3**

  - [ ]* 12.6 写单元测试：ForgettingEngine
    - 测试受保护实体不被遗忘
    - 测试 PII 实体优先清理
    - 测试 LLM 不可用时跳过 Reflection-Summary 但其他阶段正常
    - 测试最大遗忘数量限制
    - _Requirements: 9.2, 9.5, 9.6, 9.9_

- [ ] 13. Checkpoint — 遗忘引擎完成
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 14. HybridRetriever L4 扩展与 AutoConfiguration 注册
  - [ ] 14.1 扩展 HybridRetriever 新增 L4 检索路径
    - 修改 `HybridRetriever` 构造函数，新增 `@Nullable IntentMatcher` 参数
    - 在 retrieve() 方法中并行执行 L4 IntentMatcher.match()
    - L4 匹配结果作为独立的 ReasoningSlot 注入，不参与 RRF 融合
    - _Requirements: 4.1, 4.2, 4.3_

  - [ ] 14.2 扩展 MemoryAutoConfiguration 注册新 Bean
    - 注册 ProceduralMemory Bean（@ConditionalOnBean(VectorSearcher.class)）
    - 注册 IntentMatcher Bean（@ConditionalOnBean 多依赖）
    - 注册 EpisodicToSemanticConsolidator Bean
    - 注册 EpisodicToProceduralConsolidator Bean
    - 注册 ConsolidationPipeline Bean
    - 注册 ForgettingEngine Bean（@ConditionalOnBean(SemanticMemory.class)）
    - 修改 HybridRetriever Bean 注册，新增 @Nullable IntentMatcher 参数
    - _Requirements: 4.1, 4.2_

  - [ ]* 14.3 写集成测试：Spring Context 加载与 Bean 注入
    - 验证 @SpringBootTest 能成功加载完整 ApplicationContext
    - 验证所有新增 Bean 注入成功
    - 验证 HybridRetriever 扩展后 L4 检索路径正常工作
    - _Requirements: 4.1, 4.2, 4.3_

- [ ] 15. Final checkpoint — 全部完成
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties (24 properties from design)
- Unit tests validate specific examples and edge cases
- sqlite-vec 向量索引虚拟表通过程序化创建（ProceduralMemory 初始化时），不在 Flyway 脚本中
- 所有业务可调参数通过 MemoryProperties @ConfigurationProperties 外部化
- LLM 降级策略贯穿巩固管线和遗忘引擎，确保非 LLM 依赖功能正常工作

# Implementation Plan: 语义记忆与混合检索

## Overview

按依赖顺序实现 L3 语义记忆层和混合检索引擎：先创建 Flyway 迁移和数据模型，再实现核心服务（VersionMerger → VectorSearcher → ConflictDetector → SemanticMemory），然后实现检索组件（FtsSearcher → GraphTraverser → HybridRetriever），最后扩展 Spring 自动配置。ConflictDetector 直接使用 JdbcTemplate 进行精确匹配查询，避免与 SemanticMemory 的循环依赖。

## Tasks

- [x] 1. Flyway V7 迁移脚本 — 创建 temporal_entities 和 temporal_relations 表
  - 创建 `src/main/resources/db/migration/V7__create_semantic_memory_tables.sql`
  - 包含 temporal_entities 表（16 列）和 temporal_relations 表（10 列）
  - 包含 5 个 temporal_entities 索引（含 3 个部分索引）和 3 个 temporal_relations 索引（含 1 个部分索引）
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [ ] 2. 数据模型 — EntityType、RelationTypes、TemporalEntity、TemporalRelation
  - [x] 2.1 实现 EntityType 枚举和 RelationTypes 常量类
    - 创建 `com.lifepilot.memory.semantic.EntityType`（11 个枚举值）
    - 创建 `com.lifepilot.memory.semantic.RelationTypes`（19 个 snake_case 常量，私有构造函数）
    - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

  - [x] 2.2 实现 TemporalEntity record
    - 创建 `com.lifepilot.memory.semantic.TemporalEntity`（16 字段）
    - compact constructor 中 `Map.copyOf()` 确保 properties 不可变
    - 实现 `textRepresentation()` 和 `isActive()` 方法
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

  - [x] 2.3 实现 TemporalRelation record
    - 创建 `com.lifepilot.memory.semantic.TemporalRelation`（10 字段）
    - compact constructor 中验证 strength ∈ [0.0, 1.0]
    - 实现 `isActive()` 方法
    - _Requirements: 5.1, 5.2, 5.3_

  - [ ]* 2.4 编写 TemporalEntity 属性测试
    - **Property 1: TemporalEntity properties 不可变性**
    - **Property 17: TemporalEntity isActive 逻辑**
    - **Property 19: TemporalEntity textRepresentation 包含名称**
    - **Validates: Requirements 4.2, 4.3, 4.4**

  - [ ]* 2.5 编写 TemporalRelation 属性测试
    - **Property 2: TemporalRelation strength 范围不变量**
    - **Property 18: TemporalRelation isActive 逻辑**
    - **Validates: Requirements 5.2, 5.3**

  - [ ]* 2.6 编写 RelationTypes 单元测试
    - **Property 20: RelationTypes 常量格式（snake_case）**
    - 验证私有构造函数、常量存在性
    - **Validates: Requirements 3.1, 3.7**

- [x] 3. Checkpoint — 确保数据模型编译通过和测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. VersionMerger — 版本合并器及相关类型
  - [x] 4.1 实现 ConflictResolution 枚举、ConflictDetail record、MergeResult record
    - 创建 `com.lifepilot.memory.semantic.ConflictResolution`（3 个枚举值）
    - 创建 `com.lifepilot.memory.semantic.ConflictDetail`（4 字段）
    - 创建 `com.lifepilot.memory.semantic.MergeResult`（3 字段）
    - _Requirements: 6.2, 6.3, 6.4_

  - [x] 4.2 实现 VersionMerger
    - 创建 `com.lifepilot.memory.semantic.VersionMerger`
    - 实现 `merge(existing, incoming, conversationId)` 方法
    - 新属性直接添加，冲突属性按 extractionConfidence 决定
    - description 取更长者，无变化时 isNewVersion=false
    - 新版本 version=existing.version()+1, importanceScore 取较高值
    - _Requirements: 6.1, 6.5, 6.6, 6.7, 6.8, 6.9_

  - [ ]* 4.3 编写 VersionMerger 属性测试
    - **Property 3: VersionMerger 合并正确性**
    - **Property 4: VersionMerger 无变化不创建新版本**
    - **Property 5: VersionMerger description 取更长者**
    - **Validates: Requirements 6.5, 6.6, 6.7, 6.8, 6.9**

- [ ] 5. VectorSearcher — 向量语义检索
  - [x] 5.1 实现 VectorSearchResult record
    - 创建 `com.lifepilot.memory.retrieval.VectorSearchResult`（2 字段）
    - _Requirements: 9.2_

  - [x] 5.2 实现 VectorSearcher
    - 创建 `com.lifepilot.memory.retrieval.VectorSearcher`
    - 依赖 vectorJdbcTemplate、mainJdbcTemplate、EmbeddingModel、vecExtensionLoaded 标志
    - 初始化时程序化创建 entity_embeddings vec0 虚拟表（sqlite-vec 可用时）
    - 实现 `searchEntities(queryText, topK, threshold)` — sqlite-vec KNN 搜索 + JVM 暴力降级
    - 实现 `upsertEntityVector(entityId, text)` — 文本向量化后存入 entity_embeddings
    - 实现 `deleteEntityVector(entityId)`
    - _Requirements: 9.1, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8_

  - [ ]* 5.3 编写 VectorSearcher 单元测试
    - 测试降级行为（Mock sqlite-vec 不可用）
    - 测试 upsert/delete 操作
    - 测试 searchEntities 正常路径和降级路径
    - _Requirements: 9.1, 9.4, 9.6, 9.8_

- [ ] 6. ConflictDetector — 三级冲突检测
  - [x] 6.1 实现 ConflictDetector
    - 创建 `com.lifepilot.memory.semantic.ConflictDetector`
    - 依赖 JdbcTemplate（精确匹配查询）、VectorSearcher（语义匹配）、ChatClient（LLM 消歧义，可选）
    - 实现三级匹配：精确匹配 → 语义匹配（阈值 semanticMatchThreshold）→ LLM 消歧义
    - LLM 调用失败时降级为仅精确匹配
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7_

  - [ ]* 6.2 编写 ConflictDetector 单元测试
    - Mock VectorSearcher 和 ChatClient
    - 测试精确匹配命中、语义匹配命中、LLM 消歧义、LLM 降级
    - _Requirements: 7.1, 7.2, 7.3, 7.5, 7.7_

- [ ] 7. SemanticMemory — 语义记忆服务
  - [x] 7.1 实现 SemanticMemory 服务
    - 创建 `com.lifepilot.memory.semantic.SemanticMemory`
    - 依赖 JdbcTemplate、ConflictDetector、VersionMerger、VectorSearcher
    - 实现 `upsertWithConflictDetection()` — 事务内冲突检测 + 版本化合并/新建 + 向量索引更新
    - 实现 `queryAtTime(point)` — 时间旅行查询
    - 实现 `getChangeHistory(name, type)` — 变更历史
    - 实现 `findRelated(entityId, maxDepth)` — 递归 CTE 图遍历
    - 实现 `findCurrentByNameAndType(name, type)` — 当前版本查找
    - 实现 `archive(entity)` — 事务内归档实体和关系
    - 实现 `incrementAccessCount(entityId)` — 访问计数
    - 实现 `addRelation(relation)` — 添加关系
    - 实现 `findAllCurrent()` — 所有当前实体
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9, 8.10, 8.11, 8.12_

  - [ ]* 7.2 编写 SemanticMemory 集成测试
    - 使用内存 SQLite + Flyway V7 迁移
    - **Property 6: upsert-findCurrent 往返属性**
    - **Property 7: 版本化更新旧版本关闭**
    - **Property 8: 时间旅行查询一致性**
    - **Property 9: 变更历史版本排序**
    - **Property 10: 图遍历深度约束**
    - **Property 11: archive 关闭实体和关系**
    - **Validates: Requirements 8.1, 8.2, 8.4, 8.5, 8.6, 8.7, 8.8**

- [x] 8. Checkpoint — 确保语义记忆核心服务测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. 检索数据模型 — RankedItem、RetrievalResult、RetrievalWeights
  - [x] 9.1 实现 RankedItem record
    - 创建 `com.lifepilot.memory.retrieval.RankedItem`（7 字段）
    - _Requirements: 10.2_

  - [x] 9.2 实现 RetrievalResult record（含 ScoreBreakdown 内部 record）
    - 创建 `com.lifepilot.memory.retrieval.RetrievalResult`（9 字段 + Comparable）
    - 内部 record `ScoreBreakdown`（8 字段）
    - _Requirements: 12.1, 12.2, 12.3_

  - [x] 9.3 实现 RetrievalWeights record
    - 创建 `com.lifepilot.memory.retrieval.RetrievalWeights`（6 字段）
    - DEFAULT 静态常量、compact constructor 权重之和校验
    - `adaptForLowVectorConfidence()` 方法
    - _Requirements: 12.4, 12.5, 12.6, 12.7_

  - [ ]* 9.4 编写 RetrievalWeights 属性测试
    - **Property 12: RetrievalWeights 权重之和约束**
    - **Property 13: adaptForLowVectorConfidence 保持权重之和**
    - **Validates: Requirements 12.6, 12.7**

- [ ] 10. FtsSearcher — FTS5 全文搜索
  - [x] 10.1 实现 FtsSearcher
    - 创建 `com.lifepilot.memory.retrieval.FtsSearcher`
    - 依赖 JdbcTemplate（主数据库）
    - 实现 FTS5 特殊字符转义预处理
    - 实现 `search(query, topK)` — FTS5 MATCH + bm25() + 关联 temporal_entities
    - 语法错误或查询失败时返回空列表
    - _Requirements: 10.1, 10.3, 10.4, 10.5_

  - [ ]* 10.2 编写 FtsSearcher 属性测试
    - **Property 15: FtsSearcher 查询安全性**
    - **Validates: Requirements 10.5**

- [ ] 11. GraphTraverser — 图遍历检索
  - [x] 11.1 实现 GraphTraverser
    - 创建 `com.lifepilot.memory.retrieval.GraphTraverser`
    - 依赖 JdbcTemplate（主数据库）
    - 实现 `traverse(query, topK)` — 名称精确匹配起始实体 + 递归 CTE 2 跳遍历
    - depth=1 得分高于 depth=2，只沿 valid_to IS NULL 的关系边遍历
    - 未识别到起始实体时返回空列表
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

  - [ ]* 11.2 编写 GraphTraverser 集成测试
    - **Property 16: GraphTraverser 只沿有效关系遍历**
    - 测试 2 跳遍历、深度得分、无起始实体
    - **Validates: Requirements 11.2, 11.3, 11.5**

- [ ] 12. HybridRetriever — 三路混合检索引擎
  - [x] 12.1 实现 HybridRetriever
    - 创建 `com.lifepilot.memory.retrieval.HybridRetriever`
    - 依赖 VectorSearcher、FtsSearcher、GraphTraverser、SemanticMemory
    - CompletableFuture + Virtual Thread 并行执行三路检索
    - 自适应权重调整（向量 Top-1 < 0.5 时）
    - 加权 RRF 融合：WeightedRRF(d) = Σ w(r) / (k + rank(r, d))
    - 时间衰减 + 重要度加成
    - 按 entity_id 去重，保留 fusedScore 最高
    - 批量更新 access_count
    - 任一路失败时使用其余路径继续融合
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7, 13.8, 13.9_

  - [ ]* 12.2 编写 HybridRetriever 属性测试
    - **Property 14: HybridRetriever 融合排序降序 + 去重**
    - Mock 三路检索器
    - **Validates: Requirements 13.6, 12.2**

- [ ] 13. MemoryAutoConfiguration 扩展 — 注册新 Bean 和配置项
  - [x] 13.1 扩展 MemoryProperties
    - 新增 `embeddingDimensions`（int，默认 1024）
    - 新增 `semanticMatchThreshold`（float，默认 0.92）
    - _Requirements: 14.3, 14.4_

  - [x] 13.2 扩展 MemoryAutoConfiguration
    - 新增 @Bean 方法注册 VersionMerger、ConflictDetector、VectorSearcher、SemanticMemory、FtsSearcher、GraphTraverser、HybridRetriever
    - 每个 Bean 添加 @ConditionalOnMissingBean
    - _Requirements: 14.1, 14.2_

  - [ ]* 13.3 编写 MemoryAutoConfiguration 扩展测试
    - 验证新 Bean 注册和条件装配
    - _Requirements: 14.1, 14.2_

- [x] 14. Final checkpoint — 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- ConflictDetector 使用 JdbcTemplate 直接查询精确匹配，避免与 SemanticMemory 的循环依赖
- entity_embeddings vec0 虚拟表由 VectorSearcher 初始化时程序化创建，不通过 Flyway 管理

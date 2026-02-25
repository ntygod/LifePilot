# 语义记忆与混合检索需求文档

参考文档：
- 架构设计：#[[file:docs/architecture/memory-system.md]]
- 特性设计：#[[file:docs/features/memory-system.md]]
- 数据模型：#[[file:docs/architecture/data-model.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 前置 spec：#[[file:.kiro/specs/memory-foundation/requirements.md]]

---

## 概述

本 spec 覆盖记忆系统的 L3 语义记忆层和混合检索引擎，包括：时序知识图谱数据模型（TemporalEntity、TemporalRelation、EntityType）、语义记忆服务（SemanticMemory + ConflictDetector + VersionMerger）、sqlite-vec 向量存储（VectorSearcher）、全文检索（FtsSearcher）、图遍历检索（GraphTraverser）、三路混合检索引擎（HybridRetriever + RetrievalResult + RetrievalWeights）、以及对应的 Flyway 数据库迁移脚本。

本 spec 不包含：L4 程序记忆、KnowledgeExtractionPipeline（知识提取管线）、ConsolidationPipeline（记忆巩固管线）、ForgettingEngine（遗忘引擎）、CompressionService（LLM 驱动压缩）、ContextAssembler 完整版。这些将在后续独立 spec 中实现。

前置依赖：memory-foundation spec 已完成（L1 工作记忆 + L2 情景记忆 + Flyway V5/V6 + MemoryProperties + MemoryAutoConfiguration）。

---

## 术语表

- **Semantic_Memory**：L3 语义记忆服务，管理时序知识图谱的实体和关系，提供版本感知的 CRUD、冲突检测、时间旅行查询和图遍历
- **TemporalEntity**：时序实体 record，知识图谱的节点，包含 id、type、name、description、properties、version、isCurrent、validFrom、validTo、sourceConversationId、extractionConfidence、importanceScore、accessCount、lastAccessedAt、createdAt、updatedAt 字段
- **TemporalRelation**：时序关系 record，知识图谱的边，包含 id、sourceEntityId、targetEntityId、relationType、strength、propertiesJson、validFrom、validTo、sourceConversationId、createdAt 字段
- **EntityType**：实体类型枚举，包含 PERSON、ORGANIZATION、PLACE、EVENT、PROJECT、TOPIC、PREFERENCE、HABIT、GOAL、SKILL、CUSTOM
- **RelationTypes**：关系类型常量类，定义已知关系类型字符串常量（KNOWS、WORKS_AT、RESPONSIBLE_FOR、RELATED_TO 等）
- **ConflictDetector**：冲突检测器，通过三级匹配策略（精确匹配 → 语义匹配 → LLM 消歧义）判断新实体是否与已有实体冲突
- **VersionMerger**：版本合并器，将新实体信息与已有实体合并生成新版本，支持属性级冲突解决
- **MergeResult**：合并结果 record，包含 mergedEntity、conflicts、isNewVersion 字段
- **ConflictDetail**：冲突详情 record，包含 fieldName、oldValue、newValue、resolution 字段
- **ConflictResolution**：冲突解决方式枚举，包含 KEEP_NEW、KEEP_OLD、KEEP_BOTH
- **VectorSearcher**：向量语义检索器，基于 sqlite-vec 的实体向量检索，支持降级为 JVM 暴力搜索
- **VectorSearchResult**：向量检索结果 record，包含 entityId 和 similarity 字段
- **FtsSearcher**：全文搜索器，基于 SQLite FTS5 + BM25 的消息和实体关键词检索
- **GraphTraverser**：图遍历检索器，基于 SQLite 递归 CTE 的 N 跳关系遍历
- **HybridRetriever**：三路混合检索引擎，并行执行向量检索、全文搜索、图遍历，通过加权 RRF 融合排序
- **RetrievalResult**：检索结果 record，包含 entityId、entityType、name、description、fusedScore、scoreBreakdown、sourcePath、lastAccessedAt、importanceScore 字段
- **RetrievalWeights**：检索权重配置 record，包含 vectorWeight、ftsWeight、graphWeight、recencyDecay、importanceBoost、rrfK 字段
- **RRF**：Reciprocal Rank Fusion，排名融合算法，公式为 Score(d) = Σ w(r) / (k + rank(r, d))
- **RankedItem**：单路检索的排名条目 record，包含 entityId、entityType、name、description、score、lastAccessedAt、importanceScore 字段

---

## 需求

### 需求 1：数据库 Schema — temporal_entities 表和 temporal_relations 表

**用户故事：** 作为核心开发者，我需要 Flyway 迁移脚本创建 temporal_entities 和 temporal_relations 表及其索引，使 L3 语义记忆有持久化存储

#### 验收标准

1. THE Flyway_Migration SHALL 创建 temporal_entities 表，包含 id(TEXT PK)、type(TEXT NOT NULL)、name(TEXT NOT NULL)、description(TEXT)、properties_json(TEXT)、version(INTEGER NOT NULL DEFAULT 1)、is_current(INTEGER NOT NULL DEFAULT 1)、valid_from(TEXT NOT NULL)、valid_to(TEXT)、source_conversation_id(TEXT FK → conversations(id))、extraction_confidence(REAL DEFAULT 0.0)、importance_score(REAL DEFAULT 0.5)、access_count(INTEGER DEFAULT 0)、last_accessed_at(TEXT)、created_at(TEXT NOT NULL)、updated_at(TEXT NOT NULL) 列
2. THE Flyway_Migration SHALL 在 temporal_entities 表上创建以下索引：idx_entities_name_type(name, type)、idx_entities_current(is_current) WHERE is_current = 1 部分索引、idx_entities_type_valid(type, valid_from, valid_to)、idx_entities_importance(importance_score, access_count) WHERE is_current = 1 部分索引、idx_entities_source(source_conversation_id) WHERE source_conversation_id IS NOT NULL 部分索引
3. THE Flyway_Migration SHALL 创建 temporal_relations 表，包含 id(TEXT PK)、source_entity_id(TEXT NOT NULL FK → temporal_entities(id))、target_entity_id(TEXT NOT NULL FK → temporal_entities(id))、relation_type(TEXT NOT NULL)、strength(REAL DEFAULT 0.5)、properties_json(TEXT)、valid_from(TEXT NOT NULL)、valid_to(TEXT)、source_conversation_id(TEXT FK → conversations(id))、created_at(TEXT NOT NULL) 列
4. THE Flyway_Migration SHALL 在 temporal_relations 表上创建以下索引：idx_relations_source(source_entity_id, relation_type)、idx_relations_target(target_entity_id, relation_type)、idx_relations_valid(valid_from, valid_to) WHERE valid_to IS NULL 部分索引
5. THE Flyway_Migration SHALL 使用版本号 V7，脚本命名为 V7__create_semantic_memory_tables.sql

### 需求 2：EntityType 实体类型枚举

**用户故事：** 作为核心开发者，我需要 EntityType 枚举定义知识图谱支持的实体类型，使实体分类有明确的类型标识

#### 验收标准

1. THE EntityType SHALL 定义为枚举，包含 PERSON、ORGANIZATION、PLACE、EVENT、PROJECT、TOPIC、PREFERENCE、HABIT、GOAL、SKILL、CUSTOM 共 11 个值
2. THE EntityType SHALL 以 TEXT 形式存储在数据库中，使用枚举值的 name() 方法
3. WHEN EntityType.valueOf() 接收到无效字符串时，THE EntityType SHALL 抛出 IllegalArgumentException

### 需求 3：RelationTypes 关系类型常量

**用户故事：** 作为核心开发者，我需要 RelationTypes 常量类定义已知的关系类型字符串，使关系类型有统一的命名规范

#### 验收标准

1. THE RelationTypes SHALL 定义为 final 类，私有构造函数禁止实例化
2. THE RelationTypes SHALL 定义以下社交关系常量：KNOWS、IS_FRIEND_OF、IS_FAMILY_OF、IS_CLIENT_OF
3. THE RelationTypes SHALL 定义以下组织关系常量：WORKS_AT、BELONGS_TO、MANAGES、REPORTS_TO
4. THE RelationTypes SHALL 定义以下项目关系常量：RESPONSIBLE_FOR、PARTICIPATES_IN、HAS_MILESTONE、DEPENDS_ON
5. THE RelationTypes SHALL 定义以下因果关系常量：CAUSED_BY、LEADS_TO、RELATED_TO
6. THE RelationTypes SHALL 定义以下偏好关系常量：PREFERS、DISLIKES、HAS_GOAL、HAS_HABIT
7. THE RelationTypes 中所有常量 SHALL 为 public static final String 类型，值为 snake_case 格式字符串

### 需求 4：TemporalEntity 时序实体

**用户故事：** 作为核心开发者，我需要 TemporalEntity record 作为知识图谱的节点数据结构，使实体能够携带版本、时间维度和来源追踪信息

#### 验收标准

1. THE TemporalEntity SHALL 定义为 record，包含 id(String)、type(EntityType)、name(String)、description(@Nullable String)、properties(Map<String, Object>)、version(int)、isCurrent(boolean)、validFrom(Instant)、validTo(@Nullable Instant)、sourceConversationId(@Nullable String)、extractionConfidence(float)、importanceScore(float)、accessCount(int)、lastAccessedAt(@Nullable Instant)、createdAt(Instant)、updatedAt(Instant) 字段
2. THE TemporalEntity SHALL 在 compact constructor 中使用 Map.copyOf() 确保 properties 不可变
3. THE TemporalEntity SHALL 提供 textRepresentation() 方法，拼接 name + description + properties 的关键值为文本，用于向量化
4. THE TemporalEntity SHALL 提供 isActive() 方法：WHEN isCurrent 为 true 且 validTo 为 null 时返回 true

### 需求 5：TemporalRelation 时序关系

**用户故事：** 作为核心开发者，我需要 TemporalRelation record 作为知识图谱的边数据结构，使实体间关系能够携带强度和时间维度

#### 验收标准

1. THE TemporalRelation SHALL 定义为 record，包含 id(String)、sourceEntityId(String)、targetEntityId(String)、relationType(String)、strength(float)、propertiesJson(@Nullable String)、validFrom(Instant)、validTo(@Nullable Instant)、sourceConversationId(@Nullable String)、createdAt(Instant) 字段
2. THE TemporalRelation SHALL 提供 isActive() 方法：WHEN validTo 为 null 时返回 true
3. WHEN TemporalRelation 被构造时，THE TemporalRelation SHALL 验证 strength 在 [0.0, 1.0] 范围内，超出范围时抛出 IllegalArgumentException

### 需求 6：VersionMerger 版本合并

**用户故事：** 作为核心开发者，我需要 VersionMerger 将新实体信息与已有实体合并生成新版本，使知识图谱能够以属性级粒度解决冲突

#### 验收标准

1. THE VersionMerger SHALL 提供 merge(existing, incoming, conversationId) 方法返回 MergeResult
2. THE MergeResult SHALL 定义为 record，包含 mergedEntity(TemporalEntity)、conflicts(Map<String, ConflictDetail>)、isNewVersion(boolean) 字段
3. THE ConflictDetail SHALL 定义为 record，包含 fieldName(String)、oldValue(Object)、newValue(Object)、resolution(ConflictResolution) 字段
4. THE ConflictResolution SHALL 定义为枚举，包含 KEEP_NEW、KEEP_OLD、KEEP_BOTH 三个值
5. WHEN incoming 的属性中存在 existing 没有的新属性时，THE VersionMerger SHALL 将新属性直接添加到合并结果
6. WHEN incoming 和 existing 的同名属性值不同时，THE VersionMerger SHALL 根据 extractionConfidence 决定保留哪个值：incoming 置信度更高时 KEEP_NEW，existing 置信度更高时 KEEP_OLD，置信度相同时 KEEP_BOTH
7. WHEN incoming 的 description 比 existing 的 description 更长时，THE VersionMerger SHALL 使用 incoming 的 description
8. WHEN 合并后无任何属性变化且 description 未变时，THE VersionMerger SHALL 返回 isNewVersion 为 false 的 MergeResult
9. WHEN 创建新版本时，THE VersionMerger SHALL 设置 version 为 existing.version() + 1、isCurrent 为 true、validFrom 为当前时间、validTo 为 null、importanceScore 取两者较高值、继承 existing 的 accessCount 和 lastAccessedAt

### 需求 7：ConflictDetector 冲突检测

**用户故事：** 作为核心开发者，我需要 ConflictDetector 通过三级匹配策略判断新实体是否与已有实体冲突，使知识图谱能够准确识别重复实体

#### 验收标准

1. THE ConflictDetector SHALL 提供 detectConflict(newEntity) 方法返回 Optional<TemporalEntity>
2. WHEN newEntity 的 name 和 type 与已有当前版本实体精确匹配时，THE ConflictDetector SHALL 直接返回该已有实体（精确匹配优先）
3. WHEN 精确匹配未命中时，THE ConflictDetector SHALL 使用 VectorSearcher 进行语义匹配，阈值为 0.92
4. WHEN 语义匹配返回候选实体时，THE ConflictDetector SHALL 对 Top-1 候选调用 LLM 进行消歧义判断
5. WHEN LLM 判断为同一实体时，THE ConflictDetector SHALL 返回该候选实体
6. WHEN 精确匹配和语义匹配均未命中时，THE ConflictDetector SHALL 返回 Optional.empty()
7. IF LLM 调用失败，THEN THE ConflictDetector SHALL 仅依赖精确匹配结果，记录警告日志并降级跳过语义匹配

### 需求 8：SemanticMemory 语义记忆服务

**用户故事：** 作为核心开发者，我需要 SemanticMemory 服务管理时序知识图谱的完整生命周期，使 Agent 能够存储、查询和维护结构化知识

#### 验收标准

1. WHEN upsertWithConflictDetection(incoming, conversationId) 被调用时，THE Semantic_Memory SHALL 在事务内执行冲突检测、版本化合并（或新建）、插入实体、更新向量索引
2. WHEN 冲突检测找到已有实体时，THE Semantic_Memory SHALL 关闭旧版本（设置 is_current=0、valid_to=当前时间）并插入合并后的新版本
3. WHEN 冲突检测未找到已有实体时，THE Semantic_Memory SHALL 创建 version=1、isCurrent=true 的新实体
4. WHEN queryAtTime(point) 被调用时，THE Semantic_Memory SHALL 返回在指定时间点有效的所有实体（valid_from <= point 且 valid_to 为 null 或 valid_to > point）
5. WHEN getChangeHistory(name, type) 被调用时，THE Semantic_Memory SHALL 返回指定名称和类型的所有版本，按 version 升序排列
6. WHEN findRelated(entityId, maxDepth) 被调用时，THE Semantic_Memory SHALL 使用递归 CTE 沿当前有效关系边遍历最多 maxDepth 跳，返回关联实体列表
7. WHEN findCurrentByNameAndType(name, type) 被调用时，THE Semantic_Memory SHALL 返回 is_current=1 的匹配实体，不存在时返回 Optional.empty()
8. WHEN archive(entity) 被调用时，THE Semantic_Memory SHALL 在事务内将实体标记为 is_current=0、设置 valid_to，并同时归档该实体的所有当前有效关系
9. WHEN incrementAccessCount(entityId) 被调用时，THE Semantic_Memory SHALL 将 access_count 加 1 并更新 last_accessed_at 为当前时间
10. THE Semantic_Memory SHALL 提供 addRelation(relation) 方法将 TemporalRelation 插入 temporal_relations 表
11. THE Semantic_Memory SHALL 提供 findAllCurrent() 方法返回所有 is_current=1 的实体，按 importance_score 升序、access_count 升序排列
12. THE Semantic_Memory SHALL 使用 JdbcTemplate 执行数据库操作

### 需求 9：VectorSearcher 向量语义检索

**用户故事：** 作为核心开发者，我需要 VectorSearcher 基于 sqlite-vec 提供实体向量检索能力，使混合检索引擎能够执行语义相似度搜索

#### 验收标准

1. THE VectorSearcher SHALL 提供 searchEntities(queryText, topK, threshold) 方法返回 List<VectorSearchResult>
2. THE VectorSearchResult SHALL 定义为 record，包含 entityId(String) 和 similarity(float) 字段
3. WHEN sqlite-vec 扩展已加载时，THE VectorSearcher SHALL 使用 vec_distance_cosine 执行 KNN 搜索，将余弦距离转换为相似度（similarity = 1 - distance / 2），按阈值过滤后返回 Top-K 结果
4. IF sqlite-vec 扩展未加载，THEN THE VectorSearcher SHALL 降级为 JVM 暴力搜索（遍历所有当前实体向量计算余弦相似度），记录 DEBUG 日志
5. THE VectorSearcher SHALL 提供 upsertEntityVector(entityId, text) 方法，将文本通过 Embedding 模型向量化后存入 entity_embeddings 表
6. IF sqlite-vec 扩展未加载，THEN THE VectorSearcher 的 upsertEntityVector() SHALL 跳过向量索引更新，记录 DEBUG 日志
7. THE VectorSearcher SHALL 提供 deleteEntityVector(entityId) 方法从 entity_embeddings 表删除指定实体的向量
8. IF 向量化或检索过程中发生异常，THEN THE VectorSearcher SHALL 降级为 JVM 暴力搜索，记录 WARN 日志

### 需求 10：FtsSearcher 全文搜索

**用户故事：** 作为核心开发者，我需要 FtsSearcher 基于 FTS5 + BM25 提供全文关键词检索能力，使混合检索引擎能够执行精确关键词匹配

#### 验收标准

1. THE FtsSearcher SHALL 提供 search(query, topK) 方法返回 List<RankedItem>
2. THE RankedItem SHALL 定义为 record，包含 entityId(String)、entityType(String)、name(String)、description(@Nullable String)、score(float)、lastAccessedAt(@Nullable Instant)、importanceScore(float) 字段
3. WHEN search() 被调用时，THE FtsSearcher SHALL 使用 FTS5 MATCH 语法在 messages_fts 中检索匹配消息，使用 bm25() 函数计算相关性得分
4. IF FTS5 搜索语法错误或查询失败，THEN THE FtsSearcher SHALL 捕获异常，记录 WARN 日志，返回空列表
5. THE FtsSearcher SHALL 对查询文本进行预处理，转义 FTS5 特殊字符，防止语法错误

### 需求 11：GraphTraverser 图遍历检索

**用户故事：** 作为核心开发者，我需要 GraphTraverser 基于递归 CTE 提供图遍历检索能力，使混合检索引擎能够发现间接关联的实体

#### 验收标准

1. THE GraphTraverser SHALL 提供 traverse(query, topK) 方法返回 List<RankedItem>
2. WHEN traverse() 被调用时，THE GraphTraverser SHALL 先从查询文本中识别起始实体（通过名称精确匹配 temporal_entities 表），再使用递归 CTE 沿当前有效关系边遍历最多 2 跳
3. THE GraphTraverser SHALL 按遍历深度计算得分：depth=1 的实体得分高于 depth=2 的实体
4. WHEN 查询文本中未识别到任何已知实体时，THE GraphTraverser SHALL 返回空列表
5. THE GraphTraverser SHALL 只沿 valid_to IS NULL 的关系边遍历，忽略已失效的关系

### 需求 12：RetrievalResult 和 RetrievalWeights 检索数据模型

**用户故事：** 作为核心开发者，我需要 RetrievalResult 和 RetrievalWeights record 定义混合检索的结果和权重配置，使检索结果可追溯、权重可调优

#### 验收标准

1. THE RetrievalResult SHALL 定义为 record，包含 entityId(String)、entityType(String)、name(String)、description(@Nullable String)、fusedScore(float)、scoreBreakdown(ScoreBreakdown)、sourcePath(String)、lastAccessedAt(@Nullable Instant)、importanceScore(float) 字段
2. THE RetrievalResult SHALL 实现 Comparable<RetrievalResult>，按 fusedScore 降序排列
3. THE ScoreBreakdown SHALL 定义为 RetrievalResult 的内部 record，包含 vectorScore(float)、vectorWeighted(float)、ftsScore(float)、ftsWeighted(float)、graphScore(float)、graphWeighted(float)、recencyBoost(float)、importanceBoost(float) 字段
4. THE RetrievalWeights SHALL 定义为 record，包含 vectorWeight(float)、ftsWeight(float)、graphWeight(float)、recencyDecay(float)、importanceBoost(float)、rrfK(int) 字段
5. THE RetrievalWeights SHALL 提供 DEFAULT 静态常量，值为 vectorWeight=0.45、ftsWeight=0.30、graphWeight=0.25、recencyDecay=0.05、importanceBoost=0.1、rrfK=60
6. WHEN RetrievalWeights 被构造时，THE RetrievalWeights SHALL 验证 vectorWeight + ftsWeight + graphWeight 之和约等于 1.0（误差 ≤ 0.01），否则抛出 IllegalArgumentException
7. THE RetrievalWeights SHALL 提供 adaptForLowVectorConfidence(topVectorScore) 方法：WHEN topVectorScore < 0.5 时降低 vectorWeight 30%，将差额按 70%/30% 分配给 ftsWeight 和 graphWeight

### 需求 13：HybridRetriever 三路混合检索引擎

**用户故事：** 作为核心开发者，我需要 HybridRetriever 并行执行三路检索并通过加权 RRF 融合排序，使 Agent 能够综合语义、关键词和关系三个维度检索记忆

#### 验收标准

1. THE HybridRetriever SHALL 提供 retrieve(query, topK, weights) 方法返回 List<RetrievalResult>
2. WHEN retrieve() 被调用时，THE HybridRetriever SHALL 使用 CompletableFuture + Virtual Thread 并行执行 VectorSearcher、FtsSearcher、GraphTraverser 三路检索
3. THE HybridRetriever SHALL 对三路检索结果应用加权 RRF 融合：WeightedRRF(d) = Σ w(r) / (k + rank(r, d))，其中 k 为 rrfK 参数
4. THE HybridRetriever SHALL 在 RRF 融合后应用时间衰减：RecencyFactor = exp(-recencyDecay × daysSinceLastAccess)
5. THE HybridRetriever SHALL 在时间衰减后应用重要度加成：ImportanceBoost = importanceBoost × importanceScore
6. THE HybridRetriever SHALL 对融合结果按 entity_id 去重，保留 fusedScore 最高的条目
7. WHEN 向量检索 Top-1 分数低于 0.5 时，THE HybridRetriever SHALL 调用 RetrievalWeights.adaptForLowVectorConfidence() 自适应调整权重
8. THE HybridRetriever SHALL 在检索完成后批量更新结果实体的 access_count
9. IF 任一路检索失败，THEN THE HybridRetriever SHALL 使用其余路径的结果继续融合，记录 WARN 日志

### 需求 14：MemoryAutoConfiguration 扩展

**用户故事：** 作为核心开发者，我需要扩展 MemoryAutoConfiguration 注册语义记忆和混合检索相关的 Bean，使新组件纳入 Spring 自动配置管理

#### 验收标准

1. THE MemoryAutoConfiguration SHALL 新增 @Bean 方法注册 SemanticMemory、ConflictDetector、VersionMerger、VectorSearcher、FtsSearcher、GraphTraverser、HybridRetriever
2. THE MemoryAutoConfiguration SHALL 为每个新 Bean 添加 @ConditionalOnMissingBean 注解，允许用户自定义覆盖
3. THE MemoryAutoConfiguration SHALL 在 MemoryProperties 中新增 embeddingDimensions(int, 默认 1024) 配置项，用于 sqlite-vec 向量维度配置
4. THE MemoryAutoConfiguration SHALL 在 MemoryProperties 中新增 semanticMatchThreshold(float, 默认 0.92) 配置项，用于冲突检测语义匹配阈值


---

## 正确性属性

### CP-1：TemporalEntity properties 不可变性
FOR ALL TemporalEntity 实例，properties() 返回的 Map SHALL 为不可变 Map，对其执行 put/remove 操作应抛出 UnsupportedOperationException。

### CP-2：TemporalRelation strength 范围不变量
FOR ALL TemporalRelation 实例，strength() SHALL 在 [0.0, 1.0] 范围内。

### CP-3：版本化更新单调递增
FOR ALL 同名同类型的 TemporalEntity 序列，version 值 SHALL 严格单调递增（每次 upsert 后 version = 旧版本 + 1）。

### CP-4：版本化更新旧版本关闭
FOR ALL upsertWithConflictDetection() 操作，WHEN 找到已有实体时，旧版本的 is_current SHALL 被设置为 0，valid_to SHALL 被设置为非 null 值。

### CP-5：SemanticMemory upsert-findCurrent 往返属性
FOR ALL 有效的 TemporalEntity，upsertWithConflictDetection(entity, conversationId) 后 findCurrentByNameAndType(entity.name(), entity.type()) SHALL 返回包含等价数据的实体，且 isCurrent 为 true。

### CP-6：时间旅行查询一致性
FOR ALL 时间点 t 和实体 e，WHEN e.validFrom <= t 且 (e.validTo 为 null 或 e.validTo > t) 时，queryAtTime(t) 的结果 SHALL 包含 e。

### CP-7：图遍历深度约束
FOR ALL findRelated(entityId, maxDepth) 调用，返回的实体 SHALL 在起始实体的 maxDepth 跳关系范围内，不包含起始实体本身。

### CP-8：archive 关闭实体和关系
FOR ALL archive(entity) 操作，操作完成后 entity 的 is_current SHALL 为 0，且该实体的所有当前有效关系的 valid_to SHALL 被设置为非 null 值。

### CP-9：RetrievalWeights 权重之和约束
FOR ALL RetrievalWeights 实例，vectorWeight + ftsWeight + graphWeight 之和 SHALL 约等于 1.0（误差 ≤ 0.01）。

### CP-10：RetrievalResult 融合排序降序
FOR ALL HybridRetriever.retrieve() 返回的结果列表，相邻元素的 fusedScore SHALL 满足非递增顺序（前一个 ≥ 后一个）。

### CP-11：HybridRetriever 去重属性
FOR ALL HybridRetriever.retrieve() 返回的结果列表，不存在两个 RetrievalResult 具有相同的 entityId。

### CP-12：VersionMerger 无变化不创建新版本
FOR ALL merge(existing, incoming, conversationId) 调用，WHEN incoming 的属性和 description 与 existing 完全相同时，MergeResult.isNewVersion() SHALL 为 false。

### CP-13：VersionMerger 冲突解决置信度规则
FOR ALL 属性冲突，WHEN incoming.extractionConfidence > existing.extractionConfidence 时 resolution SHALL 为 KEEP_NEW，WHEN incoming.extractionConfidence < existing.extractionConfidence 时 resolution SHALL 为 KEEP_OLD。

### CP-14：VectorSearcher 降级等价性
FOR ALL 查询，sqlite-vec 检索和 JVM 暴力搜索 SHALL 返回相同的实体集合（允许排序因浮点精度略有差异）。

### CP-15：FtsSearcher 查询安全性
FOR ALL 包含 FTS5 特殊字符的查询文本，FtsSearcher SHALL 正确转义后执行搜索，不抛出语法异常。

---

## 非功能需求

- HybridRetriever 端到端检索延迟目标 < 50ms（三路并行，取最慢路径延迟）
- SemanticMemory 的 upsertWithConflictDetection() 在事务内执行，保证原子性
- VectorSearcher 在 sqlite-vec 不可用时自动降级为 JVM 暴力搜索，核心功能不受影响
- 所有日志、注释、异常消息、Javadoc 使用中文
- 类名、方法名、变量名使用英文
- 测试方法名使用中文
- 包结构：com.lifepilot.memory.semantic、com.lifepilot.memory.retrieval
- Flyway 迁移脚本遵循 V{版本号}__{描述}.sql 命名规范，从 V7 开始
- 向量数据库（vectors.db）的 entity_embeddings 表 Schema 由应用启动时程序化创建（非 Flyway 管理），因为 sqlite-vec 扩展需要在连接上加载后才能创建 vec0 虚拟表
- 所有新组件通过 @Bean 在 MemoryAutoConfiguration 中注册，不在服务类上标注 @Service/@Component

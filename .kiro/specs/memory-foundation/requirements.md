# 记忆系统基础层需求文档

参考文档：
- 架构设计：#[[file:docs/architecture/memory-system.md]]
- 特性设计：#[[file:docs/features/memory-system.md]]
- 数据模型：#[[file:docs/architecture/data-model.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

---

## 概述

LifePilot 记忆系统是一个受认知科学启发的四层认知记忆架构。本 spec 覆盖记忆系统的基础层，包括：记忆配置（MemoryProperties + MemoryAutoConfiguration）、数据库 Schema（Flyway 迁移创建 conversations/messages 表 + FTS5 索引）、L1 工作记忆（WorkingMemorySlot 类型体系 + WorkingMemory 服务 + TokenBudgetAllocator）、L2 情景记忆（CompressionLevel + MessageRecord + ConversationRecord + EpisodicMemory 服务）。

本 spec 不包含：L3 语义记忆、L4 程序记忆、HybridRetriever、KnowledgeExtractionPipeline、ConsolidationPipeline、ForgettingEngine、CompressionService（LLM 驱动压缩）、sqlite-vec 向量存储。这些将在后续独立 spec 中实现。

---

## 术语表

- **Memory_System**：LifePilot 的四层认知记忆系统，包含 L1 工作记忆、L2 情景记忆、L3 语义记忆、L4 程序记忆
- **MemoryProperties**：记忆系统的 Spring Boot 配置属性类，绑定 lifepilot.memory 前缀，使用 JavaBean 风格（class + getters/setters）
- **MemoryAutoConfiguration**：记忆系统的 Spring Boot 自动配置类，通过 @ConditionalOnProperty 控制启用
- **WorkingMemorySlot**：L1 工作记忆中的最小存储单元，sealed interface，permits ConversationSlot、ToolResultSlot、ReasoningSlot
- **ConversationSlot**：对话槽位 record，存储用户消息或 Agent 回复，包含 role、content、tokenCount、importance、isPinned、toolCallJson、createdAt 字段
- **ToolResultSlot**：工具结果槽位 record，缓存工具执行结果，包含 toolId、toolAction、result、tokenCount、importance、createdAt 字段
- **ReasoningSlot**：推理槽位 record，存储中间推理状态（检索上下文、思考链），包含 thought、source、tokenCount、importance、createdAt 字段
- **WorkingMemory**：L1 工作记忆服务，使用 ConcurrentHashMap<String, List<WorkingMemorySlot>> 管理会话级内存状态
- **TokenBudgetAllocator**：Token 预算分配器，将 LLM 上下文窗口动态划分为系统提示词区（10%）、工作记忆区（40-60%）、检索上下文区（20-35%）、用户消息区（15%）
- **BudgetAllocation**：Token 预算分配结果 record，包含 systemPromptBudget、workingMemoryBudget、retrievalBudget、userMessageBudget、totalBudget 字段
- **CompressionLevel**：压缩层级枚举，包含 ORIGINAL(0)、SUMMARY(1)、KEYPOINTS(2)、ARCHIVED(3)
- **MessageRecord**：消息记录 record，L2 情景记忆的消息级存储单元，包含 effectiveContent() 和 effectiveTokenCount() 方法
- **ConversationRecord**：对话记录 record，L2 情景记忆的对话级存储单元，包含不可变消息列表
- **EpisodicMemory**：L2 情景记忆服务，提供 save、getRecent、search（FTS5 BM25）、getByIntent、compress 方法
- **FTS5**：SQLite 全文搜索扩展，用于对话消息的关键词检索
- **messages_fts**：FTS5 虚拟表，对 messages 表的 content 列建立全文索引，通过触发器自动同步

---

## 需求

### 需求 1：记忆系统配置

**用户故事：** 作为核心开发者，我需要记忆系统的 Spring Boot 配置绑定，使记忆系统的关键参数可通过 YAML 配置文件调整

#### 验收标准

1. THE MemoryProperties SHALL 使用 @ConfigurationProperties 绑定 lifepilot.memory 配置前缀，采用 JavaBean 风格（class + getters/setters）
2. THE MemoryProperties SHALL 包含以下配置项及默认值：enabled(true)、workingMemoryTokenBudget(8000)、idleSessionTimeoutMinutes(30)、compressionThresholdTokens(4000)、consolidationLookbackDays(7)、forgettingThreshold(0.7)、maxRetentionDays(180)
3. WHEN lifepilot.memory.enabled 为 false 时，THE MemoryAutoConfiguration SHALL 不创建任何记忆系统 Bean
4. WHEN lifepilot.memory.enabled 为 true 时，THE MemoryAutoConfiguration SHALL 创建 WorkingMemory、TokenBudgetAllocator、EpisodicMemory Bean
5. THE MemoryAutoConfiguration SHALL 使用 @ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true) 控制启用

### 需求 2：数据库 Schema — conversations 表和 messages 表

**用户故事：** 作为核心开发者，我需要 Flyway 迁移脚本创建 conversations 和 messages 表，使 L2 情景记忆有持久化存储

#### 验收标准

1. THE Flyway_Migration SHALL 创建 conversations 表，包含 id(TEXT PK)、session_id(TEXT NOT NULL)、goal(TEXT NOT NULL)、summary(TEXT)、created_at(TEXT NOT NULL)、updated_at(TEXT NOT NULL) 列
2. THE Flyway_Migration SHALL 在 conversations 表上创建 idx_conversations_session 索引（session_id 列）
3. THE Flyway_Migration SHALL 创建 messages 表，包含 id(TEXT PK)、conversation_id(TEXT NOT NULL FK → conversations(id))、role(TEXT NOT NULL)、content(TEXT NOT NULL)、compressed_content(TEXT)、compression_level(INTEGER NOT NULL DEFAULT 0)、is_pinned(INTEGER NOT NULL DEFAULT 0)、tool_call_json(TEXT)、token_count(INTEGER DEFAULT 0)、created_at(TEXT NOT NULL) 列
4. THE Flyway_Migration SHALL 在 messages 表上创建 idx_messages_conversation 索引（conversation_id, created_at 列）和 idx_messages_pinned 部分索引（conversation_id WHERE is_pinned = 1）
5. THE Flyway_Migration SHALL 使用版本号 V5，脚本命名为 V5__create_memory_tables.sql

### 需求 3：数据库 Schema — FTS5 全文索引

**用户故事：** 作为核心开发者，我需要 FTS5 虚拟表和同步触发器，使 L2 情景记忆支持全文关键词检索

#### 验收标准

1. THE Flyway_Migration SHALL 创建 messages_fts 虚拟表，使用 FTS5 引擎，索引 content 列，content 表为 messages，tokenizer 为 unicode61 remove_diacritics 2
2. THE Flyway_Migration SHALL 创建 messages_fts_ai 触发器（AFTER INSERT ON messages），将新消息的 content 插入 messages_fts
3. THE Flyway_Migration SHALL 创建 messages_fts_ad 触发器（AFTER DELETE ON messages），从 messages_fts 删除对应记录
4. THE Flyway_Migration SHALL 创建 messages_fts_au 触发器（AFTER UPDATE ON messages），先删除旧记录再插入新记录到 messages_fts
5. THE Flyway_Migration SHALL 使用版本号 V6，脚本命名为 V6__create_messages_fts.sql

### 需求 4：WorkingMemorySlot 类型体系

**用户故事：** 作为核心开发者，我需要 WorkingMemorySlot sealed interface 及其三种实现，使 L1 工作记忆能够以类型安全的方式存储不同类型的上下文信息

#### 验收标准

1. THE WorkingMemorySlot SHALL 定义为 sealed interface，permits ConversationSlot、ToolResultSlot、ReasoningSlot
2. THE WorkingMemorySlot SHALL 声明 tokenCount()、createdAt()、importance() 三个方法，返回类型分别为 int、Instant、float
3. THE ConversationSlot SHALL 定义为 record，包含 role(String)、content(String)、tokenCount(int)、importance(float)、isPinned(boolean)、toolCallJson(@Nullable String)、createdAt(Instant) 字段
4. THE ConversationSlot SHALL 提供 userMessage(content, tokenCount)、assistantMessage(content, tokenCount)、systemMessage(content, tokenCount) 静态工厂方法
5. WHEN ConversationSlot.userMessage() 被调用时，THE ConversationSlot SHALL 设置 role 为 "user"、importance 为 0.8f、isPinned 为 false
6. WHEN ConversationSlot.systemMessage() 被调用时，THE ConversationSlot SHALL 设置 role 为 "system"、importance 为 0.9f、isPinned 为 true
7. THE ToolResultSlot SHALL 定义为 record，包含 toolId(String)、toolAction(String)、result(String)、tokenCount(int)、importance(float)、createdAt(Instant) 字段
8. THE ReasoningSlot SHALL 定义为 record，包含 thought(String)、source(String)、tokenCount(int)、importance(float)、createdAt(Instant) 字段
9. THE ReasoningSlot SHALL 提供 retrievalContext(context, tokenCount) 和 chainOfThought(thought, tokenCount) 静态工厂方法
10. WHEN ReasoningSlot.retrievalContext() 被调用时，THE ReasoningSlot SHALL 设置 source 为 "hybrid-retrieval"、importance 为 0.3f

### 需求 5：TokenBudgetAllocator 预算分配

**用户故事：** 作为核心开发者，我需要 Token 预算分配器，使 LLM 上下文窗口能够在系统提示词、工作记忆、检索上下文、用户消息四个区域之间动态分配

#### 验收标准

1. THE BudgetAllocation SHALL 定义为 record，包含 systemPromptBudget(int)、workingMemoryBudget(int)、retrievalBudget(int)、userMessageBudget(int)、totalBudget(int) 字段
2. WHEN BudgetAllocation 被构造时，THE BudgetAllocation SHALL 验证四个区域预算之和不超过 totalBudget，超过时抛出 IllegalArgumentException
3. THE TokenBudgetAllocator SHALL 提供 allocate(contextWindowSize, conversationTurns, topRetrievalScore) 方法返回 BudgetAllocation
4. THE TokenBudgetAllocator SHALL 将系统提示词区固定分配为总预算的 10%
5. THE TokenBudgetAllocator SHALL 将用户消息区固定分配为总预算的 15%
6. WHEN topRetrievalScore 大于 0.9 时，THE TokenBudgetAllocator SHALL 将检索上下文区扩展到 35%、工作记忆区压缩到 40%（归一化后占剩余 75% 的比例）
7. WHEN conversationTurns 大于 10 时，THE TokenBudgetAllocator SHALL 将工作记忆区扩展到 60%、检索上下文区压缩到 20%（归一化后占剩余 75% 的比例）
8. WHEN 无特殊条件时，THE TokenBudgetAllocator SHALL 使用默认比例：工作记忆区 50%、检索上下文区 25%（归一化后占剩余 75% 的比例）

### 需求 6：WorkingMemory 服务

**用户故事：** 作为核心开发者，我需要 WorkingMemory 服务管理所有活跃会话的内存状态，使 Agent 在对话过程中能够维持上下文

#### 验收标准

1. THE WorkingMemory SHALL 使用 ConcurrentHashMap<String, List<WorkingMemorySlot>> 管理会话级槽位列表
2. WHEN append(sessionId, slot) 被调用时，THE WorkingMemory SHALL 将槽位追加到对应会话的列表末尾
3. WHEN append() 导致会话总 Token 数超过 workingMemoryTokenBudget 时，THE WorkingMemory SHALL 执行重要度加权淘汰，按优先级顺序淘汰：ReasoningSlot → ToolResultSlot → ConversationSlot
4. THE WorkingMemory SHALL 在淘汰时跳过 isPinned 为 true 的 ConversationSlot
5. WHEN getContext(sessionId) 被调用时，THE WorkingMemory SHALL 返回该会话所有槽位的不可变列表副本
6. WHEN getTokenCount(sessionId) 被调用时，THE WorkingMemory SHALL 返回该会话所有槽位的 tokenCount 总和
7. WHEN flush(sessionId) 被调用时，THE WorkingMemory SHALL 将会话中的 ConversationSlot 转换为 ConversationRecord + MessageRecord 列表，通过 EpisodicMemory.save() 持久化到 L2，然后清除该会话的所有槽位
8. WHEN clearSession(sessionId) 被调用时，THE WorkingMemory SHALL 移除该会话的所有槽位
9. THE WorkingMemory SHALL 提供 activeSessions() 方法返回当前活跃会话 ID 的不可变集合

### 需求 7：CompressionLevel 枚举

**用户故事：** 作为核心开发者，我需要 CompressionLevel 枚举定义消息的压缩层级，使渐进式压缩策略有明确的层级标识

#### 验收标准

1. THE CompressionLevel SHALL 定义为枚举，包含 ORIGINAL(0)、SUMMARY(1)、KEYPOINTS(2)、ARCHIVED(3) 四个值
2. THE CompressionLevel SHALL 提供 level() 方法返回对应的整数值
3. THE CompressionLevel SHALL 提供 fromLevel(int) 静态方法，根据整数值返回对应枚举值
4. IF fromLevel() 接收到无效整数值，THEN THE CompressionLevel SHALL 抛出 IllegalArgumentException

### 需求 8：MessageRecord 消息记录

**用户故事：** 作为核心开发者，我需要 MessageRecord record 作为 L2 情景记忆的消息级存储单元，使消息能够携带压缩状态和元数据

#### 验收标准

1. THE MessageRecord SHALL 定义为 record，包含 id(String)、conversationId(String)、role(String)、content(String)、compressedContent(@Nullable String)、compressionLevel(CompressionLevel)、isPinned(boolean)、toolCallJson(@Nullable String)、tokenCount(int)、createdAt(Instant) 字段
2. THE MessageRecord SHALL 提供 effectiveContent() 方法：WHEN compressedContent 不为 null 时返回 compressedContent，否则返回 content
3. THE MessageRecord SHALL 提供 effectiveTokenCount() 方法：WHEN compressionLevel 为 ORIGINAL 时返回 tokenCount，否则根据压缩率估算（SUMMARY 约 40%、KEYPOINTS 约 20%）
4. THE MessageRecord SHALL 提供 isCompressed() 方法：WHEN compressionLevel 不为 ORIGINAL 时返回 true

### 需求 9：ConversationRecord 对话记录

**用户故事：** 作为核心开发者，我需要 ConversationRecord record 作为 L2 情景记忆的对话级存储单元，使对话能够以不可变结构聚合消息列表

#### 验收标准

1. THE ConversationRecord SHALL 定义为 record，包含 id(String)、sessionId(String)、goal(String)、summary(@Nullable String)、messages(List<MessageRecord>)、createdAt(Instant)、updatedAt(Instant) 字段
2. THE ConversationRecord SHALL 在构造时使用 List.copyOf() 确保 messages 列表不可变
3. THE ConversationRecord SHALL 提供 totalTokenCount() 方法返回所有消息 effectiveTokenCount() 的总和
4. THE ConversationRecord SHALL 提供 messageCount() 方法返回消息数量

### 需求 10：EpisodicMemory 服务

**用户故事：** 作为核心开发者，我需要 EpisodicMemory 服务提供对话的持久化存储和检索能力，使 L2 情景记忆能够保存、查询和压缩对话记录

#### 验收标准

1. WHEN save(record) 被调用时，THE EpisodicMemory SHALL 在事务内将 ConversationRecord 写入 conversations 表，将所有 MessageRecord 写入 messages 表
2. WHEN getRecent(limit) 被调用时，THE EpisodicMemory SHALL 返回最近 limit 条对话记录，按 created_at 降序排列，每条对话包含完整的消息列表
3. WHEN search(query) 被调用时，THE EpisodicMemory SHALL 使用 FTS5 BM25 在 messages_fts 中检索匹配的消息，返回包含匹配消息的对话记录列表
4. WHEN getByIntent(goal) 被调用时，THE EpisodicMemory SHALL 在 conversations 表中按 goal 列进行模糊匹配，返回匹配的对话记录列表
5. WHEN compress(conversationId, level) 被调用时，THE EpisodicMemory SHALL 更新指定对话中未固定消息的 compression_level 和 compressed_content 列
6. THE EpisodicMemory SHALL 在 compress() 中跳过 is_pinned = 1 的消息
7. WHEN getById(conversationId) 被调用时，THE EpisodicMemory SHALL 返回指定对话记录及其完整消息列表，对话不存在时返回 Optional.empty()
8. THE EpisodicMemory SHALL 使用 JdbcTemplate 执行数据库操作

---

## 正确性属性

### CP-1：BudgetAllocation 预算约束不变量
FOR ALL BudgetAllocation 实例，systemPromptBudget + workingMemoryBudget + retrievalBudget + userMessageBudget SHALL 不超过 totalBudget。

### CP-2：WorkingMemory Token 预算不变量
FOR ALL 活跃会话，append() 操作完成后会话总 Token 数 SHALL 不超过 workingMemoryTokenBudget（淘汰后）。

### CP-3：WorkingMemorySlot 类型穷举
WorkingMemorySlot 的 permits 集合 SHALL 恰好包含 ConversationSlot、ToolResultSlot、ReasoningSlot 三种类型，switch 表达式可穷举匹配。

### CP-4：ConversationRecord 消息列表不可变性
FOR ALL ConversationRecord 实例，messages() 返回的列表 SHALL 为不可变列表，外部修改不影响内部状态。

### CP-5：MessageRecord effectiveContent 一致性
FOR ALL MessageRecord 实例，WHEN compressedContent 不为 null 时 effectiveContent() SHALL 返回 compressedContent，否则 SHALL 返回 content。

### CP-6：CompressionLevel 往返属性
FOR ALL CompressionLevel 枚举值 level，CompressionLevel.fromLevel(level.level()) SHALL 返回原始枚举值。

### CP-7：EpisodicMemory save-getById 往返属性
FOR ALL 有效的 ConversationRecord，save(record) 后 getById(record.id()) SHALL 返回包含等价数据的 ConversationRecord。

### CP-8：WorkingMemory flush 清空属性
FOR ALL 活跃会话 sessionId，flush(sessionId) 完成后 getContext(sessionId) SHALL 返回空列表。

### CP-9：淘汰策略保护 pinned 消息
FOR ALL WorkingMemory 淘汰操作，isPinned 为 true 的 ConversationSlot SHALL 在淘汰后仍然存在于会话中。

---

## 非功能需求

- L1 工作记忆操作为纯 JVM 堆内存操作，访问延迟目标 < 1ms
- WorkingMemory 使用 ConcurrentHashMap 保证线程安全
- EpisodicMemory 的 save() 操作在事务内执行，保证原子性
- 所有日志、注释、异常消息、Javadoc 使用中文
- 类名、方法名、变量名使用英文
- 测试方法名使用中文
- 包结构：com.lifepilot.memory.config、com.lifepilot.memory.working、com.lifepilot.memory.episodic
- Flyway 迁移脚本遵循 V{版本号}__{描述}.sql 命名规范，从 V5 开始（V1-V4 已存在）

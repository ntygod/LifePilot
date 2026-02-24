# Implementation Plan: 记忆系统基础层 (memory-foundation)

## Overview

按自底向上的依赖顺序实现记忆系统基础层：先创建 Flyway 迁移脚本（无代码依赖），再实现数据模型 record/enum（无服务依赖），然后实现配置类和服务层，最后通过 MemoryAutoConfiguration 装配所有组件。

参考文档：
- 需求文档：#[[file:.kiro/specs/memory-foundation/requirements.md]]
- 设计文档：#[[file:.kiro/specs/memory-foundation/design.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## Tasks

- [ ] 1. 创建 Flyway 迁移脚本
  - [ ] 1.1 创建 V5__create_memory_tables.sql
    - 在 `src/main/resources/db/migration/` 下创建 V5 迁移脚本
    - 创建 conversations 表（id, session_id, goal, summary, created_at, updated_at）
    - 创建 idx_conversations_session 索引
    - 创建 messages 表（id, conversation_id, role, content, compressed_content, compression_level, is_pinned, tool_call_json, token_count, created_at），conversation_id 外键引用 conversations(id)
    - 创建 idx_messages_conversation 索引和 idx_messages_pinned 部分索引
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [ ] 1.2 创建 V6__create_messages_fts.sql
    - 创建 messages_fts FTS5 虚拟表，索引 content 列，tokenizer 为 unicode61 remove_diacritics 2
    - 创建 messages_fts_ai（AFTER INSERT）、messages_fts_ad（AFTER DELETE）、messages_fts_au（AFTER UPDATE）三个同步触发器
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [ ] 2. 实现 L2 情景记忆数据模型
  - [ ] 2.1 实现 CompressionLevel 枚举
    - 创建 `com.lifepilot.memory.episodic.CompressionLevel` 枚举
    - 包含 ORIGINAL(0)、SUMMARY(1)、KEYPOINTS(2)、ARCHIVED(3) 四个值
    - 实现 level() 方法和 fromLevel(int) 静态方法，无效值抛出 IllegalArgumentException
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [ ]* 2.2 编写 CompressionLevel 单元测试
    - 创建 `CompressionLevelTest`，测试方法名使用中文
    - 验证所有枚举值的 level() 返回值
    - 验证 fromLevel() 往返属性（Property 8: CompressionLevel 往返属性）
    - 验证无效值抛出 IllegalArgumentException
    - **Property 8: CompressionLevel 往返属性**
    - **Validates: Requirements 7.3**

  - [ ] 2.3 实现 MessageRecord record
    - 创建 `com.lifepilot.memory.episodic.MessageRecord` record
    - 包含 id, conversationId, role, content, compressedContent(@Nullable), compressionLevel, isPinned, toolCallJson(@Nullable), tokenCount, createdAt 字段
    - 实现 effectiveContent()、effectiveTokenCount()、isCompressed() 方法
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [ ]* 2.4 编写 MessageRecord 单元测试
    - 创建 `MessageRecordTest`，测试方法名使用中文
    - 验证 effectiveContent() 在 compressedContent 为 null 和非 null 时的行为
    - 验证 effectiveTokenCount() 在各压缩层级下的计算结果
    - 验证 isCompressed() 与 compressionLevel 的一致性
    - **Property 9: MessageRecord 计算字段与 compressionLevel 一致性**
    - **Validates: Requirements 8.2, 8.3, 8.4**

  - [ ] 2.5 实现 ConversationRecord record
    - 创建 `com.lifepilot.memory.episodic.ConversationRecord` record
    - 包含 id, sessionId, goal, summary(@Nullable), messages(List<MessageRecord>), createdAt, updatedAt 字段
    - compact constructor 中使用 List.copyOf() 确保 messages 不可变
    - 实现 totalTokenCount() 和 messageCount() 方法
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [ ]* 2.6 编写 ConversationRecord 单元测试
    - 创建 `ConversationRecordTest`，测试方法名使用中文
    - 验证 messages 列表不可变性（修改操作抛出 UnsupportedOperationException）
    - 验证 totalTokenCount() 和 messageCount() 计算正确性
    - **Property 10: ConversationRecord 消息列表不可变性**
    - **Property 11: ConversationRecord 派生方法一致性**
    - **Validates: Requirements 9.2, 9.3, 9.4**

- [ ] 3. 实现 L1 工作记忆类型体系
  - [ ] 3.1 实现 WorkingMemorySlot sealed interface 及三种实现
    - 创建 `com.lifepilot.memory.working.WorkingMemorySlot` sealed interface，声明 tokenCount()、createdAt()、importance() 方法
    - 创建 `ConversationSlot` record，包含静态工厂方法 userMessage(0.8f)、assistantMessage(0.6f)、systemMessage(0.9f, pinned=true)
    - 创建 `ToolResultSlot` record
    - 创建 `ReasoningSlot` record，包含静态工厂方法 retrievalContext(0.3f)、chainOfThought(0.4f)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10_

  - [ ]* 3.2 编写 WorkingMemorySlot 类型体系单元测试
    - 创建 `ConversationSlotTest`，验证静态工厂方法的默认值（role、importance、isPinned）
    - 创建 `ReasoningSlotTest`，验证静态工厂方法的默认值（source、importance）
    - **Property 3 (CP-3): WorkingMemorySlot 类型穷举** — 验证 sealed interface permits 三种类型
    - **Validates: Requirements 4.4, 4.5, 4.6, 4.9, 4.10**

  - [ ] 3.3 实现 BudgetAllocation record
    - 创建 `com.lifepilot.memory.working.BudgetAllocation` record
    - compact constructor 中验证四个区域预算之和不超过 totalBudget，超过时抛出 IllegalArgumentException
    - _Requirements: 5.1, 5.2_

  - [ ]* 3.4 编写 BudgetAllocation 单元测试
    - 创建 `BudgetAllocationTest`，测试方法名使用中文
    - 验证合法构造成功、预算超限抛出 IllegalArgumentException
    - **Property 1: BudgetAllocation 预算约束不变量**
    - **Validates: Requirements 5.2**

- [ ] 4. Checkpoint — 确认数据模型和类型体系
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 5. 实现配置层
  - [ ] 5.1 实现 MemoryProperties 配置类
    - 创建 `com.lifepilot.memory.config.MemoryProperties`，JavaBean 风格（class + getters/setters）
    - 使用 @ConfigurationProperties(prefix = "lifepilot.memory") 绑定
    - 包含 enabled(true)、workingMemoryTokenBudget(8000)、idleSessionTimeoutMinutes(30)、compressionThresholdTokens(4000)、consolidationLookbackDays(7)、forgettingThreshold(0.7)、maxRetentionDays(180) 配置项
    - _Requirements: 1.1, 1.2_

  - [ ] 5.2 更新 application.yml 添加 lifepilot.memory 配置段
    - 在 application.yml 中新增 lifepilot.memory 配置段及默认值
    - _Requirements: 1.2_

- [ ] 6. 实现 TokenBudgetAllocator 服务
  - [ ] 6.1 实现 TokenBudgetAllocator
    - 创建 `com.lifepilot.memory.working.TokenBudgetAllocator`
    - 实现 allocate(contextWindowSize, conversationTurns, topRetrievalScore) 方法
    - 系统提示词区固定 10%，用户消息区固定 15%，剩余 75% 按条件动态分配工作记忆区和检索上下文区
    - 注入 MemoryProperties（构造函数注入）
    - _Requirements: 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_

  - [ ]* 6.2 编写 TokenBudgetAllocator 单元测试
    - 创建 `TokenBudgetAllocatorTest`，测试方法名使用中文
    - 验证固定比例（系统提示词 10%、用户消息 15%）
    - 验证 topRetrievalScore > 0.9 时检索区扩展到 35%
    - 验证 conversationTurns > 10 时工作记忆区扩展到 60%
    - 验证默认条件下工作记忆 50%、检索 25%
    - **Property 2: TokenBudgetAllocator 固定比例不变量**
    - **Property 3: TokenBudgetAllocator 动态分配条件正确性**
    - **Validates: Requirements 5.4, 5.5, 5.6, 5.7, 5.8**

- [ ] 7. 实现 EpisodicMemory 服务
  - [ ] 7.1 实现 EpisodicMemory
    - 创建 `com.lifepilot.memory.episodic.EpisodicMemory`
    - 使用 JdbcTemplate 执行所有数据库操作
    - 实现 save(ConversationRecord)（@Transactional 事务内写入 conversations + messages）
    - 实现 getRecent(int limit)（按 created_at 降序）
    - 实现 search(String query)（FTS5 BM25 检索）
    - 实现 getByIntent(String goal)（LIKE 模糊匹配）
    - 实现 getById(String conversationId)（返回 Optional）
    - 实现 compress(String conversationId, CompressionLevel level)（跳过 is_pinned=1）
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8_

  - [ ]* 7.2 编写 EpisodicMemory 集成测试
    - 创建 `EpisodicMemoryIntegrationTest`，使用 @SpringBootTest + SQLite 临时数据库
    - 验证 save-getById 往返（Property 12）
    - 验证 getRecent 排序（Property 13）
    - 验证 FTS5 search 包含性（Property 14）
    - 验证 compress 保护 pinned 消息（Property 15）
    - 验证 getByIntent 模糊匹配
    - **Property 12: EpisodicMemory save-getById 往返属性**
    - **Property 13: EpisodicMemory getRecent 排序不变量**
    - **Property 14: EpisodicMemory search 包含性**
    - **Property 15: EpisodicMemory compress 保护 pinned 消息**
    - **Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7**

- [ ] 8. 实现 WorkingMemory 服务
  - [ ] 8.1 实现 WorkingMemory
    - 创建 `com.lifepilot.memory.working.WorkingMemory`
    - 使用 ConcurrentHashMap<String, List<WorkingMemorySlot>> 管理会话槽位
    - 实现 append(sessionId, slot)，超预算时执行重要度加权淘汰（ReasoningSlot → ToolResultSlot → ConversationSlot，跳过 pinned）
    - 实现 getContext(sessionId)（返回不可变列表副本）
    - 实现 getTokenCount(sessionId)
    - 实现 flush(sessionId)（ConversationSlot → MessageRecord，创建 ConversationRecord，调用 EpisodicMemory.save()，清除会话）
    - 实现 clearSession(sessionId) 和 activeSessions()
    - 注入 MemoryProperties、TokenBudgetAllocator、EpisodicMemory（构造函数注入）
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9_

  - [ ]* 8.2 编写 WorkingMemory 单元测试
    - 创建 `WorkingMemoryTest`，Mock EpisodicMemory，测试方法名使用中文
    - 验证 append 追加和淘汰逻辑
    - 验证 Token 预算不变量（Property 4）
    - 验证淘汰保护 pinned 消息（Property 5）
    - 验证 getTokenCount 一致性（Property 6）
    - 验证 flush 清空属性（Property 7）
    - 验证 clearSession 和 activeSessions
    - **Property 4: WorkingMemory Token 预算不变量**
    - **Property 5: WorkingMemory 淘汰保护 pinned 消息**
    - **Property 6: WorkingMemory getTokenCount 一致性**
    - **Property 7: WorkingMemory flush 清空属性**
    - **Validates: Requirements 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9**

- [ ] 9. Checkpoint — 确认所有服务实现
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 10. 装配与收尾
  - [ ] 10.1 实现 MemoryAutoConfiguration
    - 创建 `com.lifepilot.memory.config.MemoryAutoConfiguration`
    - 使用 @AutoConfiguration + @EnableConfigurationProperties(MemoryProperties.class)
    - 使用 @ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    - 注册 TokenBudgetAllocator、EpisodicMemory、WorkingMemory 三个 @Bean（@ConditionalOnMissingBean）
    - _Requirements: 1.3, 1.4, 1.5_

  - [ ]* 10.2 编写 MemoryAutoConfiguration 测试
    - 创建 `MemoryAutoConfigurationTest`
    - 验证 enabled=true 时创建所有 Bean
    - 验证 enabled=false 时不创建任何 Bean
    - **Validates: Requirements 1.3, 1.4, 1.5**

  - [ ] 10.3 创建 package-info.java 文件
    - 创建 `com.lifepilot.memory.config.package-info.java`
    - 创建 `com.lifepilot.memory.working.package-info.java`
    - 创建 `com.lifepilot.memory.episodic.package-info.java`
    - 更新已有的 `com.lifepilot.memory.package-info.java`

- [ ] 11. Final Checkpoint — 确认所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 所有代码遵循编码规范：中文注释/Javadoc/日志/异常消息/测试方法名，英文类名/方法名/变量名
- 服务类不使用 @Service/@Component，通过 MemoryAutoConfiguration 的 @Bean 注册
- JUnit 5 ONLY，不使用 jqwik
- 每个子任务完成后独立 git commit，遵循 `<type>(<scope>): <中文描述>` 格式

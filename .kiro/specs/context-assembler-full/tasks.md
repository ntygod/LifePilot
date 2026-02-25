# Implementation Plan: ContextAssembler 完整版

## Overview

将基础版 ContextAssembler 升级为完整版，集成 HybridRetriever、WorkingMemory、TokenBudgetAllocator，实现基于记忆检索的上下文工程。按依赖顺序实现：策略接口 → AssembledContext 增强 → 核心组装逻辑 → 自动配置 → 测试。

## Tasks

- [x] 1. 实现 MemoryRetrievalStrategy 接口和 RetrievalStrategyConfig record
  - [x] 1.1 创建 RetrievalStrategyConfig record
    - 在 `com.lifepilot.agent.context` 包下创建 `RetrievalStrategyConfig.java`
    - 字段：`topK`(int)、`weights`(RetrievalWeights)、`graphEnabled`(boolean)、`skip`(boolean)
    - 定义 `SKIP` 静态常量（topK=0, weights=DEFAULT, graphEnabled=false, skip=true）
    - 类级别 Javadoc 包含 `@author zsg` 和 `@since`
    - _Requirements: 1.1_

  - [x] 1.2 创建 MemoryRetrievalStrategy 接口
    - 在 `com.lifepilot.agent.context` 包下创建 `MemoryRetrievalStrategy.java`
    - 定义 `getStrategy(AgentPhase phase)` 方法，返回 `RetrievalStrategyConfig`
    - _Requirements: 1.1_

  - [x] 1.3 实现 DefaultMemoryRetrievalStrategy
    - 在 `com.lifepilot.agent.context` 包下创建 `DefaultMemoryRetrievalStrategy.java`
    - 使用 `switch` 表达式穷举匹配六个 AgentPhase
    - UNDERSTANDING: topK=10, vectorWeight=0.50, ftsWeight=0.30, graphWeight=0.20, graphEnabled=true
    - PLANNING: topK=5, vectorWeight=0.30, ftsWeight=0.30, graphWeight=0.40, graphEnabled=true
    - EXECUTING: topK=3, vectorWeight=0.45, ftsWeight=0.30, graphWeight=0.25, graphEnabled=false
    - REFLECTING: topK=8, vectorWeight=0.25, ftsWeight=0.45, graphWeight=0.30, graphEnabled=true
    - RESPONDING: topK=5, vectorWeight=0.35, ftsWeight=0.35, graphWeight=0.30, graphEnabled=true
    - TERMINATED: 返回 `RetrievalStrategyConfig.SKIP`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [x] 1.4 编写 DefaultMemoryRetrievalStrategy 单元测试
    - 创建 `DefaultMemoryRetrievalStrategyTest.java`
    - 测试方法：`UNDERSTANDING阶段_topK为10_向量权重最高()`、`PLANNING阶段_topK为5_图遍历权重最高()`、`EXECUTING阶段_topK为3()`、`REFLECTING阶段_topK为8_FTS权重最高()`、`RESPONDING阶段_topK为5_权重平衡()`、`TERMINATED阶段_跳过检索()`
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [ ]* 1.5 编写 Property 1 属性测试：检索策略对所有活跃阶段返回有效配置
    - **Property 1: 检索策略对所有活跃阶段返回有效配置**
    - 在 `DefaultMemoryRetrievalStrategyTest` 中添加 jqwik 属性测试
    - `@ForAll AgentPhase`（排除 TERMINATED）验证 topK > 0、三权重之和 ≈ 1.0、skip == false
    - TERMINATED 阶段验证 skip == true
    - **Validates: Requirements 1.1, 1.7**

- [x] 2. 增强 AssembledContext record
  - [x] 2.1 扩展 AssembledContext 字段
    - 新增字段：`retrievalCount`(int)、`topRetrievalScore`(float)、`workingMemoryTokens`(int)、`degraded`(boolean)
    - 保持紧凑构造器中 `retrievedMemories = List.copyOf(retrievedMemories)` 防御性拷贝
    - 更新 `totalTokens()` 方法
    - 更新基础版 ContextAssembler 中 `assemble()` 方法的 AssembledContext 构造调用，传入新字段默认值（retrievalCount=0, topRetrievalScore=0.0f, workingMemoryTokens=0, degraded=false）
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [ ]* 2.2 编写 Property 7 属性测试：AssembledContext 防御性拷贝不可变性
    - **Property 7: AssembledContext 防御性拷贝不可变性**
    - 随机生成 `List<String>`，构造 AssembledContext 后修改原始列表，验证 `retrievedMemories()` 不受影响
    - **Validates: Requirements 5.5**

- [x] 3. Checkpoint - 确保策略接口和 AssembledContext 编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. 实现完整版 ContextAssembler 核心逻辑
  - [x] 4.1 添加完整版构造器和依赖字段
    - 在 `ContextAssembler` 中新增字段：`HybridRetriever`、`WorkingMemory`、`TokenBudgetAllocator`、`MemoryRetrievalStrategy`
    - 添加 SLF4J Logger
    - 新增完整版构造器（注入四个记忆系统依赖），保留基础版构造器（向后兼容，记忆字段设为 null）
    - 添加 `isFullMode()` 内部方法判断是否为完整版
    - _Requirements: 7.1, 7.2_

  - [x] 4.2 实现降级容错方法
    - 实现 `safeRetrieve(String query, RetrievalStrategyConfig config)` — 捕获异常返回空列表，WARN 日志
    - 实现 `safeGetContext(String sessionId)` — 捕获异常返回空列表，WARN 日志
    - 实现 `safeAllocate(int conversationTurns, float topScore)` — 捕获异常回退到静态 BudgetAllocation（10%/50%/25%/15%）
    - 所有降级日志使用参数化格式
    - _Requirements: 8.1, 8.2, 8.3, 8.5, 3.6, 9.4_

  - [x] 4.3 实现截断和格式化方法
    - 实现 `truncateByBudget(List<RetrievalResult>, int tokenBudget)` — 按 fusedScore 降序保留预算内结果
    - 实现 `truncateSlotsByBudget(List<WorkingMemorySlot>, int tokenBudget)` — 按 importance 降序保留预算内槽位
    - 实现 `formatRetrievalResults(List<RetrievalResult>)` — 格式化为 `[{entityType}] {name}: {description} (score={fusedScore})`
    - 实现 `countConversationTurns(List<WorkingMemorySlot>)` — 统计 ConversationSlot 数量
    - 实现 `estimateTokens(String text)` — 中英文混合约 2 字符/Token 估算
    - _Requirements: 2.5, 3.4, 3.5, 4.3_

  - [x] 4.4 实现增强版 User Prompt 构建
    - 实现 `buildEnhancedUserPrompt(AgentState, List<String>, List<WorkingMemorySlot>)`
    - 内容区域顺序：用户请求 → 相关记忆 → 对话历史 → 工具结果 → 推理上下文 → 已执行步骤 → 预算剩余
    - ConversationSlot 按 `createdAt` 时间顺序排列
    - ToolResultSlot 拼接到"工具结果"区域
    - ReasoningSlot 拼接到"推理上下文"区域
    - 条件性区域：retrievedMemories 非空时才添加"相关记忆"，有 ConversationSlot 时才添加"对话历史"
    - 超预算时优先截断对话历史区域，保留用户请求和记忆检索结果
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 2.2, 2.3, 2.4_

  - [x] 4.5 实现 TokenBudget 构建和预算映射
    - 实现 `buildTokenBudget(AgentPhase, BudgetAllocation, List<String>, List<WorkingMemorySlot>)`
    - BudgetAllocation.systemPromptBudget → TokenBudget.systemPromptBudget
    - BudgetAllocation.workingMemoryBudget → TokenBudget.historyBudget
    - BudgetAllocation.retrievalBudget → TokenBudget.memoryBudget
    - 保留 toolSchemaBudget、toolResultBudget、reservedBuffer 使用原 TokenBudget 静态分配
    - 记录各槽位实际 Token 消耗量
    - _Requirements: 4.5, 4.6, 5.6_

  - [x] 4.6 重写 assemble() 主方法
    - 完整版流程：获取策略 → TERMINATED 返回空 → 记忆检索 → 会话槽位 → 动态预算 → 截断 → 格式化 → 构建 Prompt → 返回增强版 AssembledContext
    - 使用 `state.goal()` 作为检索查询文本
    - 使用 `config.getContext().getMaxContextTokens()` 作为 contextWindowSize
    - 最外层 try-catch 兜底，降级为基础版 AssembledContext（degraded=true）
    - 基础版构造器调用时保持原有行为不变
    - _Requirements: 3.2, 4.2, 4.4, 2.1, 2.6, 8.4, 8.5_

  - [x] 4.7 添加可观测性日志
    - INFO 级别记录组装完成：phase、sessionId、totalTokensConsumed、assemblyDurationMs
    - DEBUG 级别记录检索结果详情：实体名称列表和分数
    - WARN 级别记录降级事件：降级原因和组件名称
    - 所有日志使用参数化格式
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

- [x] 5. Checkpoint - 确保完整版 ContextAssembler 编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. 编写 ContextAssembler 单元测试
  - [x] 6.1 编写核心逻辑单元测试
    - 创建 `ContextAssemblerTest.java`，使用 Mockito Mock HybridRetriever、WorkingMemory、TokenBudgetAllocator
    - 测试方法：`使用goal作为检索查询文本()`、`使用maxContextTokens作为窗口大小()`、`conversationTurns等于ConversationSlot数量()`、`topRetrievalScore等于最高fusedScore()`
    - _Requirements: 3.2, 4.2, 4.3, 4.4_

  - [x] 6.2 编写降级容错单元测试
    - 测试方法：`不存在的sessionId使用空上下文()`、`HybridRetriever异常时降级为空记忆()`、`WorkingMemory异常时降级为空槽位()`、`TokenBudgetAllocator异常时使用静态分配()`
    - 验证降级时 `degraded == true`
    - _Requirements: 2.6, 8.1, 8.2, 8.3, 8.4_

  - [ ]* 6.3 编写 Property 6 属性测试：Token 预算不变量
    - **Property 6: Token 预算不变量**
    - 随机生成 AgentState，验证 `tokenBudget.totalConsumed() <= tokenBudget.totalBudget()`
    - **Validates: Requirements 5.6, 4.6**

  - [ ]* 6.4 编写 Property 12 属性测试：assemble() 永不抛出异常
    - **Property 12: assemble() 方法永不抛出异常**
    - 随机生成 AgentState + 随机注入组件异常（Mockito thenThrow），验证始终返回有效 AssembledContext
    - **Validates: Requirements 8.5, 2.6, 3.6, 8.1, 8.2, 8.3**

  - [ ]* 6.5 编写 Property 13 属性测试：降级时 degraded 标志为 true
    - **Property 13: 降级时 degraded 标志为 true**
    - 随机注入组件异常，验证 `degraded() == true`；正常组装时验证 `degraded() == false`
    - **Validates: Requirements 8.4**

- [ ] 7. 编写截断和格式化属性测试
  - [ ]* 7.1 编写 Property 3 属性测试：会话槽位超预算按重要度截断
    - **Property 3: 会话槽位超预算时按重要度截断**
    - 随机生成超预算的槽位列表 + 随机 historyBudget，验证保留槽位总 Token ≤ budget，被截断槽位 importance 不高于保留槽位
    - **Validates: Requirements 2.5**

  - [ ]* 7.2 编写 Property 4 属性测试：检索结果格式化包含必要字段
    - **Property 4: 检索结果格式化包含实体名称、类型和描述**
    - 随机生成 RetrievalResult，验证格式化字符串包含 entityType、name，description 非 null 时包含 description
    - **Validates: Requirements 3.4**

  - [ ]* 7.3 编写 Property 5 属性测试：检索结果超预算按分数截断
    - **Property 5: 检索结果超预算时按 fusedScore 截断**
    - 随机生成超预算的 RetrievalResult 列表 + 随机 memoryBudget，验证保留结果总 Token ≤ budget，被截断结果 fusedScore 不高于保留结果
    - **Validates: Requirements 3.5**

- [ ] 8. 编写 User Prompt 属性测试
  - [ ]* 8.1 编写 Property 2 属性测试：会话槽位按类型路由到正确区域
    - **Property 2: 会话槽位按类型路由到正确的 User Prompt 区域**
    - 随机生成 ConversationSlot/ToolResultSlot/ReasoningSlot 列表，验证各类型内容出现在对应区域
    - **Validates: Requirements 2.2, 2.3, 2.4**

  - [ ]* 8.2 编写 Property 8 属性测试：User Prompt 内容区域顺序正确
    - **Property 8: User Prompt 内容区域顺序正确**
    - 随机生成包含记忆和槽位的 AgentState，验证区域标题索引位置单调递增
    - **Validates: Requirements 6.1, 6.4**

  - [ ]* 8.3 编写 Property 9 属性测试：条件性区域仅在数据存在时出现
    - **Property 9: 条件性区域仅在数据存在时出现**
    - 随机生成空/非空的记忆和槽位组合，验证"相关记忆"和"对话历史"区域的条件性出现
    - **Validates: Requirements 6.2, 6.3**

  - [ ]* 8.4 编写 Property 10 属性测试：User Prompt 超预算优先截断历史
    - **Property 10: User Prompt 超预算时优先截断对话历史**
    - 随机生成大量 ConversationSlot 使 User Prompt 超预算，验证用户请求和记忆检索结果完整保留
    - **Validates: Requirements 6.5**

- [x] 9. 更新 AgentAutoConfiguration 条件化 Bean 注册
  - [x] 9.1 修改 AgentAutoConfiguration
    - 新增 `fullContextAssembler` Bean 方法：`@ConditionalOnBean({HybridRetriever.class, WorkingMemory.class})` + `@ConditionalOnMissingBean(ContextAssembler.class)`
    - 注入 AgentConfigProperties、HybridRetriever、WorkingMemory、TokenBudgetAllocator
    - 内部创建 DefaultMemoryRetrievalStrategy 实例
    - 原 `contextAssembler` Bean 方法改为 `basicContextAssembler`，添加 `@ConditionalOnMissingBean(ContextAssembler.class)` 作为兜底
    - 完整版 Bean 方法声明在基础版之前，确保优先评估
    - INFO 日志记录注册的版本
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [x] 9.2 编写 AgentAutoConfiguration 集成测试
    - 创建 `AgentAutoConfigurationTest.java`
    - 测试方法：`记忆系统可用时注册完整版()`、`记忆系统不可用时注册基础版()`、`自定义Bean覆盖默认实现()`
    - 使用 `@SpringBootTest` + `@TestConfiguration` 模拟不同 Bean 组合
    - _Requirements: 7.1, 7.2, 7.4_

- [ ] 10. 编写预算映射属性测试
  - [ ]* 10.1 编写 Property 11 属性测试：BudgetAllocation 到 TokenBudget 映射正确
    - **Property 11: BudgetAllocation 到 TokenBudget 映射正确**
    - 随机生成 BudgetAllocation，验证映射后 systemPromptBudget、historyBudget、memoryBudget 对应正确
    - **Validates: Requirements 4.5**

- [x] 11. Final checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 实现语言：Java 22，遵循 LifePilot 编码规范（中文注释/Javadoc/日志/测试方法名，英文类名/方法名/变量名）
- 属性测试使用 jqwik 库，每个属性测试最少 100 次迭代
- 单元测试使用 Mockito Mock 记忆系统组件（HybridRetriever、WorkingMemory、TokenBudgetAllocator）
- 所有 Property 测试注释格式：`// Feature: context-assembler-full, Property {number}: {property_text}`

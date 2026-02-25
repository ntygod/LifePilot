# 需求文档：ContextAssembler 完整版

## 简介

将 `com.lifepilot.agent.context.ContextAssembler` 从基础版升级为完整版，集成 Phase 2 已完成的记忆系统组件（WorkingMemory、EpisodicMemory、SemanticMemory、HybridRetriever、TokenBudgetAllocator），实现基于记忆检索的上下文工程。

基础版 ContextAssembler 当前仅构建 System Prompt 和 User Prompt，记忆检索槽位返回空列表。完整版将通过 HybridRetriever 执行三路混合检索，从 WorkingMemory 获取会话上下文，根据 AgentPhase 差异化分配 Token 预算，并将检索结果填充到 AssembledContext 的 retrievedMemories 槽位。

参考文档：
- 架构设计：docs/architecture/agent-engine.md §4 ContextAssembler
- 特性设计：docs/features/agent-engine.md §4 ContextAssembler
- 记忆系统架构：docs/architecture/memory-system.md
- 编码规范：.kiro/steering/coding-standards.md

## 术语表

- **ContextAssembler**：上下文组装器，负责将记忆检索结果、会话上下文、系统提示词组装为 LLM 可消费的上下文快照
- **AssembledContext**：组装完成的上下文快照 record，包含 systemPrompt、userPrompt、retrievedMemories、tokenBudget
- **TokenBudget**：Token 预算分配与消耗记录，按 AgentPhase 将总预算分配到六个槽位
- **HybridRetriever**：三路混合检索引擎，并行执行向量检索、FTS5 全文搜索、图遍历，通过加权 RRF 融合排序
- **WorkingMemory**：L1 工作记忆服务，管理会话级别的短期记忆槽位（ConcurrentHashMap）
- **EpisodicMemory**：L2 情景记忆服务，管理对话记录的持久化存储与 FTS5 检索
- **SemanticMemory**：L3 语义记忆服务，管理时序知识图谱的实体和关系
- **RetrievalResult**：HybridRetriever 返回的单条检索结果，包含 entityId、fusedScore、scoreBreakdown
- **RetrievalWeights**：检索权重配置，控制三路检索的融合权重
- **TokenBudgetAllocator**：Token 预算分配器，根据上下文窗口大小和会话状态动态分配四区域预算
- **BudgetAllocation**：TokenBudgetAllocator 的分配结果，包含 systemPromptBudget、workingMemoryBudget、retrievalBudget、userMessageBudget
- **AgentPhase**：Agent 执行阶段枚举（UNDERSTANDING、PLANNING、EXECUTING、REFLECTING、RESPONDING、TERMINATED）
- **AgentState**：Agent 不可变状态快照，包含 sessionId、phase、goal、budget 等字段
- **MemoryRetrievalStrategy**：记忆检索策略接口，根据 AgentPhase 决定检索行为（topK、权重、是否启用各路检索）

## 需求

### 需求 1：记忆检索策略

**用户故事：** 作为 Agent 引擎开发者，我希望不同 AgentPhase 使用差异化的记忆检索策略，以便在每个阶段获取最相关的上下文信息。

#### 验收标准

1. THE MemoryRetrievalStrategy SHALL 为每个非 TERMINATED 的 AgentPhase 定义独立的检索参数（topK、RetrievalWeights、是否启用图遍历）
2. WHILE AgentPhase 为 UNDERSTANDING，THE MemoryRetrievalStrategy SHALL 使用较高的记忆检索 topK（默认 10）和较高的向量权重，以获取广泛的语义相关记忆
3. WHILE AgentPhase 为 PLANNING，THE MemoryRetrievalStrategy SHALL 使用中等 topK（默认 5）并提高图遍历权重，以获取实体关系上下文
4. WHILE AgentPhase 为 EXECUTING，THE MemoryRetrievalStrategy SHALL 使用较低的 topK（默认 3）并降低记忆检索预算比例，将更多预算分配给工具 Schema
5. WHILE AgentPhase 为 REFLECTING，THE MemoryRetrievalStrategy SHALL 使用较高的 topK（默认 8）和较高的 FTS 权重，以检索历史执行经验
6. WHILE AgentPhase 为 RESPONDING，THE MemoryRetrievalStrategy SHALL 使用中等 topK（默认 5）并平衡三路权重，以生成连贯的最终回复
7. WHILE AgentPhase 为 TERMINATED，THE MemoryRetrievalStrategy SHALL 跳过记忆检索，返回空结果


### 需求 2：WorkingMemory 集成

**用户故事：** 作为 Agent 引擎开发者，我希望 ContextAssembler 从 WorkingMemory 获取当前会话的槽位数据，以便 LLM 能感知当前对话上下文。

#### 验收标准

1. WHEN ContextAssembler 组装上下文时，THE ContextAssembler SHALL 通过 WorkingMemory.getContext(sessionId) 获取当前会话的所有槽位
2. THE ContextAssembler SHALL 将 ConversationSlot 内容按时间顺序拼接到 User Prompt 的对话历史区域
3. THE ContextAssembler SHALL 将 ToolResultSlot 内容拼接到 User Prompt 的工具结果区域
4. THE ContextAssembler SHALL 将 ReasoningSlot 内容拼接到 User Prompt 的推理上下文区域
5. WHEN 会话槽位的总 Token 数超过 TokenBudget 的 historyBudget 时，THE ContextAssembler SHALL 按重要度降序保留槽位，截断低重要度内容
6. IF WorkingMemory 中不存在指定 sessionId 的会话，THEN THE ContextAssembler SHALL 使用空的会话上下文继续组装，不抛出异常

### 需求 3：HybridRetriever 集成

**用户故事：** 作为 Agent 引擎开发者，我希望 ContextAssembler 通过 HybridRetriever 执行三路混合检索，以便将语义相关的长期记忆注入 LLM 上下文。

#### 验收标准

1. WHEN ContextAssembler 组装上下文时，THE ContextAssembler SHALL 调用 HybridRetriever.retrieve(query, topK, weights) 执行三路混合检索
2. THE ContextAssembler SHALL 使用 AgentState.goal() 作为检索查询文本
3. THE ContextAssembler SHALL 根据当前 AgentPhase 从 MemoryRetrievalStrategy 获取对应的 topK 和 RetrievalWeights
4. THE ContextAssembler SHALL 将 RetrievalResult 列表转换为 AssembledContext.retrievedMemories 中的字符串列表，每条包含实体名称、类型和描述
5. WHEN 检索结果的总 Token 估算超过 TokenBudget 的 memoryBudget 时，THE ContextAssembler SHALL 按 fusedScore 降序截断，仅保留预算内的结果
6. IF HybridRetriever 检索过程发生异常，THEN THE ContextAssembler SHALL 记录 WARN 级别日志并使用空的检索结果继续组装，不中断上下文组装流程

### 需求 4：Token 预算集成

**用户故事：** 作为 Agent 引擎开发者，我希望 ContextAssembler 使用 TokenBudgetAllocator 进行动态预算分配，以便根据会话状态和检索质量自适应调整各区域的 Token 配额。

#### 验收标准

1. THE ContextAssembler SHALL 调用 TokenBudgetAllocator.allocate(contextWindowSize, conversationTurns, topRetrievalScore) 获取动态预算分配
2. THE ContextAssembler SHALL 使用 AgentConfigProperties.context.maxContextTokens 作为 contextWindowSize 参数
3. THE ContextAssembler SHALL 从 WorkingMemory 的 ConversationSlot 数量计算 conversationTurns 参数
4. THE ContextAssembler SHALL 在 HybridRetriever 检索完成后，使用检索结果中最高的 fusedScore 作为 topRetrievalScore 参数
5. THE ContextAssembler SHALL 将 BudgetAllocation 的各区域预算映射到 TokenBudget 的对应槽位（systemPromptBudget → systemPromptBudget，workingMemoryBudget → historyBudget，retrievalBudget → memoryBudget）
6. THE ContextAssembler SHALL 在 TokenBudget 中记录各槽位的实际 Token 消耗量

### 需求 5：AssembledContext 增强

**用户故事：** 作为 Agent 引擎开发者，我希望 AssembledContext 携带更丰富的上下文元数据，以便 AgentLoop 和 TraceRecorder 能追踪上下文组装的详细信息。

#### 验收标准

1. THE AssembledContext SHALL 包含 retrievedMemories 字段，类型为 List<String>，存储格式化后的记忆检索结果
2. THE AssembledContext SHALL 包含 retrievalCount 字段，记录本次检索返回的结果总数（截断前）
3. THE AssembledContext SHALL 包含 topRetrievalScore 字段，记录本次检索的最高 fusedScore
4. THE AssembledContext SHALL 包含 workingMemoryTokens 字段，记录从 WorkingMemory 注入的 Token 总数
5. THE AssembledContext SHALL 保持 record 的不可变性，所有集合字段使用 List.copyOf() 防御性拷贝
6. FOR ALL 有效的 AssembledContext 实例，tokenBudget.totalConsumed() SHALL 不超过 tokenBudget.totalBudget()


### 需求 6：User Prompt 增强

**用户故事：** 作为 Agent 引擎开发者，我希望 User Prompt 包含结构化的记忆检索结果和会话上下文，以便 LLM 能利用长期记忆做出更准确的决策。

#### 验收标准

1. THE ContextAssembler SHALL 在 User Prompt 中按以下顺序组织内容区域：用户请求 → 当前情境（记忆检索结果）→ 对话历史 → 已执行步骤 → 预算剩余
2. WHEN retrievedMemories 非空时，THE ContextAssembler SHALL 在 User Prompt 中添加"相关记忆"区域，每条记忆包含实体名称、类型和描述摘要
3. WHEN WorkingMemory 包含 ConversationSlot 时，THE ContextAssembler SHALL 在 User Prompt 中添加"对话历史"区域，按时间顺序展示最近的对话轮次
4. THE ContextAssembler SHALL 将最重要的信息（用户请求和记忆检索结果）放在 User Prompt 的开头，预算信息放在末尾，以利用 LLM 注意力 U 型曲线
5. WHEN User Prompt 的总 Token 估算超过 BudgetAllocation.userMessageBudget 时，THE ContextAssembler SHALL 优先截断对话历史区域，保留用户请求和记忆检索结果

### 需求 7：Spring 自动配置更新

**用户故事：** 作为 Agent 引擎开发者，我希望完整版 ContextAssembler 通过 Spring Boot 自动配置注入记忆系统依赖，以便在记忆系统可用时自动升级为完整版，不可用时降级为基础版。

#### 验收标准

1. WHEN HybridRetriever Bean 和 WorkingMemory Bean 均存在时，THE AgentAutoConfiguration SHALL 注册完整版 ContextAssembler（注入 HybridRetriever、WorkingMemory、TokenBudgetAllocator）
2. WHEN HybridRetriever Bean 或 WorkingMemory Bean 不存在时，THE AgentAutoConfiguration SHALL 注册基础版 ContextAssembler（当前行为，记忆检索返回空列表）
3. THE AgentAutoConfiguration SHALL 使用 @ConditionalOnBean 和 @ConditionalOnMissingBean 实现条件化 Bean 注册
4. THE ContextAssembler Bean SHALL 允许用户通过自定义 Bean 覆盖默认实现

### 需求 8：降级与容错

**用户故事：** 作为 Agent 引擎开发者，我希望 ContextAssembler 在记忆系统组件异常时能优雅降级，以便 Agent 循环不因记忆检索失败而中断。

#### 验收标准

1. IF HybridRetriever.retrieve() 抛出异常，THEN THE ContextAssembler SHALL 记录 WARN 级别日志并使用空的 retrievedMemories 继续组装
2. IF WorkingMemory.getContext() 抛出异常，THEN THE ContextAssembler SHALL 记录 WARN 级别日志并使用空的会话上下文继续组装
3. IF TokenBudgetAllocator.allocate() 抛出异常，THEN THE ContextAssembler SHALL 使用 TokenBudget.allocate(phase, totalTokens) 的静态分配作为降级方案
4. THE ContextAssembler SHALL 在降级时通过 AssembledContext 的元数据标记降级状态，以便 TraceRecorder 记录
5. THE ContextAssembler 的 assemble() 方法 SHALL 保证不抛出未检查异常，所有内部异常均被捕获并降级处理

### 需求 9：可观测性

**用户故事：** 作为 Agent 引擎开发者，我希望 ContextAssembler 的组装过程可追踪，以便通过 TraceRecorder 审计上下文组装的详细信息。

#### 验收标准

1. THE ContextAssembler SHALL 使用参数化日志记录每次组装的关键指标：phase、sessionId、retrievalCount、topRetrievalScore、totalTokensConsumed、assemblyDurationMs
2. THE ContextAssembler SHALL 在 DEBUG 级别记录检索结果的详细信息（实体名称列表和分数）
3. THE ContextAssembler SHALL 在 INFO 级别记录组装完成事件，包含 phase 和总 Token 消耗
4. IF 发生降级，THEN THE ContextAssembler SHALL 在 WARN 级别记录降级原因和降级组件名称

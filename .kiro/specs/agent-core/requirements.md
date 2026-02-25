# Requirements Document

## Introduction

本文档定义 Agent 引擎核心模块（Phase 1 第 2 项）的需求。Agent 引擎核心是 LifePilot 的状态化控制循环引擎，依赖已完成的 LLM Router 模块，向上为工具系统（Phase 1 第 3 项）和 MCP 协议支持（Phase 1 第 4 项）提供 Agent 执行基础设施。

核心设计命题：**概率决策（LLM）与确定性状态（StateReducer）严格分离**。LLM 的输出被解析为结构化的 Action，由确定性的 StateReducer 执行状态转换。这确保了状态转换的可预测性、可测试性和可回放性。

本 spec 聚焦 Agent 引擎核心组件，包括：AgentState 不可变状态、AgentPhase 阶段枚举、Action 密封接口、StateReducer 纯函数状态转换器、Budget 三维预算、TraceRecorder 决策轨迹记录器、ContextAssembler 基础版上下文组装器、AgentLoop 核心控制循环、SessionManager 会话管理器、ActionParser LLM 输出解析器、SQLite 迁移脚本和 Spring Boot 自动配置。

ContextAssembler 基础版不含记忆检索（记忆系统在 Phase 2），仅做 Token 预算分配和 System Prompt 组装。工具系统（ToolContract、DynamicToolRegistry、GuardrailEngine）不在本 spec 范围内（Phase 1 第 3 项）。主动推理引擎（ProactiveReasoner）不在本 spec 范围内（Phase 3）。

参考文档：
- 架构设计：docs/architecture/agent-engine.md
- 特性设计：docs/features/agent-engine.md
- 编码规范：.kiro/steering/coding-standards.md

## Glossary

- **Agent_Loop**: Agent 核心控制循环，协调 LLM 调用、状态转换、预算检查、轨迹记录，循环执行直到终止条件满足
- **Agent_State**: Agent 不可变状态快照 record，使用 @Builder(toBuilder=true) 实现更新，每次状态转换生成新实例
- **Agent_Phase**: Agent 执行阶段枚举，包含 UNDERSTANDING、PLANNING、EXECUTING、REFLECTING、RESPONDING、TERMINATED 六个阶段
- **Action**: Agent 动作密封接口（sealed interface），定义 LLM 产生和系统产生的所有动作类型，是概率域与确定性域之间的桥梁
- **State_Reducer**: 纯函数状态转换器，接收 (AgentState, Action) 返回新的 AgentState，相同输入永远产生相同输出
- **Budget**: 三维预算 record（Token/步骤/时间），任一维度超限即触发终止，支持 SubAgent 预算分配
- **Trace_Recorder**: 决策轨迹记录器，记录每步的输入/输出/状态变化/Token 消耗，支持完整回放
- **Trace_Step**: 单步轨迹记录 record，包含步骤序号、阶段变化、Action、工具调用信息、Token 消耗和耗时
- **Context_Assembler**: 上下文组装器（基础版），根据 AgentPhase 分配 Token 预算，组装 System Prompt 和 User Prompt，不含记忆检索
- **Assembled_Context**: 组装完成的上下文快照 record，封装一次 LLM 调用所需的全部信息
- **Token_Budget**: Token 预算分配与消耗记录 record，按阶段分配六个槽位的预算比例
- **Session_Manager**: 会话管理器，支持多轮对话的会话持久化（SQLite）和恢复
- **Session_Snapshot**: 会话快照 record，存储跨轮次恢复上下文所需的最小信息集
- **Action_Parser**: LLM 输出解析器，将 LLM 的结构化输出（JSON）解析为具体的 Action 类型
- **Agent_Request**: Agent 请求 record，包含用户消息、会话 ID、通道信息
- **Agent_Response**: Agent 响应 record，包含最终输出、Trace ID、Token 消耗统计
- **LLM_Router**: 已完成的 LLM 路由策略引擎（com.lifepilot.llm.LlmRouter），提供 getChatClient(scene) 和 call() 等方法
- **Agent_Auto_Configuration**: Agent 引擎 Spring Boot 自动配置类，注册所有核心 Bean
- **Agent_Config_Properties**: @ConfigurationProperties("lifepilot.agent") 配置属性绑定类
- **Agent_Tool_Provider**: 工具桥接层接口，本 spec 仅定义接口，具体实现在工具系统 spec 中

## Requirements

### Requirement 1: AgentState 不可变状态快照

**User Story:** As a 开发者, I want 一个完全不可变的 Agent 状态数据结构, so that 每次状态转换生成新实例，实现天然的线程安全、审计轨迹和状态回放能力。

#### Acceptance Criteria

1. THE Agent_State SHALL 使用 Java record 定义，包含 traceId（String）、sessionId（String）、goal（String）、phase（AgentPhase）、channel（String）、steps（List&lt;StepRecord&gt;）、stepCount（int）、plan（@Nullable ExecutionPlan）、planStepIndex（int）、shortTermMemory（List&lt;String&gt;）、mentionedEntities（List&lt;String&gt;）、budget（Budget）、parentTraceId（@Nullable String）、depth（int）、done（boolean）、finalOutput（@Nullable String）、terminationReason（@Nullable String）字段
2. THE Agent_State SHALL 使用 @Builder(toBuilder=true) 注解，通过 toBuilder() 模式实现不可变更新
3. THE Agent_State SHALL 在紧凑构造器中对 steps、shortTermMemory、mentionedEntities 执行 List.copyOf() 防御性拷贝
4. WHEN Agent_State.init(request) 被调用时, THE Agent_State SHALL 创建初始状态，phase 设为 UNDERSTANDING，stepCount 设为 0，budget 设为默认预算，depth 设为 0，done 设为 false
5. WHEN Agent_State.fromSession(session, request) 被调用时, THE Agent_State SHALL 从会话快照恢复上下文，使用新的 traceId 和 goal，phase 重置为 UNDERSTANDING
6. WHEN Agent_State.forSubAgent(parentState, subGoal, subBudget) 被调用时, THE Agent_State SHALL 创建子状态，继承父 Agent 的 sessionId，depth 加 1，使用独立的 traceId 和预算
7. THE Agent_State SHALL 提供 isDone() 方法返回 done 字段值
8. THE Agent_State SHALL 提供 toResponse() 方法将最终状态转换为 Agent_Response

### Requirement 2: AgentPhase 执行阶段枚举

**User Story:** As a 开发者, I want 一个定义明确的 Agent 执行阶段枚举, so that Agent 的生命周期阶段和合法转换路径在编译期可验证。

#### Acceptance Criteria

1. THE Agent_Phase SHALL 定义六个阶段：UNDERSTANDING（意图理解）、PLANNING（任务规划）、EXECUTING（工具执行）、REFLECTING（反思评估）、RESPONDING（生成响应）、TERMINATED（终止）
2. THE Agent_Phase SHALL 提供 canTransitionTo(target) 方法，使用 switch 表达式定义所有合法转换路径
3. WHEN 当前阶段为 UNDERSTANDING 时, THE Agent_Phase SHALL 允许转换到 PLANNING、RESPONDING、TERMINATED
4. WHEN 当前阶段为 PLANNING 时, THE Agent_Phase SHALL 允许转换到 EXECUTING、TERMINATED
5. WHEN 当前阶段为 EXECUTING 时, THE Agent_Phase SHALL 允许转换到 EXECUTING（多步执行）、REFLECTING、RESPONDING（单步直接响应）、UNDERSTANDING（护栏回退）、TERMINATED
6. WHEN 当前阶段为 REFLECTING 时, THE Agent_Phase SHALL 允许转换到 EXECUTING（调整后重新执行）、PLANNING（重新规划）、RESPONDING、TERMINATED
7. WHEN 当前阶段为 RESPONDING 时, THE Agent_Phase SHALL 仅允许转换到 TERMINATED
8. WHEN 当前阶段为 TERMINATED 时, THE Agent_Phase SHALL 不允许转换到任何阶段（canTransitionTo 返回 false）
9. THE Agent_Phase SHALL 提供 isTerminal() 方法，仅 TERMINATED 返回 true
10. THE Agent_Phase SHALL 提供 isActive() 方法，非 TERMINATED 阶段返回 true

### Requirement 3: Action 密封接口

**User Story:** As a 开发者, I want 一个类型安全的 Action 密封接口层次, so that LLM 输出和系统事件被统一建模为结构化动作，编译器保证 switch 穷举匹配。

#### Acceptance Criteria

1. THE Action SHALL 使用 sealed interface 定义，包含九种 record 实现：IntentUnderstood、PlanGenerated、ToolResult、ReflectionComplete、ResponseGenerated、BudgetExhausted、Blocked、ErrorRecovery、SubAgentResult
2. THE Action.IntentUnderstood SHALL 携带 summary（String）、needsClarification（boolean）、clarificationQuestion（@Nullable String）、canProceed（boolean）、entities（List&lt;String&gt;）、complexity（TaskComplexity）字段
3. THE Action.PlanGenerated SHALL 携带 steps（List&lt;PlanStep&gt;）、estimatedTokens（int）、rationale（String）字段
4. THE Action.ToolResult SHALL 携带 toolId（String）、success（boolean）、output（String）、tokensUsed（int）、latencyMs（long）、hasMore（boolean）字段
5. THE Action.ReflectionComplete SHALL 携带 satisfied（boolean）、adjustmentPlan（@Nullable String）、summary（String）、needsReplanning（boolean）字段
6. THE Action.ResponseGenerated SHALL 携带 content（String）、suggestions（List&lt;String&gt;）字段
7. THE Action.BudgetExhausted SHALL 携带 reason（String）字段
8. THE Action.Blocked SHALL 携带 reason（String）、riskLevel（@Nullable RiskLevel）、toolId（@Nullable String）字段，并提供仅接受 reason 的简化构造器
9. THE Action.ErrorRecovery SHALL 携带 errorType（AgentErrorType）、errorMessage（String）、recoverable（boolean）、recoveryStrategy（@Nullable String）字段
10. THE Action.SubAgentResult SHALL 携带 subTraceId（String）、skillId（String）、success（boolean）、output（String）、tokensUsed（int）字段
11. THE Action 的所有 record 实现 SHALL 对集合字段执行 List.copyOf() 防御性拷贝

### Requirement 4: 辅助数据模型

**User Story:** As a 开发者, I want Action 和 AgentState 依赖的辅助数据类型, so that Agent 引擎的数据模型完整且类型安全。

#### Acceptance Criteria

1. THE TaskComplexity SHALL 使用枚举定义三个级别：SIMPLE（单步或无需工具）、MODERATE（2-5 步）、COMPLEX（5+ 步或需要 SubAgent）
2. THE AgentErrorType SHALL 使用枚举定义错误类型：LLM_UNAVAILABLE、LLM_PARSE_FAILURE、TOOL_EXECUTION_FAILURE、TOOL_TIMEOUT、GUARDRAIL_VIOLATION、INTERNAL_ERROR
3. THE RiskLevel SHALL 使用枚举定义四个风险等级：LOW、MEDIUM、HIGH、CRITICAL
4. THE PlanStep SHALL 使用 record 定义，包含 index（int）、toolId（String）、params（Map&lt;String, Object&gt;）、dependsOn（List&lt;Integer&gt;）、description（String）字段
5. THE StepRecord SHALL 使用 record 定义，记录已执行步骤的信息，包含 toolId（@Nullable String）、success（boolean）、output（String）、blocked（boolean）、tokensUsed（int）、latencyMs（long）字段
6. THE Agent_Request SHALL 使用 record 定义，包含 message（String）、sessionId（String）、channel（String）字段
7. THE Agent_Response SHALL 使用 record 定义，包含 traceId（String）、sessionId（String）、content（String）、tokensUsed（int）、stepCount（int）、terminationReason（@Nullable String）字段，并提供 error(state, exception) 静态工厂方法

### Requirement 5: StateReducer 纯函数状态转换器

**User Story:** As a 开发者, I want 一个确定性的纯函数状态转换器, so that 相同的 (state, action) 输入永远产生相同的输出，实现可测试、可回放、可调试的状态管理。

#### Acceptance Criteria

1. THE State_Reducer SHALL 提供 reduce(state, action) 方法，接收当前 Agent_State 和 Action，返回新的 Agent_State 实例
2. THE State_Reducer SHALL 使用 switch 表达式对所有九种 Action 类型进行穷举匹配
3. WHEN reduce() 接收到 Action.IntentUnderstood 且 needsClarification 为 true 时, THE State_Reducer SHALL 将 phase 转换为 RESPONDING
4. WHEN reduce() 接收到 Action.IntentUnderstood 且 canProceed 为 true 时, THE State_Reducer SHALL 将 phase 转换为 PLANNING
5. WHEN reduce() 接收到 Action.PlanGenerated 时, THE State_Reducer SHALL 将 phase 从 PLANNING 转换为 EXECUTING，并将 plan 存入状态
6. WHEN reduce() 接收到 Action.ToolResult 且 hasMore 为 true 时, THE State_Reducer SHALL 保持 phase 为 EXECUTING
7. WHEN reduce() 接收到 Action.ToolResult 且 hasMore 为 false 时, THE State_Reducer SHALL 将 phase 转换为 REFLECTING
8. WHEN reduce() 接收到 Action.ReflectionComplete 且 satisfied 为 true 时, THE State_Reducer SHALL 将 phase 转换为 RESPONDING
9. WHEN reduce() 接收到 Action.ReflectionComplete 且 needsReplanning 为 true 时, THE State_Reducer SHALL 将 phase 转换为 PLANNING（最多允许 2 次修正循环）
10. WHEN reduce() 接收到 Action.ResponseGenerated 时, THE State_Reducer SHALL 将 phase 转换为 TERMINATED，设置 done 为 true，存储 finalOutput
11. WHEN reduce() 接收到 Action.BudgetExhausted 时, THE State_Reducer SHALL 将 phase 转换为 TERMINATED，设置 done 为 true，记录终止原因
12. WHEN reduce() 接收到 Action.Blocked 时, THE State_Reducer SHALL 将 phase 回退到 UNDERSTANDING（高风险）或保持当前阶段（低风险），CRITICAL 级别直接终止
13. WHEN reduce() 接收到 Action.ErrorRecovery 且 recoverable 为 false 时, THE State_Reducer SHALL 将 phase 转换为 TERMINATED
14. WHILE Agent_State 的 phase 为 TERMINATED 时, THE State_Reducer SHALL 忽略所有后续 Action，直接返回当前状态（吸收态）
15. THE State_Reducer SHALL 在每次状态转换时递增 stepCount
16. IF 阶段转换不在合法转换集合中, THEN THE State_Reducer SHALL 抛出 IllegalStateException

### Requirement 6: StateReducer 不变量保证

**User Story:** As a 开发者, I want StateReducer 维护一组可验证的不变量, so that Agent 引擎的状态始终处于合法、一致的状态空间内。

#### Acceptance Criteria

1. FOR ALL 合法的 (state, action) 组合, THE State_Reducer SHALL 保证 reduce(state, action).stepCount() 大于等于 state.stepCount()（stepCount 单调递增）
2. FOR ALL Action 类型, WHILE Agent_State 的 phase 为 TERMINATED 时, THE State_Reducer SHALL 保证 reduce(state, action).phase() 等于 TERMINATED（吸收态不变量）
3. FOR ALL 合法的 (state, action) 组合, THE State_Reducer SHALL 保证 reduce(state, action).steps() 包含 state.steps() 的所有元素（steps 列表只增不减）
4. FOR ALL 产生阶段变化的 (state, action) 组合, THE State_Reducer SHALL 保证 state.phase().canTransitionTo(newState.phase()) 返回 true（阶段转换遵循合法转换图）
5. FOR ALL 合法的 (state, action) 组合, THE State_Reducer SHALL 保证 reduce 是纯函数——相同的 (state, action) 输入永远产生相同的输出

### Requirement 7: Budget 三维预算

**User Story:** As a 开发者, I want 一个三维预算控制机制, so that Agent 执行在 Token、步骤和时间三个维度上都有明确的上限，防止资源失控。

#### Acceptance Criteria

1. THE Budget SHALL 使用 record 定义，包含 Token 维度（maxTokens、tokensUsed、tokensReserved）、步骤维度（maxSteps）、时间维度（maxDuration、elapsed）字段
2. THE Budget SHALL 使用 @Builder(toBuilder=true) 注解实现不可变更新
3. THE Budget SHALL 提供 defaultBudget() 静态工厂方法，返回默认预算（32000 Token、20 步、120 秒）
4. THE Budget SHALL 提供 exceeded() 方法，当 tokensUsed 大于等于 maxTokens 或 elapsed 大于等于 maxDuration 时返回 true（OR 逻辑，任一维度超限即终止）
5. THE Budget SHALL 提供 exceedReason() 方法，返回具体的超限原因描述
6. THE Budget SHALL 提供 tokensRemaining() 方法，返回 max(0, maxTokens - tokensUsed - tokensReserved)
7. THE Budget SHALL 提供 deductTokens(tokens) 方法，返回扣减后的新 Budget 实例
8. THE Budget SHALL 提供 withElapsed(elapsed) 方法，返回更新已用时间后的新 Budget 实例
9. WHEN allocateForSubAgent(ratio) 被调用时, THE Budget SHALL 从当前预算中按比例分配 Token、步骤和时间给 SubAgent，并将分配的 Token 标记为 reserved
10. WHEN returnFromSubAgent(subBudget) 被调用时, THE Budget SHALL 将 SubAgent 未使用的 Token 归还给主 Agent
11. THE Budget SHALL 提供 tokenUtilization() 和 timeUtilization() 方法，返回 0.0 到 1.0 之间的使用率

### Requirement 8: TraceRecorder 决策轨迹记录器

**User Story:** As a 开发者, I want 一个完整的决策轨迹记录器, so that Agent 每一步的输入、输出、状态变化和 Token 消耗都可追溯和回放。

#### Acceptance Criteria

1. THE Trace_Recorder SHALL 提供 startTrace(traceId, sessionId, userMessage) 方法，创建新的轨迹记录上下文
2. THE Trace_Recorder SHALL 提供 recordStep(traceContext, traceStep) 方法，记录单步轨迹信息
3. THE Trace_Recorder SHALL 提供 endTrace(traceContext, finalOutput, success, errorMessage, terminationReason) 方法，完成轨迹记录
4. THE Trace_Step SHALL 使用 record 定义，包含 traceId（String）、stepIndex（int）、phaseBefore（AgentPhase）、phaseAfter（AgentPhase）、action（Action）、toolId（@Nullable String）、toolInput（@Nullable String）、toolOutput（@Nullable String）、blocked（boolean）、blockReason（@Nullable String）、tokensUsed（int）、latencyMs（long）、timestamp（Instant）字段
5. THE Trace_Recorder SHALL 提供 persistTrace(traceId) 方法，将内存中的轨迹数据持久化到 agent_traces 和 agent_trace_steps 表
6. WHEN persistTrace() 被调用时, THE Trace_Recorder SHALL 将 Trace 元信息写入 agent_traces 表，将每步详情写入 agent_trace_steps 表
7. THE Trace_Recorder SHALL 提供 findTrace(traceId) 方法，从数据库加载完整的轨迹记录
8. THE Trace_Recorder SHALL 提供 findTracesBySession(sessionId) 方法，查询指定会话的所有轨迹

### Requirement 9: ContextAssembler 基础版上下文组装器

**User Story:** As a 开发者, I want 一个基于阶段的上下文组装器, so that 不同 Agent 阶段获得信噪比最高的上下文信息，Token 预算按阶段策略分配。

#### Acceptance Criteria

1. THE Context_Assembler SHALL 提供 assemble(state) 方法，根据当前 Agent_State 组装 Assembled_Context
2. THE Context_Assembler SHALL 根据 Agent_Phase 按以下比例分配 Token 预算到六个槽位（System Prompt / 对话历史 / 记忆检索 / 工具 Schema / 工具结果 / 保留缓冲）
3. WHEN 当前阶段为 UNDERSTANDING 时, THE Context_Assembler SHALL 按 15% / 30% / 35% / 10% / 0% / 10% 分配 Token 预算
4. WHEN 当前阶段为 PLANNING 时, THE Context_Assembler SHALL 按 15% / 20% / 15% / 35% / 5% / 10% 分配 Token 预算
5. WHEN 当前阶段为 EXECUTING 时, THE Context_Assembler SHALL 按 10% / 10% / 5% / 40% / 25% / 10% 分配 Token 预算
6. WHEN 当前阶段为 REFLECTING 时, THE Context_Assembler SHALL 按 10% / 15% / 10% / 5% / 50% / 10% 分配 Token 预算
7. WHEN 当前阶段为 RESPONDING 时, THE Context_Assembler SHALL 按 15% / 25% / 20% / 0% / 30% / 10% 分配 Token 预算
8. THE Context_Assembler 基础版 SHALL 将记忆检索槽位留空（返回空列表），记忆检索功能在 Phase 2 记忆系统中实现
9. THE Context_Assembler SHALL 构建阶段专用的 System Prompt，包含角色定义、行为约束和阶段指令
10. THE Context_Assembler SHALL 构建结构化的 User Prompt，融合用户原始消息、当前阶段指令、已执行步骤摘要和预算剩余信息

### Requirement 10: TokenBudget 预算分配记录

**User Story:** As a 开发者, I want 一个记录各槽位 Token 预算分配和实际消耗的数据结构, so that 上下文组装过程可观测且可调优。

#### Acceptance Criteria

1. THE Token_Budget SHALL 使用 record 定义，包含六个槽位的预算上限（systemPromptBudget、historyBudget、memoryBudget、toolSchemaBudget、toolResultBudget、reservedBuffer）和五个槽位的实际消耗值（systemPromptUsed、historyUsed、memoryUsed、toolSchemaUsed、toolResultUsed）
2. THE Token_Budget SHALL 提供 allocate(phase, totalTokens) 静态方法，根据 Agent_Phase 和总 Token 数按预定义比例创建预算分配
3. THE Token_Budget SHALL 提供 totalBudget() 方法返回所有槽位预算之和
4. THE Token_Budget SHALL 提供 totalConsumed() 方法返回所有槽位实际消耗之和
5. THE Token_Budget SHALL 提供 isOverBudget() 方法，当 totalConsumed 超过 totalBudget 减去 reservedBuffer 时返回 true

### Requirement 11: AssembledContext 上下文快照

**User Story:** As a 开发者, I want 一个封装 LLM 调用所需全部信息的不可变上下文快照, so that AgentLoop 可以将组装好的上下文传递给 LLM 调用。

#### Acceptance Criteria

1. THE Assembled_Context SHALL 使用 record 定义，包含 systemPrompt（String）、userPrompt（String）、retrievedMemories（List）、tokenBudget（TokenBudget）字段
2. THE Assembled_Context SHALL 在紧凑构造器中对 retrievedMemories 执行 List.copyOf() 防御性拷贝
3. THE Assembled_Context SHALL 提供 totalTokens() 方法返回 tokenBudget.totalConsumed()

### Requirement 12: ActionParser LLM 输出解析器

**User Story:** As a 开发者, I want 一个将 LLM 结构化输出解析为 Action 类型的解析器, so that LLM 的概率性输出被转换为类型安全的结构化动作。

#### Acceptance Criteria

1. THE Action_Parser SHALL 提供 parse(phase, llmOutput) 方法，根据当前 Agent_Phase 将 LLM 的 JSON 输出解析为对应的 Action 类型
2. WHEN 当前阶段为 UNDERSTANDING 时, THE Action_Parser SHALL 将 LLM 输出解析为 Action.IntentUnderstood
3. WHEN 当前阶段为 PLANNING 时, THE Action_Parser SHALL 将 LLM 输出解析为 Action.PlanGenerated
4. WHEN 当前阶段为 REFLECTING 时, THE Action_Parser SHALL 将 LLM 输出解析为 Action.ReflectionComplete
5. WHEN 当前阶段为 RESPONDING 时, THE Action_Parser SHALL 将 LLM 输出解析为 Action.ResponseGenerated
6. IF LLM 输出无法解析为有效的 JSON 或不符合预期的 Action 结构, THEN THE Action_Parser SHALL 返回 Action.ErrorRecovery，errorType 为 LLM_PARSE_FAILURE，recoverable 为 true
7. THE Action_Parser SHALL 将 LLM 输出格式化为 Action 的 JSON 序列化形式，支持 Action 到 JSON 的序列化和 JSON 到 Action 的反序列化（round-trip）

### Requirement 13: SessionManager 会话管理器

**User Story:** As a 开发者, I want 一个支持多轮对话的会话管理器, so that Agent 可以跨轮次维护上下文、支持会话持久化和恢复。

#### Acceptance Criteria

1. THE Session_Manager SHALL 提供 findSession(sessionId) 方法，返回 Optional&lt;SessionSnapshot&gt;
2. THE Session_Manager SHALL 提供 saveSession(state) 方法，将 Agent_State 的关键上下文信息保存为 Session_Snapshot 并持久化到 agent_sessions 表
3. THE Session_Manager SHALL 提供 deleteSession(sessionId) 方法，删除指定会话
4. THE Session_Snapshot SHALL 使用 record 定义，包含 sessionId（String）、channelId（String）、recentTurns（List&lt;ConversationTurn&gt;）、mentionedEntities（List&lt;String&gt;）、lastActiveAt（Instant）、totalTurns（int）、totalTokensUsed（int）字段
5. THE Session_Snapshot SHALL 提供 isExpired(timeout) 方法，当距 lastActiveAt 超过 timeout 时返回 true
6. THE ConversationTurn SHALL 使用 record 定义，包含 userMessage（String）、agentResponse（String）、toolsUsed（List&lt;String&gt;）、timestamp（Instant）字段
7. WHEN saveSession() 被调用时, THE Session_Manager SHALL 仅保留最近 N 轮对话（默认 10 轮），丢弃更早的轮次
8. THE Session_Manager SHALL 提供定时清理过期会话的能力，默认清理间隔 5 分钟，会话过期时间 30 分钟

### Requirement 14: AgentLoop 核心控制循环

**User Story:** As a 开发者, I want 一个状态化的 Agent 控制循环, so that 用户请求通过"上下文组装 → LLM 决策 → 状态转换 → 轨迹记录"的循环迭代完成多步推理和工具调用。

#### Acceptance Criteria

1. THE Agent_Loop SHALL 提供 run(request) 方法，接收 Agent_Request 返回 Agent_Response
2. WHEN run() 被调用时, THE Agent_Loop SHALL 初始化 Agent_State（新会话调用 init，已有会话调用 fromSession）
3. THE Agent_Loop SHALL 在每轮循环中依次执行：更新已用时间 → 预算检查 → 上下文组装 → LLM 决策 → 状态转换 → 轨迹记录
4. THE Agent_Loop SHALL 使用 LLM_Router.getChatClient(scene) 获取 ChatClient，根据 Agent_Phase 映射不同的 LLM 场景（UNDERSTANDING/PLANNING 使用 agent-reasoning，EXECUTING 使用 agent-tool-calling，REFLECTING 使用 agent-reasoning，RESPONDING 使用 agent-generation）
5. THE Agent_Loop SHALL 将 LLM 的文本输出通过 Action_Parser 解析为 Action，再交由 State_Reducer 执行状态转换
6. WHEN Agent_State.isDone() 返回 true 时, THE Agent_Loop SHALL 退出循环并返回 Agent_Response
7. WHEN 循环次数达到硬限制 50 次时, THE Agent_Loop SHALL 生成 Action.BudgetExhausted 强制终止
8. WHEN Budget.exceeded() 返回 true 时, THE Agent_Loop SHALL 生成 Action.BudgetExhausted 终止循环
9. IF LLM 调用抛出 LlmUnavailableException, THEN THE Agent_Loop SHALL 生成 Action.BudgetExhausted 终止循环
10. IF 循环过程中发生不可恢复异常, THEN THE Agent_Loop SHALL 记录 Trace 错误信息并返回 AgentResponse.error()
11. THE Agent_Loop SHALL 在循环完成后异步执行后处理：会话持久化和 Trace 持久化（使用 Virtual Thread，不阻塞响应返回）
12. WHEN 连续 3 次护栏阻断（steps 末尾连续 3 个 blocked 为 true 的 StepRecord）时, THE Agent_Loop SHALL 强制终止循环

### Requirement 15: AgentToolProvider 工具桥接层接口

**User Story:** As a 开发者, I want 一个工具桥接层接口定义, so that AgentLoop 可以通过统一接口获取工具回调，具体实现在工具系统 spec 中完成。

#### Acceptance Criteria

1. THE Agent_Tool_Provider SHALL 定义为接口，提供 getToolCallbacks(state) 方法，接收 Agent_State 返回工具回调列表
2. THE Agent_Tool_Provider SHALL 在 agent-core 模块中提供一个空实现（NoOpAgentToolProvider），返回空列表
3. WHEN 工具系统 spec 完成后, THE Agent_Tool_Provider 的具体实现 SHALL 替换空实现，聚合 Java 原生工具、YAML 声明式工具和 MCP 外部工具

### Requirement 16: SQLite 迁移脚本

**User Story:** As a 开发者, I want Agent 引擎相关的数据库表通过 Flyway 迁移脚本创建, so that 数据库 Schema 版本化管理，支持增量升级。

#### Acceptance Criteria

1. THE agent_sessions 表 SHALL 包含 id（TEXT PRIMARY KEY）、channel_id（TEXT NOT NULL）、recent_turns_json（TEXT NOT NULL DEFAULT '[]'）、mentioned_entities_json（TEXT NOT NULL DEFAULT '[]'）、active_task_context（TEXT）、last_active_at（TEXT NOT NULL）、total_turns（INTEGER NOT NULL DEFAULT 0）、total_tokens_used（INTEGER NOT NULL DEFAULT 0）、archived（INTEGER NOT NULL DEFAULT 0）、created_at（TEXT NOT NULL）、updated_at（TEXT NOT NULL）字段
2. THE agent_sessions 表 SHALL 创建 idx_agent_sessions_channel（channel_id, last_active_at）和 idx_agent_sessions_active（archived, last_active_at）索引
3. THE agent_traces 表 SHALL 包含 id（TEXT PRIMARY KEY）、session_id（TEXT NOT NULL）、user_message（TEXT NOT NULL）、final_output（TEXT）、success（INTEGER NOT NULL DEFAULT 1）、error_message（TEXT）、termination_reason（TEXT）、total_steps（INTEGER NOT NULL DEFAULT 0）、total_tokens（INTEGER NOT NULL DEFAULT 0）、duration_ms（INTEGER NOT NULL DEFAULT 0）、model_id（TEXT）、parent_trace_id（TEXT）、depth（INTEGER NOT NULL DEFAULT 0）、created_at（TEXT NOT NULL）字段，session_id 外键关联 agent_sessions(id)
4. THE agent_traces 表 SHALL 创建 idx_agent_traces_session（session_id, created_at）和 idx_agent_traces_time（created_at）索引
5. THE agent_trace_steps 表 SHALL 包含 id（TEXT PRIMARY KEY）、trace_id（TEXT NOT NULL）、step_index（INTEGER NOT NULL）、phase_before（TEXT NOT NULL）、phase_after（TEXT NOT NULL）、action_type（TEXT NOT NULL）、action_json（TEXT NOT NULL）、tool_id（TEXT）、tool_input_json（TEXT）、tool_output（TEXT）、success（INTEGER NOT NULL DEFAULT 1）、blocked（INTEGER NOT NULL DEFAULT 0）、block_reason（TEXT）、tokens_used（INTEGER NOT NULL DEFAULT 0）、latency_ms（INTEGER NOT NULL DEFAULT 0）、created_at（TEXT NOT NULL）字段，trace_id 外键关联 agent_traces(id)
6. THE agent_trace_steps 表 SHALL 创建 idx_trace_steps_trace（trace_id, step_index）索引
7. THE 迁移脚本 SHALL 使用 Flyway 命名规范，版本号从 V3 开始（V1 为项目骨架，V2 为 LLM Router）

### Requirement 17: Spring Boot 自动配置

**User Story:** As a 开发者, I want Agent 引擎通过 Spring Boot 自动配置加载, so that 引入模块后即可使用，无需手动装配 Bean。

#### Acceptance Criteria

1. THE Agent_Auto_Configuration SHALL 使用 @AutoConfiguration 和 @EnableConfigurationProperties(AgentConfigProperties.class) 注解
2. THE Agent_Auto_Configuration SHALL 通过 @ConditionalOnProperty(prefix = "lifepilot.agent", name = "enabled", havingValue = "true", matchIfMissing = true) 控制启用
3. WHEN Agent 自动配置生效时, THE Agent_Auto_Configuration SHALL 注册 StateReducer、ContextAssembler、TraceRecorder、SessionManager、ActionParser、AgentLoop、AgentToolProvider（NoOp 实现）为 Spring Bean
4. THE Agent_Auto_Configuration SHALL 对每个 Bean 使用 @ConditionalOnMissingBean 注解，允许用户自定义覆盖
5. THE Agent_Auto_Configuration SHALL 依赖 LLM_Router Bean（通过构造器注入），确保 LLM Router 模块已加载

### Requirement 18: 配置属性绑定

**User Story:** As a 开发者, I want 通过 application.yml 声明式配置 Agent 引擎参数, so that 用户可以通过修改配置文件调整 Agent 行为。

#### Acceptance Criteria

1. THE Agent_Config_Properties SHALL 使用 @ConfigurationProperties(prefix = "lifepilot.agent") 绑定配置
2. THE Agent_Config_Properties SHALL 支持 loop.max-iterations（默认 50）配置项
3. THE Agent_Config_Properties SHALL 支持 loop.scene-mapping 配置项，定义 AgentPhase 到 LLM 场景的映射（understanding→agent-reasoning、planning→agent-reasoning、executing→agent-tool-calling、reflecting→agent-reasoning、responding→agent-generation）
4. THE Agent_Config_Properties SHALL 支持 budget.default-max-tokens（默认 32000）、budget.default-max-steps（默认 20）、budget.default-max-duration-seconds（默认 120）、budget.sub-agent-budget-ratio（默认 0.3）配置项
5. THE Agent_Config_Properties SHALL 支持 context.max-context-tokens（默认 32000）、context.output-reserved-tokens（默认 4000）配置项
6. THE Agent_Config_Properties SHALL 支持 session.timeout-minutes（默认 30）、session.max-recent-turns（默认 10）、session.cleanup-interval-ms（默认 300000）配置项

### Requirement 19: 错误处理与降级

**User Story:** As a 开发者, I want Agent 引擎在各种异常场景下优雅降级, so that 即使 LLM 不可用或工具执行失败，系统也能返回有意义的响应。

#### Acceptance Criteria

1. WHEN Budget 耗尽时, THE Agent_Loop SHALL 根据当前阶段生成降级响应：UNDERSTANDING 阶段提示简化请求、EXECUTING 阶段返回已完成的部分结果、REFLECTING 阶段返回执行结果摘要
2. IF LLM 调用失败且 Action_Parser 返回 ErrorRecovery(recoverable=true), THEN THE Agent_Loop SHALL 在当前阶段重试一次（通过 StateReducer 保持当前阶段）
3. IF LLM 调用失败且 Action_Parser 返回 ErrorRecovery(recoverable=false), THEN THE Agent_Loop SHALL 终止循环并返回错误响应
4. WHEN Trace 持久化失败时, THE Trace_Recorder SHALL 记录 WARN 日志但不影响 Agent 响应的返回
5. WHEN Session 持久化失败时, THE Session_Manager SHALL 记录 WARN 日志但不影响 Agent 响应的返回

# Implementation Plan: Agent Core

## Overview

按依赖顺序实现 Agent 引擎核心模块。先建立辅助枚举和数据模型（无依赖），再实现核心状态管理（AgentState、Budget、StateReducer），然后实现上下文组装、轨迹记录、会话管理、LLM 输出解析等组件，最后通过 AgentLoop 将所有组件串联，并配置 Spring Boot 自动装配。

所有代码位于 `com.lifepilot.agent` 包下，遵循编码规范（中文注释、record 优先、sealed interface、@Builder(toBuilder=true)）。

## Tasks

- [x] 1. 实现辅助枚举和基础数据模型
  - [x] 1.1 创建 TaskComplexity、AgentErrorType、RiskLevel 枚举
    - 创建 `com.lifepilot.agent.model.TaskComplexity` 枚举（SIMPLE、MODERATE、COMPLEX）
    - 创建 `com.lifepilot.agent.model.AgentErrorType` 枚举（LLM_UNAVAILABLE、LLM_PARSE_FAILURE、TOOL_EXECUTION_FAILURE、TOOL_TIMEOUT、GUARDRAIL_VIOLATION、INTERNAL_ERROR）
    - 创建 `com.lifepilot.agent.model.RiskLevel` 枚举（LOW、MEDIUM、HIGH、CRITICAL）
    - _Requirements: 4.1, 4.2, 4.3_

  - [ ] 1.2 创建 PlanStep 和 ExecutionPlan record
    - 创建 `com.lifepilot.agent.model.PlanStep` record（index、toolId、params、dependsOn、description），紧凑构造器中 Map.copyOf(params)、List.copyOf(dependsOn)
    - 创建 `com.lifepilot.agent.model.ExecutionPlan` record（steps、estimatedTokens、rationale），紧凑构造器中 List.copyOf(steps)
    - _Requirements: 4.4_

  - [ ] 1.3 创建 StepRecord record
    - 创建 `com.lifepilot.agent.model.StepRecord` record（toolId、success、output、blocked、tokensUsed、latencyMs）
    - _Requirements: 4.5_

  - [ ] 1.4 创建 AgentRequest 和 AgentResponse record
    - 创建 `com.lifepilot.agent.model.AgentRequest` record（message、sessionId、channel）
    - 创建 `com.lifepilot.agent.model.AgentResponse` record（traceId、sessionId、content、tokensUsed、stepCount、terminationReason），包含 error(state, exception) 静态工厂方法
    - _Requirements: 4.6, 4.7_

  - [ ]* 1.5 编写辅助数据模型单元测试
    - 测试 PlanStep、ExecutionPlan 的防御性拷贝
    - 测试 AgentResponse.error() 工厂方法
    - **Property 8: AgentResponse.error() 工厂方法正确性**
    - **Validates: Requirements 4.7**

- [ ] 2. 实现 AgentPhase 执行阶段枚举
  - [ ] 2.1 创建 AgentPhase 枚举
    - 创建 `com.lifepilot.agent.model.AgentPhase` 枚举，定义六个阶段（UNDERSTANDING、PLANNING、EXECUTING、REFLECTING、RESPONDING、TERMINATED）
    - 实现 canTransitionTo(target) 方法，使用 switch 表达式定义合法转换路径
    - 实现 isTerminal() 和 isActive() 方法
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10_

  - [ ]* 2.2 编写 AgentPhase 属性测试
    - **Property 6: AgentPhase 合法转换图完整性**
    - **Property 7: AgentPhase isTerminal/isActive 互补性**
    - **Validates: Requirements 2.2~2.10**

- [ ] 3. 实现 Action 密封接口
  - [ ] 3.1 创建 Action sealed interface 及 9 个 record 实现
    - 创建 `com.lifepilot.agent.model.Action` sealed interface
    - 实现 IntentUnderstood、PlanGenerated、ToolResult、ReflectionComplete、ResponseGenerated、BudgetExhausted、Blocked、ErrorRecovery、SubAgentResult 九个 record
    - 所有集合字段执行 List.copyOf() 防御性拷贝
    - Blocked 提供仅接受 reason 的简化构造器
    - _Requirements: 3.1~3.11_

  - [ ]* 3.2 编写 Action 防御性拷贝单元测试
    - 测试 IntentUnderstood.entities、PlanGenerated.steps、ResponseGenerated.suggestions 的防御性拷贝
    - **Property 1: Record 集合字段防御性拷贝不变量（Action 部分）**
    - **Validates: Requirements 3.11**

- [ ] 4. 实现 Budget 三维预算
  - [ ] 4.1 创建 Budget record
    - 创建 `com.lifepilot.agent.model.Budget` record（maxTokens、tokensUsed、tokensReserved、maxSteps、maxDuration、elapsed）
    - 使用 @Builder(toBuilder=true) 注解
    - 实现 defaultBudget()、exceeded()、exceedReason()、tokensRemaining()、deductTokens()、withElapsed()、allocateForSubAgent()、returnFromSubAgent()、tokenUtilization()、timeUtilization()
    - _Requirements: 7.1~7.11_

  - [ ]* 4.2 编写 Budget 属性测试
    - **Property 16: Budget exceeded() OR 逻辑与 exceedReason() 一致性**
    - **Property 17: Budget tokensRemaining 与 deductTokens 一致性**
    - **Property 18: Budget withElapsed 不可变更新**
    - **Property 19: Budget SubAgent 预算分配与归还**
    - **Property 20: Budget utilization 范围不变量**
    - **Validates: Requirements 7.4~7.11**

- [ ] 5. 实现 AgentState 不可变状态快照
  - [ ] 5.1 创建 AgentState record
    - 创建 `com.lifepilot.agent.model.AgentState` record，包含 traceId、sessionId、goal、phase、channel、steps、stepCount、plan、planStepIndex、shortTermMemory、mentionedEntities、budget、parentTraceId、depth、done、finalOutput、terminationReason 字段
    - 使用 @Builder(toBuilder=true) 注解
    - 紧凑构造器中对 steps、shortTermMemory、mentionedEntities 执行 List.copyOf()
    - 实现 init(request)、fromSession(session, request)、forSubAgent(parentState, subGoal, subBudget) 静态工厂方法
    - 实现 isDone() 和 toResponse() 方法
    - _Requirements: 1.1~1.8_

  - [ ]* 5.2 编写 AgentState 属性测试
    - **Property 1: Record 集合字段防御性拷贝不变量（AgentState 部分）**
    - **Property 2: AgentState.init() 初始状态不变量**
    - **Property 3: AgentState.fromSession() 会话恢复不变量**
    - **Property 4: AgentState.forSubAgent() 子状态不变量**
    - **Property 5: AgentState.toResponse() 字段映射正确性**
    - **Validates: Requirements 1.3~1.8**

- [ ] 6. Checkpoint — 数据模型层编译通过
  - 确保所有数据模型类（model/ 包下所有文件）编译通过
  - 确保已有测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. 实现 StateReducer 纯函数状态转换器
  - [ ] 7.1 创建 StateReducer 类
    - 创建 `com.lifepilot.agent.StateReducer`，使用 @Service 注解
    - 实现 reduce(state, action) 方法，使用 switch 表达式穷举匹配九种 Action 类型
    - 实现吸收态逻辑：TERMINATED 后忽略所有动作，直接返回当前状态
    - 实现 validateTransition() 方法，非法转换抛出 IllegalStateException
    - 实现 reduceIntentUnderstood：needsClarification=true → RESPONDING，canProceed=true → PLANNING
    - 实现 reducePlanGenerated：PLANNING → EXECUTING，存储 plan
    - 实现 reduceToolResult：hasMore=true 保持 EXECUTING，hasMore=false → REFLECTING
    - 实现 reduceReflectionComplete：satisfied=true → RESPONDING，needsReplanning=true → PLANNING（最多 2 次修正循环）
    - 实现 reduceResponseGenerated：→ TERMINATED，done=true，存储 finalOutput
    - 实现 reduceBudgetExhausted：→ TERMINATED，done=true
    - 实现 reduceBlocked：CRITICAL → TERMINATED，HIGH → UNDERSTANDING，LOW/MEDIUM/null → 保持当前
    - 实现 reduceErrorRecovery：recoverable=false → TERMINATED，recoverable=true → 保持当前
    - 实现 reduceSubAgentResult：→ REFLECTING
    - 每次状态转换递增 stepCount，追加 StepRecord 到 steps
    - _Requirements: 5.1~5.16, 6.1~6.5_

  - [ ]* 7.2 编写 StateReducer 属性测试
    - 创建 `AgentTestGenerators` 随机数据生成器（randomRequest、randomState、randomActionForPhase、randomAction、randomBudget）
    - **Property 9: StateReducer stepCount 单调递增不变量**
    - **Property 10: StateReducer TERMINATED 吸收态不变量**
    - **Property 11: StateReducer steps 列表只增不减不变量**
    - **Property 12: StateReducer 阶段转换合法性不变量**
    - **Property 13: StateReducer 纯函数不变量**
    - **Property 14: StateReducer 阶段转换正确性**
    - **Property 15: StateReducer 终止动作正确性**
    - **Validates: Requirements 5.3~5.16, 6.1~6.5**

- [ ] 8. 实现 TokenBudget 和 AssembledContext
  - [ ] 8.1 创建 TokenBudget record
    - 创建 `com.lifepilot.agent.context.TokenBudget` record，包含六个槽位预算上限和五个槽位实际消耗值
    - 实现 allocate(phase, totalTokens) 静态方法，按阶段预定义比例分配
    - 实现 totalBudget()、totalConsumed()、isOverBudget() 方法
    - _Requirements: 10.1~10.5_

  - [ ] 8.2 创建 AssembledContext record
    - 创建 `com.lifepilot.agent.context.AssembledContext` record（systemPrompt、userPrompt、retrievedMemories、tokenBudget）
    - 紧凑构造器中对 retrievedMemories 执行 List.copyOf()
    - 实现 totalTokens() 方法
    - _Requirements: 11.1~11.3_

  - [ ]* 8.3 编写 TokenBudget 和 AssembledContext 属性测试
    - **Property 23: TokenBudget 阶段分配比例正确性**
    - **Property 26: TokenBudget 算术一致性**
    - **Property 1: Record 集合字段防御性拷贝不变量（AssembledContext 部分）**
    - **Validates: Requirements 9.2~9.7, 10.2~10.5, 11.2~11.3**

- [ ] 9. 实现 ContextAssembler 基础版上下文组装器
  - [ ] 9.1 创建 ContextAssembler 类
    - 创建 `com.lifepilot.agent.context.ContextAssembler`，使用 @Service 注解
    - 注入 AgentConfigProperties
    - 实现 assemble(state) 方法，根据 AgentPhase 分配 Token 预算，组装 SystemPrompt 和 UserPrompt
    - 实现 buildSystemPrompt(phase) 方法，构建阶段专用 System Prompt（角色定义、行为约束、阶段指令）
    - 实现 buildUserPrompt(state) 方法，融合用户原始消息、当前阶段指令、已执行步骤摘要和预算剩余信息
    - 基础版记忆检索槽位返回空列表
    - _Requirements: 9.1~9.10_

  - [ ]* 9.2 编写 ContextAssembler 属性测试
    - **Property 24: ContextAssembler 基础版记忆检索为空**
    - **Property 25: ContextAssembler 上下文包含必要信息**
    - **Validates: Requirements 9.8~9.10**

- [ ] 10. 实现 TraceStep 和 TraceRecorder
  - [ ] 10.1 创建 TraceStep record
    - 创建 `com.lifepilot.agent.trace.TraceStep` record（traceId、stepIndex、phaseBefore、phaseAfter、action、toolId、toolInput、toolOutput、blocked、blockReason、tokensUsed、latencyMs、timestamp）
    - 使用 @Builder(toBuilder=true) 注解
    - _Requirements: 8.4_

  - [ ] 10.2 创建 TraceRecorder 类
    - 创建 `com.lifepilot.agent.trace.TraceRecorder`，使用 @Service 注解
    - 注入 JdbcTemplate 和 ObjectMapper
    - 使用 ConcurrentHashMap 缓存内存中的轨迹上下文（TraceContext 内部类）
    - 实现 startTrace(traceId, sessionId, userMessage) 方法
    - 实现 recordStep(traceContext, traceStep) 方法
    - 实现 endTrace(traceContext, finalOutput, success, errorMessage, terminationReason) 方法
    - 实现 persistTrace(traceId) 方法，写入 agent_traces 和 agent_trace_steps 表
    - 实现 findTrace(traceId) 和 findTracesBySession(sessionId) 查询方法
    - 持久化失败时记录 WARN 日志，不抛出异常
    - _Requirements: 8.1~8.8, 19.4_

  - [ ]* 10.3 编写 TraceRecorder 集成测试
    - **Property 21: Trace 持久化往返**
    - **Property 22: Trace 按会话查询过滤**
    - **Validates: Requirements 8.5~8.8**

- [ ] 11. 实现 SessionSnapshot、ConversationTurn 和 SessionManager
  - [ ] 11.1 创建 ConversationTurn 和 SessionSnapshot record
    - 创建 `com.lifepilot.agent.session.ConversationTurn` record（userMessage、agentResponse、toolsUsed、timestamp），紧凑构造器中 List.copyOf(toolsUsed)
    - 创建 `com.lifepilot.agent.session.SessionSnapshot` record（sessionId、channelId、recentTurns、mentionedEntities、lastActiveAt、totalTurns、totalTokensUsed），紧凑构造器中 List.copyOf()
    - 实现 isExpired(timeout) 方法
    - _Requirements: 13.4~13.6_

  - [ ] 11.2 创建 SessionManager 类
    - 创建 `com.lifepilot.agent.session.SessionManager`，使用 @Service 注解
    - 注入 JdbcTemplate、ObjectMapper、AgentConfigProperties
    - 实现 findSession(sessionId) 方法，返回 Optional<SessionSnapshot>
    - 实现 saveSession(state) 方法，仅保留最近 N 轮对话（默认 10 轮）
    - 实现 deleteSession(sessionId) 方法
    - 实现 cleanupExpiredSessions() 定时清理方法（@Scheduled）
    - 持久化失败时记录 WARN 日志，不抛出异常
    - _Requirements: 13.1~13.3, 13.7~13.8, 19.5_

  - [ ]* 11.3 编写 SessionManager 集成测试
    - **Property 29: Session 持久化往返**
    - **Property 30: SessionSnapshot.isExpired() 正确性**
    - **Property 31: Session 保存截断最近 N 轮**
    - **Validates: Requirements 13.1~13.8**

- [ ] 12. 实现 ActionParser LLM 输出解析器
  - [ ] 12.1 创建 ActionParser 类
    - 创建 `com.lifepilot.agent.ActionParser`，使用 @Service 注解
    - 注入 ObjectMapper
    - 实现 parse(phase, llmOutput) 方法，根据 AgentPhase 将 JSON 输出解析为对应 Action 类型
    - UNDERSTANDING → IntentUnderstood，PLANNING → PlanGenerated，REFLECTING → ReflectionComplete，RESPONDING → ResponseGenerated
    - 解析失败返回 Action.ErrorRecovery(LLM_PARSE_FAILURE, errorMessage, true, null)
    - 实现 serialize(action) 方法，将 Action 序列化为 JSON
    - 配置 Jackson 支持 sealed interface 多态序列化（@JsonTypeInfo + @JsonSubTypes 或自定义序列化器）
    - _Requirements: 12.1~12.7_

  - [ ]* 12.2 编写 ActionParser 属性测试
    - **Property 27: ActionParser 序列化/反序列化往返**
    - **Property 28: ActionParser 无效输入返回 ErrorRecovery**
    - **Validates: Requirements 12.1~12.7**

- [ ] 13. 实现 AgentToolProvider 接口和 NoOpAgentToolProvider
  - 创建 `com.lifepilot.agent.AgentToolProvider` 接口，定义 getToolCallbacks(state) 方法
  - 创建 `com.lifepilot.agent.NoOpAgentToolProvider` 空实现，返回 List.of()
  - _Requirements: 15.1~15.3_

- [ ] 14. Checkpoint — 核心组件层编译通过
  - 确保所有核心组件（StateReducer、ContextAssembler、TraceRecorder、SessionManager、ActionParser、AgentToolProvider）编译通过
  - 确保已有测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 15. 创建 SQLite 迁移脚本
  - [ ] 15.1 创建 V3__create_agent_sessions.sql
    - 创建 agent_sessions 表（id、channel_id、recent_turns_json、mentioned_entities_json、active_task_context、last_active_at、total_turns、total_tokens_used、archived、created_at、updated_at）
    - 创建 idx_agent_sessions_channel 和 idx_agent_sessions_active 索引
    - _Requirements: 16.1, 16.2, 16.7_

  - [ ] 15.2 创建 V4__create_agent_traces.sql
    - 创建 agent_traces 表（id、session_id、user_message、final_output、success、error_message、termination_reason、total_steps、total_tokens、duration_ms、model_id、parent_trace_id、depth、created_at），session_id 外键关联 agent_sessions(id)
    - 创建 idx_agent_traces_session 和 idx_agent_traces_time 索引
    - 创建 agent_trace_steps 表（id、trace_id、step_index、phase_before、phase_after、action_type、action_json、tool_id、tool_input_json、tool_output、success、blocked、block_reason、tokens_used、latency_ms、created_at），trace_id 外键关联 agent_traces(id)
    - 创建 idx_trace_steps_trace 索引
    - _Requirements: 16.3~16.7_

- [ ] 16. 实现 AgentConfigProperties 配置属性绑定
  - 创建 `com.lifepilot.agent.config.AgentConfigProperties`，使用 @ConfigurationProperties(prefix = "lifepilot.agent")
  - 定义嵌套 LoopConfig（maxIterations、sceneMapping）、BudgetConfig（defaultMaxTokens、defaultMaxSteps、defaultMaxDurationSeconds、subAgentBudgetRatio）、ContextConfig（maxContextTokens、outputReservedTokens）、SessionConfig（timeoutMinutes、maxRecentTurns、cleanupIntervalMs）
  - _Requirements: 18.1~18.6_

- [ ] 17. 实现 AgentAutoConfiguration 自动配置
  - [ ] 17.1 新增 LlmScene 场景常量
    - 在 `com.lifepilot.llm.LlmScene` 中新增 AGENT_REASONING、AGENT_TOOL_CALLING、AGENT_GENERATION 三个常量
    - 更新 all() 方法包含新增常量
    - _Requirements: 14.4_

  - [ ] 17.2 创建 AgentAutoConfiguration 类
    - 创建 `com.lifepilot.agent.config.AgentAutoConfiguration`，使用 @AutoConfiguration + @EnableConfigurationProperties + @ConditionalOnProperty
    - 注册 StateReducer、ContextAssembler、TraceRecorder、SessionManager、ActionParser、AgentToolProvider（NoOp）、AgentLoop 为 Spring Bean
    - 每个 Bean 使用 @ConditionalOnMissingBean 允许用户覆盖
    - 依赖 LlmRouter Bean
    - _Requirements: 17.1~17.5_

  - [ ] 17.3 更新 application.yml 配置
    - 在 application.yml 中添加 lifepilot.agent 配置段（enabled、loop、budget、context、session）
    - 在 application-test.yml 中添加 lifepilot.agent.enabled=false
    - _Requirements: 18.1~18.6_

  - [ ]* 17.4 编写 AgentAutoConfiguration 集成测试
    - 测试自动配置加载、Bean 注册
    - 测试 @ConditionalOnMissingBean 覆盖
    - 测试 enabled=false 禁用
    - 测试 YAML 配置绑定和默认值
    - _Requirements: 17.1~17.5, 18.1~18.6_

- [ ] 18. 实现 AgentLoop 核心控制循环
  - [ ] 18.1 创建 AgentLoop 类
    - 创建 `com.lifepilot.agent.AgentLoop`，使用 @Service 注解
    - 注入 StateReducer、ContextAssembler、LlmRouter、TraceRecorder、SessionManager、ActionParser、AgentToolProvider、AgentConfigProperties
    - 实现 run(request) 方法：
      - 初始化 AgentState（新会话 init / 已有会话 fromSession）
      - TraceRecorder.startTrace()
      - while 循环：更新已用时间 → 预算检查 → 上下文组装 → LLM 决策 → ActionParser 解析 → StateReducer 状态转换 → TraceRecorder 记录
      - 根据 AgentPhase 映射 LLM 场景（UNDERSTANDING/PLANNING/REFLECTING → agent-reasoning，EXECUTING → agent-tool-calling，RESPONDING → agent-generation）
      - 循环次数达到硬限制 50 次 → BudgetExhausted
      - Budget.exceeded() → BudgetExhausted
      - LlmUnavailableException → BudgetExhausted
      - 连续 3 次护栏阻断 → 强制终止
      - 循环完成后异步后处理（Virtual Thread）：SessionManager.saveSession() + TraceRecorder.persistTrace()
      - 不可恢复异常 → AgentResponse.error()
    - _Requirements: 14.1~14.12, 19.1~19.5_

  - [ ]* 18.2 编写 AgentLoop 集成测试
    - Mock LlmRouter，测试完整循环流程
    - 测试 Budget 耗尽终止
    - 测试硬限制终止
    - 测试 LLM 不可用降级
    - 测试连续护栏阻断终止
    - **Property 32: AgentPhase 到 LLM 场景映射完整性**
    - **Property 33: NoOpAgentToolProvider 返回空列表**
    - **Validates: Requirements 14.1~14.12, 15.2, 19.1~19.5**

- [ ] 19. Final Checkpoint — 所有测试通过
  - 确保所有代码编译通过（getDiagnostics 无错误）
  - 确保所有单元测试和集成测试通过
  - 确保 Flyway 迁移脚本执行成功
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 每个任务引用了具体的 Requirements 编号，确保需求可追溯
- Checkpoints（任务 6、14、19）确保增量验证，每个阶段编译通过
- 属性测试覆盖设计文档中的 33 个 Correctness Properties
- 提交格式遵循 Git 工作流规范：`feat(agent): 中文描述` / `test(agent): 中文描述`
- 不使用 jqwik，属性测试通过 JUnit 5 参数化测试 + AgentTestGenerators 随机数据生成器实现

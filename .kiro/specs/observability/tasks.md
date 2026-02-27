# Implementation Plan: 可观测性与护栏引擎完善

## Overview

将 LifePilot 现有的基础可观测性实现升级为架构文档定义的完整系统。按自底向上顺序：先建立数据库 Schema 和配置层，再实现核心数据模型（TraceStep sealed interface、GuardrailPolicy sealed interface、RiskLevel 统一），然后实现 DataRedactor 脱敏引擎、TraceRecorder 追踪记录器、TraceQuery 查询服务、GuardrailEngine 策略执行引擎，接着实现 Spring AI Advisor（TraceAdvisor、GuardrailAdvisor），再实现 TrajectoryEvaluator 轨迹评估，最后完成跨模块迁移适配和 Spring 自动配置。模块 20.5（Agentic Evals）已完成，本模块聚焦生产环境可观测性，不重复评估框架的工作。

## Tasks

- [x] 1. 数据库迁移与配置层
  - [x] 1.1 创建 Flyway V19 迁移脚本
    - 创建 `src/main/resources/db/migration/V19__create_observability_tables.sql`
    - 创建 traces、trace_steps、guardrail_logs、redaction_logs、evaluation_results 表
    - 创建 traces_fts FTS5 虚拟表及插入/删除/更新触发器
    - 创建所有必要索引（时间范围、会话 ID、成功状态、策略 ID、评分等）
    - 不创建 metrics_snapshots 表（可选功能，后续按需添加）
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.7, 11.8_

  - [x] 1.2 实现 ObservabilityProperties 配置属性类
    - 创建 `com.lifepilot.observability.config.ObservabilityProperties`
    - 实现嵌套类 Trace（enabled、recordPrompts、retentionDays、useScopedValue）
    - 实现嵌套类 Guardrail（enabled、ToolRisk、ContentSafety、BudgetLimit、RateLimit）
    - 实现嵌套类 Redaction（enabled、redactBeforeLlm、redactInTrace、redactInLog）
    - 实现嵌套类 Evaluation（enabled、onlineEvaluation、五维权重、passThreshold、attentionThreshold）
    - 更新 `application.yml` 声明 `lifepilot.observability.*` 所有配置项及默认值
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7_

- [x] 2. Checkpoint - 确保数据库迁移和配置层正确
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. 核心数据模型 — RiskLevel 统一与 Trace 类型层次
  - [x] 3.1 统一 RiskLevel 和 ApprovalMode 枚举
    - 创建 `com.lifepilot.observability.guardrail.RiskLevel` 枚举（LOW、MEDIUM、HIGH、CRITICAL）
    - 创建 `com.lifepilot.observability.guardrail.ApprovalMode` 枚举（AUTO、AUTO_WITH_AUDIT、USER_CONFIRM、USER_CONFIRM_WITH_VERIFICATION）
    - 修改所有引用 `com.lifepilot.tool.model.RiskLevel` 和 `com.lifepilot.agent.model.RiskLevel` 的代码，改为导入新的统一枚举
    - 标记旧的 RiskLevel 类 `@Deprecated`
    - _Requirements: 5.7, 5.8_

  - [x] 3.2 实现 TraceStep sealed interface 和五种 record 类型
    - 创建 `com.lifepilot.observability.trace.TraceStep` sealed interface，定义 stepIndex()、timestamp()、duration()、typeName() 方法
    - 实现 LlmCallStep record（providerId、modelId、scene、inputTokens、outputTokens、latency、cacheHit、temperature、finishReason）
    - 实现 ToolCallStep record（toolId、toolAction、inputJson、outputJson、duration、success、errorMessage、riskLevel）
    - 实现 GuardrailStep record（policyId、checkType、passed、reason、riskLevel、approvalMode）
    - 实现 StateTransitionStep record（phaseBefore、phaseAfter、actionType、actionSummary）
    - 实现 EvaluationStep record（五维评分 + overallScore + violations + suggestions）
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [x] 3.3 实现 TraceRecord、TraceMetadata 和辅助 record
    - 创建 `com.lifepilot.observability.trace.TraceRecord` record，包含 traceId、sessionId、goal、startTime、endTime、totalDuration、totalSteps、totalTokens、inputTokens、outputTokens、success、terminationReason、finalOutput、errorMessage、steps、metadata
    - 创建 `com.lifepilot.observability.trace.TraceMetadata` record（channelType、userId、clientVersion、tags）
    - 紧凑构造函数中使用 List.copyOf() 和 Map.copyOf() 保证不可变性
    - 创建 TraceQueryParams、TraceSummary、TraceDetail、ReplayStep、TokenConsumptionStats record
    - _Requirements: 1.7, 1.8, 1.9_

  - [ ]* 3.4 写属性测试：TraceRecord 和 TraceStep 集合字段不可变性
    - **Property 1: TraceRecord 和 TraceStep 集合字段不可变性**
    - **Validates: Requirements 1.9**

- [x] 4. 核心数据模型 — GuardrailPolicy 类型层次
  - [x] 4.1 实现 GuardrailResult sealed interface
    - 创建 `com.lifepilot.observability.guardrail.GuardrailResult` sealed interface
    - 实现 Passed record（policyId）
    - 实现 Blocked record（policyId、reason、riskLevel）
    - 实现 NeedsConfirmation record（policyId、message、approvalMode）
    - _Requirements: 5.6_

  - [x] 4.2 实现 GuardrailPolicy sealed interface 和五种策略 record
    - 创建 `com.lifepilot.observability.guardrail.GuardrailPolicy` sealed interface，定义 policyId()、enabled()、priority() 方法
    - 实现 ToolRiskPolicy record（policyId、enabled、priority、toolRiskMapping、defaultRiskLevel）
    - 实现 BudgetLimitPolicy record（policyId、enabled、priority、dailyTokenLimit）
    - 实现 ContentSafetyPolicy record（policyId、enabled、priority、blockedPatterns、sensitiveTopics）
    - 实现 RateLimitPolicy record（policyId、enabled、priority、maxCallsPerMinute、maxCallsPerHour）
    - 实现 DataRedactionPolicy record（policyId、enabled、priority）
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 4.3 实现自定义异常类型
    - 创建 `com.lifepilot.observability.trace.TraceDeserializationException`
    - 创建 `com.lifepilot.observability.trace.TraceNotFoundException`
    - 创建 `com.lifepilot.observability.trace.TraceExportException`
    - 创建 `com.lifepilot.observability.guardrail.GuardrailBlockedException`（含 policyId、reason、riskLevel）
    - 创建 `com.lifepilot.observability.guardrail.GuardrailConfirmationRequiredException`（含 policyId、message、approvalMode）
    - _Requirements: 7.5, 7.6_

- [-] 5. Checkpoint - 确保核心数据模型编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 6. DataRedactor 脱敏引擎
  - [~] 6.1 实现 RedactionRule record 和 DataRedactor
    - 创建 `com.lifepilot.observability.redactor.RedactionRule` record（name、description、pattern、replacement、priority、enabled）
    - 创建 `com.lifepilot.observability.redactor.RedactionAudit` record（redactedText、appliedRules）
    - 创建 `com.lifepilot.observability.redactor.DataRedactor`
    - 实现内置中国常见 PII 脱敏规则：手机号（138****5678）、身份证号（110***********1234）、银行卡号（6222****0123）、邮箱（u***@example.com）、IP 地址、API 密钥（sk-****）
    - 实现 redact() 方法，按优先级顺序应用所有启用的规则
    - 实现 redactWithAudit() 方法，执行脱敏并记录应用的规则
    - 实现 containsSensitiveData() 和 detectSensitiveTypes() 方法
    - 实现 registerRule() 和 unregisterRule() 动态规则管理
    - 正则匹配异常时记录 WARN 日志，跳过该规则继续应用其他规则
    - 标记旧的 `com.lifepilot.interaction.middleware.audit.DataRedactor` 为 `@Deprecated`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9_

  - [ ]* 6.2 写属性测试：DataRedactor 规则优先级排序
    - **Property 19: DataRedactor 规则优先级排序**
    - **Validates: Requirements 8.3**

  - [ ]* 6.3 写属性测试：DataRedactor redactWithAudit 一致性
    - **Property 20: DataRedactor redactWithAudit 一致性**
    - **Validates: Requirements 8.4**

  - [ ]* 6.4 写属性测试：DataRedactor containsSensitiveData 元变换属性
    - **Property 21: DataRedactor containsSensitiveData 元变换属性**
    - **Validates: Requirements 8.5**

  - [ ]* 6.5 写属性测试：DataRedactor 动态规则注册
    - **Property 22: DataRedactor 动态规则注册**
    - **Validates: Requirements 8.7**

  - [ ]* 6.6 写属性测试：DataRedactor 敏感数据移除
    - **Property 23: DataRedactor 敏感数据移除**
    - **Validates: Requirements 8.9**

- [ ] 7. TraceStepSerializer 序列化器
  - [~] 7.1 实现 TraceStepSerializer
    - 创建 `com.lifepilot.observability.trace.TraceStepSerializer`
    - 实现 serialize(TraceStep) → JSON 字符串，使用 Jackson ObjectMapper
    - 实现 deserialize(String json, String stepType) → TraceStep，根据 step_type 确定具体 record 类型
    - 反序列化失败时抛出 TraceDeserializationException
    - _Requirements: 2.8, 2.9_

  - [ ]* 7.2 写属性测试：TraceStep 序列化 round-trip
    - **Property 2: TraceStep 序列化 round-trip**
    - **Validates: Requirements 2.8, 2.9**

- [ ] 8. TraceRecorder 追踪记录器
  - [~] 8.1 实现 TraceRecorder 接口和 TraceContext
    - 创建 `com.lifepilot.observability.trace.TraceRecorder` 接口，定义 startTrace、recordStep、endTrace、onStep、currentContext 方法
    - 创建 `com.lifepilot.observability.trace.TraceContext` 可变工作对象
    - _Requirements: 2.1, 2.5_

  - [~] 8.2 实现 TraceContextPropagator
    - 创建 `com.lifepilot.observability.trace.TraceContextPropagator`
    - 实现 ScopedValue 模式（Virtual Thread 自动传播）
    - 实现 ThreadLocal 兼容模式（传统线程池场景）
    - 根据 ObservabilityProperties.trace.useScopedValue 配置选择模式
    - _Requirements: 2.5, 2.6_

  - [~] 8.3 实现 TraceRecorderImpl
    - 创建 `com.lifepilot.observability.trace.TraceRecorderImpl`
    - 注入 JdbcTemplate、TraceStepSerializer、TraceContextPropagator、DataRedactor、ObservabilityProperties
    - 实现 startTrace()：创建 TraceContext，通过 Propagator 绑定到当前线程
    - 实现 recordStep()：通过 DataRedactor 脱敏 LlmCallStep 和 ToolCallStep 的敏感字段，添加到 TraceContext，触发 onStep 回调
    - 实现 endTrace()：构建 TraceRecord，通过 Virtual Thread 异步写入 SQLite（traces + trace_steps 表）
    - SQLite 写入失败时记录 WARN 日志，不阻塞 Agent 主循环
    - 序列化失败时记录 ERROR 日志，写入空 JSON `{}`
    - _Requirements: 2.2, 2.3, 2.4, 2.7_

  - [ ]* 8.4 写属性测试：recordStep 自动脱敏
    - **Property 3: recordStep 自动脱敏**
    - **Validates: Requirements 2.3**

  - [ ]* 8.5 写属性测试：onStep 回调触发
    - **Property 4: onStep 回调触发**
    - **Validates: Requirements 2.7**

- [ ] 9. Checkpoint - 确保 Trace 核心层测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 10. TraceQuery 轨迹查询服务
  - [~] 10.1 实现 TraceQuery 多维度查询与分页
    - 创建 `com.lifepilot.observability.trace.TraceQuery`
    - 注入 JdbcTemplate、TraceStepSerializer、ObservabilityProperties
    - 实现 query(TraceQueryParams) → List<TraceSummary>，支持按时间范围、会话 ID、成功/失败状态、最小步骤数、最小 Token 数过滤，支持 limit + offset 分页
    - 实现 searchByKeyword(String keyword, int limit) → List<TraceSummary>，使用 FTS5 全文搜索 goal 和 finalOutput
    - 实现 getDetail(String traceId) → TraceDetail，返回包含完整步骤列表的详情，Trace 不存在时抛出 TraceNotFoundException
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

  - [~] 10.2 实现 TraceQuery 回放、导出与统计
    - 实现 replay(String traceId) → List<ReplayStep>，将每个步骤转换为包含人类可读摘要的 ReplayStep，Trace 不存在时抛出 TraceNotFoundException
    - 实现 exportAsJson(String traceId) → String，将完整 Trace 序列化为 JSON 字符串，失败时抛出 TraceExportException
    - 实现 getTokenStats(Instant start, Instant end) → TokenConsumptionStats，返回时间范围内的 Token 消耗统计（总量、平均值、最大值、成功率、平均耗时）
    - _Requirements: 4.5, 4.6, 4.7_

  - [ ]* 10.3 写属性测试：TraceQuery 多维度过滤与分页
    - **Property 5: TraceQuery 多维度过滤与分页**
    - **Validates: Requirements 4.1, 4.3**

  - [ ]* 10.4 写属性测试：TraceQuery FTS5 全文搜索
    - **Property 6: TraceQuery FTS5 全文搜索**
    - **Validates: Requirements 4.2**

  - [ ]* 10.5 写属性测试：TraceQuery getDetail 持久化 round-trip
    - **Property 7: TraceQuery getDetail 持久化 round-trip**
    - **Validates: Requirements 4.4**

  - [ ]* 10.6 写属性测试：TraceQuery replay 步骤完整性
    - **Property 8: TraceQuery replay 步骤完整性**
    - **Validates: Requirements 4.5**

  - [ ]* 10.7 写属性测试：TraceQuery exportAsJson 可解析性
    - **Property 9: TraceQuery exportAsJson 可解析性**
    - **Validates: Requirements 4.6**

  - [ ]* 10.8 写属性测试：TraceQuery Token 统计聚合正确性
    - **Property 10: TraceQuery Token 统计聚合正确性**
    - **Validates: Requirements 4.7**

- [ ] 11. Checkpoint - 确保 TraceQuery 层测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 12. GuardrailEngine 策略执行引擎
  - [~] 12.1 实现 GuardrailEngine
    - 创建 `com.lifepilot.observability.guardrail.GuardrailEngine`
    - 注入 JdbcTemplate、TraceRecorder、ObservabilityProperties
    - 使用 ConcurrentHashMap 管理策略注册表，支持 registerPolicy / unregisterPolicy
    - 使用 ConcurrentHashMap 管理工具白名单，支持 addAllowedTools / removeAllowedTools
    - 实现 checkToolCall(ToolContract, ToolInput)：按优先级排序遍历启用的策略，短路逻辑（Blocked 或 NeedsConfirmation 立即返回）
    - 实现 checkInput(String content)：检查 LLM 输入内容安全（ContentSafetyPolicy）
    - 实现 checkOutput(String content)：检查 LLM 输出内容合规（ContentSafetyPolicy）
    - 检查完成后将结果记录为 GuardrailStep 到当前 TraceContext
    - Blocked 或 NeedsConfirmation 结果写入 guardrail_logs 审计日志表
    - 策略执行异常时 fail-open（记录 ERROR 日志，视为 Passed）
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [ ]* 12.2 写属性测试：ToolRiskPolicy 风险等级到审批模式映射
    - **Property 11: ToolRiskPolicy 风险等级到审批模式映射**
    - **Validates: Requirements 5.2**

  - [ ]* 12.3 写属性测试：BudgetLimitPolicy 阈值检查
    - **Property 12: BudgetLimitPolicy 阈值检查**
    - **Validates: Requirements 5.3**

  - [ ]* 12.4 写属性测试：ContentSafetyPolicy 模式匹配
    - **Property 13: ContentSafetyPolicy 模式匹配**
    - **Validates: Requirements 5.4**

  - [ ]* 12.5 写属性测试：RateLimitPolicy 速率检查
    - **Property 14: RateLimitPolicy 速率检查**
    - **Validates: Requirements 5.5**

  - [ ]* 12.6 写属性测试：GuardrailEngine 动态注册表一致性
    - **Property 15: GuardrailEngine 动态注册表一致性**
    - **Validates: Requirements 6.1, 6.6**

  - [ ]* 12.7 写属性测试：GuardrailEngine 短路求值
    - **Property 16: GuardrailEngine 短路求值**
    - **Validates: Requirements 6.2**

  - [ ]* 12.8 写属性测试：GuardrailEngine 审计日志记录
    - **Property 17: GuardrailEngine 审计日志记录**
    - **Validates: Requirements 6.5**

- [ ] 13. Spring AI Advisor 集成
  - [~] 13.1 实现 TraceAdvisor
    - 创建 `com.lifepilot.observability.trace.TraceAdvisor`，实现 Spring AI CallAdvisor 接口
    - 优先级设为 HIGHEST_PRECEDENCE + 100（在 GuardrailAdvisor 之后执行）
    - adviseRequest 阶段记录开始时间
    - adviseResponse 阶段从 ChatClientResponse 提取 Token 使用量、缓存命中状态、完成原因，构建 LlmCallStep 并通过 TraceRecorder.recordStep() 记录
    - LLM 调用异常时记录异常信息到 LlmCallStep 并重新抛出
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [~] 13.2 实现 GuardrailAdvisor
    - 创建 `com.lifepilot.observability.guardrail.GuardrailAdvisor`，实现 Spring AI CallAdvisor 接口
    - 优先级设为 HIGHEST_PRECEDENCE（在所有其他 Advisor 之前执行）
    - adviseRequest 阶段调用 GuardrailEngine.checkInput() 检查输入内容安全
    - adviseResponse 阶段调用 GuardrailEngine.checkOutput() 检查输出内容合规
    - Blocked 结果抛出 GuardrailBlockedException
    - NeedsConfirmation 结果抛出 GuardrailConfirmationRequiredException
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [ ]* 13.3 写属性测试：GuardrailAdvisor 异常映射
    - **Property 18: GuardrailAdvisor 异常映射**
    - **Validates: Requirements 7.5, 7.6**

- [ ] 14. Checkpoint - 确保 Advisor 层测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 15. TrajectoryEvaluator 轨迹评估引擎
  - [~] 15.1 实现 EvaluationResult record
    - 创建 `com.lifepilot.observability.evaluation.EvaluationResult` record（traceId、evaluatedAt、五维评分、overallScore、actualSteps、actualTokens、violations、suggestions）
    - 紧凑构造函数中使用 List.copyOf() 保证不可变性
    - _Requirements: 9.7_

  - [~] 15.2 实现 TrajectoryEvaluator
    - 创建 `com.lifepilot.observability.evaluation.TrajectoryEvaluator`
    - 注入 JdbcTemplate、ObservabilityProperties
    - 实现五维评估逻辑：工具选择正确性（失败/重复调用降分）、参数合法性（JSON Schema 校验）、步骤效率（步骤数比率）、策略合规性（护栏拦截降分）、Token 效率（Token 消耗比率）
    - 实现 evaluateOnline(TraceRecord)：评估并持久化到 evaluation_results 表
    - 实现 evaluateOffline(TraceRecord)：评估但不持久化（用于历史重评估）
    - 综合评分 = 各维度评分 × 对应权重之和（权重从 ObservabilityProperties 读取）
    - 评估异常时记录 ERROR 日志，返回默认评分（所有维度 0.5）
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.8, 9.9_

  - [ ]* 15.3 写属性测试：TrajectoryEvaluator 缺陷惩罚
    - **Property 24: TrajectoryEvaluator 缺陷惩罚**
    - **Validates: Requirements 9.4, 9.5, 9.6**

  - [ ]* 15.4 写属性测试：TrajectoryEvaluator 评分范围不变量
    - **Property 25: TrajectoryEvaluator 评分范围不变量**
    - **Validates: Requirements 9.8**

  - [ ]* 15.5 写属性测试：TrajectoryEvaluator 确定性
    - **Property 26: TrajectoryEvaluator 确定性**
    - **Validates: Requirements 9.9**

- [ ] 16. Checkpoint - 确保评估引擎测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 17. 跨模块迁移适配
  - [~] 17.1 适配 AgentLoop 使用新 TraceRecorder 接口
    - 修改 `com.lifepilot.agent.AgentLoop` 的 TraceRecorder 导入，从 `com.lifepilot.agent.trace.TraceRecorder` 改为 `com.lifepilot.observability.trace.TraceRecorder`
    - 修改 `reduceAndRecord()` 方法，构建 `StateTransitionStep` 替代旧的 `TraceStep`
    - 修改 TraceContext 导入为 `com.lifepilot.observability.trace.TraceContext`
    - 确保 AgentLoop 的所有 TraceRecorder 调用（startTrace、recordStep、endTrace）与新接口签名一致
    - _Requirements: 2.1, 2.2_

  - [~] 17.2 标记旧类 @Deprecated 并更新引用
    - 标记 `com.lifepilot.agent.trace.TraceRecorder`（旧具体类）为 `@Deprecated`
    - 标记 `com.lifepilot.agent.trace.TraceStep`（旧简单 record）为 `@Deprecated`
    - 标记 `com.lifepilot.guardrail.GuardrailPolicy`（旧具体类）为 `@Deprecated`
    - 标记 `com.lifepilot.guardrail.GuardrailResult`（旧简单 record）为 `@Deprecated`
    - 标记 `com.lifepilot.interaction.middleware.audit.DataRedactor`（旧临时实现）为 `@Deprecated`
    - 更新 `com.lifepilot.interaction.web.service.TraceQueryService` 使用新的 TraceQuery
    - 更新所有引用旧 RiskLevel 的代码，改为导入 `com.lifepilot.observability.guardrail.RiskLevel`
    - _Requirements: 5.7_

- [ ] 18. ObservabilityAutoConfiguration 自动配置
  - [~] 18.1 实现 ObservabilityAutoConfiguration
    - 创建 `com.lifepilot.observability.config.ObservabilityAutoConfiguration`
    - 注册 Trace 相关 Bean：TraceStepSerializer、TraceContextPropagator、TraceRecorderImpl、TraceQuery、TraceAdvisor
    - 注册护栏相关 Bean：DataRedactor、GuardrailEngine、GuardrailAdvisor
    - 注册评估相关 Bean：TrajectoryEvaluator
    - 使用 `@ConditionalOnProperty` 按模块开关控制 Bean 注册（trace.enabled、guardrail.enabled、evaluation.enabled）
    - 注册到 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6_

- [ ] 19. 集成测试
  - [~] 19.1 写集成测试：TraceRecorderImpl → SQLite 写入读取 round-trip
    - `@SpringBootTest` + 内存 SQLite
    - 验证 startTrace → recordStep（多种步骤类型）→ endTrace → TraceQuery.getDetail() 完整流程
    - 验证步骤数量、类型、字段值与原始记录一致
    - 验证 DataRedactor 脱敏在持久化链路中生效
    - _Requirements: 2.2, 2.3, 2.4, 4.4_

  - [~] 19.2 写集成测试：GuardrailEngine 审计日志持久化
    - `@SpringBootTest` + 内存 SQLite
    - 注册策略 → 触发 Blocked/NeedsConfirmation → 验证 guardrail_logs 表有对应记录
    - _Requirements: 6.4, 6.5_

  - [~] 19.3 写集成测试：ObservabilityAutoConfiguration 条件 Bean 注册
    - 测试 enabled=true 时所有 Bean 正常注册
    - 测试 trace.enabled=false 时 Trace 相关 Bean 不注册
    - 测试 guardrail.enabled=false 时护栏相关 Bean 不注册
    - 测试 evaluation.enabled=false 时评估相关 Bean 不注册
    - _Requirements: 13.4, 13.5, 13.6_

  - [~] 19.4 写集成测试：Flyway V19 迁移脚本验证
    - 验证迁移脚本在干净数据库上执行成功
    - 验证所有表和索引创建正确
    - 验证 FTS5 虚拟表和触发器工作正常
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.7, 11.8_

- [ ] 20. Final checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests use jqwik（Java 属性测试框架，JUnit 5 原生集成），每个 property 最少 100 次迭代
- 遵循编码规范：中文注释/Javadoc/测试方法名，@author zsg，@since 文件创建日期
- Flyway 迁移脚本版本号 V19，紧接已有的 V18
- MetricsCollector 和 ObservabilityEndpoint 为可选功能（Property 27、28），本 tasks 不包含，时间允许时作为后续 spec 补充
- 模块 20.5（Agentic Evals）已完成，本模块的 TrajectoryEvaluator 聚焦生产环境在线/离线评估，与 Agentic Evals 的开发期评估互补
- 所有单元测试 Mock JdbcTemplate、TraceRecorder、LlmRouter，不依赖外部服务
- 集成测试使用 `@SpringBootTest` + 内存 SQLite
- 已知的预存在集成测试失败（sync、knowledge、gateway、app-launch-modes、LifePilotApplicationTest）与本模块无关，不影响本模块测试
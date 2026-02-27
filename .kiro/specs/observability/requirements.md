# 需求文档：可观测性与护栏引擎完善

## 简介

本模块（模块 20）完善 LifePilot 的可观测性与护栏引擎，将现有的基础实现升级为架构文档中定义的完整系统。核心目标是实现 AI Agent 的四支柱可观测性模型：决策轨迹追踪（Traces）、运行指标（Metrics）、护栏审计（Guardrails）、轨迹评估（Evaluation）。通过 Spring AI Advisor 模式实现零侵入的横切注入，通过 DataRedactor 实现全链路敏感数据脱敏。

参考文档：
- 架构设计：#[[file:docs/architecture/observability.md]]
- 特性设计：#[[file:docs/features/observability.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## 术语表

- **TraceRecorder**：追踪记录器，管理 Trace 的生命周期（开始-记录-结束），将决策轨迹持久化到 SQLite
- **TraceStep**：追踪步骤 sealed interface，包含 LlmCallStep、ToolCallStep、GuardrailStep、StateTransitionStep、EvaluationStep 五种类型
- **TraceRecord**：顶层追踪记录 record，代表一次完整的 Agent 执行，包含所有步骤和聚合指标
- **TraceContext**：追踪上下文，在调用链中传播的可变工作对象，通过 ScopedValue 在 Virtual Thread 中传播
- **TraceQuery**：轨迹查询服务，支持多维度查询、轨迹回放、JSON 导出、Token 消耗统计
- **TraceAdvisor**：Spring AI CallAdvisor 实现，自动记录每次 LLM 调用的完整轨迹，零侵入
- **TrajectoryEvaluator**：轨迹评估器，对完整的 Agent 执行轨迹进行五维评估（工具选择、参数合法性、步骤效率、策略合规、Token 效率）
- **GuardrailEngine**：护栏执行引擎，管理策略注册表，按优先级执行策略检查，记录审计日志
- **GuardrailPolicy**：护栏策略 sealed interface，包含 ToolRiskPolicy、BudgetLimitPolicy、ContentSafetyPolicy、RateLimitPolicy、DataRedactionPolicy 五种策略类型
- **GuardrailResult**：护栏检查结果 sealed interface，包含 Passed、Blocked、NeedsConfirmation 三种结果
- **GuardrailAdvisor**：Spring AI CallAdvisor 实现，在 LLM 调用前后自动执行护栏检查（输入安全、输出合规）
- **DataRedactor**：敏感数据脱敏器，基于 RedactionRule 规则系统，支持手机号、身份证号、银行卡号、邮箱、IP 地址、API 密钥等中国常见 PII 类型的自动脱敏
- **RedactionRule**：脱敏规则 record，定义一种敏感数据的匹配模式和替换逻辑
- **MetricsCollector**：指标收集器，收集 Agent/LLM/Tool/Memory 维度的运行指标，定期生成快照持久化到 SQLite
- **MetricsSnapshot**：指标快照 record，某一时刻的完整指标数据
- **ObservabilityProperties**：可观测性配置属性，绑定 `lifepilot.observability` 前缀下的所有配置项
- **RiskLevel**：风险等级枚举，LOW / MEDIUM / HIGH / CRITICAL
- **ApprovalMode**：审批模式枚举，AUTO / AUTO_WITH_AUDIT / USER_CONFIRM / USER_CONFIRM_WITH_VERIFICATION
- **TraceStepSerializer**：步骤序列化器，将 TraceStep sealed interface 序列化为 JSON 并反序列化回具体类型

## 需求

### 需求 1：增强 Trace 数据模型

**用户故事：** 作为开发者，我希望 Trace 系统使用 sealed interface 类型层次记录不同类型的步骤，以便通过编译时穷举检查确保每种步骤类型都被正确处理。

#### 验收标准

1. THE TraceStep SHALL 定义为 sealed interface，permits LlmCallStep、ToolCallStep、GuardrailStep、StateTransitionStep、EvaluationStep 五种 record 类型
2. THE LlmCallStep SHALL 记录 providerId、modelId、scene、inputTokens、outputTokens、latency、cacheHit、temperature、finishReason 字段，对齐 OpenTelemetry GenAI 语义约定
3. THE ToolCallStep SHALL 记录 toolId、toolAction、inputJson、outputJson、duration、success、errorMessage、riskLevel 字段
4. THE GuardrailStep SHALL 记录 policyId、checkType、passed、reason、riskLevel、approvalMode 字段
5. THE StateTransitionStep SHALL 记录 phaseBefore、phaseAfter、actionType、actionSummary 字段
6. THE EvaluationStep SHALL 记录五维评分（toolSelectionScore、parameterValidityScore、stepEfficiencyScore、policyComplianceScore、tokenEfficiencyScore）、overallScore、violations、suggestions 字段
7. THE TraceRecord SHALL 使用 record 定义，包含 traceId、sessionId、goal、startTime、endTime、totalDuration、totalSteps、totalTokens、inputTokens、outputTokens、success、terminationReason、finalOutput、errorMessage、steps 列表和 metadata 字段
8. THE TraceMetadata SHALL 记录 channelType、userId、clientVersion 和自定义 tags
9. WHEN TraceRecord 或 TraceStep 的集合字段被构造时，THE TraceRecord SHALL 使用 List.copyOf() 和 Map.copyOf() 保证不可变性

### 需求 2：增强 TraceRecorder 与 TraceContext

**用户故事：** 作为开发者，我希望 TraceRecorder 支持接口化设计、ScopedValue 上下文传播和异步持久化，以便在不阻塞 Agent 主循环的前提下完整记录决策轨迹。

#### 验收标准

1. THE TraceRecorder SHALL 定义为接口，包含 startTrace、recordStep、endTrace、onStep、currentContext 方法
2. THE TraceRecorderImpl SHALL 实现 TraceRecorder 接口，使用 SQLite 持久化 Trace 数据
3. WHEN recordStep 被调用时，THE TraceRecorderImpl SHALL 通过 DataRedactor 对步骤中的敏感数据执行脱敏（LlmCallStep 的 prompt 和 output、ToolCallStep 的 inputJson 和 outputJson）
4. WHEN endTrace 被调用时，THE TraceRecorderImpl SHALL 通过 Virtual Thread 异步将 TraceRecord 写入 SQLite，避免阻塞 Agent 主循环
5. THE TraceContext SHALL 通过 ScopedValue 在 Virtual Thread 中传播，支持自动传播到子线程
6. THE TraceContextPropagator SHALL 提供 ScopedValue 模式和 ThreadLocal 兼容模式两种传播策略
7. WHEN 步骤回调通过 onStep 注册后，THE TraceRecorderImpl SHALL 在每次 recordStep 时触发回调，用于实时推送执行进度到前端
8. THE TraceStepSerializer SHALL 将 TraceStep sealed interface 序列化为 JSON 存储到 detail_json 列，反序列化时根据 step_type 列确定具体类型
9. FOR ALL TraceStep 实例，序列化后再反序列化 SHALL 产生等价的对象（round-trip 属性）

### 需求 3：TraceAdvisor — Spring AI Advisor 集成

**用户故事：** 作为开发者，我希望 LLM 调用的轨迹记录通过 Spring AI Advisor 自动注入，以便业务代码（AgentLoop）完全不感知可观测性逻辑。

#### 验收标准

1. THE TraceAdvisor SHALL 实现 Spring AI 的 CallAdvisor 接口
2. WHEN LLM 调用发生时，THE TraceAdvisor SHALL 自动记录一个 LlmCallStep，包含 providerId、modelId、inputTokens、outputTokens、latency、cacheHit、temperature、finishReason
3. THE TraceAdvisor SHALL 从 ChatClientResponse 中提取 Token 使用量、缓存命中状态和完成原因
4. THE TraceAdvisor SHALL 的优先级为 HIGHEST_PRECEDENCE + 100，确保在 GuardrailAdvisor 之后执行
5. IF LLM 调用抛出异常，THEN THE TraceAdvisor SHALL 记录异常信息到 LlmCallStep 并重新抛出异常

### 需求 4：TraceQuery — 轨迹查询与回放

**用户故事：** 作为开发者，我希望能够按多维度查询历史轨迹、回放完整决策过程、导出 JSON 和查看 Token 消耗统计，以便调试和分析 Agent 行为。

#### 验收标准

1. THE TraceQuery SHALL 支持按时间范围、会话 ID、成功/失败状态、最小步骤数、最小 Token 消耗进行多维度查询
2. THE TraceQuery SHALL 支持 FTS5 全文关键词搜索（搜索 goal 和 finalOutput 字段）
3. THE TraceQuery SHALL 支持分页查询（limit + offset）
4. THE TraceQuery SHALL 提供 getDetail 方法，返回包含完整步骤列表的 TraceDetail
5. THE TraceQuery SHALL 提供 replay 方法，将历史 Trace 的每个步骤转换为包含人类可读摘要的 ReplayStep 列表
6. THE TraceQuery SHALL 提供 exportAsJson 方法，将完整 Trace 序列化为 JSON 字符串
7. THE TraceQuery SHALL 提供 getTokenStats 方法，返回指定时间范围内的 Token 消耗统计（总量、平均值、最大值、成功率、平均耗时）

### 需求 5：GuardrailPolicy 重构为 sealed interface

**用户故事：** 作为开发者，我希望护栏策略使用 sealed interface 类型层次定义，以便通过编译时穷举检查确保每种策略类型都被正确处理。

#### 验收标准

1. THE GuardrailPolicy SHALL 重构为 sealed interface，permits ToolRiskPolicy、BudgetLimitPolicy、ContentSafetyPolicy、RateLimitPolicy、DataRedactionPolicy 五种 record 类型
2. THE ToolRiskPolicy SHALL 支持按工具 ID 映射风险等级，并根据风险等级决定审批模式
3. THE BudgetLimitPolicy SHALL 支持每日 Token 上限检查
4. THE ContentSafetyPolicy SHALL 支持正则模式阻断和敏感话题检测
5. THE RateLimitPolicy SHALL 支持每分钟和每小时调用次数限制
6. THE GuardrailResult SHALL 重构为 sealed interface，permits Passed、Blocked、NeedsConfirmation 三种 record 类型
7. THE RiskLevel SHALL 统一为 `com.lifepilot.observability.guardrail` 包下的单一枚举，包含 LOW、MEDIUM、HIGH、CRITICAL 四个等级
8. THE ApprovalMode SHALL 定义为枚举，包含 AUTO、AUTO_WITH_AUDIT、USER_CONFIRM、USER_CONFIRM_WITH_VERIFICATION 四种模式

### 需求 6：GuardrailEngine — 策略执行引擎

**用户故事：** 作为开发者，我希望护栏引擎统一管理策略注册表，按优先级执行策略检查，并将检查结果记录到 Trace 和审计日志。

#### 验收标准

1. THE GuardrailEngine SHALL 管理策略注册表，支持运行时动态注册和注销策略
2. WHEN checkToolCall 被调用时，THE GuardrailEngine SHALL 按短路逻辑遍历所有启用的策略（任何策略阻断或需要确认时立即返回）
3. THE GuardrailEngine SHALL 提供 checkInput 方法检查 LLM 输入内容安全，提供 checkOutput 方法检查 LLM 输出内容合规
4. WHEN 护栏检查完成时，THE GuardrailEngine SHALL 将检查结果记录为 GuardrailStep 到当前 TraceContext
5. WHEN 护栏检查结果为 Blocked 或 NeedsConfirmation 时，THE GuardrailEngine SHALL 将事件写入 guardrail_logs 审计日志表
6. THE GuardrailEngine SHALL 支持工具白名单动态管理（addAllowedTools / removeAllowedTools）

### 需求 7：GuardrailAdvisor — Spring AI Advisor 护栏

**用户故事：** 作为开发者，我希望护栏检查通过 Spring AI Advisor 自动注入到 LLM 调用链路，以便在 LLM 调用前后自动执行输入安全和输出合规检查。

#### 验收标准

1. THE GuardrailAdvisor SHALL 实现 Spring AI 的 CallAdvisor 接口
2. THE GuardrailAdvisor SHALL 的优先级为 HIGHEST_PRECEDENCE，确保在所有其他 Advisor 之前执行
3. WHEN LLM 调用前，THE GuardrailAdvisor SHALL 检查用户输入内容安全（ContentSafetyPolicy）
4. WHEN LLM 调用后，THE GuardrailAdvisor SHALL 检查 LLM 输出内容合规（ContentSafetyPolicy）
5. IF 输入或输出被护栏阻断，THEN THE GuardrailAdvisor SHALL 抛出 GuardrailBlockedException，包含阻断原因和策略 ID
6. IF 操作需要用户确认，THEN THE GuardrailAdvisor SHALL 抛出 GuardrailConfirmationRequiredException，包含确认详情

### 需求 8：DataRedactor 增强

**用户故事：** 作为开发者，我希望 DataRedactor 基于可扩展的 RedactionRule 规则系统实现，支持自定义规则注册、敏感数据检测和脱敏审计日志。

#### 验收标准

1. THE RedactionRule SHALL 使用 record 定义，包含 name、description、pattern（正则）、replacement、priority、enabled 字段
2. THE DataRedactor SHALL 内置中国常见 PII 脱敏规则：手机号（138****5678）、身份证号（110***********1234）、银行卡号（6222****0123）、邮箱（u***@example.com）、IP 地址、API 密钥（sk-****）
3. THE DataRedactor SHALL 提供 redact 方法，按优先级顺序应用所有启用的规则
4. THE DataRedactor SHALL 提供 redactWithAudit 方法，执行脱敏并将应用的规则记录到 redaction_logs 审计日志表
5. THE DataRedactor SHALL 提供 containsSensitiveData 方法检测文本是否包含敏感数据
6. THE DataRedactor SHALL 提供 detectSensitiveTypes 方法返回匹配的规则名称列表
7. THE DataRedactor SHALL 支持运行时动态注册和注销自定义脱敏规则
8. FOR ALL 不包含敏感数据的文本，redact 方法 SHALL 返回与输入相同的文本（幂等性）
9. FOR ALL 包含敏感数据的文本，redact 方法 SHALL 返回不包含原始敏感数据的文本

### 需求 9：轨迹评估引擎

**用户故事：** 作为开发者，我希望每次 Agent 执行完成后能自动评估决策轨迹的质量，以便发现工具选择不当、步骤冗余、Token 浪费等问题。

#### 验收标准

1. THE TrajectoryEvaluator SHALL 支持五维评估：工具选择正确性（30%）、参数合法性（20%）、步骤效率（20%）、策略合规性（20%）、Token 效率（10%）
2. THE TrajectoryEvaluator SHALL 支持在线评估模式（evaluateOnline），在 Agent 执行完成后自动评估并持久化结果
3. THE TrajectoryEvaluator SHALL 支持离线评估模式（evaluateOffline），对历史 Trace 重新评估
4. WHEN 工具调用失败或存在重复调用时，THE TrajectoryEvaluator SHALL 降低工具选择正确性评分
5. WHEN 工具调用参数不符合 JSON Schema 时，THE TrajectoryEvaluator SHALL 降低参数合法性评分
6. WHEN 存在护栏拦截事件时，THE TrajectoryEvaluator SHALL 降低策略合规性评分至低于满分
7. THE EvaluationResult SHALL 使用 record 定义，包含五维评分、综合评分、实际步骤数、实际 Token 数、违规项列表和建议列表
8. FOR ALL TraceRecord 输入，THE TrajectoryEvaluator SHALL 产生所有评分在 0.0 到 1.0 范围内的 EvaluationResult
9. FOR ALL 相同的 TraceRecord 输入，THE TrajectoryEvaluator SHALL 产生相同的评估结果（确定性）

### 需求 10：MetricsCollector — 指标收集与 Actuator 集成

**用户故事：** 作为开发者，我希望系统自动收集 Agent/LLM/Tool 维度的运行指标，定期生成快照并通过 Actuator 端点暴露，以便监控系统健康状态和 Token 消耗趋势。

#### 验收标准

1. THE MetricsCollector SHALL 收集 Agent 指标（总执行次数、成功/失败次数、平均步骤数、平均耗时）
2. THE MetricsCollector SHALL 收集 LLM 指标（总调用次数、输入/输出 Token 消耗、缓存命中率、延迟 P50/P99、错误次数、估算成本）
3. THE MetricsCollector SHALL 收集工具指标（总调用次数、成功/失败次数、护栏拦截次数、高风险操作次数）
4. THE MetricsCollector SHALL 支持按 Provider 分组的 Token 消耗统计和按工具分组的调用次数统计
5. THE MetricsCollector SHALL 使用 LongAdder 和 AtomicLong 保证线程安全
6. THE MetricsCollector SHALL 定期（可配置间隔，默认 5 分钟）生成 MetricsSnapshot 并持久化到 metrics_snapshots 表
7. THE ObservabilityEndpoint SHALL 通过 Spring Boot Actuator 端点（`/actuator/observability`）暴露当前指标快照
8. THE MetricsSnapshot SHALL 使用 record 定义，包含所有指标字段和计算方法（successRate、toolSuccessRate、totalTokens）

### 需求 11：Flyway 数据库迁移

**用户故事：** 作为开发者，我希望可观测性模块的数据库表通过 Flyway 迁移脚本创建，以便数据库 schema 版本可控。

#### 验收标准

1. THE Flyway 迁移脚本 SHALL 创建 traces 表，包含 trace_id（主键）、session_id、goal、start_time、end_time、total_duration_ms、total_steps、total_tokens、input_tokens、output_tokens、success、termination_reason、final_output、error_message、metadata_json、created_at 字段
2. THE Flyway 迁移脚本 SHALL 创建 trace_steps 表，包含 id（自增主键）、trace_id（外键）、step_index、step_type、timestamp、duration_ms、detail_json、created_at 字段
3. THE Flyway 迁移脚本 SHALL 创建 guardrail_logs 表，包含 id（自增主键）、trace_id、tool_id、policy_id、result_type、reason、risk_level、approval_mode、user_decision、created_at 字段
4. THE Flyway 迁移脚本 SHALL 创建 redaction_logs 表，包含 id（自增主键）、trace_id、context、applied_rules_json、created_at 字段
5. THE Flyway 迁移脚本 SHALL 创建 evaluation_results 表，包含 trace_id（唯一）、五维评分、综合评分、actual_steps、actual_tokens、violations_json、suggestions_json、created_at 字段
6. THE Flyway 迁移脚本 SHALL 创建 metrics_snapshots 表，包含所有指标字段和 tokens_by_provider_json、calls_by_tool_json 字段
7. THE Flyway 迁移脚本 SHALL 创建 traces_fts FTS5 虚拟表，索引 trace_id、goal、final_output 字段，并创建插入/删除/更新触发器同步 FTS5 索引
8. THE Flyway 迁移脚本 SHALL 为所有表创建必要的索引（时间范围、会话 ID、成功状态、策略 ID、评分等）

### 需求 12：ObservabilityProperties — 配置外部化

**用户故事：** 作为开发者，我希望可观测性模块的所有业务可调参数通过 `@ConfigurationProperties` 外部化，以便在不修改代码的情况下调整行为。

#### 验收标准

1. THE ObservabilityProperties SHALL 绑定 `lifepilot.observability` 前缀，使用嵌套 record 组织配置
2. THE TraceProperties SHALL 包含 enabled、recordPrompts、retentionDays、useScopedValue 配置项
3. THE GuardrailProperties SHALL 包含 enabled、toolRisk（默认风险等级 + 工具风险映射）、contentSafety（阻断模式 + 敏感话题）、budgetLimit（Token 上限 + 步骤上限 + 时长上限 + 每日 Token 上限）、rateLimit（每分钟/每小时调用上限）配置项
4. THE RedactionProperties SHALL 包含 enabled、redactBeforeLlm、redactInTrace、redactInLog 配置项
5. THE EvaluationProperties SHALL 包含 enabled、onlineEvaluation、五维权重、passThreshold、attentionThreshold 配置项
6. THE MetricsProperties SHALL 包含 enabled、snapshotIntervalSeconds、maxLatencySamples 配置项
7. THE application.yml SHALL 声明所有配置项及默认值

### 需求 13：ObservabilityAutoConfiguration — Spring 自动配置

**用户故事：** 作为开发者，我希望可观测性模块的所有 Bean 通过 Spring 自动配置注册，以便模块可以通过配置开关启用或禁用。

#### 验收标准

1. THE ObservabilityAutoConfiguration SHALL 注册 TraceRecorderImpl、TraceStepSerializer、TraceContextPropagator、TraceQuery、TraceAdvisor Bean
2. THE ObservabilityAutoConfiguration SHALL 注册 GuardrailEngine、GuardrailAdvisor、DataRedactor Bean
3. THE ObservabilityAutoConfiguration SHALL 注册 TrajectoryEvaluator、MetricsCollector、ObservabilityEndpoint Bean
4. WHEN `lifepilot.observability.trace.enabled` 为 false 时，THE ObservabilityAutoConfiguration SHALL 跳过 Trace 相关 Bean 的注册
5. WHEN `lifepilot.observability.guardrail.enabled` 为 false 时，THE ObservabilityAutoConfiguration SHALL 跳过护栏相关 Bean 的注册
6. WHEN `lifepilot.observability.evaluation.enabled` 为 false 时，THE ObservabilityAutoConfiguration SHALL 跳过评估相关 Bean 的注册

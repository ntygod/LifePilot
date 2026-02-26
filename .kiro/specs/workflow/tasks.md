# Implementation Plan: 工作流/自动化编排引擎

## Overview

在已完成的 Agent 引擎、Skill 系统、工具系统和主动推理引擎基础上，实现 YAML 声明式工作流编排引擎。按自底向上顺序：先实现数据模型（record / sealed interface / enum），再实现配置属性和表达式引擎，然后实现 YAML 解析/打印、变量上下文和持久化层，接着实现步骤执行器和核心引擎，最后实现注册中心、自动配置、触发器集成和崩溃恢复。

## Tasks

- [x] 1. 数据模型（record / sealed interface / enum）
  - [x] 1.1 实现 WorkflowState 和 StepState 枚举
    - 创建 `com.lifepilot.workflow.model.WorkflowState` 枚举（CREATED, RUNNING, PAUSED, WAITING, COMPLETED, FAILED, CANCELLED）
    - 创建 `com.lifepilot.workflow.model.StepState` 枚举（PENDING, RUNNING, COMPLETED, FAILED, SKIPPED）
    - _Requirements: 4.1_

  - [x] 1.2 实现 ErrorStrategy sealed interface
    - 创建 `com.lifepilot.workflow.model.ErrorStrategy` sealed interface
    - 实现四个 permits：Retry(maxAttempts, initialDelayMs, maxDelayMs)、Skip(reason)、Fail()、Compensate(compensationStep)
    - _Requirements: 5.1_

  - [x] 1.3 实现 WorkflowTrigger sealed interface
    - 创建 `com.lifepilot.workflow.model.WorkflowTrigger` sealed interface
    - 实现三个 permits：CronTrigger(cron)、EventTrigger(eventType)、ManualTrigger()
    - _Requirements: 6.1_

  - [x] 1.4 实现 WorkflowStep sealed interface
    - 创建 `com.lifepilot.workflow.model.WorkflowStep` sealed interface，定义 id()、name()、errorStrategy() 方法
    - 实现九个 permits：SkillStep、ToolStep、LlmStep、ConditionStep、LoopStep、ParallelStep、SubWorkflowStep、NoopStep、WaitStep
    - 每个 record 包含 design 文档中定义的字段
    - _Requirements: 2.1_

  - [x] 1.5 实现 WorkflowDefinition 和 WorkflowInputParam record
    - 创建 `com.lifepilot.workflow.model.WorkflowInputParam` record（name, type, required, defaultValue, description）
    - 创建 `com.lifepilot.workflow.model.WorkflowDefinition` record（id, name, description, version, enabled, triggers, inputs, steps, metadata），使用 @Builder(toBuilder = true)
    - 紧凑构造器：id/name/steps 非空校验，集合字段防御性拷贝
    - _Requirements: 1.1_

  - [x] 1.6 实现 WorkflowInstance record
    - 创建 `com.lifepilot.workflow.model.WorkflowInstance` record，使用 @Builder(toBuilder = true)
    - 包含字段：id, workflowId, state, context, currentStepIndex, startedAt, completedAt, failureReason, createdAt, updatedAt
    - _Requirements: 4.1, 4.2_

  - [x] 1.7 实现 StepLog record
    - 创建 `com.lifepilot.workflow.model.StepLog` record
    - 包含字段：id, instanceId, stepId, stepType, state, attempt, inputJson, outputJson, errorMessage, startedAt, completedAt, durationMs, createdAt
    - _Requirements: 5.8_

  - [x] 1.8 实现 Result sealed interface 和 WorkflowException sealed interface
    - 创建 `com.lifepilot.workflow.model.Result<T, E>` sealed interface，permits Ok(value) 和 Err(error)
    - 创建 `com.lifepilot.workflow.model.WorkflowException` sealed interface，permits ParseException、ExpressionException、ExecutionException、StateTransitionException
    - _Requirements: 1.2, 3.8_

- [x] 2. 配置属性与 application.yml
  - [x] 2.1 实现 WorkflowConfigProperties
    - 创建 `com.lifepilot.workflow.config.WorkflowConfigProperties`，使用 `@ConfigurationProperties("lifepilot.workflow")`
    - 实现 `Retry` 嵌套静态内部类（initialDelayMs=500, maxDelayMs=5000, maxAttempts=3）
    - 字段：enabled=true, defaultStepTimeoutSeconds=300, maxParallelBranches=10, maxNestingDepth=3, maxLoopIterations=100, definitionsDir="~/.lifepilot/workflows", crashRecoveryEnabled=true, scanIntervalSeconds=30
    - _Requirements: 11.1~11.11_

  - [x] 2.2 在 application.yml 中声明工作流配置
    - 在 `application.yml` 中添加 `lifepilot.workflow` 配置段，显式声明所有配置项及默认值
    - _Requirements: 11.11_

- [x] 3. 表达式引擎
  - [x] 3.1 实现 ExpressionEngine
    - 创建 `com.lifepilot.workflow.expression.ExpressionEngine`
    - 实现 `resolve(String template, WorkflowContext context)` — 解析 `${...}` 变量插值
    - 实现 `resolveMap(Map<String, Object> params, WorkflowContext context)` — 批量解析 Map 中的表达式
    - 实现 `evaluateCondition(String condition, WorkflowContext context)` — 条件求值（比较运算 + 逻辑运算）
    - 支持嵌套路径访问（`steps.stepId.output.field`、`inputs.paramName`）
    - 支持比较运算符（==, !=, >, <, >=, <=）和逻辑运算符（&&, ||, !）
    - 支持字符串字面量（单引号包裹）
    - 变量不存在时抛出 ExpressionException，语法错误时抛出 ExpressionException 并指示错误位置
    - _Requirements: 3.1~3.8_

  - [ ]* 3.2 写属性测试：表达式变量解析
    - **Property 3: 表达式引擎变量解析**
    - 对任意 WorkflowContext 中存在的嵌套路径，`${path}` 解析返回该路径的值；不存在的路径返回错误
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4**

  - [ ]* 3.3 写属性测试：条件求值
    - **Property 4: 表达式引擎条件求值**
    - 对任意两个可比较值和比较运算符，以及任意布尔值和逻辑运算符，求值结果与标准语义一致
    - **Validates: Requirements 3.5, 3.6, 3.7**

- [x] 4. Checkpoint - 确保数据模型、配置和表达式引擎编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. YAML 解析与打印
  - [x] 5.1 实现 WorkflowYamlParser
    - 创建 `com.lifepilot.workflow.parser.WorkflowYamlParser`
    - 实现 `parse(String yaml)` 方法，返回 `Result<WorkflowDefinition, List<String>>`
    - 使用 SnakeYAML 解析 YAML 字符串为 Map，再映射到 WorkflowDefinition
    - 支持解析全部 9 种步骤类型、3 种触发器类型、4 种错误策略
    - 校验必填字段（id, name, steps），未知步骤类型、重复步骤 ID 返回 Err
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.7_

  - [x] 5.2 实现 WorkflowYamlPrinter
    - 创建 `com.lifepilot.workflow.parser.WorkflowYamlPrinter`
    - 实现 `print(WorkflowDefinition definition)` 方法，将 WorkflowDefinition 序列化为 YAML 字符串
    - _Requirements: 1.5_

  - [ ]* 5.3 写属性测试：YAML round-trip
    - **Property 1: YAML 解析/打印 round-trip**
    - 对任意有效 WorkflowDefinition，print → parse → 结果等价于原始定义
    - **Validates: Requirements 1.1, 1.5, 1.6**

  - [ ]* 5.4 写属性测试：解析器拒绝无效输入
    - **Property 2: YAML 解析器拒绝无效输入并报告错误**
    - 缺失必填字段、未知步骤类型、重复步骤 ID 均返回 Err 并包含描述性错误信息
    - **Validates: Requirements 1.2, 1.3, 1.4**

- [x] 6. WorkflowContext 变量上下文
  - [x] 6.1 实现 WorkflowContext
    - 创建 `com.lifepilot.workflow.model.WorkflowContext`
    - 实现 `get(String path)` — 嵌套路径访问（点号分隔）
    - 实现 `set(String path, Object value)` — 嵌套路径设置
    - 实现 `toJson()` / `fromJson(String)` — JSON 序列化/反序列化
    - 支持 LoopStep 变量绑定（loopVar 和 loopVar_index）
    - _Requirements: 8.1~8.6_

  - [ ]* 6.2 写属性测试：Context 路径访问 round-trip
    - **Property 8: WorkflowContext 路径访问 round-trip**
    - 对任意路径和可序列化值，set 后 get 返回原始值；toJson → fromJson 产生等价上下文
    - **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5**

- [x] 7. 数据库持久化
  - [x] 7.1 创建 Flyway V15 迁移脚本
    - 创建 `src/main/resources/db/migration/V15__create_workflow_tables.sql`
    - 创建 workflow_definitions、workflow_instances、workflow_step_logs 三张表
    - 创建索引：idx_workflow_instances_workflow_id、idx_workflow_instances_state、idx_workflow_step_logs_instance_id
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

  - [x] 7.2 实现 WorkflowRepository
    - 创建 `com.lifepilot.workflow.repository.WorkflowRepository`
    - 构造函数注入 JdbcTemplate
    - 实现 WorkflowDefinition CRUD：saveDefinition、findDefinition、findAllDefinitions、updateDefinitionEnabled
    - 实现 WorkflowInstance CRUD：saveInstance、updateInstance、findInstance、findInstancesByState
    - 实现 StepLog：insertStepLog、findStepLogs
    - _Requirements: 9.6, 9.7, 9.8_

  - [ ]* 7.3 写属性测试：状态变更持久化
    - **Property 13: 状态变更持久化**
    - 对任意 WorkflowInstance 状态转换，Repository 持久化更新后的 state、context_json、current_step_index；对任意步骤执行，插入 StepLog 记录
    - **Validates: Requirements 4.10, 5.8, 9.7, 9.8**

- [x] 8. Checkpoint - 确保解析、上下文和持久化层测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. StepExecutor 步骤分发器
  - [x] 9.1 实现 StepExecutor
    - 创建 `com.lifepilot.workflow.engine.StepExecutor`
    - 构造函数注入 SkillRegistry、SkillActionDispatcher、DynamicToolRegistry、LlmRouter、WorkflowRegistry、WorkflowConfigProperties
    - 实现 `execute(WorkflowStep, WorkflowContext, ExpressionEngine)` 方法
    - 使用 switch 表达式穷举匹配 9 种步骤类型：
      - SkillStep → SkillRegistry.find() + SkillActionDispatcher.dispatch()
      - ToolStep → DynamicToolRegistry.resolve() + ToolContract.execute()
      - LlmStep → LlmRouter.call()
      - ConditionStep → ExpressionEngine.evaluateCondition() + 递归执行 then/else 分支
      - LoopStep → 遍历集合，绑定 loopVar 和 loopVar_index，递归执行 body
      - ParallelStep → Virtual Thread 并发执行分支，等待全部完成
      - SubWorkflowStep → WorkflowRegistry.find() + 递归执行子工作流
      - NoopStep → 直接返回空 Map
      - WaitStep → 返回特殊标记，由 WorkflowEngine 处理状态转换
    - ParallelStep 分支数不超过 maxParallelBranches 配置
    - LoopStep 迭代次数不超过 maxLoopIterations 配置
    - SubWorkflowStep 嵌套深度不超过 maxNestingDepth 配置
    - _Requirements: 2.2~2.10_

  - [ ]* 9.2 写属性测试：步骤执行分发正确性
    - **Property 7: 步骤执行分发正确性**
    - 对任意 WorkflowStep，StepExecutor 分发到正确的处理器
    - **Validates: Requirements 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.10**

  - [ ]* 9.3 写属性测试：LoopStep 变量绑定
    - **Property 15: LoopStep 变量绑定**
    - 对任意 N 个元素的集合，第 i 次迭代时 loopVar 绑定为 items[i]，loopVar_index 绑定为 i
    - **Validates: Requirements 2.6, 8.6**

- [x] 10. WorkflowEngine 核心引擎
  - [x] 10.1 实现 WorkflowEngine
    - 创建 `com.lifepilot.workflow.engine.WorkflowEngine`
    - 构造函数注入 WorkflowRegistry、StepExecutor、ExpressionEngine、WorkflowRepository、WorkflowConfigProperties
    - 实现 `execute(String workflowId, Map<String, Object> inputs)` — 创建实例、状态机驱动、步骤遍历
    - 实现 `resume(String instanceId)` — 从 currentStepIndex 恢复执行
    - 实现 `cancel(String instanceId)` — 取消实例
    - 状态机转换：CREATED→RUNNING→{COMPLETED,FAILED,CANCELLED,WAITING}，WAITING→RUNNING
    - 拒绝无效状态转换（如 COMPLETED→RUNNING），WARN 日志
    - 每次状态转换后持久化到 SQLite
    - 步骤执行后存储输出到 WorkflowContext（`steps.{stepId}.output`），失败时存储错误（`steps.{stepId}.error`）
    - 错误处理：按 ErrorStrategy 分发（Retry 指数退避、Skip 跳过、Fail 终止、Compensate 补偿）
    - 无 ErrorStrategy 时默认使用 Fail
    - 重试耗尽后回退到 Fail
    - 每次步骤执行（含重试）插入 StepLog
    - WaitStep 处理：转换为 WAITING 状态，持久化当前位置
    - _Requirements: 4.2~4.10, 5.2~5.8, 8.2, 8.3_

  - [ ]* 10.2 写属性测试：状态机转换正确性
    - **Property 5: 工作流状态机转换正确性**
    - 初始状态为 CREATED，有效转换遵循定义的转换图，无效转换被拒绝，终态为 COMPLETED/FAILED/CANCELLED
    - **Validates: Requirements 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9**

  - [ ]* 10.3 写属性测试：错误策略分发正确性
    - **Property 6: 错误策略分发正确性**
    - Retry 重试后回退 Fail，Skip 标记 SKIPPED 继续，Fail 终止工作流，Compensate 执行补偿步骤，无策略默认 Fail
    - **Validates: Requirements 5.2, 5.3, 5.4, 5.5, 5.6, 5.7**

- [x] 11. Checkpoint - 确保核心引擎测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. WorkflowRegistry 注册中心
  - [x] 12.1 实现 WorkflowRegistry
    - 创建 `com.lifepilot.workflow.registry.WorkflowRegistry`
    - 构造函数注入 WorkflowRepository、WorkflowYamlParser
    - 使用 ConcurrentHashMap 存储 WorkflowDefinition
    - 实现 register / unregister / enable / disable / find / listAll / listEnabled
    - register 时验证定义（必填字段、步骤类型、表达式语法），无效定义返回 false
    - 重复 ID 注册时更新已有定义，INFO 日志
    - disable 时阻止新实例创建，允许运行中实例完成
    - 实现 `scanAndRegister(Path directory)` — 扫描目录中的 YAML 文件并注册
    - 启动时从 workflow_definitions 表加载所有定义
    - _Requirements: 7.1~7.8_

  - [ ]* 12.2 写属性测试：Registry 查找一致性
    - **Property 9: WorkflowRegistry 查找一致性**
    - find(id) 返回已注册定义，listAll() 返回全部，listEnabled() 仅返回 enabled=true 的子集
    - **Validates: Requirements 7.5, 7.6, 7.7**

  - [ ]* 12.3 写属性测试：注册验证拒绝无效定义
    - **Property 10: 注册中心验证拒绝无效定义**
    - 缺失必填字段或包含无效步骤类型的定义被拒绝
    - **Validates: Requirements 7.2**

  - [ ]* 12.4 写属性测试：禁用工作流阻止新实例
    - **Property 11: 禁用工作流阻止新实例创建**
    - 禁用的 WorkflowDefinition 执行时被拒绝
    - **Validates: Requirements 7.4**

- [x] 13. WorkflowAutoConfiguration 自动配置
  - [x] 13.1 实现 WorkflowAutoConfiguration
    - 创建 `com.lifepilot.workflow.config.WorkflowAutoConfiguration`
    - 注册 WorkflowEngine、WorkflowRegistry、WorkflowRepository、WorkflowYamlParser、WorkflowYamlPrinter、ExpressionEngine、StepExecutor 为 Spring Bean
    - 注入已有模块依赖：SkillRegistry、SkillActionDispatcher、DynamicToolRegistry、LlmRouter、AgentLoop、JdbcTemplate
    - 使用 `@ConditionalOnProperty(name = "lifepilot.workflow.enabled", havingValue = "true", matchIfMissing = true)`
    - 注册到 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
    - _Requirements: 12.1~12.4_

- [x] 14. 触发器集成
  - [x] 14.1 实现 CronTrigger 调度
    - 在 WorkflowAutoConfiguration 中使用 `TaskScheduler` 注册 CronTrigger 定时任务
    - CronTrigger 触发时检查同一工作流是否有 RUNNING 实例，有则跳过并 INFO 日志
    - _Requirements: 6.2, 6.5_

  - [x] 14.2 实现 EventTrigger 监听
    - 使用 Spring `ApplicationEventListener` 监听指定事件类型
    - 事件触发时将 event payload 作为工作流输入参数
    - _Requirements: 6.3, 6.6_

  - [x] 14.3 实现 ManualTrigger
    - ManualTrigger 仅通过 WorkflowEngine.execute() 显式调用
    - _Requirements: 6.4_

  - [ ]* 14.4 写属性测试：Cron 跳过重复执行
    - **Property 12: Cron 触发器跳过重复执行**
    - 同一工作流有 RUNNING 实例时，新 Cron 触发跳过执行
    - **Validates: Requirements 6.5**

- [x] 15. 崩溃恢复
  - [x] 15.1 实现崩溃恢复逻辑
    - 在 WorkflowEngine 中实现 `recoverInterruptedInstances()` 方法
    - 查询 RUNNING 和 WAITING 状态的实例
    - RUNNING 实例：从 currentStepIndex 恢复执行，使用持久化的 context_json
    - WAITING 实例：检查等待时长是否已过，已过则恢复，未过则重新调度
    - context_json 损坏时：标记 FAILED，failure_reason="崩溃恢复失败：上下文数据损坏"，ERROR 日志
    - 使用 Virtual Thread 执行，避免阻塞主启动序列
    - _Requirements: 10.1~10.5_

  - [x] 15.2 注册 ApplicationReadyEvent 监听器
    - 在 WorkflowAutoConfiguration 中监听 ApplicationReadyEvent
    - 触发 WorkflowEngine.recoverInterruptedInstances()
    - 仅在 crashRecoveryEnabled=true 时执行
    - _Requirements: 12.5_

  - [ ]* 15.3 写属性测试：崩溃恢复正确性
    - **Property 14: 崩溃恢复正确性**
    - RUNNING 实例从 currentStepIndex 恢复，WAITING 实例检查等待时长，损坏上下文标记 FAILED
    - **Validates: Requirements 10.2, 10.3, 10.4**

- [x] 16. YAML 文件热加载
  - [x] 16.1 实现热加载逻辑
    - 在 WorkflowRegistry 中实现定时扫描 definitionsDir 目录
    - 启动时扫描并注册所有 YAML 文件
    - 新增文件 → 注册新定义
    - 修改文件 → 更新已有定义
    - 删除文件 → 禁用对应定义（不删除，保留实例历史）
    - 解析失败 → WARN 日志，跳过该文件
    - 扫描间隔从 scanIntervalSeconds 配置读取（默认 30 秒）
    - _Requirements: 13.1~13.6_

- [ ] 17. 集成测试
  - [ ]* 17.1 写集成测试：WorkflowAutoConfiguration Bean 注册
    - 验证 Spring Context 加载，所有 Bean 注册成功
    - 验证 Bean 注入链完整（SkillRegistry、DynamicToolRegistry、LlmRouter 等）
    - _Requirements: 12.1, 12.2, 12.3_

  - [ ]* 17.2 写集成测试：WorkflowRepository + Flyway V15
    - 使用内存 SQLite 验证 Flyway V15 迁移
    - 验证 CRUD 操作、外键约束
    - _Requirements: 9.1~9.8_

  - [ ]* 17.3 写集成测试：WorkflowEngine 端到端执行
    - Mock SkillRegistry、DynamicToolRegistry、LlmRouter
    - 验证完整工作流执行（多步骤、条件分支、循环）
    - 验证状态转换和持久化
    - _Requirements: 4.2~4.10, 2.2~2.10_

- [x] 18. Final checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests use jqwik (already in project dependencies)
- SkillStep 通过 SkillActionDispatcher.dispatch() 执行，不重新实现 Skill 执行逻辑
- CronTrigger 独立使用 Spring TaskScheduler，不依赖 ProactiveReasoner
- WorkflowException 使用 sealed interface 而非继承层次，ParseException 通过 Result 类型返回
- 遵循编码规范：中文注释/Javadoc/测试方法名，@author zsg，@since 当前日期

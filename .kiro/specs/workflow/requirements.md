# Requirements Document

## Introduction

工作流/自动化编排引擎（WorkflowEngine）是 LifePilot Phase 4 的核心模块，为用户提供多步骤自动化能力。用户通过 YAML 声明式定义工作流（包含步骤、触发器、输入变量），引擎负责解析、调度、执行和持久化。

工作流引擎整合了 Agent 引擎（LLM 推理步骤）、Skill 系统（技能步骤）、工具系统（工具步骤）和主动推理引擎（触发器集成），实现从简单的定时任务到复杂的多分支、并行、子工作流编排。

核心设计目标：
1. YAML 声明式定义，零代码创建自动化流程
2. 丰富的步骤类型覆盖常见自动化场景
3. 表达式引擎支持变量插值和条件求值
4. 完善的错误处理和崩溃恢复机制
5. 与主动推理引擎集成，支持 Cron / 事件 / 条件触发

参考文档：
- 架构设计：#[[file:docs/architecture/proactive-reasoning.md]]
- Skill 系统：#[[file:docs/architecture/skill-system.md]]
- 工具生态：#[[file:docs/architecture/tool-ecosystem.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## Glossary

- **WorkflowDefinition**: 工作流定义，从 YAML 文件解析的不可变数据载体，描述工作流的完整蓝图（步骤、触发器、输入、元数据）
- **WorkflowInstance**: 工作流实例，WorkflowDefinition 的一次运行时执行，维护独立的状态、变量上下文和步骤日志
- **WorkflowEngine**: 工作流执行引擎，负责实例创建、状态机驱动、步骤分发和错误处理
- **WorkflowRegistry**: 工作流注册中心，管理 WorkflowDefinition 的注册、查询、启用/禁用
- **WorkflowStep**: 工作流步骤，sealed interface，穷举所有步骤类型（skill / tool / llm / condition / loop / parallel / sub-workflow / noop / wait）
- **StepExecutor**: 步骤执行器，根据 WorkflowStep 类型分发到对应的执行逻辑
- **ExpressionEngine**: 表达式引擎，解析和求值 `${...}` 语法的变量插值和条件表达式
- **WorkflowTrigger**: 工作流触发器，sealed interface，穷举触发类型（Cron / Event / Manual）
- **WorkflowState**: 工作流实例状态枚举，包括 CREATED / RUNNING / PAUSED / WAITING / COMPLETED / FAILED / CANCELLED
- **StepState**: 步骤执行状态枚举，包括 PENDING / RUNNING / COMPLETED / FAILED / SKIPPED
- **ErrorStrategy**: 步骤错误处理策略，sealed interface，包括 Retry / Skip / Fail / Compensate
- **WorkflowContext**: 工作流变量上下文，存储输入参数、步骤输出和中间变量，支持嵌套字段访问
- **WorkflowYamlParser**: YAML 工作流定义解析器，将 YAML 文件解析为 WorkflowDefinition
- **WorkflowYamlPrinter**: YAML 工作流定义打印器，将 WorkflowDefinition 序列化回 YAML 格式
- **WorkflowConfigProperties**: 工作流配置属性类，外部化所有业务可调参数
- **WorkflowRepository**: 工作流持久化仓储，管理 workflow_definitions、workflow_instances、workflow_step_logs 三张表
- **SkillRegistry**: 已完成模块，Skill 注册中心，提供 find(String skillId) 方法查找 Skill 定义
- **DynamicToolRegistry**: 已完成模块，动态工具注册中心，提供 find(String toolId) 方法查找工具
- **LlmRouter**: 已完成模块，LLM 路由器，提供 call(String scene, String prompt, String outputSchema) 方法执行 LLM 调用
- **AgentLoop**: 已完成模块，Agent 核心控制循环，提供 run(AgentRequest request) 方法执行 Agent 推理
- **ProactiveReasoner**: 已完成模块，主动推理引擎，提供定时触发能力

## Requirements

### Requirement 1: YAML 工作流定义解析

**User Story:** As a LifePilot user, I want to define automation workflows in YAML files, so that I can create multi-step automations without writing code.

#### Acceptance Criteria

1. WHEN a valid YAML workflow file is provided, THE WorkflowYamlParser SHALL parse it into a WorkflowDefinition record containing id, name, description, version, triggers, inputs, steps, and metadata
2. WHEN a YAML workflow file contains an unknown step type, THE WorkflowYamlParser SHALL return a descriptive parse error indicating the invalid step type and its location
3. WHEN a YAML workflow file is missing required fields (id, name, steps), THE WorkflowYamlParser SHALL return a descriptive parse error listing all missing fields
4. WHEN a YAML workflow file contains duplicate step IDs, THE WorkflowYamlParser SHALL return a descriptive parse error indicating the duplicated step ID
5. THE WorkflowYamlPrinter SHALL format WorkflowDefinition records back into valid YAML workflow files
6. FOR ALL valid WorkflowDefinition records, parsing then printing then parsing SHALL produce an equivalent WorkflowDefinition (round-trip property)
7. THE WorkflowYamlParser SHALL support all nine step types: skill, tool, llm, condition, loop, parallel, sub-workflow, noop, wait

### Requirement 2: 工作流步骤类型

**User Story:** As a LifePilot user, I want a rich set of step types to cover common automation scenarios, so that I can build workflows for diverse use cases.

#### Acceptance Criteria

1. THE WorkflowStep sealed interface SHALL define nine permitted step types: SkillStep, ToolStep, LlmStep, ConditionStep, LoopStep, ParallelStep, SubWorkflowStep, NoopStep, WaitStep
2. WHEN a SkillStep is executed, THE StepExecutor SHALL invoke the Skill identified by skillId through SkillRegistry.find() and execute it with the resolved input parameters
3. WHEN a ToolStep is executed, THE StepExecutor SHALL invoke the tool identified by toolId through DynamicToolRegistry and execute it with the resolved input parameters
4. WHEN an LlmStep is executed, THE StepExecutor SHALL invoke LlmRouter.call() with the resolved prompt template and scene name, and store the LLM response in the step output
5. WHEN a ConditionStep is executed, THE StepExecutor SHALL evaluate the condition expression using ExpressionEngine and execute either the thenSteps or elseSteps branch
6. WHEN a LoopStep is executed, THE StepExecutor SHALL iterate over the collection resolved from the items expression, executing the body steps for each item with the loop variable bound in WorkflowContext
7. WHEN a ParallelStep is executed, THE StepExecutor SHALL execute all branch step lists concurrently using Virtual Threads and wait for all branches to complete before proceeding
8. WHEN a SubWorkflowStep is executed, THE StepExecutor SHALL look up the referenced workflow by workflowId in WorkflowRegistry and execute it as a nested WorkflowInstance with the resolved input parameters
9. WHEN a NoopStep is executed, THE StepExecutor SHALL record the step as completed without performing any action
10. WHEN a WaitStep is executed, THE StepExecutor SHALL pause the workflow instance for the specified duration and transition the instance state to WAITING

### Requirement 3: 表达式引擎

**User Story:** As a LifePilot user, I want to use expressions in workflow definitions to reference variables and evaluate conditions, so that workflows can be dynamic and data-driven.

#### Acceptance Criteria

1. WHEN a string contains `${variableName}` syntax, THE ExpressionEngine SHALL resolve it by looking up the variable in WorkflowContext and substituting the value
2. WHEN a string contains `${steps.stepId.output.fieldName}` syntax, THE ExpressionEngine SHALL resolve it by navigating the nested path to retrieve the value from a previous step's output
3. WHEN a string contains `${inputs.paramName}` syntax, THE ExpressionEngine SHALL resolve it by looking up the parameter in the workflow instance's input map
4. WHEN an expression references a variable that does not exist in WorkflowContext, THE ExpressionEngine SHALL return an error indicating the unresolved variable path
5. THE ExpressionEngine SHALL support comparison operators (==, !=, >, <, >=, <=) for condition evaluation in ConditionStep
6. THE ExpressionEngine SHALL support logical operators (&&, ||, !) for combining conditions in ConditionStep
7. THE ExpressionEngine SHALL support string literal values enclosed in single quotes within expressions
8. WHEN an expression contains a syntax error, THE ExpressionEngine SHALL return a descriptive error indicating the error position and expected syntax

### Requirement 4: 工作流状态机

**User Story:** As a LifePilot user, I want the workflow engine to manage execution state reliably, so that I can track workflow progress and handle interruptions gracefully.

#### Acceptance Criteria

1. THE WorkflowInstance SHALL maintain a state field with values from WorkflowState enum: CREATED, RUNNING, PAUSED, WAITING, COMPLETED, FAILED, CANCELLED
2. WHEN a WorkflowInstance is created, THE WorkflowEngine SHALL set its initial state to CREATED
3. WHEN execution begins, THE WorkflowEngine SHALL transition the instance state from CREATED to RUNNING
4. WHEN all steps complete successfully, THE WorkflowEngine SHALL transition the instance state from RUNNING to COMPLETED
5. WHEN a step fails and the error strategy is Fail, THE WorkflowEngine SHALL transition the instance state from RUNNING to FAILED and record the failure reason
6. WHEN a user requests cancellation, THE WorkflowEngine SHALL transition the instance state to CANCELLED and stop executing further steps
7. WHEN a WaitStep is encountered, THE WorkflowEngine SHALL transition the instance state from RUNNING to WAITING and persist the current execution position
8. WHEN the wait duration expires, THE WorkflowEngine SHALL transition the instance state from WAITING to RUNNING and resume execution from the next step
9. THE WorkflowEngine SHALL reject invalid state transitions (e.g., COMPLETED to RUNNING) and log the rejected transition at WARN level
10. THE WorkflowEngine SHALL persist the instance state to SQLite after every state transition for crash recovery

### Requirement 5: 步骤错误处理

**User Story:** As a LifePilot user, I want to define per-step error handling strategies, so that workflow failures are handled appropriately based on the step's importance.

#### Acceptance Criteria

1. THE ErrorStrategy sealed interface SHALL define four permitted strategies: Retry, Skip, Fail, Compensate
2. WHEN a step fails and its error strategy is Retry, THE WorkflowEngine SHALL retry the step using exponential backoff (initial delay from configuration, multiplier 2.0, max delay from configuration) up to the configured maximum retry count
3. WHEN a step fails and its error strategy is Skip, THE WorkflowEngine SHALL mark the step as SKIPPED, log the skip reason at WARN level, and continue to the next step
4. WHEN a step fails and its error strategy is Fail, THE WorkflowEngine SHALL mark the step as FAILED and transition the workflow instance to FAILED state
5. WHEN a step fails and its error strategy is Compensate, THE WorkflowEngine SHALL execute the compensation step defined in the strategy before marking the original step as FAILED
6. WHEN no error strategy is specified for a step, THE WorkflowEngine SHALL use the Fail strategy as the default
7. WHEN all retry attempts are exhausted, THE WorkflowEngine SHALL fall back to the Fail strategy for that step
8. THE WorkflowEngine SHALL record each step execution attempt (including retries) in the workflow_step_logs table with attempt number, status, error message, and duration

### Requirement 6: 工作流触发器

**User Story:** As a LifePilot user, I want workflows to be triggered automatically by schedules, events, or manually, so that automations run at the right time without manual intervention.

#### Acceptance Criteria

1. THE WorkflowTrigger sealed interface SHALL define three permitted trigger types: CronTrigger, EventTrigger, ManualTrigger
2. WHEN a WorkflowDefinition has a CronTrigger, THE WorkflowEngine SHALL schedule the workflow to execute according to the cron expression using Spring @Scheduled infrastructure
3. WHEN a WorkflowDefinition has an EventTrigger, THE WorkflowEngine SHALL listen for the specified Spring ApplicationEvent type and execute the workflow when the event is published
4. WHEN a WorkflowDefinition has a ManualTrigger, THE WorkflowEngine SHALL only execute the workflow when explicitly invoked through the WorkflowEngine.execute() method
5. WHEN a CronTrigger fires but the previous instance of the same workflow is still RUNNING, THE WorkflowEngine SHALL skip the new execution and log the skip at INFO level
6. THE WorkflowEngine SHALL pass event payload data as workflow input parameters when triggered by an EventTrigger

### Requirement 7: 工作流注册中心

**User Story:** As a LifePilot user, I want to manage my workflow definitions (register, query, enable, disable), so that I can organize and control my automations.

#### Acceptance Criteria

1. THE WorkflowRegistry SHALL store WorkflowDefinition instances in a ConcurrentHashMap for thread-safe concurrent access
2. WHEN a WorkflowDefinition is registered, THE WorkflowRegistry SHALL validate the definition (required fields, step type validity, expression syntax) before accepting it
3. WHEN a WorkflowDefinition with a duplicate ID is registered, THE WorkflowRegistry SHALL update the existing definition and log the update at INFO level
4. WHEN a WorkflowDefinition is disabled, THE WorkflowRegistry SHALL prevent new instances from being created for that workflow while allowing running instances to complete
5. THE WorkflowRegistry SHALL provide a find(String workflowId) method returning Optional<WorkflowDefinition>
6. THE WorkflowRegistry SHALL provide a listAll() method returning an immutable list of all registered WorkflowDefinition instances
7. THE WorkflowRegistry SHALL provide a listEnabled() method returning only enabled WorkflowDefinition instances
8. THE WorkflowRegistry SHALL persist workflow definitions to the workflow_definitions table in SQLite and load them on application startup

### Requirement 8: 工作流变量上下文

**User Story:** As a LifePilot user, I want workflow steps to share data through a variable context, so that later steps can use the output of earlier steps.

#### Acceptance Criteria

1. THE WorkflowContext SHALL store input parameters, step outputs, and intermediate variables in a hierarchical Map structure
2. WHEN a step completes successfully, THE WorkflowEngine SHALL store the step's output in WorkflowContext under the path `steps.{stepId}.output`
3. WHEN a step fails, THE WorkflowEngine SHALL store the error information in WorkflowContext under the path `steps.{stepId}.error`
4. THE WorkflowContext SHALL support nested field access using dot notation (e.g., `steps.fetchData.output.items`)
5. THE WorkflowContext SHALL be serializable to JSON for persistence and crash recovery
6. WHEN a LoopStep is executing, THE WorkflowContext SHALL bind the current item to the loop variable name and the current index to `{loopVar}_index`

### Requirement 9: 数据库持久化

**User Story:** As a LifePilot user, I want workflow definitions, instances, and step logs to be persisted, so that workflows survive application restarts and I can review execution history.

#### Acceptance Criteria

1. THE Flyway migration script V15 SHALL create a workflow_definitions table with columns: id (TEXT, PRIMARY KEY), name (TEXT), description (TEXT), version (TEXT), enabled (INTEGER), definition_yaml (TEXT), created_at (TEXT), updated_at (TEXT)
2. THE Flyway migration script V15 SHALL create a workflow_instances table with columns: id (TEXT, PRIMARY KEY), workflow_id (TEXT), state (TEXT), input_json (TEXT), context_json (TEXT), current_step_index (INTEGER), started_at (TEXT), completed_at (TEXT), failure_reason (TEXT), created_at (TEXT), updated_at (TEXT)
3. THE Flyway migration script V15 SHALL create a workflow_step_logs table with columns: id (TEXT, PRIMARY KEY), instance_id (TEXT), step_id (TEXT), step_type (TEXT), state (TEXT), attempt (INTEGER), input_json (TEXT), output_json (TEXT), error_message (TEXT), started_at (TEXT), completed_at (TEXT), duration_ms (INTEGER), created_at (TEXT)
4. THE workflow_instances table SHALL have a foreign key reference to workflow_definitions(id)
5. THE workflow_step_logs table SHALL have a foreign key reference to workflow_instances(id)
6. WHEN the application starts, THE WorkflowRegistry SHALL load all workflow definitions from the workflow_definitions table
7. WHEN a workflow instance state changes, THE WorkflowRepository SHALL persist the updated state, context_json, and current_step_index to the workflow_instances table
8. WHEN a step execution completes (success or failure), THE WorkflowRepository SHALL insert a record into the workflow_step_logs table

### Requirement 10: 崩溃恢复

**User Story:** As a LifePilot user, I want workflows that were interrupted by an application crash to be recoverable, so that long-running automations are not lost.

#### Acceptance Criteria

1. WHEN the application starts, THE WorkflowEngine SHALL query the workflow_instances table for instances with state RUNNING or WAITING
2. WHEN a recoverable instance is found with state RUNNING, THE WorkflowEngine SHALL resume execution from the step indicated by current_step_index using the persisted context_json
3. WHEN a recoverable instance is found with state WAITING, THE WorkflowEngine SHALL check if the wait duration has elapsed and either resume execution or re-schedule the wait
4. IF the persisted context_json is corrupted or unparseable, THEN THE WorkflowEngine SHALL transition the instance to FAILED state with failure reason "崩溃恢复失败：上下文数据损坏" and log the error at ERROR level
5. THE WorkflowEngine SHALL execute crash recovery on a Virtual Thread during application startup to avoid blocking the main startup sequence

### Requirement 11: 配置外部化

**User Story:** As a LifePilot administrator, I want all business-tunable parameters of the workflow engine to be externalized to configuration, so that behavior can be adjusted without code changes.

#### Acceptance Criteria

1. THE WorkflowConfigProperties SHALL externalize the default step timeout with configuration key lifepilot.workflow.default-step-timeout-seconds and default value 300
2. THE WorkflowConfigProperties SHALL externalize the maximum parallel branches with configuration key lifepilot.workflow.max-parallel-branches and default value 10
3. THE WorkflowConfigProperties SHALL externalize the maximum workflow nesting depth with configuration key lifepilot.workflow.max-nesting-depth and default value 3
4. THE WorkflowConfigProperties SHALL externalize the maximum loop iterations with configuration key lifepilot.workflow.max-loop-iterations and default value 100
5. THE WorkflowConfigProperties SHALL externalize the retry initial delay with configuration key lifepilot.workflow.retry.initial-delay-ms and default value 500
6. THE WorkflowConfigProperties SHALL externalize the retry max delay with configuration key lifepilot.workflow.retry.max-delay-ms and default value 5000
7. THE WorkflowConfigProperties SHALL externalize the retry max attempts with configuration key lifepilot.workflow.retry.max-attempts and default value 3
8. THE WorkflowConfigProperties SHALL externalize the workflow definitions directory with configuration key lifepilot.workflow.definitions-dir and default value "~/.lifepilot/workflows"
9. THE WorkflowConfigProperties SHALL externalize the crash recovery enabled toggle with configuration key lifepilot.workflow.crash-recovery-enabled and default value true
10. THE WorkflowConfigProperties SHALL be registered as a Spring Bean via @ConfigurationProperties prefix "lifepilot.workflow"
11. THE application.yml SHALL declare all workflow configuration keys with their default values

### Requirement 12: Spring AutoConfiguration 与 Bean 注册

**User Story:** As a developer, I want all workflow engine components to be registered as Spring Beans via AutoConfiguration, so that the module integrates cleanly with the LifePilot Spring Boot application.

#### Acceptance Criteria

1. THE WorkflowAutoConfiguration SHALL register WorkflowEngine, WorkflowRegistry, WorkflowRepository, WorkflowYamlParser, WorkflowYamlPrinter, ExpressionEngine, and StepExecutor as Spring Beans via @Bean methods
2. THE WorkflowAutoConfiguration SHALL inject dependencies from existing modules: SkillRegistry, DynamicToolRegistry, LlmRouter, AgentLoop, and JdbcTemplate
3. THE WorkflowAutoConfiguration SHALL be conditional on lifepilot.workflow.enabled being true (default)
4. THE WorkflowAutoConfiguration SHALL be registered in META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
5. THE WorkflowAutoConfiguration SHALL trigger crash recovery after all beans are initialized using ApplicationReadyEvent listener

### Requirement 13: 工作流 YAML 文件热加载

**User Story:** As a LifePilot user, I want the system to automatically detect and load new or modified workflow YAML files, so that I can update automations without restarting the application.

#### Acceptance Criteria

1. WHEN the application starts, THE WorkflowEngine SHALL scan the configured definitions directory for YAML files and register all valid workflow definitions
2. WHEN a new YAML file is added to the definitions directory, THE WorkflowEngine SHALL detect the change and register the new workflow definition
3. WHEN an existing YAML file is modified, THE WorkflowEngine SHALL detect the change and update the registered workflow definition
4. WHEN a YAML file is deleted from the definitions directory, THE WorkflowEngine SHALL disable the corresponding workflow definition (not delete, to preserve instance history)
5. IF a YAML file fails to parse, THEN THE WorkflowEngine SHALL log the parse error at WARN level and skip the file without affecting other workflow definitions
6. THE WorkflowEngine SHALL use a configurable scan interval (default 30 seconds) for detecting file changes

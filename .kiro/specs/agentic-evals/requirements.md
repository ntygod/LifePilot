# Requirements Document

## Introduction

LifePilot 当前已具备基础的可观测性基础设施（TraceRecorder 记录 Agent 执行轨迹、TraceStep 记录单步信息），架构文档中规划了 TrajectoryEvaluator 五维评估引擎。但目前缺少开发期的系统性 Agent 质量评估手段——无法在 Prompt 调整、StateReducer 重构、工具注册变更后自动回归验证 Agent 的决策质量。

本 spec 的目标是建立一个开发期的 Agent 质量评估框架（Agentic Evals），支持声明式 Benchmark 场景定义、基于轨迹的多维度自动评估、LLM-as-a-Judge 语义评估、JUnit 5 回归测试集成，以及评估报告生成与退化告警。

参考文档：
- 架构设计：#[[file:docs/architecture/observability.md]]（§6 轨迹评估引擎）
- 特性设计：#[[file:docs/features/observability.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## Glossary

- **Eval_Engine**：评估引擎，负责加载 Benchmark 场景、执行 Agent、采集轨迹、运行评估器、生成报告的核心协调组件
- **Benchmark_Scenario**：基准测试场景，以 YAML 声明式定义的标准化测试用例，包含固定输入、期望工具调用序列、期望最终结果、评估维度权重等
- **Scenario_Loader**：场景加载器，负责从 YAML 文件解析 Benchmark_Scenario 并进行 Schema 校验
- **Trajectory_Evaluator**：轨迹评估器，基于 TraceRecord 对 Agent 执行轨迹进行五维评估（工具选择正确性、参数合法性、步骤效率、策略合规性、Token 效率）
- **Dimension_Evaluator**：维度评估器，针对单个评估维度的评估逻辑实现，是 sealed interface 的具体 permit
- **LLM_Judge**：LLM 评判器，使用 LLM 对无法用规则评估的维度（如回答质量、语义正确性）进行评分
- **Eval_Result**：评估结果 record，包含各维度评分、违规项、改进建议、综合评分
- **Eval_Report**：评估报告，聚合多个 Eval_Result 生成的汇总报告，包含趋势对比和退化告警
- **Eval_Store**：评估结果持久化存储，将 Eval_Result 写入 SQLite 的 eval_results 表
- **Regression_Runner**：回归测试运行器，集成到 JUnit 5 的测试基础设施，支持 `@EvalTest` 注解驱动的回归测试

## Requirements

### Requirement 1: Benchmark 场景声明式定义

**User Story:** As a developer, I want to define benchmark scenarios in YAML files, so that I can create standardized, reproducible test cases for Agent quality evaluation without writing Java code.

#### Acceptance Criteria

1. THE Scenario_Loader SHALL parse YAML files from a configurable directory path into Benchmark_Scenario record instances
2. WHEN a YAML file contains syntax errors or missing required fields, THE Scenario_Loader SHALL return a descriptive error message indicating the file path, line number, and violated constraint
3. THE Benchmark_Scenario SHALL include the following required fields: scenario ID, scenario name, user input message, expected tool call sequence, expected final output pattern, evaluation dimension weights, and timeout duration
4. THE Benchmark_Scenario SHALL support optional fields: mock tool responses, initial context setup, tags for filtering, and LLM_Judge evaluation criteria
5. WHEN a Benchmark_Scenario specifies mock tool responses, THE Eval_Engine SHALL use the mock responses instead of executing actual tool calls
6. THE Scenario_Loader SHALL validate that evaluation dimension weights sum to 1.0 within a tolerance of 0.001
7. THE Scenario_Loader SHALL validate that scenario IDs are unique across all loaded YAML files within the same directory

### Requirement 2: 轨迹评估引擎（规则维度）

**User Story:** As a developer, I want the evaluation engine to automatically assess Agent trajectory quality across multiple rule-based dimensions, so that I can detect decision quality regressions after code changes.

#### Acceptance Criteria

1. THE Trajectory_Evaluator SHALL evaluate TraceRecord instances across five dimensions: tool selection accuracy, parameter validity, step efficiency, policy compliance, and token efficiency
2. WHEN evaluating tool selection accuracy, THE Trajectory_Evaluator SHALL compare the actual tool call sequence against the expected tool call sequence defined in the Benchmark_Scenario, and compute a score between 0.0 and 1.0
3. WHEN evaluating parameter validity, THE Trajectory_Evaluator SHALL validate each tool call's input parameters against the tool's JSON Schema, and compute a score between 0.0 and 1.0
4. WHEN evaluating step efficiency, THE Trajectory_Evaluator SHALL compute the ratio of expected step count to actual step count, capped at 1.0
5. WHEN evaluating policy compliance, THE Trajectory_Evaluator SHALL check that no GuardrailStep in the trace was blocked, and compute a score between 0.0 and 1.0
6. WHEN evaluating token efficiency, THE Trajectory_Evaluator SHALL compare actual token consumption against the Benchmark_Scenario's expected token budget, and compute a score between 0.0 and 1.0
7. THE Trajectory_Evaluator SHALL compute a weighted overall score using the dimension weights defined in the Benchmark_Scenario
8. THE Dimension_Evaluator SHALL be a sealed interface with one permit per evaluation dimension, enabling exhaustive switch matching when processing evaluation results

### Requirement 3: LLM-as-a-Judge 语义评估

**User Story:** As a developer, I want to use an LLM to evaluate dimensions that cannot be assessed by deterministic rules (such as response quality and semantic correctness), so that I can measure Agent output quality holistically.

#### Acceptance Criteria

1. WHEN a Benchmark_Scenario includes LLM_Judge evaluation criteria, THE LLM_Judge SHALL send the Agent's final output, the expected output pattern, and the evaluation criteria to the LLM via LlmRouter
2. THE LLM_Judge SHALL parse the LLM response into a structured score (0.0 to 1.0) and a textual justification
3. IF the LLM call fails or times out, THEN THE LLM_Judge SHALL return a fallback score of 0.5 with a justification indicating the evaluation was inconclusive
4. THE LLM_Judge SHALL include the LLM evaluation cost (token consumption) in the Eval_Result metadata, separate from the Agent execution token consumption
5. WHEN the LLM returns a response that cannot be parsed into a valid score, THE LLM_Judge SHALL retry once with a simplified prompt before falling back to the default score

### Requirement 4: JUnit 5 回归测试集成

**User Story:** As a developer, I want to run Agent evaluations as part of the JUnit 5 test suite, so that Prompt changes, StateReducer refactors, and tool registration changes are automatically regression-tested.

#### Acceptance Criteria

1. THE Regression_Runner SHALL provide an `@EvalTest` annotation that can be applied to JUnit 5 test methods to mark them as evaluation tests
2. WHEN a test method is annotated with `@EvalTest` and specifies a scenario ID, THE Regression_Runner SHALL load the corresponding Benchmark_Scenario, execute the Agent, collect the trace, run the Trajectory_Evaluator, and assert the overall score meets the configured pass threshold
3. THE Regression_Runner SHALL provide an `@EvalSuite` annotation that can be applied to test classes to run all Benchmark_Scenarios matching specified tags
4. WHEN an evaluation test fails (overall score below threshold), THE Regression_Runner SHALL include in the assertion message: the overall score, the pass threshold, per-dimension scores, and the list of violations
5. THE Regression_Runner SHALL support a configurable pass threshold with a default value of 0.7, overridable per scenario and per test method
6. THE Regression_Runner SHALL integrate with Spring's test context so that Agent dependencies (LlmRouter, ToolRegistry, TraceRecorder) are injected from the application context

### Requirement 5: 评估结果持久化

**User Story:** As a developer, I want evaluation results to be persisted to SQLite, so that I can track quality trends over time and detect gradual degradation.

#### Acceptance Criteria

1. THE Eval_Store SHALL persist each Eval_Result to the eval_results table in SQLite, including trace ID, scenario ID, per-dimension scores, overall score, violations, suggestions, and evaluation timestamp
2. THE Eval_Store SHALL persist evaluation metadata including the Git commit hash, branch name, and evaluation run ID for traceability
3. WHEN persisting an Eval_Result, THE Eval_Store SHALL use asynchronous writes via Virtual Thread to avoid blocking the test execution
4. THE Eval_Store SHALL provide a query method to retrieve historical Eval_Results for a given scenario ID, ordered by evaluation timestamp descending, with configurable limit

### Requirement 6: 评估报告生成与退化告警

**User Story:** As a developer, I want the evaluation framework to generate summary reports and alert on quality degradation, so that I can quickly identify when Agent quality drops after changes.

#### Acceptance Criteria

1. THE Eval_Report SHALL aggregate multiple Eval_Results from a single evaluation run into a summary containing: total scenarios evaluated, pass count, fail count, average overall score, and per-dimension average scores
2. WHEN the average overall score of the current run is lower than the previous run's average by more than a configurable degradation threshold, THE Eval_Report SHALL flag the report as "degraded" and list the regressed scenarios
3. THE Eval_Report SHALL output the summary to the console in a human-readable tabular format at the end of the evaluation run
4. THE Eval_Report SHALL support JSON export of the full report for programmatic consumption
5. IF a scenario's overall score drops below the pass threshold for the first time compared to the previous run, THEN THE Eval_Report SHALL mark that scenario as a "new regression"

### Requirement 7: Benchmark 场景 YAML 序列化往返一致性

**User Story:** As a developer, I want to ensure that Benchmark_Scenario YAML parsing and serialization are consistent, so that scenarios can be reliably round-tripped without data loss.

#### Acceptance Criteria

1. THE Scenario_Loader SHALL parse a valid YAML file into a Benchmark_Scenario record
2. THE Scenario_Serializer SHALL serialize a Benchmark_Scenario record back into a valid YAML string
3. FOR ALL valid Benchmark_Scenario instances, parsing then serializing then parsing SHALL produce an equivalent Benchmark_Scenario object (round-trip property)
4. WHEN a YAML file contains unknown fields, THE Scenario_Loader SHALL ignore the unknown fields and parse the remaining valid fields without error

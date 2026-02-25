# Implementation Plan: Agentic Evals（Agent 质量评估框架）

## Overview

在已有的可观测性基础设施（TraceRecorder / TraceStep）之上，构建开发期 Agent 质量评估框架。按自底向上顺序：先实现配置与数据模型层，再实现场景加载与序列化，然后实现五维规则评估器和 LLM Judge，接着实现持久化与报告层，最后实现 EvalEngine 协调器和 JUnit 5 集成。

## Tasks

- [ ] 1. 配置层与数据模型
  - [ ] 1.1 实现 EvalConfigProperties 配置属性类
    - 创建 `com.lifepilot.eval.config.EvalConfigProperties`，包含 `enabled`、`scenarioDirectory`、`defaultPassThreshold`、`degradationThreshold` 字段
    - 创建嵌套类 `LlmJudge`（scene、timeoutSeconds、fallbackScore、maxRetries）
    - 创建嵌套类 `Store`（defaultQueryLimit）
    - 更新 `application.yml` 声明 `lifepilot.eval.*` 所有配置项及默认值
    - _Requirements: 4.5, 6.2_

  - [ ] 1.2 实现 BenchmarkScenario record 和 ScenarioLoadException
    - 创建 `com.lifepilot.eval.scenario.BenchmarkScenario` record，包含 id、name、userInput、expectedToolCalls、expectedOutputPattern、dimensionWeights、timeoutSeconds、mockToolResponses、initialContext、tags、llmJudgeCriteria、expectedTokenBudget、expectedStepCount
    - 紧凑构造函数中校验必填字段非 null，集合字段防御性拷贝
    - 创建 `ScenarioLoadException` 异常类
    - _Requirements: 1.3, 1.4_

  - [ ]* 1.3 写属性测试：BenchmarkScenario 构造有效性
    - **Property 2: BenchmarkScenario 构造有效性**
    - **Validates: Requirements 1.3, 1.4**

  - [ ] 1.4 实现 DimensionScore record 和 EvalResult record
    - 创建 `com.lifepilot.eval.evaluator.DimensionScore` record（dimensionName、score、violations、suggestions）
    - 创建 `com.lifepilot.eval.model.EvalResult` record（evalId、traceId、scenarioId、dimensionScores、overallScore、violations、suggestions、llmJudgeScore、llmJudgeJustification、llmJudgeTokensUsed、evaluatedAt、gitCommitHash、gitBranch、evalRunId）
    - 实现 `EvalResult.passed(double threshold)` 方法
    - _Requirements: 2.1, 5.1, 5.2_

  - [ ] 1.5 实现 JudgeResult record 和 ReportSummary record
    - 创建 `com.lifepilot.eval.judge.JudgeResult` record（score、justification、tokensUsed、fallback）
    - 创建 `com.lifepilot.eval.report.ReportSummary` record（evalRunId、totalScenarios、passCount、failCount、averageOverallScore、dimensionAverages、degraded、regressedScenarios、newRegressions、evaluatedAt）
    - _Requirements: 3.2, 6.1_

- [ ] 2. 场景加载与序列化
  - [ ] 2.1 实现 ScenarioLoader 场景加载器
    - 创建 `com.lifepilot.eval.scenario.ScenarioLoader`，使用 Jackson YAML ObjectMapper
    - 实现 `loadAll()` 从配置目录加载所有 YAML 文件
    - 实现 `loadById(String scenarioId)` 按 ID 加载单个场景
    - 实现 `loadByTags(List<String> tags)` 按标签过滤
    - 实现 `validateScenarios()` 校验维度权重和为 1.0（容差 0.001）、场景 ID 唯一
    - 配置 ObjectMapper 忽略未知字段（`DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false`）
    - YAML 语法错误时抛出 `ScenarioLoadException`，包含文件路径和错误描述
    - _Requirements: 1.1, 1.2, 1.6, 1.7, 7.1, 7.4_

  - [ ] 2.2 实现 ScenarioSerializer 场景序列化器
    - 创建 `com.lifepilot.eval.scenario.ScenarioSerializer`，使用 Jackson YAML ObjectMapper
    - 实现 `serialize(BenchmarkScenario scenario)` → YAML 字符串
    - _Requirements: 7.2_

  - [ ]* 2.3 写属性测试：BenchmarkScenario YAML 往返一致性
    - **Property 1: BenchmarkScenario YAML 往返一致性**
    - **Validates: Requirements 1.1, 7.1, 7.2, 7.3**

  - [ ]* 2.4 写属性测试：维度权重和校验
    - **Property 3: 维度权重和校验**
    - **Validates: Requirements 1.6**

  - [ ]* 2.5 写属性测试：场景 ID 唯一性校验
    - **Property 4: 场景 ID 唯一性校验**
    - **Validates: Requirements 1.7**

  - [ ]* 2.6 写属性测试：未知 YAML 字段容忍性
    - **Property 15: 未知 YAML 字段容忍性**
    - **Validates: Requirements 7.4**

- [ ] 3. Checkpoint - 确保场景层测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. 五维规则评估器
  - [ ] 4.1 实现 DimensionEvaluator sealed interface 和五个维度评估器
    - 创建 `com.lifepilot.eval.evaluator.DimensionEvaluator` sealed interface，permits 五个实现
    - 实现 `ToolSelectionEvaluator`：比较实际工具调用序列与期望序列，计算 LCS 相似度
    - 实现 `ParameterValidityEvaluator`：对每个工具调用的 toolInput 进行 JSON Schema 校验（依赖 `DynamicToolRegistry`）
    - 实现 `StepEfficiencyEvaluator`：`min(expectedStepCount / actualStepCount, 1.0)`
    - 实现 `PolicyComplianceEvaluator`：检查 `blocked == true` 的步骤占比
    - 实现 `TokenEfficiencyEvaluator`：`min(expectedTokenBudget / actualTokens, 1.0)`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.8_

  - [ ]* 4.2 写属性测试：所有维度评分范围
    - **Property 5: 所有维度评分范围**
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.5, 2.6**

  - [ ]* 4.3 写属性测试：步骤效率公式正确性
    - **Property 6: 步骤效率公式正确性**
    - **Validates: Requirements 2.4**

  - [ ] 4.4 实现 TrajectoryEvaluator 轨迹评估器
    - 创建 `com.lifepilot.eval.evaluator.TrajectoryEvaluator`
    - 注入 `List<DimensionEvaluator>` 和 `DynamicToolRegistry`
    - 实现 `evaluate(List<TraceStep> steps, BenchmarkScenario scenario)` → `EvalResult`
    - 计算加权综合评分：各维度评分 × 对应权重之和
    - _Requirements: 2.7_

  - [ ]* 4.5 写属性测试：加权综合评分正确性
    - **Property 7: 加权综合评分正确性**
    - **Validates: Requirements 2.7**

- [ ] 5. LLM-as-a-Judge 语义评估
  - [ ] 5.1 实现 LlmJudge LLM 评判器
    - 创建 `com.lifepilot.eval.judge.LlmJudge`，注入 `LlmRouter` 和 `EvalConfigProperties`
    - 实现 `judge(String actualOutput, String expectedPattern, String criteria)` → `JudgeResult`
    - 构造评估 Prompt，通过 `LlmRouter.call()` 发送
    - 解析 LLM 响应为结构化评分（0.0 ~ 1.0）和理由
    - LLM 调用失败/超时时返回降级 `JudgeResult(score=fallbackScore, fallback=true)`
    - 响应无法解析时使用简化 Prompt 重试一次，仍失败则降级
    - 评分超出 [0.0, 1.0] 时裁剪到范围内
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [ ]* 5.2 写属性测试：LLM Judge 评分解析范围
    - **Property 8: LLM Judge 评分解析范围**
    - **Validates: Requirements 3.2**

  - [ ] 5.3 写单元测试：LlmJudge 降级与重试
    - Mock LlmRouter，测试超时降级、不可解析响应重试、评分裁剪
    - _Requirements: 3.3, 3.4, 3.5_

- [ ] 6. Checkpoint - 确保评估器层测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. 持久化层
  - [ ] 7.1 创建 Flyway V14 迁移脚本
    - 创建 `V14__create_eval_results.sql`
    - 创建 `eval_results` 表（eval_id、trace_id、scenario_id、dimension_scores_json、overall_score、violations_json、suggestions_json、llm_judge_score、llm_judge_justification、llm_judge_tokens_used、git_commit_hash、git_branch、eval_run_id、evaluated_at、created_at）
    - 创建索引 `idx_eval_results_scenario_id`、`idx_eval_results_eval_run_id`、`idx_eval_results_evaluated_at`
    - _Requirements: 5.1_

  - [ ] 7.2 实现 EvalStore 评估结果持久化
    - 创建 `com.lifepilot.eval.store.EvalStore`，注入 `JdbcTemplate` 和 `ObjectMapper`
    - 实现 `persist(EvalResult result)` 同步写入
    - 实现 `persistAsync(EvalResult result)` 通过 Virtual Thread 异步写入
    - 实现 `findByScenarioId(String scenarioId, int limit)` 按时间降序查询
    - 实现 `findByRunId(String evalRunId)` 查询指定运行的所有结果
    - JSON 字段使用 ObjectMapper 序列化/反序列化
    - 写入失败时记录 ERROR 日志，不阻断评估流程
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [ ]* 7.3 写属性测试：EvalResult 持久化往返一致性
    - **Property 9: EvalResult 持久化往返一致性**
    - **Validates: Requirements 5.1, 5.2**

  - [ ]* 7.4 写属性测试：历史查询时间降序
    - **Property 10: 历史查询时间降序**
    - **Validates: Requirements 5.4**

- [ ] 8. 报告层
  - [ ] 8.1 实现 EvalReport 评估报告生成器
    - 创建 `com.lifepilot.eval.report.EvalReport`，注入 `EvalStore` 和 `EvalConfigProperties`
    - 实现 `generateSummary(List<EvalResult> results, String evalRunId)` → `ReportSummary`
    - 计算 totalScenarios、passCount、failCount、averageOverallScore、dimensionAverages
    - 对比上次运行平均分，超过 degradationThreshold 时标记 degraded
    - 检测新增退化场景（上次通过本次未通过）加入 newRegressions
    - 实现 `printToConsole(ReportSummary summary)` 表格格式输出
    - 实现 `exportJson(ReportSummary summary)` JSON 导出
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [ ]* 8.2 写属性测试：报告聚合数学正确性
    - **Property 11: 报告聚合数学正确性**
    - **Validates: Requirements 6.1**

  - [ ]* 8.3 写属性测试：退化检测正确性
    - **Property 12: 退化检测正确性**
    - **Validates: Requirements 6.2**

  - [ ]* 8.4 写属性测试：ReportSummary JSON 导出往返一致性
    - **Property 13: ReportSummary JSON 导出往返一致性**
    - **Validates: Requirements 6.4**

  - [ ]* 8.5 写属性测试：新增退化场景检测
    - **Property 14: 新增退化场景检测**
    - **Validates: Requirements 6.5**

- [ ] 9. Checkpoint - 确保持久化和报告层测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 10. EvalEngine 协调器
  - [ ] 10.1 实现 EvalEngine 评估引擎
    - 创建 `com.lifepilot.eval.engine.EvalEngine`，注入 ScenarioLoader、AgentLoop、TraceRecorder、TrajectoryEvaluator、LlmJudge、EvalStore、EvalReport、EvalConfigProperties
    - 实现 `evaluateScenario(BenchmarkScenario scenario)` → `EvalResult`：执行 Agent → 采集轨迹 → 规则评估 → 可选 LLM Judge → 异步持久化
    - 实现 `evaluateBatch(List<BenchmarkScenario> scenarios)` → `ReportSummary`：批量评估 + 生成汇总报告
    - 场景指定 mockToolResponses 时使用 Mock 响应替代实际工具调用
    - Agent 执行超时/异常时该场景评分为 0.0，记录到 violations，继续下一个场景
    - _Requirements: 1.5, 2.7, 3.1, 5.3, 6.1_

  - [ ] 10.2 写单元测试：EvalEngine 协调流程
    - Mock AgentLoop、TraceRecorder、LlmJudge，测试完整评估流程
    - 测试 Agent 超时/异常场景的降级处理
    - _Requirements: 1.5, 2.7_

- [ ] 11. JUnit 5 集成层
  - [ ] 11.1 实现 @EvalTest 和 @EvalSuite 注解
    - 创建 `com.lifepilot.eval.junit.EvalTest` 注解（scenarioId、passThreshold 默认 0.7）
    - 创建 `com.lifepilot.eval.junit.EvalSuite` 注解（tags、passThreshold 默认 0.7）
    - _Requirements: 4.1, 4.3, 4.5_

  - [ ] 11.2 实现 EvalTestExtension
    - 创建 `com.lifepilot.eval.junit.EvalTestExtension` 实现 `BeforeEachCallback` / `AfterEachCallback`
    - 从 Spring ApplicationContext 获取 EvalEngine
    - 加载场景 → 执行评估 → 断言评分 ≥ 阈值
    - 失败时输出详细的维度评分、违规项、通过阈值
    - _Requirements: 4.2, 4.4, 4.5, 4.6_

  - [ ] 11.3 实现 EvalSuiteExtension
    - 创建 `com.lifepilot.eval.junit.EvalSuiteExtension` 实现 `BeforeAllCallback` / `AfterAllCallback`
    - 按标签加载场景，批量执行评估，输出汇总报告
    - _Requirements: 4.3, 4.4_

- [ ] 12. 自动配置与装配
  - [ ] 12.1 实现 EvalAutoConfiguration
    - 创建 `com.lifepilot.eval.config.EvalAutoConfiguration`
    - `@ConditionalOnProperty(prefix = "lifepilot.eval", name = "enabled", havingValue = "true", matchIfMissing = true)` 条件注册
    - 注册 ScenarioLoader、ScenarioSerializer、五个 DimensionEvaluator、TrajectoryEvaluator、LlmJudge、EvalStore、EvalReport、EvalEngine Bean
    - 注册到 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
    - _Requirements: 4.6_

- [ ] 13. 集成测试
  - [ ] 13.1 写集成测试：EvalStore_SQLite 持久化
    - `@SpringBootTest` + 内存 SQLite 验证 persist → findByScenarioId → findByRunId 完整流程
    - 验证 JSON 字段序列化/反序列化正确性
    - _Requirements: 5.1, 5.2, 5.4_

  - [ ] 13.2 写集成测试：EvalEngine_AgentLoop 端到端评估
    - `@SpringBootTest` 加载完整 ApplicationContext
    - Mock AgentLoop 和 LlmRouter，验证场景加载 → Agent 执行 → 轨迹评估 → 持久化 → 报告生成完整流程
    - _Requirements: 1.1, 2.7, 5.1, 6.1_

- [ ] 14. Final checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests use jqwik（Java 属性测试框架，JUnit 5 原生集成），每个 property 最少 100 次迭代
- 遵循编码规范：中文注释/Javadoc/测试方法名，@author zsg，@since 2026-08-01
- Flyway 迁移脚本版本号 V14，紧接已有的 V13
- 所有单元测试 Mock LlmRouter、AgentLoop、TraceRecorder，不依赖外部服务
- 集成测试使用 `@SpringBootTest` + 内存 SQLite

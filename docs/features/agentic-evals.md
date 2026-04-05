# Agentic Evals 评估框架 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.eval`
> **最后更新**：2026-03（eval-optimization 重构后更新）

## 1. 功能概述

Agentic Evals 提供系统化的 Agent 行为评估能力，帮助开发者量化 Agent 在不同场景下的决策质量，及时发现性能退化。通过 YAML 声明式场景定义、五维规则评估和 LLM 语义评判的组合，实现从工具选择到输出质量的全方位评估覆盖。

## 2. 核心特性

### 2.1 YAML 声明式场景定义

使用 YAML 文件定义评估场景，包含用户输入、期望工具调用序列、维度权重、Mock 工具响应等。场景文件存放在可配置的目录中，支持按 ID 加载和按标签过滤。加载时自动校验场景 ID 唯一性和维度权重和（必须为 1.0）。

### 2.2 五维规则评估

基于 `EvaluationCore` 共享评估核心（位于 observability 模块），统一 eval 和 observability 两个模块的五维评估逻辑。五个评估维度独立评分（0.0~1.0）：

- 工具选择正确性：Agent 是否选择了场景期望的工具（LCS 算法计算匹配度）
- 参数合法性：工具调用参数是否符合约束
- 步骤效率：完成任务的步骤数与期望值的偏差
- 策略合规：是否遵循安全策略和护栏规则
- Token 效率：Token 消耗是否在预算范围内

综合评分通过场景定义的维度权重加权计算。eval 模块的 `TrajectoryEvaluator` 和 observability 模块的 `TrajectoryEvaluator` 均委托给 `EvaluationCore` 执行评估。

### 2.3 LLM-as-a-Judge 语义评估

对 Agent 的最终输出进行语义质量评判。当场景定义了 `llmJudgeCriteria` 时自动触发。采用三级降级策略确保评估稳定性：结构化输出解析 → 正则手动解析 → 简化 Prompt 重试。降级结果会标记 `degraded` 标志。

### 2.4 退化检测与趋势对比

评估结果持久化到 SQLite，支持跨运行对比。退化检测机制：
- 平均分差值超过可配置阈值时标记为退化
- 识别新增退化场景（上次通过、本次失败）
- 报告汇总包含各维度平均评分趋势

### 2.5 JUnit 5 集成

通过自定义注解无缝集成到测试流程：
- `@EvalTest`：标注在测试方法上，指定场景 ID 和通过阈值，运行单场景评估
- `@EvalSuite`：标注在测试类上，指定标签过滤和通过阈值，运行批量评估并输出汇总报告

评估可作为 CI 流水线的一部分自动运行。

### 2.6 评估报告

支持两种输出格式：
- 控制台表格：包含通过/失败数、平均分、各维度评分、退化场景列表
- JSON 导出：结构化数据，便于集成到外部监控系统

## 3. 使用场景

开发者在完成 Agent 能力迭代后，编写 YAML 场景文件描述典型交互用例，通过 `@EvalTest` 注解将评估纳入测试套件。CI 流水线在每次提交后自动运行评估，`EvalReport` 对比历史结果检测退化。当某个场景的评分从通过变为失败时，报告中会高亮标记为新增退化，开发者可据此快速定位问题。

对于需要评估 Agent 输出语义质量的场景（如对话生成、摘要提取），在 YAML 中配置 `llmJudgeCriteria`，LLM Judge 会自动参与评估并提供评判理由。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.eval.enabled` | `true` | 评估框架总开关（matchIfMissing=true） |
| `lifepilot.eval.scenario-directory` | `${user.home}/.zhiwei/eval/scenarios` | 场景 YAML 文件目录（不存在时自动创建） |
| `lifepilot.eval.default-pass-threshold` | `0.7` | 默认通过阈值（0.0~1.0） |
| `lifepilot.eval.degradation-threshold` | `0.1` | 退化检测阈值 |
| `lifepilot.eval.execution.default-timeout-seconds` | `60` | Agent 执行默认超时（秒） |
| `lifepilot.eval.llm-judge.scene` | `eval-judge` | LLM Judge 使用的 GenerationRouter 场景 |
| `lifepilot.eval.llm-judge.timeout-seconds` | `30` | LLM 调用超时（秒） |
| `lifepilot.eval.llm-judge.fallback-score` | `0.5` | LLM 降级时的默认评分 |
| `lifepilot.eval.llm-judge.max-retries` | `1` | LLM 调用最大重试次数 |
| `lifepilot.eval.store.default-query-limit` | `50` | 历史查询默认返回条数 |

## 5. 限制与未来方向

当前限制：
- LLM Judge 的 `callEntity()` 路径不返回 Token 使用量统计
- 评估场景不支持多轮对话，仅支持单轮输入-输出评估

未来方向：
- 支持多轮对话场景评估
- 引入 A/B 对比评估（不同模型/策略的对比）
- 评估结果可视化仪表盘（Web UI 集成）
- 支持自定义评估维度扩展

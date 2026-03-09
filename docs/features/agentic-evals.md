# Agentic Evals 特性说明

> **模块归属**：`com.lifepilot.eval`
> **最后更新**：2026-02
> **状态**：✅ 已实现

---

## 1. 功能概述

Agentic Evals 是 ZhiWei 的开发期 Agent 质量评估框架。当开发者调整 Prompt、重构 StateReducer、变更工具注册后，可以通过声明式 Benchmark 场景自动回归验证 Agent 的决策质量，及时发现退化。

核心价值：
- 将 Agent 质量评估从"人工检查"升级为"自动化回归测试"
- 五维规则评估 + LLM 语义评估，覆盖可量化和不可量化的质量维度
- 评估结果持久化，支持历史趋势分析和退化告警
- 与 JUnit 5 无缝集成，融入现有 CI/CD 流程

---

## 2. 核心特性

### 2.1 声明式 Benchmark 场景

以 YAML 文件定义标准化评估用例，无需编写 Java 代码：

```yaml
id: "weather-query-001"
name: "查询明天天气"
userInput: "帮我查一下明天的天气"
expectedToolCalls:
  - "weather.query"
expectedOutputPattern: ".*天气.*"
dimensionWeights:
  toolSelection: 0.30
  parameterValidity: 0.20
  stepEfficiency: 0.20
  policyCompliance: 0.20
  tokenEfficiency: 0.10
timeoutSeconds: 60
expectedTokenBudget: 1000
expectedStepCount: 3
mockToolResponses:
  "weather.query": '{"temperature": 25, "condition": "晴"}'
tags:
  - "core"
  - "tool-calling"
llmJudgeCriteria: "回答应包含温度和天气状况信息"
```

特性：
- 支持 Mock 工具响应，评估不依赖外部服务
- 支持标签过滤，按场景类别批量运行
- 维度权重可自定义，适应不同场景的评估重点
- 未知 YAML 字段自动忽略，向前兼容

### 2.2 五维规则评估

基于 Agent 执行轨迹的五个维度自动评分：

| 维度 | 评估内容 | 评分方式 |
|------|---------|---------|
| 工具选择正确性 | Agent 是否选择了正确的工具 | 实际 vs 期望工具序列的 LCS 相似度 |
| 参数合法性 | 工具调用参数是否符合 Schema | JSON Schema 校验通过率 |
| 步骤效率 | Agent 是否用最少步骤完成任务 | 期望步骤数 / 实际步骤数 |
| 策略合规性 | Agent 是否遵守安全策略 | 未被护栏拦截的步骤占比 |
| Token 效率 | Token 消耗是否在预算内 | 期望预算 / 实际消耗 |

每个维度评分范围 [0.0, 1.0]，综合评分为加权平均。

### 2.3 LLM-as-a-Judge 语义评估

对于无法用规则衡量的维度（如回答质量、语义正确性），使用 LLM 进行评估：

- 将 Agent 实际输出、期望输出模式、评估标准发送给 LLM
- LLM 返回结构化评分（0.0~1.0）和评判理由
- 内置降级策略：LLM 不可用时返回 0.5 分，不阻断评估流程
- 评估消耗的 Token 独立记录，不计入 Agent 执行消耗

### 2.4 JUnit 5 回归测试集成

两个注解将评估融入测试流程：

```java
// 单场景评估
@EvalTest(scenarioId = "weather-query-001", passThreshold = 0.8)
void 天气查询场景评估() {
    // EvalTestExtension 自动执行评估并断言
}

// 批量场景评估
@EvalSuite(tags = {"core"}, passThreshold = 0.7)
class 核心场景评估套件 {
    // EvalSuiteExtension 自动加载匹配标签的所有场景
}
```

评估失败时，断言消息包含：综合评分、通过阈值、各维度评分、违规项列表。

### 2.5 评估结果持久化

每次评估结果自动持久化到 SQLite `eval_results` 表：
- 各维度评分、综合评分、违规项、改进建议
- Git commit hash 和分支名，支持代码变更溯源
- 评估运行 ID，支持批量查询同一次运行的所有结果
- 异步写入（Virtual Thread），不阻塞测试执行

### 2.6 退化检测与报告

评估完成后自动生成汇总报告：
- 总场景数、通过数、失败数、平均综合评分
- 各维度平均评分
- 与上次运行对比，检测退化（平均分下降超过阈值）
- 标记新增退化场景（上次通过、本次失败）
- 支持控制台表格输出和 JSON 导出

---

## 3. 使用场景

### 场景 1：Prompt 调整后回归验证

修改 Agent 的 System Prompt 后，运行评估套件验证决策质量未退化：

```bash
mvn test -Dtest="*EvalSuite*" -Dlifepilot.eval.enabled=true
```

### 场景 2：工具注册变更后验证

新增或修改工具后，验证 Agent 仍能正确选择工具：

```bash
mvn test -Dtest="*EvalTest*" -Dlifepilot.eval.enabled=true
```

### 场景 3：历史趋势分析

查询某个场景的历史评估结果，分析质量趋势：

```java
List<EvalResult> history = evalStore.findByScenarioId("weather-query-001", 20);
// 分析 overallScore 的变化趋势
```

---

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.eval.enabled` | `false` | 评估框架总开关，需显式开启 |
| `lifepilot.eval.scenario-directory` | `~/.zhiwei/eval/scenarios` | Benchmark YAML 目录 |
| `lifepilot.eval.default-pass-threshold` | `0.7` | 默认通过阈值 |
| `lifepilot.eval.degradation-threshold` | `0.1` | 退化检测阈值 |
| `lifepilot.eval.llm-judge.scene` | `eval-judge` | LLM Judge 场景名 |
| `lifepilot.eval.llm-judge.timeout-seconds` | `30` | LLM 调用超时 |
| `lifepilot.eval.llm-judge.fallback-score` | `0.5` | 降级默认评分 |
| `lifepilot.eval.llm-judge.max-retries` | `1` | 最大重试次数 |
| `lifepilot.eval.store.default-query-limit` | `50` | 历史查询默认限制 |

---

## 5. 限制与未来扩展

### 当前限制

- 评估框架仅支持开发期使用，不适合生产环境实时评估
- LLM-as-a-Judge 依赖 LLM 可用性，离线环境下只能使用规则评估
- 场景定义需要人工编写，暂不支持自动生成
- 评估结果仅存储在本地 SQLite，不支持远程聚合

### 未来扩展方向

- **自动场景生成**：基于历史 Trace 自动生成 Benchmark 场景
- **在线评估模式**：每次 Agent 执行后自动评估，实时监控质量
- **评估结果可视化**：Web UI 评估仪表盘，展示趋势图和退化告警
- **多 Agent 评估**：支持评估 SubAgent 的独立决策质量
- **自定义维度**：允许开发者扩展评估维度（通过 SPI 机制）

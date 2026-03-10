# 可观测性 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.observability`
> **最后更新**：2026-03

## 1. 功能概述

可观测性模块为 Agent 执行提供全链路追踪、安全护栏、数据脱敏和质量评估四大能力。通过 Spring AI Advisor 横切注入，无侵入地记录每次 LLM 调用和工具执行的详细信息，在 LLM 调用前后执行内容安全检查，对敏感数据自动脱敏，并在执行完成后进行五维质量评估。

## 2. 核心特性

### 2.1 全链路追踪

记录 Agent 执行过程中的每个步骤，包括 LLM 调用（Token 消耗、延迟、模型信息）、工具调用（成功/失败、耗时）、状态转换和护栏检查。追踪数据持久化到 SQLite，支持多维度查询、FTS5 全文搜索、轨迹回放和 JSON 导出。

### 2.2 护栏策略引擎

五种策略类型协同保障 Agent 执行安全：
- 工具风险策略：按风险等级（LOW → MEDIUM → HIGH → CRITICAL）决定审批模式
- 预算限制策略：控制每日 Token 消耗上限和单次请求步骤/时长限制
- 内容安全策略：正则模式阻断和敏感话题检测
- 速率限制策略：分钟/小时级调用频率控制
- 数据脱敏策略：标记需要脱敏的工具输入/输出

策略按优先级排序执行，支持动态注册/注销，异常时 fail-open 不阻塞正常执行。

### 2.3 数据脱敏

内置 6 条中国常见 PII 脱敏规则（API 密钥、手机号、身份证号、银行卡号、邮箱、IP 地址），采用保留部分可识别信息的脱敏策略（如手机号 138****5678）。支持动态注册自定义规则和自定义替换函数。脱敏在 LLM 调用前、Trace 记录和日志输出三个环节自动执行。

### 2.4 轨迹质量评估

对 Agent 执行轨迹进行五维加权评分：
- 工具选择正确性（30%）— 是否选择了合适的工具
- 参数合法性（20%）— 工具参数是否正确
- 步骤效率（20%）— 是否存在冗余步骤
- 策略合规性（20%）— 是否遵守护栏策略
- Token 效率（10%）— Token 消耗是否合理

支持在线评估（执行完成后自动触发）和离线评估，结果持久化到 evaluation_results 表。

### 2.5 轨迹查询与统计

提供丰富的查询和统计能力：
- 多维度查询：按时间范围、会话、成功/失败、步骤数、Token 数过滤
- 轨迹回放：将步骤转换为人类可读摘要，支持 Web UI 时间线展示
- Token 统计：指定时间范围内的 Token 消耗汇总
- 概览统计：24h/7d/30d 窗口的成功率、平均步骤、平均耗时
- 工具使用统计：各工具的调用次数、成功率、平均耗时

## 3. 使用场景

Agent 每次执行时，TraceAdvisor 和 GuardrailAdvisor 自动注入到 Spring AI ChatClient 调用链中。用户无需手动配置，追踪和护栏在后台透明运行。当 Agent 尝试调用高风险工具时，护栏引擎自动拦截并要求用户确认。执行完成后，轨迹评估引擎自动评分并记录结果。运维人员可通过 Web UI 查看轨迹回放、Token 消耗趋势和工具使用统计，定位性能瓶颈和异常行为。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.observability.trace.enabled` | `true` | 追踪总开关 |
| `lifepilot.observability.trace.record-prompts` | `false` | 记录完整 prompt（调试模式） |
| `lifepilot.observability.trace.retention-days` | `30` | 数据保留天数 |
| `lifepilot.observability.guardrail.enabled` | `true` | 护栏总开关 |
| `lifepilot.observability.guardrail.tool-risk.default-risk-level` | `LOW` | 默认工具风险等级 |
| `lifepilot.observability.guardrail.budget-limit.daily-token-limit` | `1000000` | 每日 Token 上限 |
| `lifepilot.observability.guardrail.rate-limit.max-calls-per-minute` | `60` | 每分钟最大调用次数 |
| `lifepilot.observability.redaction.enabled` | `true` | 脱敏总开关 |
| `lifepilot.observability.evaluation.enabled` | `true` | 评估总开关 |
| `lifepilot.observability.evaluation.pass-threshold` | `0.7` | 评估通过阈值 |

## 5. 限制与未来方向

- 速率限制目前为简化的分钟级滑动窗口实现，未来可引入更精确的令牌桶算法
- 内容安全检查基于正则模式和关键词匹配，未来可集成 LLM-as-a-Judge 语义检测
- 轨迹评估目前为规则评估，未来可结合 LLM 语义评估（已在 Agentic Evals 模块实现）
- 脱敏规则目前聚焦中国 PII，未来可扩展国际化规则集
- Trace 数据保留策略目前按天数清理，未来可支持按存储空间自动清理

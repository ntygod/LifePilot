# 预算控制逻辑与链路

> **文档性质**：架构说明文档  
> **模块归属**：`com.lifepilot.agent` / `com.lifepilot.interaction` / `com.lifepilot.observability` / `com.lifepilot.multiagent`  
> **最后更新**：2026-03-23

## 1. 文档目标

本文整理当前项目中“预算控制”的完整逻辑与实际接线链路，覆盖以下四个层面：

1. Agent 单请求运行时预算  
   控制一轮 Agent 执行最多可消耗多少 Token、多少步、多少时间。
2. Prompt 上下文 Token 分配  
   控制 system prompt、历史对话、工作区、记忆等片段在上下文窗口中的分配比例。
3. 可观测性护栏预算  
   控制按自然日累计的 Token 消耗上限。
4. 并行 Worker 子预算  
   控制 `spawn_workers` 为每个 Worker 派生出的预算。

这四层都与“预算”有关，但职责不同，不能混为一谈。

---

## 2. 总览

### 2.1 当前预算体系分层

| 层级 | 作用 | 核心类型 / 组件 |
|------|------|------------------|
| 运行时预算 | 控制单次 Agent 执行何时终止 | `Budget`、`ExecutionMiddleware`、`AgentOrchestrator`、`ReactAgentLoop` |
| 上下文 Token 分配 | 控制提示词各区块如何分配上下文窗口 | `AgentConfigProperties.ContextConfig`、`ContextAssembler` |
| 护栏预算 | 控制每日累计 Token 上限 | `ObservabilityProperties.Guardrail.BudgetLimit`、`GuardrailEngine` |
| Worker 子预算 | 控制 `spawn_workers` 的池预算与单 Worker 预算 | `ToolBridgeAgentToolProvider`、`SpawnWorkersToolFactory`、`SubAgentBudgetAllocator` |

### 2.2 当前三维运行时预算

运行时预算由 [Budget.java](../../src/main/java/com/lifepilot/agent/model/Budget.java) 建模，包含三个硬限制维度：

- `maxTokens`
- `maxSteps`
- `maxDuration`

对应运行时累计值：

- `tokensUsed`
- `stepsUsed`
- `elapsed`

任一维度超限，都会触发终止。

---

## 3. 核心对象

### 3.1 `Budget`

[Budget.java](../../src/main/java/com/lifepilot/agent/model/Budget.java) 是运行时预算的唯一核心模型，负责：

- 构建默认预算：`fromConfig(...)`
- 判断是否超限：`exceeded()`
- 返回超限原因：`exceedReason()`
- 扣减 Token：`deductTokens(...)`
- 增加步骤：`incrementStep()`
- 刷新耗时：`withElapsed(...)`
- 计算剩余额度：`tokensRemaining()` / `stepsRemaining()` / `durationRemaining()`
- 派生子预算：`allocateForSubAgent(...)`

### 3.2 `AgentConfigProperties.BudgetConfig`

[AgentConfigProperties.java](../../src/main/java/com/lifepilot/agent/config/AgentConfigProperties.java) 下的 `BudgetConfig` 定义了主 Agent 默认预算：

- `defaultMaxTokens`
- `defaultMaxSteps`
- `defaultMaxDurationSeconds`

当前 `application.yml` 中主 Agent 默认值为：

- `default-max-tokens: 131072`
- `default-max-steps: 60`
- `default-max-duration-seconds: 300`

### 3.3 会话级预算配置模型

Web 会话支持以下三个预算覆盖字段：

- [SessionConfigRequest.java](../../src/main/java/com/lifepilot/interaction/web/model/SessionConfigRequest.java)
- [SessionConfigKeys.java](../../src/main/java/com/lifepilot/interaction/web/model/SessionConfigKeys.java)
- [SessionDetailInfo.java](../../src/main/java/com/lifepilot/interaction/web/model/SessionDetailInfo.java)

字段名称分别为：

- `maxTokens`
- `maxSteps`
- `maxDurationSeconds`

### 3.4 多 Agent 默认预算模型

[MultiAgentProperties.java](../../src/main/java/com/lifepilot/multiagent/config/MultiAgentProperties.java) 定义了并行 Worker 的默认预算基线：

- `defaultMaxTokens = 16000`
- `defaultMaxSteps = 15`
- `defaultTimeoutSeconds = 180`

注意：这不是主 Agent 默认预算，而是多 Agent 模块的独立默认值。

---

## 4. 预算来源

当前系统中存在四类主要预算来源。

### 4.1 主 Agent 全局默认预算

来源：

- [application.yml](../../src/main/resources/application.yml)
- [AgentConfigProperties.java](../../src/main/java/com/lifepilot/agent/config/AgentConfigProperties.java)

适用场景：

- Web 请求未设置会话预算
- A2A 未指定具名 AgentDefinition 预算
- `cron` / `heartbeat`
- 其他直接构造 `AgentRequest(message, sessionId, channel)` 的场景

### 4.2 Web 会话级预算覆盖

来源：

- [ChatSessionService.java](../../src/main/java/com/lifepilot/interaction/web/service/ChatSessionService.java)
- [ExecutionMiddleware.java](../../src/main/java/com/lifepilot/interaction/middleware/execution/ExecutionMiddleware.java)

适用场景：

- Web 聊天会话

特点：

- 只覆盖当前会话
- 支持按字段独立覆盖
- 未传字段继续继承全局默认值

### 4.3 具名 AgentDefinition 预算

来源：

- [AgentBudget.java](../../src/main/java/com/lifepilot/multiagent/model/AgentBudget.java)
- [A2aAgentExecutor.java](../../src/main/java/com/lifepilot/a2a/server/A2aAgentExecutor.java)
- [AgentController.java](../../src/main/java/com/lifepilot/interaction/web/controller/AgentController.java)

适用场景：

- A2A 指定 Agent
- 管理后台直接运行指定 Agent

特点：

- 通过 `AgentBudget.toAgentBudget()` 直接构造独立 `Budget`
- 不走 Web 会话配置链路

### 4.4 并行 Worker 子预算

来源：

- [ToolBridgeAgentToolProvider.java](../../src/main/java/com/lifepilot/tool/bridge/ToolBridgeAgentToolProvider.java)
- [SpawnWorkersToolFactory.java](../../src/main/java/com/lifepilot/multiagent/execution/SpawnWorkersToolFactory.java)
- [SubAgentBudgetAllocator.java](../../src/main/java/com/lifepilot/multiagent/execution/SubAgentBudgetAllocator.java)

特点：

- 优先基于父请求剩余额度派生
- 父预算不存在时回退到 `lifepilot.agent.multi-agent.budget.*`
- 支持每个 Worker 的任务级预算收紧

---

## 5. Web 会话预算链路

### 5.1 写入链路

Web 侧通过 [ChatSessionService.java](../../src/main/java/com/lifepilot/interaction/web/service/ChatSessionService.java) 的 `updateSessionConfig(...)` 持久化以下预算字段：

- `maxTokens`
- `maxSteps`
- `maxDurationSeconds`

### 5.2 读取链路

[ExecutionMiddleware.java](../../src/main/java/com/lifepilot/interaction/middleware/execution/ExecutionMiddleware.java) 在 `toAgentRequest(...)` 中：

1. 调用 `getSessionConfig(sessionId)` 读取会话配置
2. 调用 `resolveSessionBudget(sessionConfig)` 构造预算覆盖
3. 把结果放入 `AgentRequest.budget`

### 5.3 覆盖规则

`resolveSessionBudget(...)` 的逻辑是：

1. 读取 `maxTokens/maxSteps/maxDurationSeconds`
2. 如果三者都为空，则返回 `null`
3. 否则以 `Budget.fromConfig(agentConfigProperties.getBudget())` 为基线
4. 逐个字段覆盖

因此，Web 会话预算是：

`全局默认预算 + 会话字段级覆盖`

而不是完全替换。

---

## 6. 预算进入运行时状态的链路

### 6.1 `AgentRequest`

[ExecutionMiddleware.java](../../src/main/java/com/lifepilot/interaction/middleware/execution/ExecutionMiddleware.java)、[A2aAgentExecutor.java](../../src/main/java/com/lifepilot/a2a/server/A2aAgentExecutor.java)、[SpawnWorkersToolFactory.java](../../src/main/java/com/lifepilot/multiagent/execution/SpawnWorkersToolFactory.java) 等入口最终都会构造 [AgentRequest.java](../../src/main/java/com/lifepilot/agent/model/AgentRequest.java)。

`AgentRequest` 中的 `budget` 是运行时预算向核心循环传递的唯一入口字段。

### 6.2 `ReactAgentState`

[ReactAgentState.java](../../src/main/java/com/lifepilot/agent/model/ReactAgentState.java) 在 `init(...)` / `fromSession(...)` 中采用以下规则：

- `request.budget() != null`：使用请求预算
- 否则：使用 `defaultBudget`

### 6.3 `AgentOrchestrator`

[AgentOrchestrator.java](../../src/main/java/com/lifepilot/agent/orchestration/AgentOrchestrator.java) 会先构造：

- `Budget.fromConfig(config.getBudget())`

然后在 `ReactAgentState.init(...)` 或 `fromSession(...)` 中，让请求预算覆盖默认预算。

因此，真正进入 `ReactAgentLoop` 的预算值，已经是最终生效值。

---

## 7. `ReactAgentLoop` 中的预算控制

### 7.1 检查时机

[ReactAgentLoop.java](../../src/main/java/com/lifepilot/agent/ReactAgentLoop.java) 中预算检查有两个核心时机：

1. 每轮开始前
2. 每轮结束后

两次都通过 `checkBudgetAndInvalidateCacheIfNeeded(...)` 完成。

### 7.2 每轮开始前做什么

每轮开始前会：

1. 用 `refreshBudgetElapsed(...)` 刷新 `elapsed`
2. 判断当前 `degradationLevel()`
3. 如达到某个渐进式降级等级，记录日志并尝试失效缓存上下文
4. 如 `budget.exceeded()`，统一调用 `DegradedResponseBuilder.terminateWithReason(...)`

### 7.3 每轮结束后做什么

每轮结束后会：

1. `advanceBudgetStep(...)`  
   把 `stepsUsed + 1`
2. `refreshBudgetElapsed(...)`  
   重新刷新总耗时
3. 再次执行 `checkBudgetAndInvalidateCacheIfNeeded(...)`

### 7.4 步骤预算语义

当前 `stepsUsed` 的语义是：

- 按外层 ReAct 迭代计数
- 不是按 `ReactStep` 条数计数
- 不是按 Tool Call 次数计数

因此：

- 一轮中即便出现多个 `Thought / ToolCall / Observation / Answer`
- 预算步数仍只增加 1

### 7.5 时间预算语义

`elapsed` 的语义是：

- 从 `loopStart` 开始累计到当前时间
- 不是单步耗时
- 不是仅 LLM 调用耗时

挂起时还会显式冻结一次 `elapsed`。

### 7.6 Token 预算语义

当前 `tokensUsed` 的直接扣减点只有一个：

- LLM 响应处理完成后，调用 `budget.deductTokens(responseTokens)`

也就是说，当前运行时 Token 预算主要统计：

- LLM 响应 Token

而不是完整统计：

- 工具执行消耗的 Token
- 并行 Worker 实际消耗的 Token

这是当前实现边界，文档需要明确区分。

---

## 8. 预算超限后的终止逻辑

### 8.1 统一终止出口

[ReactAgentLoop.java](../../src/main/java/com/lifepilot/agent/ReactAgentLoop.java) 在检测到任一维度超限时，会统一调用：

- [DegradedResponseBuilder.java](../../src/main/java/com/lifepilot/agent/DegradedResponseBuilder.java)

### 8.2 终止结果

`terminateWithReason(...)` 会：

- `done = true`
- `finalOutput = 降级响应文本`
- `terminationReason = 超限原因`
- `completionMode = DEGRADED`

### 8.3 降级响应内容

降级响应会根据已有成功 Observation 决定：

- 如果没有成功工具结果：返回中断提示
- 如果已有成功工具结果：把已获得的信息汇总出来，再附加中断原因

---

## 9. 渐进式降级的当前实际效果

### 9.1 已接线的部分

[Budget.java](../../src/main/java/com/lifepilot/agent/model/Budget.java) 定义了以下渐进式降级等级：

- `NORMAL`
- `COMPRESS_HISTORY`
- `TRIM_TOOLS`
- `SKIP_MEMORY`
- `TERMINATE`

[ReactAgentLoop.java](../../src/main/java/com/lifepilot/agent/ReactAgentLoop.java) 已根据等级：

- 打日志
- 失效缓存的 `AssembledContext`

### 9.2 当前尚未形成强行为差异的部分

当前代码中，没有看到 `ContextAssembler` 或工具裁剪逻辑对 `degradationLevel()` 做显式分支。

这意味着当前渐进式降级的实际效果主要是：

- 预留了等级模型
- 在循环中留下了失效缓存与重新组装的接点

但尚未形成完整的“压缩历史 / 裁剪工具 / 停止注入记忆”的独立策略分支。

因此，当前真正的硬终止仍主要依赖：

- `budget.exceeded()`

---

## 10. 上下文 Token 分配与运行时预算的关系

### 10.1 二者不是同一个东西

[ContextAssembler.java](../../src/main/java/com/lifepilot/agent/context/ContextAssembler.java) 中的 `buildTokenBudget(...)` 用于分配 Prompt 上下文的区域预算，依赖的是：

- `context.maxContextTokens`
- 模型上下文窗口
- `outputReservedTokens`
- `tokenAllocation` 百分比分配

它控制的是：

- system prompt 占多少
- history 占多少
- memory 占多少
- tool schema 占多少

### 10.2 与运行时预算的边界

运行时预算控制的是：

- 整轮 Agent 最终何时停止

上下文 Token 分配控制的是：

- 单次 LLM Prompt 组装时，各个区块如何分配上下文窗口

因此：

- `maxTokens/maxSteps/maxDuration` 属于运行时预算
- `maxContextTokens/outputReservedTokens/tokenAllocation` 属于上下文预算

二者相关，但不等价。

---

## 11. 会话累计 Token 统计

### 11.1 存储位置

[SessionManager.java](../../src/main/java/com/lifepilot/agent/session/SessionManager.java) 会把每轮结束时的：

- `state.budget().tokensUsed()`

累加到 `agent_sessions.total_tokens_used`。

### 11.2 含义

这个字段是：

- 会话历史统计字段

不是：

- 当前请求预算控制字段
- 每日护栏字段

因此不能把 `agent_sessions.total_tokens_used` 等同于预算护栏或运行时预算。

---

## 12. 可观测性预算护栏链路

### 12.1 配置来源

[ObservabilityProperties.java](../../src/main/java/com/lifepilot/observability/config/ObservabilityProperties.java) 当前只保留一个预算护栏配置：

- `dailyTokenLimit`

### 12.2 注册链路

[ObservabilityAutoConfiguration.java](../../src/main/java/com/lifepilot/observability/config/ObservabilityAutoConfiguration.java) 会：

1. 注册 `GuardrailEngine`
2. 构造 `defaultBudgetLimitPolicy`
3. 调用 `guardrailEngine.registerPolicy(...)`
4. 把 `dailyTokenUsageListener` 注册为 `TraceRecorder.onTraceEnd` 监听器

### 12.3 聚合表

[V17__daily_token_usage.sql](../../src/main/resources/db/migration/V17__daily_token_usage.sql) 新增了：

- `daily_token_usage`

表字段：

- `usage_date`
- `input_tokens`
- `output_tokens`
- `total_tokens`

### 12.4 入账时机

当 Trace 结束时，`dailyTokenUsageListener` 会把当前 Trace 的：

- `inputTokens`
- `outputTokens`
- `totalTokens`

累计到当天的聚合表中。

### 12.5 拦截时机

[GuardrailEngine.java](../../src/main/java/com/lifepilot/observability/guardrail/GuardrailEngine.java) 在每次工具调用前会执行 `checkToolCall(...)`。

其中 `BudgetLimitPolicy` 的判断逻辑是：

`当天已累计 total_tokens + 当前 Trace 尚未落表的 token 增量 >= dailyTokenLimit`

一旦满足，返回：

- `GuardrailResult.Blocked`

### 12.6 当前护栏的职责边界

当前预算护栏只负责：

- 每日累计 Token 上限

不负责：

- 单请求 `maxTokens`
- 单请求 `maxSteps`
- 单请求 `maxDuration`

这些都属于 `Budget` 负责的运行时预算。

---

## 13. `spawn_workers` 子预算链路

### 13.1 父预算如何下传

[ToolBridgeAgentToolProvider.java](../../src/main/java/com/lifepilot/tool/bridge/ToolBridgeAgentToolProvider.java) 会在工具调用上下文里写入：

- `SESSION_ID`
- `CALLER_TRACE_ID`
- `CALLER_DEPTH`
- `CALLER_BUDGET`

### 13.2 Worker 池预算

[SpawnWorkersToolFactory.java](../../src/main/java/com/lifepilot/multiagent/execution/SpawnWorkersToolFactory.java) 会：

1. 读取 `CALLER_BUDGET`
2. 如存在父预算，则按父预算剩余额度派生
3. 如不存在父预算，则回退到 `lifepilot.agent.multi-agent.budget.*`
4. 再乘以 `worker-budget-ratio`

### 13.3 单 Worker 预算

[SubAgentBudgetAllocator.java](../../src/main/java/com/lifepilot/multiagent/execution/SubAgentBudgetAllocator.java) 会：

1. 从 Worker 池预算均分到每个 Worker
2. 支持任务级 `budget.max_tokens/max_steps/timeout_seconds`
3. 覆盖规则是取更小值，只允许收紧

### 13.4 子预算如何计算剩余额度

[Budget.java](../../src/main/java/com/lifepilot/agent/model/Budget.java) 的 `allocateForSubAgent(...)` 使用的是：

- `tokensRemaining()`
- `stepsRemaining()`
- `durationRemaining()`

也就是说，子预算基线是父预算剩余额度，而不是父预算总量。

### 13.5 当前边界

当前 `spawn_workers` 已实现：

- 父预算 -> Worker 池预算
- Worker 池预算 -> 单 Worker 预算

但当前没有完整实现：

- 子 Worker 实际消耗回写父 `Budget`
- `tokensReserved/returnFromSubAgent()` 的真实记账闭环

这部分仍属于预留能力。

---

## 14. 其他入口的预算逻辑

### 14.1 A2A 指定 Agent

[A2aAgentExecutor.java](../../src/main/java/com/lifepilot/a2a/server/A2aAgentExecutor.java) 在指定 AgentDefinition 时，会使用：

- `definition.get().budget().toAgentBudget()`

因此 A2A 指定 Agent 的预算来源是 Agent 自己的预算定义，而不是 Web 会话预算。

### 14.2 Cron / Heartbeat

`cron` / `heartbeat` 构造 `AgentRequest` 时不显式传预算，因此最终会回退到：

- `Budget.fromConfig(agent.budget.*)`

### 14.3 后台直接运行 Agent

管理后台直接运行具名 Agent 时，同样会显式使用 `AgentBudget.toAgentBudget()`。

---

## 15. 当前实现边界与注意事项

### 15.1 已完整接线的部分

- Web 会话三维预算覆盖
- 运行时三维预算终止
- 步数预算递增
- 时间预算统一降级终止
- 每日累计 Token 护栏
- Worker 子预算按父剩余额度裁剪

### 15.2 当前仍需注意的边界

- `Budget.tokensUsed` 当前主要统计 LLM 响应 Token，不是完整的全链路 Token 消耗
- 工具执行的 `ToolResultMeta.tokensUsed` 当前默认多为 `0`
- `spawn_workers` 子 Worker 的真实消耗不会回写父 `Budget`
- `tokensReserved/returnFromSubAgent()` 仍未接入实际调用链
- `degradationLevel()` 已建模，但上下文组装层尚未形成完整的等级化行为差异

---

## 16. 调试与验证建议

当前预算控制相关验证点建议优先看以下测试：

- [ExecutionMiddleware_单元测试.java](../../src/test/java/com/lifepilot/interaction/middleware/execution/ExecutionMiddleware_单元测试.java)
- [ReactAgentLoop_预算控制测试.java](../../src/test/java/com/lifepilot/agent/ReactAgentLoop_预算控制测试.java)
- [ObservabilityAutoConfiguration_集成测试.java](../../src/test/java/com/lifepilot/observability/config/ObservabilityAutoConfiguration_集成测试.java)
- [GuardrailEngine_预算护栏_集成测试.java](../../src/test/java/com/lifepilot/observability/guardrail/GuardrailEngine_预算护栏_集成测试.java)
- [SpawnWorkersToolFactoryTest.java](../../src/test/java/com/lifepilot/multiagent/execution/SpawnWorkersToolFactoryTest.java)

---

## 17. 一句话总结

当前系统的预算控制可以概括为：

- **运行时预算**：由 `Budget` 控制单次 Agent 执行何时终止
- **会话预算覆盖**：由 Web 会话配置链路注入 `AgentRequest.budget`
- **上下文 Token 分配**：由 `ContextAssembler` 独立控制 Prompt 组装，不等同于运行时预算
- **每日 Token 护栏**：由 `GuardrailEngine + daily_token_usage` 控制自然日累计上限
- **Worker 子预算**：由 `spawn_workers` 按父预算剩余额度派生，但尚未形成父子真实记账闭环

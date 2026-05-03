# Agent 引擎 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.agent`
> **最后更新**：2026-05-03

## 1. 功能概述

Agent 引擎是知微的智能推理核心，接收用户自然语言输入，经过 ReAct 循环（Thought → Action → Observation）完成推理和执行，最终生成响应。支持非流式和 SSE 流式两种输出模式，内置预算控制防止资源过度消耗。

## 2. 核心架构

### 2.1 ReAct 循环实现

```java
// ReactAgentLoop 核心循环
public class ReactAgentLoop {
    // 每次迭代：
    // 1. 调用 LLM（禁用自动 tool calling）获取原始响应
    // 2. 检查响应是否包含 tool call 请求
    // 3. 若有 tool call → 记录 ToolCall 步骤 → 手动执行工具 → 记录 Observation 步骤 → 继续循环
    // 4. 若无 tool call（纯文本）→ 记录 Answer 步骤 → 循环结束
}
```

通过 `ChatModel.call(Prompt)` + `internalToolExecutionEnabled=false` 实现手动 tool calling 控制，确保每个工具调用都被显式记录到 ReAct 步骤和 Trace 中。

### 2.2 状态管理

- `ReactAgentState`: 不可变状态快照（record）
  - `boolean done` — 循环是否结束
  - `boolean suspended` — 是否处于挂起状态
  - `CompletionMode` — 完成模式（NORMAL / DEGRADED / SUSPENDED）
  - `CompletionReason` — 11 种终止原因
- `ReactStep`: 7 种步骤类型（sealed interface）
  - `Progress` — 执行进度提示
  - `Thought` — LLM 推理文本
  - `ToolCall` — 工具调用请求
  - `Observation` — 工具返回结果
  - `Answer` — 最终回答
  - `Suspend` — 挂起点
  - `Resume` — 恢复点
- `Budget`: 预算控制（token数量/时间/步骤数）

### 2.3 核心依赖

| 组件 | 职责 |
|------|------|
| `AgentOrchestrator` | 编排器，同步/流式/恢复三个入口（`run` / `runStreaming` / `resumeFromSuspend`） |
| `ContextAssembler` | 动态组装 LLM 上下文（系统Prompt/记忆检索/对话历史/知识库） |
| `GenerationRouter` | 多模型路由、熔断器、故障转移 |
| `TraceRecorder` | 执行轨迹记录 |
| `AgentToolProvider` | 工具提供（内置工具/Skill/MCP） |
| `ToolExecutionCoordinator` | 波次并行工具执行协调器 |
| `ExecutionCompletionPolicy` | 任务完成判定策略（`<completion_control>` 和 `<await_user_input>` 协议） |
| `CompactionEngine` | 循环中途上下文压缩 |

### 2.4 可选依赖（@Nullable）

| 组件 | 职责 |
|------|------|
| `MultimodalRouter` | 多模态流式请求 |
| `MediaDataExtractor` | 媒体数据提取 |
| `MediaValidator` | 媒体验证 |

## 3. 核心特性

### 3.1 预算控制与渐进式降级

- Token 数量限制
- 执行时间限制
- 步骤数限制

预算耗尽时通过 `DegradedResponseBuilder.terminateWithReason()` 进入降级路径，若已有有意义的执行进展（`hasMeaningfulProgress`），优先调用 LLM 做结构化总结（`buildGracefulSummary()`）再返回；无进展时直接截断终止。工具执行失败后（`reflect-on-tool-failure`），循环自动触发反思回顾（Reflect），分析失败原因并输出调查报告，帮助 LLM 决定是否重试或调整策略。

5 级渐进式预算降级策略：NORMAL → COMPRESS_HISTORY → TRIM_TOOLS → SKIP_MEMORY → TERMINATE，逐步收缩能力而非直接终止。

### 3.1.1 挂起/恢复

支持需要用户确认的长任务中断（`Suspend` 步骤）和恢复（`Resume` 步骤），通过 `AgentOrchestrator.resumeFromSuspend(traceId, ResumePayload)` 从挂起点继续执行。

### 3.2 SSE 流式输出

支持 Server-Sent Events 流式响应，多种事件类型：

- `TRACE_STEP`: 步骤事件
- `TRACE_END`: 结束事件
- `A2UI_COMPONENT`: UI 组件事件
- `REASONING`: 推理过程事件

### 3.3 A2UI 组件

支持 Generative UI，LLM 返回的结构化数据可直接渲染为 UI 组件：

```java
// A2uiComponentTree: 组件树结构
// StreamingA2uiParser: 流式解析
// A2uiComponentCatalog: 组件目录
```

### 3.4 上下文智能组装

`ContextAssembler` 当前采用更明确的分层组装方式：

- 系统 Prompt（通过 `PromptRegistry`）
- 运行时环境（当前时间、位置、天气摘要等，注入 `react-user-prompt.st` 的 `<runtime_context>` 段）
  - 天气摘要通过 `WeatherService`（实现为 `OpenMeteoWeatherService`）注入到 `{weather}` 模板变量
- 当前 session 最近完整轮次（通过 `ContextEngine` 从 transcript 读取）
- L1 临时工作区摘要（通过 `ContextEngine.ContextSnapshot.workspaceItems()` 读取）
- L3 用户画像与经验实体
  - 用户画像优先读取 `UserProfileConsolidator` 巩固后的连贯画像（`__consolidated_profile` CUSTOM 实体），降级为零散实体拼接
- 其他段落按需预留

位置通过 `LocationResolver` 解析（配置手动覆盖 > IP 自动检测），天气通过 `OpenMeteoWeatherService` 获取（仅读缓存，不阻塞对话路径）。

跨会话原始对话不会自动注入主 Prompt；如需回忆别的会话，Agent 应显式调用 `memory.recall`。

### 3.6 对话完成后处理

对话结束时发布 `ConversationCompletedEvent`，由 `ConversationCompletionHook`（虚拟线程异步）触发：

1. **隐式信号检测**（`ImplicitSignalCollector`）：检查用户是否因主动通知而发起对话（参与度检测），以及是否存在引擎该推但没推的内容（未命中检测）
2. **对话摘要生成**（`ConversationSummaryGenerator`）：消息数 >= 3 且无现有摘要时，调用 LLM 生成简短摘要写入 `session_store.summary`
3. **用户画像巩固**（`UserProfileConsolidator`）：内置 2h 防抖，从 L3 碎片实体 + L2 近期对话 + L4 偏好规则出发，LLM 生成第三人称画像写入 L3

### 3.5 媒体处理

支持多模态输入（图片/音频），通过 MediaProcessor 验证和提取内容。

## 4. 核心类说明

| 类 | 职责 |
|---|------|
| `AgentOrchestrator` | 编排器（同步/流式/恢复入口） |
| `ReactAgentLoop` | ReAct 循环执行器（仅暴露 `coreLoop()`） |
| `AgentRequest` | 请求模型（消息/会话ID/附件/配置） |
| `AgentResponse` | 响应模型（内容/流式/工具调用/Token使用） |
| `ContextAssembler` | 上下文组装器 |
| `ToolExecutionCoordinator` | 波次并行工具执行协调器 |
| `ExecutionCompletionPolicy` | 任务完成判定策略 |
| `CompactionEngine` | 循环中途上下文压缩 |
| `DegradedResponseBuilder` | 降级响应构建器 |

## 5. 配置项

```yaml
lifepilot:
  agent:
    enabled: true
    loop:
      max-iterations: 25
      max-consecutive-failures: 3
      max-parallel-tool-calls: 4
      llm-scene: agent_react
      reflect-on-tool-failure: true
      stall-detection-threshold: 3
    budget:
      default-max-tokens: 20000000
      default-max-steps: 30
      default-max-duration-seconds: 1800
    context:
      max-context-tokens: 2000000
      output-reserved-tokens: 8192
    checkpoint:
      enabled: true
      max-age: 7d
    execution-retry:
      enabled: true
      max-attempts: 2
    session:
      timeout-minutes: 30
    debug:
      log-llm-prompts: false
```

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.agent.enabled` | `true` | Agent 引擎总开关 |
| `lifepilot.agent.loop.max-iterations` | 25 | 单次循环最大迭代次数 |
| `lifepilot.agent.loop.max-consecutive-failures` | 3 | 连续失败最大次数 |
| `lifepilot.agent.loop.max-parallel-tool-calls` | 4 | 单个工具波次最大并发数 |
| `lifepilot.agent.budget.default-max-tokens` | 20000000 | 对话总 Token 预算 |
| `lifepilot.agent.budget.default-max-steps` | 30 | 步数预算上限 |
| `lifepilot.agent.budget.default-max-duration-seconds` | 1800 | 时间预算上限（秒，2026-05 从 300 上调至 1800） |
| `lifepilot.agent.loop.reflect-on-tool-failure` | `true` | 工具执行失败时触发反思回顾 |
| `lifepilot.agent.loop.stall-detection-threshold` | 3 | 停滞检测阈值（连续重复模式判定） |
| `lifepilot.agent.context.max-context-tokens` | 2000000 | 单次 LLM 调用最大上下文 Token 数 |
| `lifepilot.agent.checkpoint.enabled` | `true` | 检查点功能开关 |
| `lifepilot.agent.execution-retry.enabled` | `true` | 主执行链路自动重试开关 |
| `lifepilot.agent.debug.log-llm-prompts` | `false` | 是否打印完整提示词 |

> 工具可见性由 `lifepilot.tool.tier1.pinned` + `activatedToolIds` + `tool.search` 共同决定，详见 [工具系统](tool-ecosystem.md)。

## 6. 使用场景

用户通过 CLI 或 Web UI 发送消息，Agent 引擎：

1. 接收用户消息
2. 组装上下文（最近完整轮次/工作区/画像/经验）
3. 进入 ReAct 循环：
   - 调用 LLM 获取响应
   - 若有工具调用，执行工具并记录结果
   - 重复直到完成
4. 返回响应（流式或非流式）

## 7. 限制与未来方向

- 当前依赖 LLM 输出格式稳定性，格式偏差可能导致解析失败
- 主动引擎的时机预测模型当前仅使用规则回退，危险率模型和时序点过程为后续迭代方向
- 未来：优化首字响应时间（TTFT）、引入更细粒度执行策略

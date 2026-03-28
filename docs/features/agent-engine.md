# Agent 引擎 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.agent`
> **最后更新**：2026-03

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

- `ReactAgentState`: 当前执行状态（idle/running/streaming/completed/error）
- `ReactStep`: 步骤记录（llm_call/tool_call/answer/error）
- `Budget`: 预算控制（token数量/时间/步骤数）

### 2.3 核心依赖

| 组件 | 职责 |
|------|------|
| `ContextAssembler` | 动态组装 LLM 上下文（系统Prompt/记忆检索/对话历史/知识库） |
| `LlmRouter` | 多模型路由、熔断器、故障转移 |
| `TraceRecorder` | 执行轨迹记录 |
| `SessionManager` | 会话管理 |
| `AgentToolProvider` | 工具提供（内置工具/Skill/MCP） |

### 2.4 可选依赖（@Nullable）

| 组件 | 职责 |
|------|------|
| `MultimodalRouter` | 多模态流式请求 |
| `MediaDataExtractor` | 媒体数据提取 |
| `MediaValidator` | 媒体验证 |

## 3. 核心特性

### 3.1 预算控制

- Token 数量限制
- 执行时间限制
- 步骤数限制

预算耗尽时自动生成降级响应（DegradedResponseBuilder），汇总已完成的步骤结果。

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
- 当前 session 最近完整轮次（通过 `ContextEngine` 从 transcript 读取）
- L1 临时工作区摘要（通过 `SessionWorkspaceService` 读取）
- L3 用户画像与经验实体
- 其他段落按需预留

跨会话原始对话不会自动注入主 Prompt；如需回忆别的会话，Agent 应显式调用 `memory.recall`。

### 3.5 媒体处理

支持多模态输入（图片/音频），通过 MediaProcessor 验证和提取内容。

## 4. 核心类说明

| 类 | 职责 |
|---|------|
| `ReactAgentLoop` | ReAct 循环执行器 |
| `AgentRequest` | 请求模型（消息/会话ID/附件/配置） |
| `AgentResponse` | 响应模型（内容/流式/工具调用/Token使用） |
| `ContextAssembler` | 上下文组装器 |
| `SessionManager` | 会话管理器 |
| `DegradedResponseBuilder` | 降级响应构建器 |

## 5. 配置项

```yaml
lifepilot:
  agent:
    max-iterations: 10
    budget:
      max-tokens: 4000
      max-duration: 60s
      max-steps: 20
    context:
      mode: full  # basic / full
```

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.agent.max-iterations` | 10 | 单次循环最大迭代次数 |
| `lifepilot.agent.budget.max-tokens` | 4000 | Token 预算上限 |
| `lifepilot.agent.budget.max-duration` | 60s | 时间预算上限 |
| `lifepilot.agent.budget.max-steps` | 20 | 步数预算上限 |
| `lifepilot.agent.context.mode` | full | 上下文组装模式 |

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
- 反思阶段评估质量依赖 LLM 能力
- 未来：优化首字响应时间（TTFT）、引入更细粒度执行策略

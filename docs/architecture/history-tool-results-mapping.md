# 历史对话与工具结果加载映射

> **文档性质**：架构映射文档  
> **对比对象**：本项目 / OpenClaw  
> **最后更新**：2026-03-23

## 1. 文档目标

本文只回答一个具体问题：

- 历史对话在本项目中存了什么
- 历史对话在下一轮请求中真正加载了什么
- 历史工具调用结果是否会再次进入模型上下文
- 这些行为与 OpenClaw 的差异在哪里

本文刻意区分四个概念，避免混淆：

1. 当前轮运行时上下文
2. 跨轮历史持久化
3. 下一轮 prompt 重建
4. UI / 调试回放数据

---

## 2. 结论先行

### 2.1 本项目当前结论

- 当前轮里，工具结果会作为 `ToolResponseMessage` 继续喂给 LLM，因此当前轮 ReAct 推理能看到工具输出。
- 跨轮后，历史工具结果不会以“一等公民 transcript”形式重新加载进下一轮模型上下文。
- 下一轮真正进入 prompt 的历史对话，来自 `chat_messages` 的最近完整轮次，并且只注入 `[role] content` 纯文本。
- `reasoning_summary`、`react_steps_json`、`a2ui_components_json`、附件、Trace 里的 `ToolCallStep`，默认都只用于 UI、调试、回放或异步后处理，不会自动回灌到下一轮模型上下文。

### 2.2 OpenClaw 对照结论

- OpenClaw 会把真实 transcript 持久化到 `<sessionId>.jsonl`，其中包含用户消息、助手消息、工具调用结果、压缩摘要等。  
  参考：[Session Management Deep Dive](https://docs.openclaw.ai/reference/session-management-compaction)
- 下一轮重建上下文时，工具调用结果属于 context 的正式组成部分。  
  参考：[Context](https://docs.openclaw.ai/concepts/context)、[Token Use and Costs](https://docs.openclaw.ai/reference/token-use)
- 但 OpenClaw 并不会保证“历史工具结果原文全量加载”。它会对旧 `toolResult` 做 `session pruning`，并在长会话中用 `compaction` 把更老历史总结后持久化。  
  参考：[Session Pruning](https://docs.openclaw.ai/concepts/session-pruning)、[Compaction](https://docs.openclaw.ai/concepts/compaction)

一句话概括：

- 本项目：`历史工具结果 = 主要存给 UI/调试看，不是下一轮主上下文`
- OpenClaw：`历史工具结果 = transcript 的正式组成部分，但会被 pruning / compaction 动态裁剪`

---

## 3. 本项目：数据存储与加载链路

## 3.1 持久化层分工

当前项目中，与“历史对话/历史工具结果”相关的主要持久化层有四类：

| 层 | 存储位置 | 主要用途 |
|---|---|---|
| Web 会话元数据 | `chat_sessions` | 会话标题、侧边栏预览、会话级配置 |
| Web 消息历史 | `chat_messages` | Web UI 历史消息、回放、分叉 |
| 运行态快照 | `agent_sessions` | Agent 恢复、最近轮次快照、实体提及 |
| 观测与调试 | `traces / trace_steps` | LLM / 工具调用观测、回放 |

对应代码入口：

- 历史写入抽象：[ConversationHistoryStore](../../src/main/java/com/lifepilot/conversation/ConversationHistoryStore.java)
- JDBC 实现：[JdbcConversationHistoryStore](../../src/main/java/com/lifepilot/interaction/web/service/JdbcConversationHistoryStore.java)
- 消息表访问：[ChatMessageRepository](../../src/main/java/com/lifepilot/interaction/web/repository/ChatMessageRepository.java)
- 会话快照：[SessionManager](../../src/main/java/com/lifepilot/agent/session/SessionManager.java)
- Trace 回放：[TraceController](../../src/main/java/com/lifepilot/interaction/web/controller/TraceController.java)

---

## 3.2 `chat_messages` 实际存了什么

`chat_messages` 当前至少存这些字段：

- `id`
- `session_id`
- `role`
- `content`
- `reasoning_summary`
- `trace_id`
- `a2ui_components_json`
- `react_steps_json`
- `completion_mode`
- `resumed_from_trace_id`
- `created_at`

代码见：[ChatMessageRepository](../../src/main/java/com/lifepilot/interaction/web/repository/ChatMessageRepository.java)

这意味着一条 assistant 历史消息，落库时通常会带上：

- 用户可见回复正文
- 本轮推理摘要
- 完整 ReAct 步骤序列化结果
- 可选 A2UI 组件树
- 恢复来源 traceId

但这些“额外字段”后续是否回灌给模型，要看 prompt 重建链路，而不是看是否落库。

---

## 3.3 `agent_sessions` 实际存了什么

`agent_sessions` 更偏运行态快照，而不是 Web transcript 主表。它保存：

- `recent_turns_json`
- `mentioned_entities_json`
- `last_active_at`
- `total_turns`
- `total_tokens_used`

其中 `recent_turns_json` 的单条结构是 [ConversationTurn](../../src/main/java/com/lifepilot/agent/session/ConversationTurn.java)：

- `userMessage`
- `agentResponse`
- `toolsUsed`
- `timestamp`
- `reasoningSummary`

但当前保存时 `toolsUsed` 实际写入的是空列表，见 [SessionManager](../../src/main/java/com/lifepilot/agent/session/SessionManager.java)。因此它现在并不是“可重建工具调用 transcript”的可靠来源。

---

## 3.4 当前轮：工具结果如何进入模型上下文

这一点和“跨轮历史加载”完全不同。

在同一轮 ReAct 循环内，工具执行完成后会先生成 `Observation` 步骤，然后在下一次构建消息列表时转换成 `ToolResponseMessage`，重新发给 LLM。代码见：

- [ReactAgentLoop.buildMessages()](../../src/main/java/com/lifepilot/agent/ReactAgentLoop.java)
- [ReactAgentLoop](../../src/main/java/com/lifepilot/agent/ReactAgentLoop.java) 中 `ReactStep.Observation -> ToolResponseMessage`

因此：

- 当前轮工具输出：LLM 能看到
- 跨轮后的历史工具输出：默认不会按工具结果再次进入模型上下文

---

## 3.5 下一轮：历史对话真正从哪里加载

下一轮组装 prompt 时，历史对话不是直接从 `agent_sessions.recent_turns_json` 来的，而是走下面这条链：

1. [ContextAssembler](../../src/main/java/com/lifepilot/agent/context/ContextAssembler.java) 调用 `safeGetRecentTurns(sessionId)`
2. [DefaultConversationViewService](../../src/main/java/com/lifepilot/conversation/DefaultConversationViewService.java) 从 [ChatMessageRepository](../../src/main/java/com/lifepilot/interaction/web/repository/ChatMessageRepository.java) 取整段会话消息
3. [ConversationTurnGrouper](../../src/main/java/com/lifepilot/conversation/ConversationTurnGrouper.java) 只保留最近 N 个“完整轮次”
4. [ContextAssembler.formatConversationHistorySection()](../../src/main/java/com/lifepilot/agent/context/ContextAssembler.java) 把它们转成：

```text
[user] ...
[assistant] ...
```

这里有两个关键点：

- 加载源是 `chat_messages`
- 注入格式只有 `role + content`

也就是说，`reasoning_summary`、`react_steps_json`、`trace_id`、附件、A2UI 组件树，都不会自动进入下一轮 prompt。

---

## 3.6 历史工具结果在本项目里的真实位置

如果只问“历史工具结果到底存在哪”，当前项目里它们分散在四个地方：

| 位置 | 是否持久化 | 是否默认进入下一轮 prompt | 说明 |
|---|---|---|---|
| `ReactStep.Observation` -> `react_steps_json` | 是 | 否 | 主要给前端步骤回放和调试 |
| `TraceStep.ToolCallStep` | 是 | 否 | 主要给 observability / trace 回放 |
| assistant 最终回复正文 | 是 | 是，但已被总结/改写 | 工具输出若进入历史，通常已经被助手总结到自然语言回复里 |
| 工具产生的媒体附件 | 是 | 否 | 通过 `message_attachments` 供 UI 查看，不自动重新注入 |

所以当前项目并没有一个类似 OpenClaw `toolResult message` 的“一等公民历史结构”。

---

## 3.7 一个当前实现上的边界

当前历史注入逻辑没有按 role 做严格过滤，只是把最近完整轮次的 `content` 拼进去。这个行为带来一个副作用：

- `system` 消息如果落在最近完整轮次中，可能会以普通文本进入历史段
- `tool-confirmation` 这类 JSON 内容，也可能作为普通文本进入历史段

相关代码：

- [JdbcConversationHistoryStore.appendSystemMessage()](../../src/main/java/com/lifepilot/interaction/web/service/JdbcConversationHistoryStore.java)
- [WebUserConfirmationService](../../src/main/java/com/lifepilot/interaction/web/service/WebUserConfirmationService.java)
- [ConversationTurnGrouper](../../src/main/java/com/lifepilot/conversation/ConversationTurnGrouper.java)

这说明当前项目的“历史重建”更接近“Web 聊天消息视图拼接”，而不是“严格 transcript 语义重建”。

---

## 4. OpenClaw：历史工具结果的正式处理模型

## 4.1 双层持久化

OpenClaw 的 session 持久化明确分两层：

1. `sessions.json`
   存会话元数据、当前 sessionId、更新时间、开关、token 统计等
2. `<sessionId>.jsonl`
   存 append-only transcript，包含真实会话树、工具结果、压缩摘要等

官方文档明确说明：

- `sessions.json` 是 metadata store
- `<sessionId>.jsonl` 存 `conversation + tool calls + compaction summaries`
- 后续请求会基于 transcript 重建模型上下文

参考：[Session Management Deep Dive](https://docs.openclaw.ai/reference/session-management-compaction)

---

## 4.2 transcript 中的工具结果是“一等公民”

OpenClaw 的 transcript entry 类型中，`message` 明确覆盖：

- `user`
- `assistant`
- `toolResult`

此外还有：

- `compaction`
- `branch_summary`
- `custom_message`

这意味着：

- 工具结果不是调试附属物
- 它属于可参与未来上下文重建的正式 transcript 项

参考：[Session Management Deep Dive](https://docs.openclaw.ai/reference/session-management-compaction)

---

## 4.3 OpenClaw 当前请求的 context 构成

OpenClaw 官方对 context 的定义非常直接：凡是发给模型的都算 context。它明确包含：

- system prompt
- 会话历史
- 工具调用与工具结果
- 附件与转录内容
- compaction 摘要
- pruning 产生的占位内容

参考：

- [Context](https://docs.openclaw.ai/concepts/context)
- [Token Use and Costs](https://docs.openclaw.ai/reference/token-use)

因此在 OpenClaw 中，历史工具结果默认属于“可能进入模型上下文”的正式内容，而不是仅供 UI 查看。

---

## 4.4 OpenClaw 不会无条件全量加载历史工具结果

虽然工具结果是 transcript 正式组成部分，但 OpenClaw 不保证历史工具结果原文全量进入下一轮模型上下文。

### 4.4.1 Session pruning

`session pruning` 的规则是：

- 只修剪 `toolResult`
- 不修改 `user` / `assistant`
- 只影响当前这次发给模型的内存 context
- 不改写磁盘上的 `*.jsonl`

修剪方式包括：

- `soft-trim`：保留头尾，中间用省略和说明替换
- `hard-clear`：直接替换成占位符

参考：[Session Pruning](https://docs.openclaw.ai/concepts/session-pruning)

### 4.4.2 Compaction

当上下文接近上限时，OpenClaw 会把更老历史压缩成 `compaction` 摘要，并持久化到 session JSONL 中。之后未来请求看到的是：

- compaction summary
- 压缩点之后的 recent messages

这同样意味着：旧工具结果不会长期原样全量保留在模型上下文里。

参考：

- [Compaction](https://docs.openclaw.ai/concepts/compaction)
- [Session Management Deep Dive](https://docs.openclaw.ai/reference/session-management-compaction)

### 4.4.3 Transcript hygiene

OpenClaw 在真正发送请求前，还会做 provider-specific transcript hygiene：

- 工具调用 ID 清洗
- 工具结果配对修复
- turn 校验
- 图像负载清洗

这些修正是内存中的，不改写正常的已存 JSONL transcript。

参考：[Transcript Hygiene](https://docs.openclaw.ai/reference/transcript-hygiene)

---

## 5. 映射对照表

| 维度 | 本项目 | OpenClaw |
|---|---|---|
| 历史主存储 | `chat_messages` + `chat_sessions` | `sessions.json` + `<sessionId>.jsonl` |
| 运行态快照 | `agent_sessions` | `sessions.json` 中的会话元数据 + runtime 状态 |
| 工具结果是否单独入历史 | 否，主要散落在 `react_steps_json` / trace / assistant 回复摘要里 | 是，`toolResult` 是 transcript 正式消息类型 |
| 当前轮工具结果是否喂回 LLM | 是 | 是 |
| 跨轮后历史工具结果是否默认再进入模型 | 否，默认不会按工具结果结构重建 | 是，属于 transcript 正式组成部分 |
| 下一轮历史加载粒度 | 最近完整轮次的 `[role] content` 文本 | transcript 重建后的完整 context |
| 历史压缩策略 | 当前没有 conversation 级 compaction | 有持久化 `compaction` |
| 历史工具结果裁剪 | 当前没有跨轮 tool-result pruning | 有 `session pruning`，只修剪旧 `toolResult` |
| 角色/顺序修正 | 基本没有 transcript hygiene | 有 provider-specific transcript hygiene |
| UI 回放与模型上下文是否同构 | 否，UI/trace 数据更丰富，模型吃得更少 | 更接近同一份 transcript 的不同视图 |

---

## 6. 对当前项目的含义

## 6.1 当前实现更像“聊天消息视图系统”

本项目目前的设计重点是：

- Web UI 历史展示
- 历史消息分叉
- ReAct 轨迹回放
- 会话快照恢复

而不是“把完整 transcript 原样重建给模型”。

因此它天然会出现以下现象：

- UI 里能看到完整 ReAct 步骤，不代表下一轮模型也能看到
- Trace 里能看到工具调用，不代表下一轮模型也能看到
- 历史 assistant 文本能进入 prompt，不代表工具原始输出能进入 prompt

## 6.2 如果未来要向 OpenClaw 靠拢，需要补的不是一个小开关

如果目标是“让历史工具结果像 OpenClaw 那样成为正式上下文组成部分”，至少需要补齐这几层：

1. 一等公民 transcript schema
   将 `toolResult` 从 `react_steps_json` / trace 中剥离，变成正式历史消息类型
2. transcript -> context rebuild 逻辑
   不再只拼 `[role] content`
3. conversation 级 compaction
   压缩更老历史，而不是无限堆积
4. tool-result pruning
   对旧工具结果做软裁剪或硬清除
5. transcript hygiene
   针对不同 provider 做顺序、配对、图像、tool call id 修复

换句话说，这不是“多读一个字段”就能完成的改造，而是历史模型从“聊天消息视图”升级到“正式 transcript 引擎”。

---

## 7. 建议结论

当前项目的真实定位可以明确成一句话：

> 历史对话主链路以 Web 消息视图为中心；工具结果主要服务于当前轮推理、前端回放和调试观测，而不是跨轮 transcript 重建。

如果后续要继续演进，建议先在架构文档中明确二选一：

- 继续维持当前模式：历史工具结果不跨轮回灌，只保留“助手总结结果进入历史”
- 升级为 transcript 模式：参考 OpenClaw，把 `toolResult` 变成正式历史项，再引入 pruning / compaction / hygiene

在没有做第二种改造之前，不应把当前项目描述成“会自动加载历史工具结果”。

---

## 8. 参考资料

### 8.1 本项目代码

- [ContextAssembler](../../src/main/java/com/lifepilot/agent/context/ContextAssembler.java)
- [ReactAgentLoop](../../src/main/java/com/lifepilot/agent/ReactAgentLoop.java)
- [ConversationHistoryStore](../../src/main/java/com/lifepilot/conversation/ConversationHistoryStore.java)
- [DefaultConversationViewService](../../src/main/java/com/lifepilot/conversation/DefaultConversationViewService.java)
- [ConversationTurnGrouper](../../src/main/java/com/lifepilot/conversation/ConversationTurnGrouper.java)
- [SessionManager](../../src/main/java/com/lifepilot/agent/session/SessionManager.java)
- [ChatMessageRepository](../../src/main/java/com/lifepilot/interaction/web/repository/ChatMessageRepository.java)
- [AgentPersistenceHandler](../../src/main/java/com/lifepilot/agent/persistence/AgentPersistenceHandler.java)
- [WebUserConfirmationService](../../src/main/java/com/lifepilot/interaction/web/service/WebUserConfirmationService.java)

### 8.2 OpenClaw 官方文档

- [Context](https://docs.openclaw.ai/concepts/context)
- [Token Use and Costs](https://docs.openclaw.ai/reference/token-use)
- [Session Pruning](https://docs.openclaw.ai/concepts/session-pruning)
- [Compaction](https://docs.openclaw.ai/concepts/compaction)
- [Session Management Deep Dive](https://docs.openclaw.ai/reference/session-management-compaction)
- [Transcript Hygiene](https://docs.openclaw.ai/reference/transcript-hygiene)

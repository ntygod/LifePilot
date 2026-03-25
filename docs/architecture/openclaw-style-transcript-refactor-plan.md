# OpenClaw 对齐改造最终计划

> 文档性质：正式计划 / 唯一执行基线
> 状态：待确认，确认前暂停继续实现
> 最后更新：2026-03-24
> 关联文档：
> - [history-tool-results-mapping.md](./history-tool-results-mapping.md)
> - [memory-capability-preservation-plan.md](./memory-capability-preservation-plan.md)
> 官方参考：
> - https://docs.openclaw.ai/concepts/context
> - https://docs.openclaw.ai/concepts/session-pruning
> - https://docs.openclaw.ai/concepts/compaction
> - https://docs.openclaw.ai/reference/transcript-hygiene
> - https://docs.openclaw.ai/reference/session-management-compaction

## 1. 目标结论

本次改造的目标不是“参考 OpenClaw 的部分思路”，而是把本项目的会话事实源、上下文构建方式、历史工具结果处理方式，正式收敛到与 OpenClaw 一致的语义模型：

- 会话历史以 `transcript` 为唯一事实源。
- 历史对话与历史工具调用结果按顺序保留在同一条 transcript 消息流中。
- `pruning` 只裁剪本次请求发给模型的上下文，不改写 transcript 事实源。
- `compaction` 负责把旧历史压缩为摘要并写回 transcript。
- `transcript hygiene` 在 provider 调用前修复消息顺序和工具调用配对问题。
- 长期记忆系统独立存在，不能被 `compaction/pruning` 替代。

同时，本项目不机械复制 OpenClaw 的 JSONL 落盘形式，而采用“语义一致、实现等价”的方式：

- OpenClaw 的 `sessions.json` / `session.jsonl`
  对应本项目的 `session_store` / `session_transcript_entries`
- OpenClaw 的 memory 文件体系
  对应本项目的 `memory_documents` / `memory_document_chunks`

结论上，本次最终形态必须满足：

> OpenClaw 的关键语义保持一致，底层存储形式允许按本项目技术栈做等价实现。

## 2. 与 OpenClaw 对齐的硬约束

以下约束视为最终目标，不允许在后续实现中漂移：

### 2.1 Transcript 是唯一会话事实源

以下内容都必须作为 transcript entry 进入统一事实源：

- `user_message`
- `assistant_message`
- `tool_call`
- `tool_result`
- `compaction_summary`
- `artifact_ref`
- `system_event`
- `memory_flush_event`

以下内容不能再承担“历史事实源”职责：

- `chat_messages`
- `chat_sessions`
- `agent_sessions`
- `react_steps_json`
- 仅用于调试的 trace steps

### 2.2 历史上下文必须保序

历史对话和历史工具结果不能再拆成：

- `conversationHistorySection`
- `toolResultsSection`

两个彼此独立的摘要模块。

最终必须改为：

- 先从 transcript 切出有序 slice
- 再构造成 provider message sequence
- 再执行 hygiene / pruning / compaction 相关逻辑

也就是说，历史上下文的核心输入不再是“若干 prompt section”，而是“有序 transcript 条目流”。

### 2.3 Pruning 只作用于单次请求上下文

`SessionPruningEngine` 的职责必须与 OpenClaw 保持一致：

- 只影响本次请求发给模型的上下文
- 不改写 transcript 存储
- 重点处理老旧 `tool_result`
- 必要时可做 `soft-trim` 或完全剔除

它不能再承担：

- 长期压缩
- 长期遗忘
- 历史事实改写

### 2.4 Compaction 是 transcript 内事件，不是 prompt 附件

`compaction_summary` 必须是 transcript 中的正式条目，而不是模板里的临时注释块。

要求：

- 压缩结果写入 transcript
- 保留压缩边界
- 后续上下文读取从边界后开始
- 模型看到的是“压缩摘要 + 较新的 transcript 条目”

### 2.5 Hygiene 发生在 provider 调用前

必须保留单独的 `TranscriptHygieneEngine`，在最终 provider message build 之后执行。

它要解决的不是业务摘要，而是消息合法性，例如：

- tool call / tool result 顺序修正
- provider 不兼容消息过滤
- 空消息和非法消息折叠
- 多模态内容合法化

### 2.6 Memory 是独立层

以下语义必须严格分离：

- `transcript`：发生过什么
- `context`：这次要给模型看什么
- `memory`：以后还应长期记住什么

`compaction` 不是 `forgetting`
`pruning` 不是 `memory consolidation`

## 3. 当前实现与目标态的关键差距

当前实现已经做了较多 transcript-first 基础设施，但还没有完全对齐 OpenClaw。核心差距如下：

### 3.1 历史上下文仍是模块化拼装，不是 transcript 重放

当前 [ContextEngine.java](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/agent/context/ContextEngine.java) 输出的是：

- `recentTurns`
- `artifactSection`
- `toolResultsSection`
- `compactionSection`

当前 [ContextAssembler.java](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/agent/context/ContextAssembler.java) 仍然把这些内容拼到 [react-user-prompt.st](D:/WorkSpace/Project/News/src/main/resources/prompts/agent/react-user-prompt.st)。

这意味着：

- 历史工具结果与历史对话失去一一对应关系
- 历史上下文 fidelity 低于执行态
- prompt 模板承担了太多 transcript 语义

### 3.2 历史 `tool_result` 被扁平化

当前 [SessionPruningEngine.java](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/agent/context/SessionPruningEngine.java) 会把多个历史 `tool_result` 扁平化成摘要行列表。

这和 OpenClaw 的目标语义不一致。OpenClaw 的做法是：

- `toolResult` 仍然是消息流中的一类消息
- pruning 在消息流上裁剪，而不是先投影成独立摘要模块

### 3.3 当前轮是结构化的，跨轮历史不是

当前 [ProviderMessageBuilder.java](D:/WorkSpace/Project/News/src/main/java/com/lifepilot/agent/context/ProviderMessageBuilder.java) 会按 `ReactStep` 顺序构造：

- assistant tool call
- tool response
- assistant reply

这说明本项目的“当前轮执行态”已经是结构化消息流。

真正缺的是：

- 把“历史 transcript”也按同样语义构造成消息流
- 而不是退回到 prompt section 拼装

### 3.4 Compaction 仍然偏“上下文辅助块”思维

虽然已经有 `compaction_summary` 与边界解析，但当前上下文链路仍把它当成 `compactionSection` 注入模板。

最终目标必须改为：

- `compaction_summary` 作为 transcript entry 参与 slice
- provider 构造阶段决定如何把它作为消息注入

### 3.5 Memory 保护原则需要固化到执行阶段

[memory-capability-preservation-plan.md](./memory-capability-preservation-plan.md) 已经定义了能力守恒要求，但后续实现必须严格遵守：

- 不能因为 context 链路改造，削弱实时自学习、遗忘系统、模式提炼、反馈学习
- 不能再让“历史消息读模型”和“长期记忆能力”混在同一套职责里

## 4. 最终目标形态

## 4.1 会话与 transcript 存储

### 4.1.1 Session Store

保留 `session_store`，作为会话元数据与 UI 列表读模型。

职责：

- 会话标题与摘要
- provider/model 覆盖配置
- token 统计
- active branch
- 最近活动时间
- compaction 统计

它不是历史事实源。

### 4.1.2 Transcript Store

`session_transcript_entries` 成为唯一历史事实源。

建议保留或收敛到以下字段：

- `id`
- `session_id`
- `parent_id`
- `branch_id`
- `entry_type`
- `role`
- `turn_id`
- `trace_id`
- `visible_to_model`
- `visible_to_user`
- `payload_json`
- `token_estimate`
- `created_at`

### 4.1.3 Artifact Store

`session_artifacts` 作为大结果、结构化中间产物和外部引用的存储层。

规则：

- transcript 中只保留 `artifact_ref`
- 大 payload 放入 artifact store

## 4.2 Provider 上下文构建链

最终上下文构建流程必须收敛成以下顺序：

1. 从 transcript 读取当前 branch 的有序条目
2. 应用 compaction 边界，得到 active slice
3. 根据上下文窗口决定 slice 范围
4. 把 transcript entry 转换为 provider-neutral messages
5. 在消息流上执行 pruning
6. 注入 memory / artifact / workspace / system prompt
7. 执行 transcript hygiene
8. 生成最终 provider messages

关键变化：

- `ContextAssembler` 退化为“少量系统提示词与记忆注入协调器”
- 不再承担历史 transcript 模块化拼装
- 主语义上移到 `ContextEngine + ProviderMessageBuilder + TranscriptHygieneEngine`

## 4.3 Transcript Entry 到消息流的映射

必须建立稳定映射层，例如：

- `user_message` -> `UserMessage`
- `assistant_message` -> `AssistantMessage`
- `tool_call` -> `AssistantMessage(toolCalls)`
- `tool_result` -> `ToolResponseMessage`
- `compaction_summary` -> 特殊 assistant/system 摘要消息
- `artifact_ref` -> 可选 system/context 注入消息

要求：

- 保留顺序
- 保留邻接关系
- 可按 provider 规则修复
- 支持多模态 payload

## 4.4 Prompt 模板收缩

[react-user-prompt.st](D:/WorkSpace/Project/News/src/main/resources/prompts/agent/react-user-prompt.st) 最终应被显著收缩。

它只保留真正属于“当前请求顶层提示词”的部分：

- 当前时间
- 用户当前目标
- 用户画像摘要
- 长期记忆摘要
- 经验摘要
- 时间约束
- 必要的系统级规则

它不再负责：

- 拼装历史对话
- 拼装历史工具结果
- 拼装 compaction 历史块

## 4.5 Web UI 与读模型

Web UI 仍然可以使用投影读模型，但只能是投影，不能反向决定底层事实结构。

要求：

- 消息列表来自 transcript projection
- 工具调用和工具结果可折叠显示
- compaction 节点可视化
- artifact 卡片可追溯到 source entry
- fork / branch 基于 transcript branch 语义

## 5. 与 OpenClaw 一致、但采用等价实现的部分

以下部分语义必须一致，但实现形式允许不同：

### 5.1 存储形式

OpenClaw 使用文件型 session/transcript。

本项目使用 SQLite 表：

- `session_store`
- `session_transcript_entries`
- `session_artifacts`
- `memory_documents`

这是等价实现，不是偏离目标。

### 5.2 Memory 形式

OpenClaw 使用 Markdown memory 文件。

本项目采用：

- Markdown 风格内容存储在 `memory_documents.content_markdown`
- 索引与 chunk 结构用于检索和治理

关键是语义对齐：

- durable
- human-readable
- traceable
- flush before compaction

### 5.3 UI 形态

OpenClaw 偏 CLI / Gateway。

本项目是 Web UI。

允许差异：

- 交互入口不同
- 会话展示不同

不允许差异：

- 底层 transcript 语义
- 上下文构建原则

## 6. 记忆系统的最终边界

本次改造不能牺牲现有记忆能力，最终边界如下：

### 6.1 Transcript Facts

记录发生过什么，不做业务推断。

### 6.2 Session Read Model

服务 UI 和情景回看，可重建，不是事实源。

### 6.3 Durable Memory

保存真正长期保留的内容：

- 用户偏好
- 项目知识
- 决策日志
- 长期事实
- 可读工作记忆沉淀

### 6.4 Structured Memory Index

包括：

- `SemanticMemory`
- `ProceduralMemory`
- `Experience`
- 遗忘治理相关索引

### 6.5 Active Workspace / Artifacts

保存当前任务正在进行中的状态和中间产物。

### 6.6 Pre-Compaction Memory Flush

在 compaction 之前，必须把即将离开 active context 的高价值信息先提炼进 durable memory。

这是本项目相对当前实现必须加强的一步。

## 7. 分阶段执行计划

以下阶段按顺序执行；每一阶段结束后都要先回归，再进入下一阶段。

## 阶段 0：冻结目标与差距核对

目标：

- 以本计划作为唯一基线
- 明确当前已实现能力与剩余差距
- 停止继续按旧 prompt section 思路扩展

产出：

- 本文档确认
- 差距清单

## 阶段 1：历史上下文改为 transcript slice 驱动

目标：

- 废弃“最近轮次 + 工具结果块”历史构建方式
- 建立有序 transcript slice -> provider-neutral messages 的主链

主要改动：

- 重写 `ContextEngine` 输出结构
- 新增 transcript entry 到 provider-neutral message 的映射层
- 让历史上下文不再依赖 `ConversationTurnView + toolResultsSection`
- 收缩 `ContextAssembler`

验收标准：

- 历史工具结果与历史对话在 prompt 构造中保序
- 多轮工具调用时，tool result 与对应 assistant turn 可追溯

## 阶段 2：Pruning 改为消息流级裁剪

目标：

- `SessionPruningEngine` 不再输出独立摘要 section
- 改为在消息流上裁剪旧 `tool_result`

主要改动：

- 保留 `tool_result` 的相对位置
- 支持 `recent-only / token-budget / off`
- 可按 provider 策略做 soft-trim

验收标准：

- pruning 不改写 transcript
- pruning 后消息流仍合法、仍保序

## 阶段 3：Compaction 彻底 transcript 化

目标：

- `compaction_summary` 成为消息流的一部分
- 从“上下文块”彻底转为“transcript event”

主要改动：

- compaction 结果写回 transcript
- compaction boundary 进入 session projection
- 上下文构建从 boundary 后开始，前置一条 compaction summary

验收标准：

- 长会话中模型看到的是“压缩摘要 + 新条目”
- UI 可以展示 compaction 节点

## 阶段 4：Hygiene 完整接管 provider 合法性修复

目标：

- 所有 provider 侧合法性修复统一由 `TranscriptHygieneEngine` 处理

主要改动：

- 工具调用配对修正
- 空 assistant/tool message 清理
- 多模态序列修正
- provider 特定非法序列修复

验收标准：

- context build 结束后得到的 provider messages 在各 provider 下都合法

## 阶段 5：Memory flush 与 durable memory 对齐

目标：

- 在不损失原有能力的前提下，把 transcript 改造与记忆系统正式对齐

主要改动：

- 建立统一 memory event 流
- compaction 前执行 memory flush
- `EpisodicMemory` 完全转为 transcript 投影
- `FeedbackProcessor`、`EffectivenessTracker`、`ConsolidationPipeline` 全量改用新 provenance

验收标准：

- 自学习、遗忘、模式提炼、反馈学习能力全部保持
- 工具结果和 artifact 也能进入长期记忆提炼链

## 阶段 6：Web 读模型与调试能力收口

目标：

- UI 与 API 全部站在 transcript projection 之上
- 提供足够强的上下文调试能力

主要改动：

- 会话消息时间线
- 工具调用/结果折叠卡片
- compaction 节点
- artifact 节点
- context report / hygiene report / pruning report

验收标准：

- UI 不再依赖旧聊天表口径
- 调试页能解释本轮实际送给模型的上下文

## 阶段 7：删除旧模块化历史拼装残留

目标：

- 清理所有与最终形态不一致的残留实现

删除范围：

- `conversationHistorySection` 历史拼装职责
- `toolResultsSection` 独立摘要职责
- 历史轮次与工具结果分离的中间读模型
- 不再需要的 legacy prompt 变量

验收标准：

- 主链中不存在“历史对话块 + 工具结果块”的旧思路
- 历史上下文只经由 ordered transcript slice 进入模型

## 8. 测试与验收标准

## 8.1 核心回归测试

至少新增或重写以下测试：

- 历史 transcript 多轮 tool call / tool result 保序构造测试
- pruning 仅影响本次上下文、不改 transcript 测试
- compaction 后上下文边界测试
- hygiene 修复 tool call/tool result 配对测试
- transcript-backed episodic recall 测试
- pre-compaction memory flush 测试
- Web transcript timeline 投影测试

## 8.2 最终验收标准

最终必须同时满足以下条件：

1. 历史上下文构建链与 OpenClaw 核心语义一致。
2. 历史工具结果不再以独立 prompt 模块注入。
3. 当前轮与历史轮都使用统一的 transcript 消息语义。
4. `pruning / compaction / hygiene / forgetting` 四者边界清晰。
5. 记忆系统原有能力不降级。
6. Web UI 和调试链可以解释模型实际拿到的上下文。
7. 旧模块化历史拼装代码被彻底删除，不保留兼容尾巴。

## 9. 执行原则

在你确认本计划前，不继续推进新的功能实现。

确认后，执行顺序必须遵守：

1. 先把最终形态对齐
2. 再逐阶段落地
3. 每阶段做定向回归
4. 最后统一清残留

后续所有实现偏差，都以本计划为准做纠偏。

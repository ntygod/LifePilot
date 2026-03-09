# Agent 4 阶段提示词复盘笔记

> 日期：2026-03-09
> 背景：prompt-optimization（外部化基础设施）和 agent-prompt-enhancement（内容增强第一轮）均已完成，本文档记录第二轮内容优化的待讨论问题。

---

## 已排除的问题

### ~~PLANNING 缺少可用工具信息注入~~

经源码验证，工具信息通过 Spring AI function calling 协议自动注入：
- `AgentLoop.callLlmAndParseAction()` 调用 `agentToolProvider.getToolCallbacks(state)` 获取当前可用工具
- `buildPrompt()` 通过 `prompt.toolCallbacks(callbacks)` 将工具注册到 ChatClient
- LLM 在 PLANNING 阶段能看到完整的工具 name / description / parameters schema
- `planning.st` 中的 `<tool_strategy>` 是策略性指导，不需要手动列出工具列表

---

## 待讨论的问题

### 1. 缺少 `{currentDateTime}` 变量

**现状**：understanding.st 要求"将相对时间转换为具体日期时间（如'明天下午三点'→ 具体 ISO 日期时间）"，但 LLM 没有当前时间锚点。

**影响**：LLM 只能依赖训练数据中的时间知识或猜测，无法准确转换"明天"、"下周一"等相对时间表达。

**可能方案**：
- 在 `buildSystemPrompt()` 或 `buildEnhancedUserPrompt()` 中注入 `currentDateTime` 变量
- 格式建议：ISO 8601（如 `2026-03-09T15:30:00+08:00`）
- 需要修改 ContextAssembler 和模板变量

### 2. reflecting.st 中的 `<duplicate_result_detection>` 业务逻辑

**现状**：reflecting.st 包含一段详细的重复结果检测逻辑，指导 LLM 在发现工具执行结果与上一轮相同时如何处理。

**问题**：
- 这是具体的业务判断逻辑，放在提示词中增加了模板复杂度
- 提示词应聚焦于"评估什么"和"如何评估"，而非编码特定的边界情况处理
- 如果类似的边界情况越来越多，提示词会膨胀

**可能方案**：
- 方案 A：保留在提示词中（简单，当前只有这一个边界情况）
- 方案 B：在 StateReducer 或 AgentLoop 中用代码检测重复结果，直接跳过 re-planning
- 方案 C：将其移到 `<context_guide>` 中作为"评估提示"而非硬性规则

### 3. responding.st 与 streaming-constraint.st 内容重叠

**现状**：
- `responding.st` 包含 `<tone_style>` 定义了回复风格（结构化格式、避免过度格式化等）
- `streaming-constraint.st` 包含"直接使用自然语言回复"、"适当使用 Markdown 格式"等
- 流式 RESPONDING 时两者都会注入到 system prompt

**问题**：
- 两个模板对格式化的指导有重叠（都提到了结构化格式和可读性）
- 可能产生冲突信号（responding.st 鼓励结构化，streaming-constraint.st 限制格式化）

**可能方案**：
- 方案 A：明确分工 — responding.st 管内容和语气，streaming-constraint.st 只管输出格式约束
- 方案 B：合并为一个模板，根据是否流式动态选择内容
- 方案 C：保持现状，streaming-constraint.st 作为追加约束覆盖 responding.st 的格式指导

### 4. Lost-in-the-Middle 效应

**现状**：understanding.st 中重要的复杂度判断规则（SIMPLE/MODERATE/COMPLEX 定义和典型场景）放在 `<instructions>` 中间位置。

**问题**：
- LLM 对提示词开头和结尾的注意力最强，中间部分容易被忽略
- 复杂度判断是 UNDERSTANDING 阶段最关键的输出之一

**可能方案**：
- 将复杂度判断规则提到 `<instructions>` 开头
- 或在末尾用"重要"标记重复关键规则（当前已有"重要：对于简单问候..."）
- 评估是否需要调整其他阶段模板的信息排列

### 5. `{userProfile}` 变量在 System Prompt 中始终为空

**现状**：
- `buildSystemPrompt()` 传入 `"userProfile", ""`（空字符串）
- 用户画像已移至 `buildEnhancedUserPrompt()` 的 User Prompt 半稳定区
- 但模板中仍保留 `{userProfile}` 占位符

**问题**：
- 模板中的 `{userProfile}` 永远渲染为空，是死代码
- 增加了模板的认知负担

**可能方案**：
- 方案 A：从所有阶段模板中移除 `{userProfile}` 变量，简化模板
- 方案 B：保留占位符，未来可能重新启用（如需要在 System Prompt 中注入用户画像摘要）

---

## 讨论优先级建议

| 问题 | 影响程度 | 实现复杂度 | 建议优先级 |
|------|---------|-----------|-----------|
| 1. currentDateTime | 高（时间转换准确性） | 低（注入一个变量） | P0 |
| 2. duplicate_result_detection | 中（提示词膨胀风险） | 中 | P2 |
| 3. responding/streaming 重叠 | 低（功能正常） | 低 | P2 |
| 4. Lost-in-the-Middle | 中（可能影响复杂度判断） | 低（调整文本顺序） | P1 |
| 5. userProfile 死代码 | 低（不影响功能） | 低 | P3 |

# AgentLoop 代码审计报告

> 审计日期：2026-03-05
> 审计范围：`AgentLoop.java`（1552 行）+ `AgentAutoConfiguration.java`
> 审计目标：调用链完整性、文档承诺能力验证、代码质量

---

## 1. 调用链路

```
HTTP 入口
  ChatController.sendMessageStream()  →  WebChannelAdapter.processMessageStreaming()
  ChatController.sendMessage()        →  WebChannelAdapter.processMessage()
      ↓
  MessageGateway 6 层中间件管道（Auth → RateLimit → Security → Router → Execution → Audit）
      ↓
  ExecutionMiddleware
      ↓
  AgentLoop.runStreaming() / AgentLoop.run()
      ↓
  核心循环（每次迭代）:
    1. forceTerminateIfIterationLimitReached()
    2. updateBudgetElapsed() → forceTerminateIfBudgetExceeded()
    3. assembleContext() → ContextAssembler.assemble()
    4. 分支:
       ├─ RESPONDING → callLlmStreamingAndParseAction()（流式）/ entity() 解析（非流式）
       ├─ EXECUTING → executeNextPlannedToolStep()
       └─ 其他阶段 → callLlmAndParseAction() → entity() → 降级 ActionParser
    5. reduceAndRecord() → StateReducer.reduce() + TraceRecorder.recordStep()
    6. LoopCounters.onAction() — 连续失败保护
      ↓
  循环结束后:
    writeAssistantMessageToL1()
    asyncPostProcess() — Virtual Thread:
      ├─ SessionManager.saveSession()
      ├─ ConversationHistoryStore.appendTurn()
      └─ RealtimeExtractor.extractAsync()
```

## 2. 文档承诺能力验证

| 能力 | 状态 | 实现位置 |
|------|------|---------|
| 可观测性（轨迹记录） | ✅ | TraceRecorder + LlmCallStep / StateTransitionStep / ToolCallStep |
| 记忆集成（L1 读写） | ✅ | writeUserMessageToL1 / writeAssistantMessageToL1 / hydrateWorkingMemoryFromConversationView |
| 记忆集成（L2 跨会话） | ✅ | ContextAssembler 中 EpisodicMemory.searchExcludingSession() |
| 记忆集成（L3 语义/AUDN） | ✅ | RealtimeExtractor.extractAsync() |
| 用户画像注入 | ✅ | ContextAssembler 中 SemanticMemory.findCurrentByType() |
| 多模态 | ✅ | callLlmStreamingAndParseAction 中 MultimodalRouter 路径 |
| SSE 流式交互 | ✅ | 完整推理事件管道 |
| 工具执行 | ✅ | executeNextPlannedToolStep 按 ExecutionPlan 执行 |
| 预算/限制保护 | ✅ | LoopLimits + LoopCounters + Budget |
| 知识库来源 | ✅ | buildKnowledgeSources |
| 护栏拦截 | ✅ | GuardrailBlockedException → Action.Blocked |
| SubAgent 支持 | ✅ | assembleContext 中 systemPrompt 合并 |
| 会话恢复 | ✅ | initState 从 SessionManager 恢复 + L1 回灌 |

## 3. 发现的问题与修复状态

| # | 优先级 | 问题 | 修复状态 |
|---|--------|------|---------|
| 1 | P0 | `logLlmPromptIfEnabled` 中 `getToolDefinition()` 死代码调用 | ✅ 已修复 |
| 2 | P0 | `hydrateWorkingMemoryFromConversationView` 角色比较冗余 | ✅ 已修复 |
| 3 | P0 | 大量使用全限定类名（FQN）代替已有 import | ✅ 已修复 |
| 4 | P0 | `asyncPostProcess` 三个操作共享 try-catch，错误隔离不足 | ✅ 已修复 |
| 5 | P1 | 双构造函数（10 参数短构造函数仅为测试存在） | ✅ 已修复 |
| 6 | P1 | `TOOL_INPUT_MAPPER` 静态 ObjectMapper 重复，应注入共享实例 | ✅ 已修复 |
| 7 | P1 | `estimateTokens` 粗略估算（text.length()/2），需调研改进 | ✅ 已修复 |
| 8 | P2 | `run()` 与 `runStreaming()` 核心循环逻辑重复（~200 行） | 📌 记录，后续 spec |
| 9 | P2 | `run()` 不显式处理 EXECUTING 阶段分支（与 runStreaming 不对称） | 📌 记录，后续 spec |

# 多 Agent 对话循环审计报告（系统性根因分析）

> 审计日期：2026-03-05
> 审计触发：一次真实对话（"帮我总结一下lifepilot的架构设计思想"）导致无限 LLM 调用循环
> 审计范围：多 Agent 协作、记忆检索、Token 预算、工具执行管线、SSE 事件、上下文组装、可观测性
> 日志来源：`docs/chat-log.md`

---

## 0. 审计方法论

本报告不采用「10 个独立问题 + 10 个独立补丁」的表面修复模式。

通过对日志中 10 个表面症状的交叉分析，我们发现它们可以追溯到 **3 个系统性根因**。
每个根因是一个架构层面的设计缺陷，修复该根因即可同时消除其下游的多个症状。

```
根因 A: 跨 Agent 边界的上下文传播链断裂
  ├─ 症状 1: writer SubAgent 自递归调用 handoff_to_writer
  ├─ 症状 2: HandoffToolFactory 硬编码 depth(0) 导致深度检查失效
  ├─ 症状 6: ToolBridgeAgentToolProvider 返回全部 36 个工具，忽略白名单
  └─ 症状 10: SubAgent 轨迹无父子关联

根因 B: ToolExecutionPipeline 对工具语义无感知
  ├─ 症状 3: handoff 工具超时后自动重试，产生并行 SubAgent
  └─ 症状 9: Agent 处理期间前端无中间 SSE 事件（handoff 被当作普通工具）

根因 C: 记忆/检索系统缺少短路和缓存机制
  ├─ 症状 4: FTS5 MATCH 未转义中文，触发 SQL 语法错误
  ├─ 症状 5: 同一请求内 20+ 次重复检索，全部返回 0
  ├─ 症状 7: Token 预算静态比例分配，记忆为空时浪费
  └─ 症状 8: 记忆为空时误报降级
```

---

## 1. 根因 A：跨 Agent 边界的上下文传播链断裂

### 1.1 问题本质

ZhiWei 的多 Agent 协作存在一条关键的上下文传播链：

```
AgentDefinition          AgentExecutor           HandoffToolFactory        AgentLoop              ToolBridgeAgentToolProvider
  (蓝图)                  (编排器)                (工具工厂)               (执行引擎)              (工具桥接)
    │                        │                        │                       │                        │
    │ allowedTools           │                        │                       │                        │
    │ canDelegate ──────────►│ buildAllowedToolIds()  │                       │                        │
    │ budget                 │ ──► allowedToolIds     │                       │                        │
    │                        │ ──► newDepth           │                       │                        │
    │                        │ ──► parentTraceId      │                       │                        │
    │                        │                        │                       │                        │
    │                        │ 构建 AgentRequest ─────┼──────────────────────►│ initState()            │
    │                        │   .allowedToolIds ✅   │                       │   → AgentState         │
    │                        │   .depth ✅            │                       │     .depth ✅          │
    │                        │   .parentTraceId ✅    │                       │     .allowedToolIds ❌  │
    │                        │                        │                       │                        │
    │                        │                        │ minimalParentState    │                        │
    │                        │                        │   .depth(0) ❌        │                        │
    │                        │                        │   .parentTraceId(null)│                        │
    │                        │                        │   .traceId(新随机) ❌ │                        │
    │                        │                        │                       │                        │
    │                        │                        │                       │ getToolCallbacks(state)│
    │                        │                        │                       │ ─────────────────────►│
    │                        │                        │                       │                       │ toolRegistry.getToolSnapshot()
    │                        │                        │                       │                       │ → 返回全部 36 个工具 ❌
    │                        │                        │                       │                       │   不读取 state.allowedToolIds
```

这条链上有 **两个断裂点**，导致四个症状同时出现：

**断裂点 1：`AgentState` 不携带 `allowedToolIds`**

`AgentRequest` 正确携带了 `allowedToolIds`（由 `AgentExecutor.buildAllowedToolIds()` 构建），
但 `AgentState.init(request)` 和 `AgentState.fromSession(session, request)` 都没有将
`allowedToolIds` 传递到 `AgentState` 中。`AgentState` record 根本没有 `allowedToolIds` 字段。

```java
// AgentState.java — 当前字段列表（无 allowedToolIds）
public record AgentState(
    String traceId, String sessionId, String goal, AgentPhase phase,
    String channel, List<StepRecord> steps, int stepCount,
    @Nullable ExecutionPlan plan, int planStepIndex, int revisionCount,
    List<String> shortTermMemory, List<String> mentionedEntities,
    Budget budget, @Nullable String parentTraceId, int depth,
    boolean done, @Nullable String finalOutput, @Nullable String terminationReason,
    @Nullable String reasoningSummary
)  // ← 没有 allowedToolIds
```

因此 `ToolBridgeAgentToolProvider.getToolCallbacks(state)` 无法从 `state` 中获取白名单，
只能返回 `toolRegistry.getToolSnapshot()` 的全部 36 个工具 → **症状 1（自递归）+ 症状 6（白名单失效）**。

**断裂点 2：`HandoffToolFactory` 创建的 `minimalParentState` 丢弃所有调用方上下文**

```java
// HandoffToolFactory.java — executor lambda 内部
AgentState minimalParentState = AgentState.builder()
    .traceId(UUID.randomUUID().toString())   // 全新 traceId，与调用方无关
    .sessionId("handoff-" + UUID.randomUUID().toString().substring(0, 8))  // 全新 sessionId
    .depth(0)                                 // 硬编码为 0，丢弃调用方深度
    .parentTraceId(null)                      // 无父关联
    // ... 其他字段也全部重置
    .build();
```

这个 `minimalParentState` 被传给 `AgentExecutor.execute(definition, task, context, minimalParentState)`，
而 `AgentExecutor` 计算 `newDepth = parentState.depth() + 1 = 0 + 1 = 1`，永远不会超过
`maxDelegationDepth`（默认 3）→ **症状 2（深度检查失效）**。

同时 `parentTraceId(null)` 导致 SubAgent 的轨迹与父 Agent 完全脱钩 → **症状 10（轨迹无关联）**。

### 1.2 根因的本质

`HandoffToolFactory` 的 executor lambda 是一个闭包，它在工具注册时就被创建，
此时无法访问「调用方 Agent 的当前 `AgentState`」。这是一个经典的**闭包捕获时机问题**：
工具在注册时绑定了执行逻辑，但执行时需要的上下文（调用方深度、traceId、allowedToolIds）
在注册时尚不存在。

当前的解决方案是在 lambda 内部创建一个 `minimalParentState`，但这个 state 是凭空构造的，
不携带任何调用方信息。

### 1.3 统一修复方案

**核心思路：建立从调用方 AgentState 到 SubAgent 的完整上下文传递链。**

#### 步骤 1：`AgentState` 新增 `allowedToolIds` 字段

```java
public record AgentState(
    // ... 现有字段 ...
    @Nullable List<String> allowedToolIds  // 新增
) {
    public AgentState {
        // ... 现有防御性拷贝 ...
        allowedToolIds = allowedToolIds != null ? List.copyOf(allowedToolIds) : null;
    }

    public static AgentState init(AgentRequest request) {
        return AgentState.builder()
            // ... 现有字段 ...
            .allowedToolIds(request.allowedToolIds())  // 传递白名单
            .build();
    }
}
```

#### 步骤 2：`ToolBridgeAgentToolProvider` 按白名单过滤

```java
@Override
public List<ToolCallback> getToolCallbacks(AgentState state) {
    List<ToolContract> tools = toolRegistry.getToolSnapshot();
    var allowedIds = state.allowedToolIds();
    if (allowedIds != null && !allowedIds.isEmpty()) {
        tools = tools.stream()
            .filter(t -> allowedIds.contains(t.id()))
            .toList();
    }
    log.debug("生成 ToolCallback: total={}, filtered={}", 
        toolRegistry.getToolSnapshot().size(), tools.size());
    return tools.stream().map(this::toToolCallback).toList();
}
```

#### 步骤 3：`HandoffToolFactory` 通过 `ToolInput` 注入调用方上下文

HandoffTool 的 executor lambda 无法直接访问调用方的 `AgentState`，
但 `ToolExecutionPipeline` 可以在调用工具前将调用方上下文注入 `ToolInput`。

方案：在 `AgentLoop.executeNextPlannedToolStep()` 中，当检测到工具 ID 以 `handoff_to_` 开头时，
将当前 `AgentState` 的 `depth`、`traceId`、`sessionId` 注入到工具参数中：

```java
// AgentLoop — executeNextPlannedToolStep() 中
if (toolId.startsWith(HandoffToolFactory.TOOL_ID_PREFIX)) {
    parameters.put("_callerDepth", state.depth());
    parameters.put("_callerTraceId", state.traceId());
    parameters.put("_callerSessionId", state.sessionId());
}
```

`HandoffToolFactory` 的 executor lambda 从 `ToolInput` 中读取这些值：

```java
// HandoffToolFactory — executor lambda 内部
int callerDepth = input.getOptionalParam("_callerDepth", Integer.class).orElse(0);
String callerTraceId = input.getOptionalParam("_callerTraceId", String.class).orElse(null);
String callerSessionId = input.getOptionalParam("_callerSessionId", String.class)
    .orElse("handoff-" + UUID.randomUUID().toString().substring(0, 8));

AgentState minimalParentState = AgentState.builder()
    .traceId(UUID.randomUUID().toString())
    .sessionId(callerSessionId)       // 继承调用方 sessionId
    .depth(callerDepth)               // 传递实际深度
    .parentTraceId(callerTraceId)     // 关联父 Agent
    // ... 其他字段 ...
    .build();
```

#### 步骤 4：全局自递归防护

即使 `canDelegate=true`，也应排除 `handoff_to_{自身id}` 工具。
在 `AgentExecutor.buildAllowedToolIds()` 中增加：

```java
// 排除指向自身的 handoff 工具
String selfHandoffId = HandoffToolFactory.TOOL_ID_PREFIX + definition.id();
if (toolId.equals(selfHandoffId)) {
    continue;
}
```

### 1.4 修复后的传播链

```
AgentDefinition → AgentExecutor.buildAllowedToolIds() → AgentRequest.allowedToolIds
    → AgentState.init(request).allowedToolIds → ToolBridgeAgentToolProvider 按白名单过滤 ✅

AgentLoop 注入 _callerDepth/_callerTraceId → HandoffToolFactory 读取
    → minimalParentState.depth(callerDepth) → AgentExecutor.newDepth = callerDepth + 1 ✅
    → minimalParentState.parentTraceId(callerTraceId) → 轨迹关联 ✅
```

---

## 2. 根因 B：ToolExecutionPipeline 对工具语义无感知

### 2.1 问题本质

`ToolExecutionPipeline` 是所有工具调用的唯一入口，它对所有工具施加相同的
超时/重试/幂等逻辑。但 ZhiWei 的工具生态中存在两类语义截然不同的工具：

| 维度 | 数据工具（如 `builtin.todo.create`） | 系统工具（如 `handoff_to_writer`） |
|------|--------------------------------------|-----------------------------------|
| 执行时间 | 毫秒级 | 秒~分钟级（内部运行完整 AgentLoop） |
| 幂等性 | 通常幂等 | 不幂等（每次创建新 Agent 实例） |
| 重试语义 | 安全（重试同一操作） | 危险（重试 = 启动并行 Agent） |
| 超时语义 | 工具故障 | Agent 执行时间长（正常行为） |
| 进度反馈 | 不需要 | 需要（用户等待时间长） |

当前 `ToolExecutionPipeline` 完全不区分这两类工具：

```java
// ToolExecutionPipeline.isRetryable() — 当前实现
private boolean isRetryable(ToolResult result) {
    String error = result.error();
    return error.contains("超时") || error.contains("timeout")
            || error.contains("连接") || error.contains("connection")
            || error.contains("临时") || error.contains("temporary");
}
```

handoff 工具超时返回 `"执行超时: 30秒"`，匹配 `"超时"` 关键字，被判定为可重试。
但 handoff 工具的超时意味着 SubAgent 执行时间过长，重试只会启动更多并行 Agent → **症状 3**。

同时，由于 pipeline 不知道 handoff 工具内部在运行一个完整的 Agent 循环，
它无法在 handoff 执行期间向前端发送进度事件 → **症状 9**。

### 2.2 根因的本质

`ToolContract` 已经有 `tags` 字段（handoff 工具的 tags 包含 `"handoff"` 和 `"multi-agent"`），
但 `ToolExecutionPipeline` 的 `isRetryable()` 和 `executeWithRetry()` 完全不读取 `tags`。
工具的语义信息（是数据操作还是系统调用）已经存在于元数据中，但执行管线没有利用它。

### 2.3 统一修复方案

**核心思路：引入工具类别感知，让 pipeline 根据工具语义调整执行策略。**

#### 步骤 1：`isRetryable()` 排除 handoff 类工具

```java
private boolean isRetryable(ToolResult result, ToolContract tool) {
    // handoff 工具不可重试 — 重试会启动并行 Agent
    if (tool.tags().contains("handoff")) {
        return false;
    }
    // 原有逻辑
    String error = result.error();
    if (error == null) return false;
    return error.contains("超时") || error.contains("timeout")
            || error.contains("连接") || error.contains("connection")
            || error.contains("临时") || error.contains("temporary");
}
```

#### 步骤 2：handoff 工具使用独立超时策略

handoff 工具的超时应远大于普通工具（SubAgent 可能需要数分钟），
且超时后不重试，而是直接返回错误让父 Agent 决策：

```java
private ToolResult executeWithRetry(ToolContract tool, ToolInput input, int maxRetries) {
    // handoff 工具：不重试，使用更长超时
    if (tool.tags().contains("handoff")) {
        return executeWithTimeout(tool, input, tool.budget().timeout());
        // handoff 工具的 budget.timeout() 应配置为 120s+
    }
    // 原有重试逻辑...
}
```

#### 步骤 3：handoff 执行期间发送 SSE 进度事件

在 `AgentLoop.executeNextPlannedToolStep()` 中，当检测到 handoff 工具时，
通过 SSE 发送 `agent_delegated` 事件，让前端显示「正在委托给 {agentName}...」：

```java
if (toolId.startsWith(HandoffToolFactory.TOOL_ID_PREFIX)) {
    emitSseEvent("agent_delegated", Map.of(
        "agentId", toolId.substring(TOOL_ID_PREFIX.length()),
        "task", parameters.get("task")
    ));
}
```

同时在各阶段切换时发送 `phase_change` 事件：

```java
// AgentLoop 核心循环中
if (newPhase != oldPhase) {
    emitSseEvent("phase_change", Map.of("phase", newPhase.name()));
}
```

---

## 3. 根因 C：记忆/检索系统缺少短路和缓存机制

### 3.1 问题本质

ZhiWei 的记忆检索管线设计为「始终执行完整检索流程」，不考虑以下边界条件：
- 系统刚安装，记忆库为空
- 同一请求内多次检索相同查询
- 查询文本包含 FTS5 特殊字符

这导致在新系统或首次对话场景下，每次 `ContextAssembler.assemble()` 都触发一次完整的
HybridRetriever 检索（3 路并行：向量 + FTS5 + 图遍历 + L4 意图匹配），
而每次检索都因为数据为空而返回 0 结果，同时 FTS5 查询因未转义而报错。

日志中的调用链：

```
主 Agent: UNDERSTANDING → assemble() → HybridRetriever(0) + EpisodicMemory(FTS5 错误)
主 Agent: PLANNING     → assemble() → HybridRetriever(0) + EpisodicMemory(FTS5 错误)
主 Agent: EXECUTING    → handoff_to_writer
  writer-1: UNDERSTANDING → assemble() → HybridRetriever(0) + EpisodicMemory(FTS5 错误)
  writer-1: PLANNING      → assemble() → HybridRetriever(0) + EpisodicMemory(FTS5 错误)
  writer-1: EXECUTING     → handoff_to_writer（自递归）
    writer-2: UNDERSTANDING → assemble() → ...
    writer-2: PLANNING      → assemble() → ...
    ...
```

每层 Agent 3 次检索 × 4+ 层递归 = 12+ 次无效检索，加上 `builtin.memory.search` 工具调用 = 20+ 次。

### 3.2 四个症状的因果关系

```
FTS5 未转义（症状 4）
  → EpisodicMemory.searchExcludingSession() 每次都报错
  → 跨会话检索永远返回空
  → 与向量/图检索的空结果叠加

无请求级缓存（症状 5）
  → 同一查询被 HybridRetriever 执行 20+ 次
  → 每次都触发 3 路并行检索 + L4 意图匹配
  → 无效 I/O 和计算开销

检索返回空 → ContextAssembler 标记 degraded=true（症状 8）
  → 但「记忆为空」≠「系统降级」
  → 新系统场景下每次 assemble() 都输出 WARN 日志

检索返回空 → TokenBudgetAllocator 仍按固定比例分配（症状 7）
  → 记忆相关区域（跨会话、知识实体、操作模板、知识库）占用大量预算
  → 实际只用了 472/8000 Token（利用率 5.9%）
```

### 3.3 根因的本质

记忆检索管线缺少两个关键的优化层：

1. **输入层**：FTS5 查询参数未经过清洗/转义，直接传入 MATCH 子句
2. **执行层**：无请求级缓存，无「数据为空时短路」机制
3. **输出层**：不区分「异常降级」和「正常空结果」，预算分配不感知数据可用性

### 3.4 统一修复方案

**核心思路：在检索管线的输入、执行、输出三层分别加入防护机制。**

#### 步骤 1：FTS5 查询转义（输入层）

提取公共方法 `escapeFts5Query()`，对所有 FTS5 MATCH 查询参数进行转义：

```java
// EpisodicMemory — 新增公共方法
static String escapeFts5Query(String query) {
    if (query == null || query.isBlank()) return query;
    // 用双引号包裹整个查询，内部双引号转义为两个双引号
    return "\"" + query.replace("\"", "\"\"") + "\"";
}

// searchExcludingSession() — 修复后
public List<MessageRecord> searchExcludingSession(String query, String excludeSessionId, int limit) {
    // ...
    String escapedQuery = escapeFts5Query(query);
    return jdbcTemplate.query(
        "... WHERE messages_fts MATCH ? AND c.session_id != ? ...",
        // ...
        escapedQuery, excludeSessionId, limit);
}
```

同样修复 `search()` 方法和 `FtsSearcher` 中的所有 FTS5 MATCH 调用。

#### 步骤 2：请求级检索缓存（执行层）

在 `ContextAssembler` 中引入请求级缓存。由于 `ContextAssembler` 是 Spring Bean（单例），
缓存需要基于 `traceId` 隔离，并在请求结束后清理：

```java
// ContextAssembler — 新增
private final Map<String, List<RetrievalResult>> retrievalCache = new ConcurrentHashMap<>();

private List<RetrievalResult> cachedRetrieve(String traceId, String query,
                                              RetrievalStrategyConfig config) {
    String cacheKey = traceId + "|" + query + "|" + config.topK();
    return retrievalCache.computeIfAbsent(cacheKey, k ->
        safeRetrieve(hybridRetriever, query, config));
}

// 在 assemble() 结束时或通过 @RequestScope 清理缓存
public void clearCache(String traceId) {
    retrievalCache.entrySet().removeIf(e -> e.getKey().startsWith(traceId + "|"));
}
```

#### 步骤 3：空数据短路（执行层）

`HybridRetriever` 在首次检索返回全空后，设置标记，后续同一请求内的检索直接返回空：

```java
// HybridRetriever — 新增
private volatile boolean knownEmpty = false;

public List<RetrievalResult> retrieve(String query, int topK, RetrievalWeights weights) {
    if (knownEmpty) {
        log.debug("混合检索: 已知数据为空，跳过检索");
        return List.of();
    }
    // ... 原有检索逻辑 ...
    if (vectorItems.isEmpty() && ftsResults.isEmpty() && graphResults.isEmpty()) {
        knownEmpty = true;  // 标记为空，后续检索短路
    }
    // ...
}

// 数据写入时重置标记
public void resetEmptyFlag() {
    knownEmpty = false;
}
```

注意：`knownEmpty` 标记需要在记忆写入时重置（如 `EpisodicMemory.save()` 后调用 `resetEmptyFlag()`）。

#### 步骤 4：区分「异常降级」和「正常空结果」（输出层）

```java
// ContextAssembler.assemble() — 修复后
if (retrievalResults.isEmpty() && state.goal() != null) {
    // 不再标记 degraded — 检索返回空是正常状态
    log.debug("记忆检索无结果: sessionId={}, goal={}", state.sessionId(),
        state.goal().length() > 50 ? state.goal().substring(0, 50) + "..." : state.goal());
}
// degraded 仅在 catch 块中设置（真正的异常降级）
```

#### 步骤 5：动态预算分配（输出层）

`TokenBudgetAllocator` 引入两阶段分配：

```java
// TokenBudgetAllocator — 修复后
public BudgetAllocation allocate(int conversationTurns, float topScore,
                                  boolean hasMemoryData) {
    if (!hasMemoryData) {
        // 将记忆相关区域的预算重新分配给用户消息和当前会话
        return BudgetAllocation.builder()
            .userMessageBudget(/* 增大 */)
            .currentSessionBudget(/* 增大 */)
            .crossSessionBudget(0)
            .knowledgeEntityBudget(0)
            .proceduralBudget(0)
            .knowledgeBaseBudget(0)
            .build();
    }
    // 原有分配逻辑...
}
```

---

## 4. 症状与根因映射表

| # | 症状 | 根因 | 修复步骤 |
|---|------|------|---------|
| 1 | writer SubAgent 自递归调用 handoff_to_writer | A（断裂点 1） | A.步骤 1 + A.步骤 2 + A.步骤 4 |
| 2 | HandoffToolFactory 硬编码 depth(0) | A（断裂点 2） | A.步骤 3 |
| 3 | handoff 工具超时后自动重试 | B | B.步骤 1 + B.步骤 2 |
| 4 | FTS5 MATCH 未转义中文 | C（输入层） | C.步骤 1 |
| 5 | 同一请求内 20+ 次重复检索 | C（执行层） | C.步骤 2 + C.步骤 3 |
| 6 | ToolBridgeAgentToolProvider 忽略白名单 | A（断裂点 1） | A.步骤 1 + A.步骤 2 |
| 7 | Token 预算静态分配浪费 | C（输出层） | C.步骤 5 |
| 8 | 记忆为空时误报降级 | C（输出层） | C.步骤 4 |
| 9 | Agent 处理期间无 SSE 中间事件 | B | B.步骤 3 |
| 10 | SubAgent 轨迹无父子关联 | A（断裂点 2） | A.步骤 3 |

---

## 5. 修复执行计划

### 5.1 执行顺序与依赖关系

```
Phase 1 — 阻止无限循环（P0，立即修复）
  ├─ 根因 A 修复（4 步）
  │   ├─ A.1 AgentState 新增 allowedToolIds 字段
  │   ├─ A.2 ToolBridgeAgentToolProvider 按白名单过滤
  │   ├─ A.3 HandoffToolFactory 通过 ToolInput 注入调用方上下文
  │   └─ A.4 AgentExecutor 排除自递归 handoff 工具
  │
  └─ 根因 B 修复（步骤 1-2）
      ├─ B.1 isRetryable() 排除 handoff 类工具
      └─ B.2 handoff 工具使用独立超时策略

Phase 2 — 修复功能正确性（P1）
  ├─ 根因 C 修复（步骤 1-3）
  │   ├─ C.1 FTS5 查询转义
  │   ├─ C.2 请求级检索缓存
  │   └─ C.3 空数据短路
  │
  └─ 根因 C 修复（步骤 4-5）
      ├─ C.4 区分异常降级和正常空结果
      └─ C.5 动态预算分配

Phase 3 — 用户体验优化（P2）
  └─ 根因 B 修复（步骤 3）
      └─ B.3 handoff 执行期间发送 SSE 进度事件
```

### 5.2 涉及文件清单

| 根因 | 修改文件 | 变更类型 |
|------|---------|---------|
| A | `AgentState.java` | record 新增字段 |
| A | `ToolBridgeAgentToolProvider.java` | 方法修改 |
| A | `HandoffToolFactory.java` | executor lambda 重写 |
| A | `AgentExecutor.java` | buildAllowedToolIds 增强 |
| A | `AgentLoop.java` | executeNextPlannedToolStep 注入上下文 |
| B | `ToolExecutionPipeline.java` | isRetryable + executeWithRetry 修改 |
| B | `AgentLoop.java` | SSE 事件发送 |
| C | `EpisodicMemory.java` | FTS5 转义 |
| C | `FtsSearcher.java` | FTS5 转义 |
| C | `ContextAssembler.java` | 缓存 + 降级逻辑 |
| C | `HybridRetriever.java` | 空数据短路 |
| C | `TokenBudgetAllocator.java` | 动态分配 |

### 5.3 预估工作量

| Phase | 工作量 | 说明 |
|-------|--------|------|
| Phase 1 | 2-3 天 | 根因 A + B 的 P0 修复，阻止无限循环 |
| Phase 2 | 2-3 天 | 根因 C 的全部修复，提升检索效率和正确性 |
| Phase 3 | 1-2 天 | SSE 进度事件，提升用户体验 |

---

## 6. 日志中的完整调用链还原

```
15:59:42 用户发送: "帮我总结一下lifepilot的架构设计思想"
         ↓
15:59:42 Gateway 6 层中间件 → AgentLoop.runStreaming()
         ↓
15:59:42 [主 Agent] UNDERSTANDING 阶段
         ├─ ContextAssembler.assemble()
         │   ├─ HybridRetriever.retrieve(): 向量=0, FTS=0, 图=0  ← 根因 C（无短路）
         │   ├─ EpisodicMemory.searchExcludingSession(): FTS5 错误  ← 根因 C（未转义）
         │   ├─ TokenBudgetAllocator: 8000 窗口，实际用 472  ← 根因 C（静态分配）
         │   └─ degraded=true  ← 根因 C（误报降级）
         ↓
15:59:55 [主 Agent] PLANNING 阶段
         ├─ ContextAssembler.assemble() — 重复检索，全部返回 0  ← 根因 C（无缓存）
         └─ LLM 决定调用 handoff_to_writer
         ↓
16:00:06 [主 Agent] EXECUTING 阶段 → handoff_to_writer
         ├─ HandoffToolFactory: depth(0), parentTraceId(null)  ← 根因 A（断裂点 2）
         ├─ AgentExecutor: newDepth=0+1=1, 未超限  ← 根因 A（断裂点 2）
         └─ ToolBridgeAgentToolProvider: count=36, 未过滤  ← 根因 A（断裂点 1）
         ↓
16:00:06 [writer-1] UNDERSTANDING → PLANNING
         ├─ 工具列表包含 handoff_to_writer（自身）  ← 根因 A（断裂点 1 + 无自递归防护）
         ├─ EpisodicMemory FTS5 错误: "no such column: 任务"  ← 根因 C
         └─ LLM 决定再次调用 handoff_to_writer  ← 根因 A
         ↓
16:00:22 [writer-2] 递归第 2 层（depth 仍为 1）  ← 根因 A（断裂点 2）
         ↓
16:00:36 [writer-1] handoff_to_writer 超时 30s
         └─ ToolExecutionPipeline 自动重试  ← 根因 B（语义无感知）
         ↓
16:00:40+ 指数级膨胀：多个 SubAgent 并行运行，无 SSE 进度事件  ← 根因 B
```

---

## 7. 附录：关键源码位置索引

| 文件 | 关键位置 | 说明 |
|------|---------|------|
| `AgentState.java` | record 字段列表 | 缺少 `allowedToolIds` 字段 |
| `AgentState.init()` | 静态工厂方法 | 未传递 `request.allowedToolIds()` |
| `AgentRequest.java` | record 定义 | 已有 `allowedToolIds` 字段（正确） |
| `AgentExecutor.execute()` | 深度检查 | `newDepth = parentState.depth() + 1` |
| `AgentExecutor.buildAllowedToolIds()` | 白名单构建 | 正确构建但未排除自递归 |
| `HandoffToolFactory.createHandoffTool()` | executor lambda | `depth(0)` 硬编码 + `parentTraceId(null)` |
| `ToolBridgeAgentToolProvider.getToolCallbacks()` | 工具过滤 | 返回全量工具，不读取白名单 |
| `ToolExecutionPipeline.isRetryable()` | 重试判断 | 基于错误消息字符串，无工具类型感知 |
| `EpisodicMemory.searchExcludingSession()` | FTS5 MATCH | 未转义查询参数 |
| `ContextAssembler.assemble()` | 降级标记 | `retrievalResults.isEmpty()` → `degraded=true` |
| `HybridRetriever.retrieve()` | 三路并行检索 | 无短路机制 |
| `TokenBudgetAllocator.allocate()` | 预算分配 | 固定比例，不感知数据可用性 |

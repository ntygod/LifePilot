# Agent 挂起-恢复架构设计

## 1. 动机

ReactAgentLoop 当前是「一次性执行到底」的模型 — 从 `run()` / `runStreaming()` 进入 coreLoop，
直到 LLM 给出最终回答、Budget 超限或取消信号才退出。这意味着 Agent 无法处理任何需要
「等待外部事件后继续」的场景。

挂起-恢复是 Agent 引擎层的通用能力，不绑定任何特定触发源。

### 1.1 典型挂起场景

| 场景 | 触发方 | 等待什么 | 恢复信号 |
|------|--------|---------|---------|
| 长时间工作流 | trigger_workflow(async=true) | 工作流到达终态 | WorkflowCompletedEvent |
| 用户确认 | HIGH/CRITICAL 风险工具执行前 | 用户点击「确认」或「拒绝」 | UserConfirmationEvent |
| A2A 远程执行 | A2A 请求路由到远程 Agent | 远程 Agent 返回结果 | A2aTaskCompletedEvent |
| 定时恢复 | Agent 主动设置 "30 分钟后继续" | 到达指定时间 | ScheduledWakeupEvent |
| 外部数据就绪 | 等待爬虫/ETL 完成 | 数据写入完成 | ExternalDataReadyEvent |

所有场景共享同一套挂起-恢复机制，区别仅在于 `SuspendReason` 的类型和恢复信号的来源。

## 2. 可行性分析

### 2.1 当前架构支撑点

| 现有机制 | 如何支撑 |
|---------|---------|
| `ReactAgentState` 不可变 record + `toBuilder()` | 状态可序列化持久化，恢复时反序列化重建 |
| `ReactStep` sealed interface | 新增 `Suspend` / `Resume` permit，switch 穷举自动提示 |
| `Budget.withElapsed()` | 挂起时冻结 elapsed，恢复时从冻结点继续计时 |
| `CancellationToken` | 挂起退出 coreLoop 的信号通道 |
| `SessionManager` + `SessionSnapshot` | 会话上下文已有持久化基础 |
| `SseSessionManager` | 推送 SUSPENDED / RESUMED 事件通知前端 |
| Virtual Thread | 恢复时在新 Virtual Thread 上重新进入 coreLoop，不阻塞平台线程 |
| `ToolResultStatus` 枚举 | 可新增 `SUSPENDED` 状态，不破坏现有 record 结构 |

### 2.2 结论

**完全可行**。核心改动集中在 Agent 模块，不需要重构现有架构。

## 3. 核心设计

### 3.1 SuspendReason — 挂起原因类型层次

```java
public sealed interface SuspendReason permits
        SuspendReason.WorkflowWait,
        SuspendReason.UserConfirmation,
        SuspendReason.RemoteDelegation,
        SuspendReason.ScheduledWakeup,
        SuspendReason.ExternalDataWait {

    /** 等待异步工作流完成。 */
    record WorkflowWait(
            String executionId,
            String workflowId,
            String workflowName
    ) implements SuspendReason {}

    /** 等待用户确认高风险工具执行。 */
    record UserConfirmation(
            String toolId,
            String inputJson,
            String riskLevel,
            String confirmationId
    ) implements SuspendReason {}

    /** 等待 A2A 远程 Agent 返回结果。 */
    record RemoteDelegation(
            String remoteTaskId,
            String remoteAgentUrl,
            String delegatedGoal
    ) implements SuspendReason {}

    /** 定时恢复 — Agent 主动设置延迟。 */
    record ScheduledWakeup(
            Instant wakeupAt,
            String reason
    ) implements SuspendReason {}

    /** 等待外部数据就绪（爬虫/ETL/文件上传等）。 */
    record ExternalDataWait(
            String dataSourceId,
            String description
    ) implements SuspendReason {}
}
```

设计要点：
- sealed interface 保证 switch 穷举，新增场景编译器强制处理
- 每个 permit 只携带恢复时必需的最小上下文
- 所有 record 字段均为不可变值类型，可安全序列化

### 3.2 ResumePayload — 恢复载荷类型层次

```java
public sealed interface ResumePayload permits
        ResumePayload.WorkflowResult,
        ResumePayload.UserDecision,
        ResumePayload.RemoteResult,
        ResumePayload.WakeupSignal,
        ResumePayload.DataReady {

    /** 工作流执行结果。 */
    record WorkflowResult(
            String executionId,
            String status,
            String outputJson
    ) implements ResumePayload {}

    /** 用户确认/拒绝决定。 */
    record UserDecision(
            String confirmationId,
            boolean approved,
            @Nullable String reason
    ) implements ResumePayload {}

    /** A2A 远程 Agent 返回结果。 */
    record RemoteResult(
            String remoteTaskId,
            String resultJson
    ) implements ResumePayload {}

    /** 定时唤醒信号。 */
    record WakeupSignal(
            Instant actualWakeupAt
    ) implements ResumePayload {}

    /** 外部数据就绪通知。 */
    record DataReady(
            String dataSourceId,
            String dataLocationOrContent
    ) implements ResumePayload {}
}
```

`SuspendReason` 与 `ResumePayload` 一一对应，恢复时通过类型匹配校验配对正确性。

### 3.3 ReactStep 扩展 — Suspend / Resume

在现有 4 个 permit 基础上新增两个：

```java
public sealed interface ReactStep permits
        ReactStep.Thought,
        ReactStep.ToolCall,
        ReactStep.Observation,
        ReactStep.Answer,
        ReactStep.Suspend,   // 新增
        ReactStep.Resume {   // 新增

    // ... 现有 4 个 permit 不变 ...

    /** Agent 挂起步骤 — 记录挂起原因和时间点。 */
    record Suspend(
            SuspendReason reason,
            Instant suspendedAt,
            int stepIndexBeforeSuspend
    ) implements ReactStep {}

    /** Agent 恢复步骤 — 记录恢复载荷和时间点。 */
    record Resume(
            ResumePayload payload,
            Instant resumedAt,
            Duration suspendDuration
    ) implements ReactStep {}
}
```

Suspend / Resume 作为 ReactStep 的一等公民，自然融入 steps 列表，Trace 和 Observability 无需特殊处理即可记录挂起-恢复事件。

### 3.4 ToolResultStatus 扩展

```java
public enum ToolResultStatus {
    SUCCESS,
    ERROR,
    PARTIAL_SUCCESS,
    RATE_LIMITED,
    SUSPENDED;       // 新增

    public boolean isSuccess() { return this == SUCCESS; }

    public boolean isTerminal() {
        return this == SUCCESS || this == ERROR;
    }

    /** 是否需要挂起 Agent 循环。 */
    public boolean isSuspend() { return this == SUSPENDED; }
}
```

工具返回 `SUSPENDED` 时，coreLoop 不将其视为失败，而是触发挂起流程。

### 3.5 ReactAgentState 扩展

```java
@Builder(toBuilder = true)
public record ReactAgentState(
        // ... 现有字段不变 ...
        boolean suspended,                    // 新增：是否处于挂起态
        @Nullable SuspendReason suspendReason  // 新增：挂起原因（非挂起态为 null）
) {
    // 紧凑构造器中 suspendReason 无需防御性拷贝（record 本身不可变）
}
```

新增两个辅助方法：

```java
/** 进入挂起态，返回新实例。 */
public ReactAgentState suspend(SuspendReason reason) {
    return this.toBuilder()
            .suspended(true)
            .suspendReason(reason)
            .build();
}

/** 从挂起态恢复，返回新实例。 */
public ReactAgentState resume() {
    return this.toBuilder()
            .suspended(false)
            .suspendReason(null)
            .build();
}
```

### 3.6 coreLoop 挂起检测

在 coreLoop 的工具执行后插入通用挂起检测逻辑：

```java
// 在 executeToolCall 返回后检查
for (var tc : toolCalls) {
    state = executeToolCall(state, tc, toolCallbacks, ...);

    // ★ 通用挂起检测 — 不绑定任何特定工具
    if (state.suspended()) {
        log.info("Agent 进入挂起态: traceId={}, reason={}",
                state.traceId(), state.suspendReason());
        state = state.appendStep(new ReactStep.Suspend(
                state.suspendReason(),
                Instant.now(),
                state.stepCount()));
        // 冻结 Budget 时间
        state = state.toBuilder()
                .budget(state.budget().withElapsed(
                        Duration.between(loopStart, Instant.now())))
                .build();
        break; // 退出 tool call 循环
    }
    if (cancellationToken.isCancelled()) break;
}

// 外层 for 循环也需要检测
if (state.suspended()) break;
```

关键设计：coreLoop 本身不知道「为什么」挂起，只检查 `state.suspended()` 布尔标志。挂起原因由具体的工具执行逻辑（或 Guardrail 拦截器）设置到 state 中。

### 3.7 executeToolCall 中的挂起触发

工具执行返回 `ToolResultStatus.SUSPENDED` 时，在 executeToolCall 中设置挂起态：

```java
private ReactAgentState executeToolCall(...) {
    // ... 现有逻辑 ...

    ToolResult toolResult = matchedCallback.call(inputJson);

    if (toolResult.status().isSuspend()) {
        // 从 toolResult.data() 中反序列化 SuspendReason
        SuspendReason reason = parseSuspendReason(toolResult);
        state = state.suspend(reason);
        state = state.appendStep(new ReactStep.Observation(
                toolId, true, "工具请求挂起: " + reason, 0));
        return state;
    }

    // ... 正常处理逻辑 ...
}
```

注意：不是所有挂起都由工具触发。UserConfirmation 场景由 Guardrail 拦截器在工具执行前触发，此时 executeToolCall 尚未被调用。Guardrail 直接设置 `state.suspend(new UserConfirmation(...))` 并跳过工具执行。

### 3.8 run() / runStreaming() 挂起处理

coreLoop 退出后，run() 和 runStreaming() 需要区分「正常完成」和「挂起退出」：

```java
// run() 中
state = coreLoop(state, request, traceContext, loopStart, callback, token, null, null);

if (state.suspended()) {
    // 持久化挂起状态
    suspendStore.save(SuspendedAgent.from(state));
    return new AgentResponse(
            state.traceId(), state.sessionId(),
            "Agent 已挂起，等待恢复信号",
            state.budget().tokensUsed(), state.stepCount(),
            "suspended"  // terminationReason
    );
}
// ... 正常完成逻辑 ...
```

```java
// runStreaming() 中
state = coreLoop(state, request, traceContext, loopStart, callback, token, sseManager, streamId);

if (state.suspended()) {
    suspendStore.save(SuspendedAgent.from(state));
    // 推送 SUSPENDED 事件通知前端
    var suspendData = Map.of(
            "traceId", state.traceId(),
            "sessionId", state.sessionId(),
            "reason", serializeSuspendReason(state.suspendReason()));
    sseManager.sendEvent(streamId, SseEventType.AGENT_SUSPENDED, suspendData);
    sseManager.closeEmitter(streamId);
    return;
}
// ... 正常完成逻辑 ...
```

### 3.9 resumeFromSuspend() — 通用恢复入口

```java
/**
 * 从挂起态恢复 Agent 执行。
 *
 * @param traceId 挂起时的 traceId
 * @param payload 恢复载荷
 */
public void resumeFromSuspend(String traceId, ResumePayload payload) {
    // 1. 从持久化存储加载挂起状态
    SuspendedAgent suspended = suspendStore.load(traceId)
            .orElseThrow(() -> new IllegalStateException("未找到挂起的 Agent: " + traceId));

    // 2. 校验 payload 类型与 suspendReason 匹配
    validateResumePayload(suspended.suspendReason(), payload);

    // 3. 重建 ReactAgentState
    ReactAgentState state = suspended.toAgentState()
            .resume()
            .appendStep(new ReactStep.Resume(
                    payload, Instant.now(),
                    Duration.between(suspended.suspendedAt(), Instant.now())));

    // 4. 将恢复载荷转换为 Observation 注入 steps
    String resumeObservation = formatResumeObservation(payload);
    state = state.appendStep(new ReactStep.Observation(
            "resume:" + suspended.suspendReason().getClass().getSimpleName(),
            true, resumeObservation, 0));

    // 5. 在新 Virtual Thread 上重新进入 coreLoop
    Thread.startVirtualThread(() -> {
        var loopStart = Instant.now();
        var token = new CancellationToken();
        // 根据原始请求的 channel 决定流式/非流式
        if (suspended.streamId() != null) {
            // 流式恢复 — 重新建立 SSE 连接
            runStreamingResume(state, suspended, token);
        } else {
            // 非流式恢复
            runResume(state, suspended, token);
        }
    });

    // 6. 清理持久化记录
    suspendStore.delete(traceId);
}
```

恢复载荷被转换为 Observation 步骤注入到 steps 中，LLM 在下一次迭代时能看到恢复结果，自然地继续推理。


## 4. 持久化 — SuspendStore

### 4.1 SuspendedAgent record

```java
@Builder(toBuilder = true)
public record SuspendedAgent(
        String traceId,
        String sessionId,
        String channel,
        SuspendReason suspendReason,
        String stateJson,           // ReactAgentState 序列化 JSON
        Instant suspendedAt,
        @Nullable String streamId,  // 流式模式下的 SSE streamId
        @Nullable String budgetJson // Budget 快照（含冻结的 elapsed）
) {
    public static SuspendedAgent from(ReactAgentState state) { ... }
    public ReactAgentState toAgentState() { ... }
}
```

### 4.2 SQLite 表结构

```sql
CREATE TABLE IF NOT EXISTS suspended_agents (
    trace_id       TEXT PRIMARY KEY,
    session_id     TEXT NOT NULL,
    channel        TEXT NOT NULL,
    reason_type    TEXT NOT NULL,       -- SuspendReason 的类型名（如 "WorkflowWait"）
    reason_json    TEXT NOT NULL,       -- SuspendReason 序列化 JSON
    state_json     TEXT NOT NULL,       -- ReactAgentState 完整快照
    budget_json    TEXT,                -- Budget 快照
    stream_id      TEXT,                -- 流式 SSE streamId（可空）
    suspended_at   TEXT NOT NULL,       -- ISO 8601
    created_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_suspended_agents_session ON suspended_agents(session_id);
CREATE INDEX idx_suspended_agents_reason  ON suspended_agents(reason_type);
```

### 4.3 SuspendStore 接口

```java
public interface SuspendStore {
    void save(SuspendedAgent agent);
    Optional<SuspendedAgent> load(String traceId);
    List<SuspendedAgent> findBySession(String sessionId);
    List<SuspendedAgent> findByReasonType(String reasonType);
    void delete(String traceId);
    /** 清理超过 maxAge 的过期挂起记录。 */
    int cleanExpired(Duration maxAge);
}
```

实现使用 `JdbcTemplate` + SQLite，与现有 DataStore 模式一致。

### 4.4 过期清理

配置项 `lifepilot.agent.suspend.max-age`（默认 24h），由定时任务周期性清理超时未恢复的挂起记录，防止资源泄漏。

## 5. 事件驱动恢复

### 5.1 架构

每种挂起场景的恢复信号来源不同，但最终都汇聚到同一个恢复路径：

```
恢复信号源                    Spring Event                  统一恢复入口
─────────────────────────────────────────────────────────────────────
WorkflowEngine          → WorkflowCompletedEvent     ─┐
Guardrail (用户确认)     → UserConfirmationEvent      ─┤
A2A TaskManager         → A2aTaskCompletedEvent      ─┼→ AgentResumeListener
ScheduledTaskExecutor   → ScheduledWakeupEvent       ─┤     ↓
ExternalDataWatcher     → ExternalDataReadyEvent     ─┘  resumeFromSuspend()
```

### 5.2 AgentResumeListener

```java
@Component
public class AgentResumeListener {

    private final ReactAgentLoop agentLoop;
    private final SuspendStore suspendStore;

    /** 工作流完成 → 恢复等待该工作流的 Agent。 */
    @EventListener
    public void onWorkflowCompleted(WorkflowCompletedEvent event) {
        suspendStore.findByReasonType("WorkflowWait").stream()
                .filter(sa -> matchWorkflow(sa, event.executionId()))
                .findFirst()
                .ifPresent(sa -> agentLoop.resumeFromSuspend(
                        sa.traceId(),
                        new ResumePayload.WorkflowResult(
                                event.executionId(),
                                event.status(),
                                event.outputJson())));
    }

    /** 用户确认 → 恢复等待确认的 Agent。 */
    @EventListener
    public void onUserConfirmation(UserConfirmationEvent event) {
        suspendStore.findByReasonType("UserConfirmation").stream()
                .filter(sa -> matchConfirmation(sa, event.confirmationId()))
                .findFirst()
                .ifPresent(sa -> agentLoop.resumeFromSuspend(
                        sa.traceId(),
                        new ResumePayload.UserDecision(
                                event.confirmationId(),
                                event.approved(),
                                event.reason())));
    }

    /** A2A 远程任务完成 → 恢复等待远程结果的 Agent。 */
    @EventListener
    public void onA2aTaskCompleted(A2aTaskCompletedEvent event) { ... }

    /** 定时唤醒 → 恢复定时挂起的 Agent。 */
    @EventListener
    public void onScheduledWakeup(ScheduledWakeupEvent event) { ... }

    /** 外部数据就绪 → 恢复等待数据的 Agent。 */
    @EventListener
    public void onExternalDataReady(ExternalDataReadyEvent event) { ... }
}
```

### 5.3 ScheduledWakeup 的特殊处理

定时恢复场景需要在挂起时注册一个延迟任务：

```java
// 在 suspendStore.save() 之后
if (state.suspendReason() instanceof SuspendReason.ScheduledWakeup sw) {
    Duration delay = Duration.between(Instant.now(), sw.wakeupAt());
    scheduledExecutor.schedule(
            () -> applicationEventPublisher.publishEvent(
                    new ScheduledWakeupEvent(state.traceId(), Instant.now())),
            delay.toMillis(), TimeUnit.MILLISECONDS);
}
```

应用重启时，从 `suspended_agents` 表中扫描 `reason_type = 'ScheduledWakeup'` 的记录，重新注册延迟任务。

## 6. SSE 事件扩展

### 6.1 新增常量

```java
public final class SseEventType {
    // ... 现有常量 ...

    // Agent 挂起-恢复事件
    /** Agent 挂起事件 */
    public static final String AGENT_SUSPENDED = "agent-suspended";
    /** Agent 恢复事件 */
    public static final String AGENT_RESUMED = "agent-resumed";
}
```

### 6.2 事件载荷

```json
// AGENT_SUSPENDED
{
    "traceId": "xxx",
    "sessionId": "yyy",
    "reasonType": "WorkflowWait",
    "reasonDetail": { "executionId": "...", "workflowName": "调研助手" },
    "suspendedAt": "2026-03-17T10:30:00Z"
}

// AGENT_RESUMED
{
    "traceId": "xxx",
    "sessionId": "yyy",
    "resumedAt": "2026-03-17T10:35:00Z",
    "suspendDurationMs": 300000
}
```

前端收到 `AGENT_SUSPENDED` 后可展示挂起状态 UI（如进度指示器、等待原因说明），收到 `AGENT_RESUMED` 后恢复正常对话流。


## 7. 时序图

### 7.1 通用挂起-恢复流程（以工作流场景为例）

```
用户            ChatController    ReactAgentLoop    coreLoop    WorkflowTool    SuspendStore    WorkflowEngine
 │                  │                  │               │             │               │               │
 │─── 发送消息 ────→│                  │               │             │               │               │
 │                  │── runStreaming ──→│               │             │               │               │
 │                  │                  │── coreLoop ──→│             │               │               │
 │                  │                  │               │── LLM ──→  │               │               │
 │                  │                  │               │←── tool call: trigger_workflow              │
 │                  │                  │               │─────────────→│               │               │
 │                  │                  │               │             │── 提交异步 ──→│               │
 │                  │                  │               │             │←─ executionId ─│               │
 │                  │                  │               │←─ SUSPENDED ─│               │               │
 │                  │                  │               │                              │               │
 │                  │                  │               │── 检测 suspended ──→         │               │
 │                  │                  │               │── appendStep(Suspend) ──→    │               │
 │                  │                  │←── state(suspended=true) ──│               │               │
 │                  │                  │                              │               │               │
 │                  │                  │── save ─────────────────────→│               │               │
 │                  │                  │── SSE: AGENT_SUSPENDED ────→│               │               │
 │←── 挂起通知 ────│                  │               │             │               │               │
 │                  │                  │               │             │               │               │
 │   ... 时间流逝 ...                  │               │             │               │               │
 │                  │                  │               │             │               │               │
 │                  │                  │               │             │               │── 工作流完成 ─→│
 │                  │                  │               │             │               │               │
 │                  │                  │←── WorkflowCompletedEvent ──│               │               │
 │                  │                  │── load ─────────────────────→│               │               │
 │                  │                  │←── SuspendedAgent ──────────│               │               │
 │                  │                  │── resumeFromSuspend ──→     │               │               │
 │                  │                  │   (Virtual Thread)          │               │               │
 │                  │                  │── coreLoop(恢复) ──→        │               │               │
 │                  │                  │               │── LLM ──→  │               │               │
 │                  │                  │               │←── 最终回答 │               │               │
 │                  │                  │── SSE: AGENT_RESUMED ─────→│               │               │
 │←── 恢复 + 回答 ─│                  │               │             │               │               │
```

### 7.2 用户确认场景（Guardrail 触发）

```
用户            Guardrail       ReactAgentLoop    coreLoop    SuspendStore
 │                  │                │               │             │
 │                  │                │── coreLoop ──→│             │
 │                  │                │               │── LLM: 调用高风险工具 ──→
 │                  │←── 拦截 ───────│               │             │
 │                  │── state.suspend(UserConfirmation) ──→        │
 │                  │                │←── state(suspended=true) ──│
 │                  │                │── save ───────────────────→│
 │←── SSE: TOOL_CONFIRMATION_REQUEST + AGENT_SUSPENDED ──────────│
 │                  │                │               │             │
 │── 点击「确认」──→│                │               │             │
 │                  │── UserConfirmationEvent ──→     │             │
 │                  │                │── resumeFromSuspend ──→     │
 │                  │                │── coreLoop(恢复，执行工具) ─→│
 │←── 回答 ────────│                │               │             │
```

## 8. 实现路径与工作量估算

| 阶段 | 内容 | 涉及模块 | 估算 |
|------|------|---------|------|
| Phase 1 | SuspendReason / ResumePayload / ReactStep 扩展 | agent.model | 1 天 |
| Phase 2 | ReactAgentState 扩展 + coreLoop 挂起检测 | agent | 1 天 |
| Phase 3 | SuspendStore（接口 + SQLite 实现 + Flyway） | agent.suspend | 1 天 |
| Phase 4 | run() / runStreaming() 挂起处理 + resumeFromSuspend() | agent | 1.5 天 |
| Phase 5 | SseEventType 扩展 + 前端挂起状态 UI | interaction + web | 1 天 |
| Phase 6 | AgentResumeListener + 事件定义 | agent.suspend | 1 天 |
| Phase 7 | WorkflowWait 场景端到端集成 | agent + workflow | 1 天 |
| Phase 8 | UserConfirmation 场景（Guardrail 集成） | agent + guardrail | 1 天 |
| Phase 9 | 测试（单元 + 集成 + 属性测试） | test | 2 天 |
| **合计** | | | **~10.5 天** |

建议分两个 spec 实施：
1. `agent-suspend-resume`（Phase 1-6）：引擎层通用挂起-恢复能力
2. `agent-suspend-scenarios`（Phase 7-8）：具体场景集成

## 9. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 状态序列化/反序列化失败 | 挂起的 Agent 无法恢复 | ReactAgentState 所有字段均为可序列化类型；添加序列化往返测试 |
| 恢复时 SSE 连接已断开 | 前端收不到恢复事件 | 恢复时前端通过轮询 `/api/agents/suspended` 检查状态；或恢复后写入消息历史，前端刷新可见 |
| 挂起记录泄漏（永远不恢复） | suspended_agents 表膨胀 | 定时清理任务（默认 24h 过期）+ 监控告警 |
| 恢复时原始上下文过期 | LLM 上下文窗口不连贯 | 恢复时重新组装上下文（contextAssembler.assemble），不依赖缓存 |
| 并发恢复同一 traceId | 重复执行 | SuspendStore.load() + delete() 使用数据库事务保证原子性 |
| Budget 时间计算不准 | 恢复后立即超时 | 挂起时冻结 elapsed，恢复时从冻结点继续；挂起期间的等待时间不计入 Budget |
| 应用重启丢失 ScheduledWakeup | 定时恢复失效 | 启动时扫描 suspended_agents 表，重新注册延迟任务 |

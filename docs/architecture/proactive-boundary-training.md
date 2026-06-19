# 主动引擎 Boundary / Focus / Training 三位一体增强

> Spec: `.kiro/specs/proactive-boundary-training/`（开发期工作区文档，未入 git）
> 关联 Gap: #[[file:docs/planned/memory-and-proactive-evolution-gaps.md]] §2 P-P0-1 / P-P0-2 / P-P0-3
> 上位架构: #[[file:docs/architecture/proactive-reminder-engine.md]]

## 1. 定位与目标

现有主动引擎的三级门控（SILENT / FAST / FULL）架构完备，真实体验瓶颈在三个层面：

1. 心跳按固定间隔唤醒，**不感知用户是否处于"任务边界"**。mid-task 打扰比 post-commit 打扰体验差 21 个百分点（JetBrains 2026 田野研究，arXiv:2601.10253）。
2. `proactive_reminder_delivery` + `reminder_feedback` + `reminder_policy_snapshot` + `reminder_topic_profile` 四张表持续落数据，但只用于 LinUCB 参数调优；**从未作为 Gate 3 LLM prompt 的 few-shot 样例回流**。
3. `ImplicitSignalCollector` 只能事后观察"忽略"，**无法事前识别用户处于专注态**（全屏 / 持续高密度输入 / IDE 编码）。

本模块把三者作为协同子系统落地：

- **BoundarySignalCollector** 捕获任务边界（对话完成 / 工作流完成 / A2A 委派完成）
- **FocusStateDetector** 融合桌面 `ReminderFocusState` + 对话节奏推断 FOCUS_MODE
- **DecisionGate** 增加 boundary + focus 两维度的阈值调整
- **ProactiveTrainingReplayService** 周级把历史 `(context, action, reward)` 编译为 few-shot 样例库（供后续 `proactive-timing-cot` spec 消费）

## 2. 核心设计

### 2.1 事件流

```
┌──────────────────────────────────────────────────────────────────┐
│                     Spring Application Event Bus                   │
└─────┬──────────────────┬──────────────────┬─────────────────────┘
      │                  │                  │
      │ Conversation     │ Workflow         │ A2A
      │ CompletedEvent   │ CompletedEvent   │ TaskCompletedEvent
      │                  │                  │
      ▼                  ▼                  ▼
┌──────────────────────────────────────────────────────────────────┐
│             BoundarySignalCollector                                 │
│  Map<userId, Deque<BoundaryEvent>>  — 每用户 32 条硬上限            │
│  isWithinBoundary(userId, now) : boolean                            │
└──────────────────────────────────────┬───────────────────────────┘
                                        │
┌──────────────────────────────────────┴───────────────────────────┐
│ (ConversationCompletedEvent 同时触发)                              │
│ FocusStateDetector                                                 │
│  Map<userId, Deque<Instant>> recentMessages                        │
│  detect(userId, ReminderFocusState) : FocusMode                    │
│                                                                    │
│  信号短路：fullscreen > IDE title > 桌面活跃 > 对话高密度           │
└──────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
┌──────────────────────────────────────────────────────────────────┐
│         ProactiveEngine.buildContextPacket                         │
│  填充 boundaryState（IN/OUT/UNKNOWN）+ focusMode（FOCUS/NORMAL）    │
└──────────────────────────────────────┬───────────────────────────┘
                                        │
                                        ▼
┌──────────────────────────────────────────────────────────────────┐
│                     DecisionGate                                   │
│  硬边界 → 候选排序 → 既有软约束（编码/自主度/偏好）                 │
│  ▶ 新增：applyBoundaryAndFocus                                     │
│    · boundaryState 已知 → 动态阈值映射（取 min(当前级别, 映射级别)）│
│    · FOCUS_MODE：NOTIFY → QUEUE（INTERRUPT 保留）                   │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 Boundary 阈值偏移

默认配置（`lifepilot.agent.task.proactive-engine-*`）：

| 状态 | NOTIFY 阈值 | INTERRUPT 阈值 |
|---|---|---|
| UNKNOWN（默认无 collector）| 0.50 | 0.70 |
| IN_BOUNDARY | 0.35 | 0.55 |
| OUT_OF_BOUNDARY | 0.75 | 0.95 |

边界保护：所有阈值在 `[QUEUE_THRESHOLD+0.01, 0.999]` 范围内 clamp。

### 2.3 Focus 降级

只对 NOTIFY 生效。INTERRUPT 保留是关键设计：FOCUS_MODE 下仍需保留真正紧急（如 deadline 临近）的打断能力，否则用户"专心时从不知道重要事项"比"偶尔被误打扰"更伤害信任。

### 2.4 训练回放独立路径

```
                             ScheduledExecutorService
                               (SharedScheduler.heartbeat)
                                        │
                 initialDelay=60s, interval=7 days (默认)
                                        │
                                        ▼
            ┌──────────────────────────────────────────────┐
            │     ProactiveTrainingScheduler.run()          │
            └─────────────────────┬────────────────────────┘
                                  │
                                  ▼
            ┌──────────────────────────────────────────────┐
            │ ProactiveTrainingReplayService                │
            │  .buildFewShotSamples(userId, since, limit)   │
            │                                               │
            │ 从 ReminderExecutionRepository 读 → 分层采样   │
            │   - 正例：reward ≥ 0.6，top-10                │
            │   - 负例：reward ≤ 0.2，top-10                │
            └─────────────────────┬────────────────────────┘
                                  │
                                  ▼
            ┌──────────────────────────────────────────────┐
            │ ProactiveFewShotLibrary                       │
            │  target/cache/proactive-few-shot/{uid}.json   │
            └──────────────────────────────────────────────┘
```

## 3. 数据结构

### 3.1 BoundarySignalCollector.BoundaryEvent

```java
record BoundaryEvent(String userId, EventType type, Instant occurredAt) {}
enum EventType { CONVERSATION, WORKFLOW, A2A }
```

- 每用户最近 32 条队列（`ConcurrentLinkedDeque`）
- 超过 4× 窗口的事件写入时淘汰

### 3.2 BoundaryState / FocusMode

```java
enum BoundaryState { IN_BOUNDARY, OUT_OF_BOUNDARY, UNKNOWN }
enum FocusMode { FOCUS_MODE, NORMAL }
```

### 3.3 ContextPacket 扩展

record 包含 `BoundaryState boundaryState` + `FocusMode focusMode` 两个字段。紧凑构造函数中通过 null-safe 默认值（`BoundaryState.UNKNOWN` / `FocusMode.NORMAL`）处理调用方传入的空值。

### 3.4 ProactiveFewShotSample

```java
record ProactiveFewShotSample(
    String candidateType,       // DUE_SOON / COMMITMENT_GAP / ...
    String topicSummary,        // title 截断 ≤ 80
    ReminderAction historicalAction,
    float reward,               // 0.0 - 1.0
    String contextDigest,       // "score=0.8,evi=0.7,outcome=ACTED" 等
    Instant originalDecidedAt,
    boolean positive
) {}
```

Jackson 兼容：显式 `@JsonCreator` + `@JsonProperty` 以便读写 JSON。

### 3.5 ProactiveFewShotLibrary 文件布局

```
target/cache/proactive-few-shot/
  ├── default.json        # defaultUserId = "default" 时
  ├── user_1.json         # 非法字符转义后的文件名
  └── ...
```

## 4. 配置项

在 `AgentConfigProperties.TaskConfig` 新增：

| 键 | 默认值 | 说明 |
|---|---|---|
| `boundary-signal-enabled` | true | BoundarySignalCollector 总开关 |
| `boundary-window-minutes` | 10 | 边界窗口分钟数 |
| `focus-detection-enabled` | true | FocusStateDetector 总开关 |
| `focus-message-density-threshold` | 5 | FOCUS_MODE 最小消息数 |
| `focus-message-interval-seconds` | 40 | FOCUS_MODE 最大平均间隔 |
| `proactive-engine-boundary-notify-delta` | -0.15 | boundary 内 NOTIFY 阈值偏移 |
| `proactive-engine-boundary-interrupt-delta` | -0.15 | boundary 内 INTERRUPT 阈值偏移 |
| `proactive-engine-out-of-boundary-delta` | +0.25 | boundary 外两级阈值偏移 |
| `proactive-training-enabled` | false | 训练回放总开关（默认关闭） |
| `proactive-training-replay-interval-days` | 7 | 回放间隔 |
| `proactive-training-positive-reward-threshold` | 0.6 | 正例 reward 阈值 |
| `proactive-training-negative-reward-threshold` | 0.2 | 负例 reward 阈值 |
| `proactive-training-top-k-positive` | 10 | 正例 top-K |
| `proactive-training-top-k-negative` | 10 | 负例 top-K |

## 5. 跨模块接口变更

| 变更接口 | 所属模块 | 变更内容 | 影响模块 |
|---|---|---|---|
| `ContextPacket` record | agent.task.proactive | 包含 boundary / focus 字段 | proactive.behavior / reminder / 测试 |
| `ProactiveEngine` 构造函数 | agent.task.proactive | 接收 boundary / focus / 分层策略依赖 | ProactiveAutoConfiguration / 测试 |
| `DecisionGate` 构造函数 | agent.task.proactive | 完整构造函数接收 boundary deltas，默认构造入口使用内置参数 | ProactiveAutoConfiguration / 测试 |
| `DecisionGate.scoreToLevel(float, ContextPacket)` | agent.task.proactive | 按 boundary / focus 动态映射投递级别 | proactive 决策测试 |

## 6. 依赖接口验证

| 接口 | 源码位置 | 验证状态 |
|------|---------|---------|
| `ConversationCompletedEvent(Object, String userId, String sessionId, @Nullable String summary)` | com.lifepilot.agent.task.proactive | ✅ 已核对 |
| `WorkflowCompletedEvent(String executionId, String status, String outputJson)` | com.lifepilot.agent.suspend.event | ✅ 已核对，record 无 userId 字段 |
| `A2aTaskCompletedEvent(String remoteTaskId, String resultJson)` | com.lifepilot.agent.suspend.event | ✅ 已核对 |
| `ReminderExecutionRepository.findReplaySamplesByUserIdSince(userId, since, limit)` | com.lifepilot.agent.task.reminder | ✅ 已核对，返回 `List<ReminderReplaySample>` |
| `ReminderFocusState(focusApp, focusTitle, fullscreen, idleMinutes, timestamp)` | com.lifepilot.agent.task.reminder | ✅ 已核对 |
| `NotificationProperties.getDefaultUserId()` | com.lifepilot.notification.config | ✅ 已核对 |
| `SharedScheduler.heartbeat()` → ScheduledExecutorService | com.lifepilot.config.threadpool | ✅ 已核对 |

## 7. 可观测性

- BoundarySignalCollector：所有 `record` 写入、`isWithinBoundary` 命中均以 DEBUG 级别输出
- FocusStateDetector：消息记录输出 DEBUG，`detect` 命中时不输出（避免热点路径日志）
- DecisionGate：boundary/focus 调整时输出 DEBUG（`决策门控: boundary/focus 调整，{}→{}`）
- ProactiveTrainingScheduler：启动、每次 run 完成输出 INFO

## 8. 前沿参考与设计依据

| 来源 | 关键结论 | 应用 |
|------|---------|------|
| JetBrains ProAIDE Field Study（arXiv:2601.10253，2026） | post-commit engagement 52% vs mid-task dismissed 62%；boundary 概念对 engagement 起决定性作用 | BoundarySignalCollector 窗口 10 分钟设计 |
| CHI 2025 Goldilocks Time Window（arXiv:2504.09332） | 时机错位比内容错位更伤害体验；window-end = deadline − p80(latency) | 下一 spec `proactive-timing-cot` 会扩展时效窗口；本 spec 打下 boundary 数据基础 |
| ProAgentBench（arXiv:2602.04482，2026） | 真实数据 SFT 让 ProAgentBench 准确率 57.3% → 74.0%（+16.7pp），远超 synthetic 数据 | ProactiveTrainingReplayService 只用真实反馈历史，不生成 synthetic |
| ContextAgent NeurIPS 2025（arXiv:2505.14668） | CoT 蒸馏 + think-before-action 让 Acc-P 从 77% → 87% | 下一 spec 消费本 spec 的 few-shot 库做 CoT prompt |
| MINJA 注入攻击（arXiv:2601.05504，2026） | 对生产 Agent 注入成功率 95%，标准 LLM 检测漏检 66% | 本 spec 不处理安全；留给 memory-security-polish |

## 9. 未来扩展（不在本 spec 范围）

- **Goldilocks Time Window 预测**（P-P1-4，归 `proactive-timing-cot` spec）：window_end = deadline − p80(latency)，超过 window_end × 0.8 且 focus_mode 则跳过
- **Gate 3 Think-before-action**（P-P1-5，归 `proactive-timing-cot` spec）：Gate 3 prompt 改为四段 `<think>` 结构化思考，消费本 spec 产出的 few-shot 库
- **行为插件分层激活**（P-P1-6，归 `proactive-timing-cot` spec）：事实驱动 / 习惯驱动 / 独立触发三组按记忆层触发
- **Off-Policy Evaluation**（P-P2-7，后续 spec）：LinUCB OPE，基于 importance-sampled reward 计算 regret 曲线
- **Meta-check**（P-P2-8，后续 spec）：Gate 3 通过后插入"用户是否清楚自己需要"自检

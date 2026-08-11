# 主动引擎 V2 — 架构设计

> **文档性质**：架构设计文档（重新设计）
> **模块归属**：`com.lifepilot.agent.initiative`（新包名，与旧 `agent.task.proactive` 完全独立）
> **最后更新**：2026-06-20
> **替代**：`com.lifepilot.agent.task.proactive`（整体废弃）

---

## 1. 为什么重新设计

### 1.1 旧系统的根本问题

旧主动引擎（ProactiveEngine）本质上是一个**通知调度系统**：定时心跳 → 扫描候选 → 评分 → 推送通知 → 结束。这个范式有不可修补的结构性缺陷：

| 问题 | 根因 | 表现 |
|------|------|------|
| 重复打扰 | 心跳式巡检 + 候选级去重（非意图级） | 同一件事被不同插件、不同心跳周期反复检测到 |
| 提醒价值低 | 8 个插件并列全跑，缺乏"这件事值不值得开口"的深度判断 | 提了不重要的事，或者方式不对 |
| 提醒是死胡同 | 输出是一条文案，不是一段对话 | 用户只能点有用/没用，无法追问、展开、拒绝并说明原因 |
| 时机错位 | 定时唤醒不感知用户状态 | 用户专注时被打断，空闲时反而沉默 |
| 缺乏内心独白 | 没有"持续思考"的中间层 | 要么沉默，要么直接输出，没有酝酿过程 |

### 1.2 范式转变

| 维度 | 旧范式 | 新范式 |
|------|--------|--------|
| 核心隐喻 | 通知调度器 | **主动对话发起者** |
| 触发方式 | 定时心跳巡检 | 事件驱动 + 空闲思考 |
| 输出形式 | 一条通知文案 | 发起一段完整对话（有上下文、有后续能力） |
| 交互模型 | 单向推送 → 有用/没用 | 双向对话 → 用户可追问、拒绝、展开 |
| 决策逻辑 | 评分 > 阈值就发 | 意图形成 → 成熟度判断 → 时机等待 → 对话发起 |
| 重复控制 | 候选级冷却时间 | 意图级去重（同一个"想法"只表达一次） |
| 学习信号 | 有用/没用二值 | 对话质量（是否展开、采纳率、对话深度） |

---

## 2. 设计目标

1. **主动行为 = 发起对话**：每次主动介入都是一段可以展开的对话，不是一条死消息
2. **事件驱动 + 空闲思考**：不再定时巡检，而是被事件唤醒或在空闲时主动思考
3. **意图级去重**：同一个"想法"从形成到表达只有一次机会，表达后无论结果如何都不再重复
4. **渠道无关**：主动发起复用正常 Agent 会话链路，不在主动层维护独立投递分支
5. **可观测**：每个意图的生命周期（形成 → 成熟 → 表达 → 结果）完整可追溯
6. **克制优先**：宁可少说一句，不多说一句。默认保守，信任积累后逐步放开

## 3. 非目标

- 不做实时流式监控（不监听每一次按键、每一次窗口切换）
- 不做复杂的强化学习（LinUCB/LinTS 过于复杂且冷启动差，用简单规则 + 反馈衰减替代）
- 不追求"全知全能"的端到端 LLM 决策
- 不保留旧系统的任何代码（完全重写，旧包 `agent.task.proactive` 整体删除）

---

## 4. 核心概念

### 4.1 Thought（想法）

系统的核心抽象不再是"候选提醒"，而是 **Thought**——一个正在酝酿中的想法。

```java
/**
 * 一个正在酝酿中的想法。
 *
 * <p>Thought 是主动引擎的核心抽象。它代表系统"想对用户说的一件事"，
 * 从产生到表达经历完整的生命周期。</p>
 */
public record Thought(
    String id,
    String intentKey,        // 意图唯一键（同一意图不重复表达）
    ThoughtKind kind,        // 想法类型
    String summary,          // 一句话概要（人类可读）
    List<Evidence> evidence, // 支撑证据
    float confidence,        // 置信度 [0,1]
    float maturity,          // 成熟度 [0,1]（越高越值得表达）
    Instant createdAt,
    Instant matureAt,        // 预计成熟时间（可选）
    ThoughtState state       // 生命周期状态
) {}
```

### 4.2 ThoughtState（想法生命周期）

```java
public enum ThoughtState {
    BREWING,      // 酝酿中：刚产生，还不够成熟
    READY,        // 就绪：已成熟，等待合适时机表达
    EXPRESSED,    // 已表达：已发起对话
    DISMISSED,    // 已放弃：过期、被新信息否定、或用户明确拒绝
    ABSORBED      // 已吸收：用户采纳并展开了对话
}
```

### 4.3 ThoughtKind（想法类型）

```java
public enum ThoughtKind {
    REMINDER,       // 提醒：某件事快到期了、该做了
    FOLLOW_UP,      // 追问：之前聊过的事，想知道进展
    INSIGHT,        // 洞察：发现了有价值的关联或模式
    PREPARATION,    // 准备：即将到来的事件需要提前准备
    CONCERN,        // 关切：注意到用户可能遇到了问题
    SUGGESTION      // 建议：基于积累的了解，有一个建议想提
}
```

### 4.4 Evidence（证据）

Evidence 不只是摘要文本，它是**记忆系统的精确指针**。当用户追问细节时，Agent 可以通过 Evidence 中的 `sourceId` 回溯到原始数据。

```java
/**
 * 支撑想法的证据。
 *
 * <p>每条证据精确指向记忆系统中的一个实体、一段对话或一个外部事件。
 * 当主动对话发起后，Evidence 列表会注入到 session 上下文中，
 * 使 Agent 在后续对话中能够回溯原始信息。</p>
 */
public record Evidence(
    String sourceType,    // 来源类型：memory_entity / conversation / calendar / observation
    String sourceId,      // 精确指向：L3 实体 ID / 对话 session ID / 日历事件 ID / 工作流 ID
    @Nullable
    String spaceId,       // 所属 MemorySpace（用于检索时的 scope 限定）
    String excerpt,       // 人类可读摘要（用于开场白生成和 UI 展示）
    @Nullable
    String retrievalHint, // 检索提示（Agent 追问时用于 memory.recall 的查询线索）
    Instant observedAt,   // 观察时间
    float relevance       // 相关度 [0,1]
) {}
```

#### 证据与记忆的关联机制

Evidence 在三个阶段发挥作用：

**1. 想法生成时（Thinker）**

Thinker 从记忆系统检索相关信息时，同时记录检索路径：

```
Thinker 空闲思考 → 查询 L3 实体（GOAL 类型，状态=ACTIVE）
  → 发现 entity-uuid-123："学日语"目标，最近 14 天无进展
  → 生成 Evidence:
      sourceType = "memory_entity"
      sourceId = "entity-uuid-123"
      spaceId = "default"
      excerpt = "用户设定了学日语的目标，最近两周没有相关对话"
      retrievalHint = "日语学习 目标 进展"
```

**2. 对话发起时（ConversationInitiator）**

所有 Evidence 被序列化为 session 的初始上下文，注入 AgentRequest 的 system prompt：

```
你正在发起一次主动对话。以下是你开口的依据：

[证据 1] 来源：记忆实体 entity-uuid-123（default 空间）
  内容：用户设定了学日语的目标，最近两周没有相关对话
  观察时间：2026-05-12

[证据 2] 来源：对话 session-abc-456
  内容：用户 5 月 1 日说"这个月要把五十音背完"
  观察时间：2026-05-01

如果用户追问细节，你可以使用 memory.recall 工具检索原始信息。
```

**3. 用户追问时（Agent 对话中）**

Agent 拥有完整的工具能力，可以：
- `memory.recall(entityId="entity-uuid-123")` → 获取目标实体的完整属性
- `memory.search(query="日语学习", spaceId="default")` → 搜索相关记忆
- 直接引用 Evidence 中的 excerpt 回答简单问题

**示例场景：**

```
系统：这几天好像没怎么碰日语，五十音背到哪了？需要我帮你安排一下复习计划吗？

用户：上次我学到哪了来着？

Agent 内部：
  → 从 session 上下文中找到 Evidence[1].sourceId = "session-abc-456"
  → 调用 memory.recall(sessionId="session-abc-456")
  → 获取原始对话："用户说已经背完了あ行和か行，正在背さ行"

Agent：上次你说已经背完了あ行和か行，正在さ行。要从さ行继续吗？
```


---

## 5. 总体架构

### 5.1 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        Initiative Engine                          │
│                                                                   │
│  ┌──────────┐    ┌──────────────┐    ┌───────────────┐          │
│  │  Signal   │───▶│   Thinker    │───▶│  Thought Pool │          │
│  │  Sources  │    │  (空闲思考)   │    │  (想法池)     │          │
│  └──────────┘    └──────────────┘    └───────┬───────┘          │
│                                               │                   │
│                                               ▼                   │
│                                      ┌───────────────┐           │
│                                      │   Gatekeeper   │           │
│                                      │  (表达门控)    │           │
│                                      └───────┬───────┘           │
│                                               │                   │
│                                               ▼                   │
│                                      ┌───────────────┐           │
│                                      │ Conversation  │           │
│                                      │  Initiator    │           │
│                                      │ (对话发起器)  │           │
│                                      └───────┬───────┘           │
│                                               │                   │
└───────────────────────────────────────────────┼───────────────────┘
                                                │
                                                ▼
                                       ┌─────────────────┐
                                       │AgentOrchestrator│
                                       │  (发起对话)     │
                                       └────────┬────────┘
                                                │
                                          ┌─────┼─────┐
                                          ▼     ▼     ▼
                                     ┌──────┐┌──────┐┌──────┐
                                     │ Web  ││ 飞书 ││ 钉钉 │
                                     │ SSE  ││Channel││Channel│
                                     └──────┘└──────┘└──────┘
```

### 5.2 五个核心组件

| 组件 | 职责 | 触发方式 |
|------|------|---------|
| **Signal Sources** | 收集事件信号，不做决策 | 被动接收 Spring Event |
| **Thinker** | 将信号转化为想法，或在空闲时主动思考 | 事件驱动 + 空闲调度 |
| **Thought Pool** | 管理所有想法的生命周期 | 被动存储 + 定期清理 |
| **Gatekeeper** | 判断"现在是否适合表达这个想法" | 被 Thinker 或定时器触发 |
| **Conversation Initiator** | 将对话类想法转化为一段对话并发起 | 被 Gatekeeper 放行后触发 |

---

## 6. Signal Sources（信号源）

### 6.1 设计原则

信号源是纯粹的事件收集器，**不做任何决策**。它只负责把"发生了什么"结构化地传递给 Thinker。

### 6.2 信号类型

```java
public sealed interface Signal permits
    Signal.ConversationEnded,
    Signal.TaskCompleted,
    Signal.TimeElapsed,
    Signal.MemoryChanged,
    Signal.CalendarApproaching,
    Signal.UserReturned,
    Signal.IdleDetected {

    Instant timestamp();
    String userId();

    /** 一轮对话结束 */
    record ConversationEnded(String userId, String sessionId,
                             String summary, Instant timestamp) implements Signal {}

    /** 定时任务/工作流完成 */
    record TaskCompleted(String userId, String taskId,
                         String taskName, Instant timestamp) implements Signal {}

    /** 时间流逝（用于到期类提醒，由轻量定时器产生） */
    record TimeElapsed(String userId, Instant timestamp,
                       List<UpcomingDeadline> deadlines) implements Signal {}

    /** 记忆发生变化（新实体写入、实体过期等） */
    record MemoryChanged(String userId, String entityId,
                         String changeType, Instant timestamp) implements Signal {}

    /** 日历事件临近 */
    record CalendarApproaching(String userId, String eventId,
                               String eventTitle, Instant eventTime,
                               Instant timestamp) implements Signal {}

    /** 用户回来了（从不活跃变为活跃） */
    record UserReturned(String userId, String channel,
                        Instant timestamp) implements Signal {}

    /** 系统检测到空闲（无对话超过阈值时间） */
    record IdleDetected(String userId, Duration idleDuration,
                        Instant timestamp) implements Signal {}
}
```

### 6.3 信号产生方式

| 信号 | 产生者 | 触发条件 |
|------|--------|---------|
| ConversationEnded | ConversationCompletionHook（已有） | 对话结束时 |
| TaskCompleted | WorkflowEngine / CronScheduler | 任务完成时 |
| TimeElapsed | 轻量定时器（每小时一次） | 检查近期到期事项 |
| MemoryChanged | Spring Event（EntityLifecycleChanged） | 记忆实体变化时 |
| CalendarApproaching | SyncEngine（如已接入） | 日历事件临近时 |
| UserReturned | Web/Channel 入口检测 | 用户发送第一条消息时 |
| IdleDetected | 空闲检测器 | 无对话超过配置阈值时 |

---

## 7. Thinker（思考器）

### 7.1 设计原则

Thinker 是整个系统的"大脑"。它有两种工作模式：

1. **事件响应模式**：收到信号后，快速判断是否值得形成一个想法
2. **空闲思考模式**：在用户不活跃时，主动回顾记忆、发现关联、酝酿想法

### 7.2 事件响应流程

```
Signal 到达
  │
  ├─ 快速过滤（纯规则，无 LLM）
  │   ├─ 信号是否与已有 READY 想法重复？→ 丢弃
  │   ├─ 信号置信度是否足够？→ 丢弃
  │   └─ 通过
  │
  └─ 想法生成（轻量 LLM 或规则）
      ├─ 从信号 + 相关记忆构建 Thought
      ├─ 设置初始 confidence 和 maturity
      └─ 放入 Thought Pool（状态 = BREWING）
```

### 7.3 空闲思考流程

```
IdleDetected 信号到达
  │
  └─ Thinker 进入"沉思"模式
      │
      ├─ 1. 回顾近期对话：有没有用户提过但没跟进的事？
      ├─ 2. 扫描记忆图谱：有没有新的关联值得分享？
      ├─ 3. 检查长期目标：有没有停滞的目标需要推动？
      ├─ 4. 审视已有想法：BREWING 中的想法是否该升级或放弃？
      │
      └─ 产出：新的 Thought 或更新已有 Thought 的 maturity
```

### 7.4 空闲思考的 Token 预算

```yaml
lifepilot:
  initiative:
    idle-thinking:
      enabled: true
      # 触发条件：无对话超过此时长
      idle-threshold-minutes: 30
      # 每次空闲思考的最大 token 预算
      max-tokens-per-session: 2000
      # 每天最多思考几次
      max-sessions-per-day: 4
      # 使用的 LLM 场景（可配置为便宜的模型）
      llm-scene: thinking
```

### 7.5 想法成熟度模型（已落地 thought-maturity-evolution）

想法不是产生了就立刻表达。它需要"酝酿"：

| maturity 区间 | 含义 | 行为 |
|:---:|------|------|
| 0.0 - 0.3 | 刚萌芽，证据不足 | 继续观察，等待更多信号 |
| 0.3 - 0.6 | 有一定依据，但不紧急 | 等待合适时机 |
| 0.6 - 0.8 | 比较成熟，值得表达 | 进入 Gatekeeper 评估 |
| 0.8 - 1.0 | 非常成熟或紧急 | 优先表达 |

成熟度由确定性纯函数组件 `MaturityModel`（`com.lifepilot.agent.initiative.maturity`）按三种力演化（不调 LLM、不依赖随机、`now` 由调用方传入，可复现）：

- **证据强化（reinforce，离散）**：同 intentKey 再次被观察到时，`ThoughtPool.submit` 按新证据 relevance 加权提升 maturity——`gain = reinforceBaseGain · weight · (1 - maturity)`，越接近 1.0 增量越小（边际递减），并合并证据、刷新 `lastReinforcedAt`。取代了原先固定 `+0.1`。
- **截止升温（deadline pull，连续）**：带未来截止锚点（`matureAt`，由 DefaultThinker 从注意力项 `dueAt` 注入）的想法，在截止前 `deadlinePullWindowHours` 窗口内随临近线性升温，逾期取最大；作为下托底（`max(decayed, pull)`），不被衰减压住。
- **停滞衰减（staleness decay，连续）**：自 `lastReinforcedAt` 起超过 `decayGraceHours` 宽限期后，maturity 按 `decayHalfLifeHours` 指数半衰期衰减。

状态迁移由 maturity 驱动且带迟滞（避免阈值附近抖动）：BREWING→READY 当 `maturity ≥ readyThreshold(0.6)`；READY→BREWING 当 `maturity < demoteThreshold(0.5)`；任意活跃态 → DISMISSED 当 `maturity ≤ dismissFloor(0.15)`。EXPRESSED/终态不被演化改写。硬 TTL（brewing/ready）保留为兜底。

演化在 `InitiativeEngine.tryExpress` / `idleThink` 前由 `ThoughtPool.evolve(now)` 周期重算并持久化（`initiative_thoughts.last_reinforced_at`，V4 迁移新增列），重启后曲线连续。配置见 §15 `lifepilot.initiative.maturity.*`。


---

## 8. Thought Pool（想法池）

### 8.1 设计原则

想法池是所有想法的生命周期管理器。核心职责：

1. **意图级去重**：同一个 `intentKey` 只允许存在一个活跃想法
2. **生命周期管理**：自动过期、自动清理
3. **优先级排序**：当多个想法同时就绪时，决定表达顺序
4. **持久化与重启恢复**（memory-trust-and-cleanup）：注入 `@Nullable ThoughtRepository`（`initiative_thoughts` 表）后，
   `submit` / `transition` / `cleanup` 即写库（best-effort，失败仅 warn 不阻塞思考）；构造时 `loadActiveFromRepository`
   从库恢复 `BREWING` / `READY` 活跃想法，避免重启丢失去重与冷却状态。未注入仓库时退化为纯内存（测试/未启用持久化）。
   终态（`DISMISSED` / `ABSORBED`）想法不在恢复集合内。

### 8.2 intentKey 设计

`intentKey` 是防止重复的核心。它标识的是"用户视角的同一件事"：

```
reminder:meeting:2026-05-15T10:00     // 提醒明天的会议
follow_up:goal:learn-japanese          // 追问日语学习进展
insight:pattern:sleep-exercise         // 发现睡眠和运动的关联
preparation:trip:shanghai-0520         // 出差准备
concern:habit:no-exercise-3days        // 连续3天没运动
suggestion:tool:obsidian-sync          // 建议开启 Obsidian 同步
```

规则：
- 同一 `intentKey` 在 `BREWING` 或 `READY` 状态下只能有一个
- 一旦进入 `EXPRESSED`，该 `intentKey` 进入冷却期（默认 7 天不再产生同类想法）
- `DISMISSED` 的想法，其 `intentKey` 冷却期更短（3 天），允许新证据重新触发

### 8.3 容量控制

```yaml
lifepilot:
  initiative:
    thought-pool:
      # 同时存在的最大想法数
      max-active-thoughts: 20
      # BREWING 状态最长存活时间
      brewing-ttl-hours: 72
      # READY 状态最长等待时间（超过则 DISMISSED）
      ready-ttl-hours: 48
      # intentKey 冷却期
      expressed-cooldown-days: 7
      dismissed-cooldown-days: 3
```

### 8.4 数据模型

```sql
CREATE TABLE initiative_thoughts (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    intent_key      TEXT NOT NULL,
    kind            TEXT NOT NULL,
    summary         TEXT NOT NULL,
    evidence_json   TEXT NOT NULL,
    confidence      REAL NOT NULL DEFAULT 0.0,
    maturity        REAL NOT NULL DEFAULT 0.0,
    state           TEXT NOT NULL DEFAULT 'BREWING',
    conversation_id TEXT,          -- 表达后关联的对话 ID
    created_at      TEXT NOT NULL,
    mature_at       TEXT,
    expressed_at    TEXT,
    resolved_at     TEXT,
    
    UNIQUE(user_id, intent_key, state)  -- 同用户同意图同状态唯一
);

CREATE INDEX idx_thoughts_user_state ON initiative_thoughts(user_id, state);
CREATE INDEX idx_thoughts_intent_key ON initiative_thoughts(intent_key);
```

---

## 9. Gatekeeper（表达门控）

### 9.1 设计原则

Gatekeeper 回答一个问题：**"现在适合表达这个想法吗？"**

它不判断想法本身的价值（那是 Thinker 的事），只判断**时机**。

### 9.2 门控维度

```java
public record GatekeeperContext(
    boolean userIsActive,          // 用户当前是否活跃
    boolean userInConversation,    // 用户是否正在对话中
    Instant lastConversationEnd,   // 上次对话结束时间
    int todayExpressedCount,       // 今天已表达的想法数
    Duration timeSinceLastExpress, // 距上次表达的时间
    @Nullable FocusState focusState // 专注状态（如可获取）
) {}
```

### 9.3 门控规则（硬规则，不用 LLM）

```java
public sealed interface GatekeeperDecision permits
    GatekeeperDecision.Express,
    GatekeeperDecision.Wait,
    GatekeeperDecision.Dismiss {

    /** 可以表达 */
    record Express(Thought thought, ExpressUrgency urgency) implements GatekeeperDecision {}
    /** 再等等 */
    record Wait(Thought thought, String reason, Duration suggestedDelay) implements GatekeeperDecision {}
    /** 放弃这个想法 */
    record Dismiss(Thought thought, String reason) implements GatekeeperDecision {}
}
```

门控规则（按优先级）：

1. **静默时段**：配置的安静时间内 → Wait（除非 CRITICAL）
2. **每日额度**：今天已表达 ≥ 配置上限 → Wait（除非 urgency=CRITICAL）
3. **用户正在对话中** → Wait（不打断正在进行的对话）
4. **间隔太短**：距上次表达 < 最小间隔 → Wait（除非 urgency=CRITICAL）
5. **通过硬门控** → Express

### 9.4 表达紧急度

```java
public enum ExpressUrgency {
    LOW,       // 随便什么时候都行
    NORMAL,    // 今天内表达即可
    HIGH,      // 尽快表达（有时效性）
    CRITICAL   // 必须立刻表达（紧急截止、重要提醒）
}
```

紧急度由 Thought 的 kind + maturity + 证据中的时间约束共同决定：
- 有明确截止时间且 < 2 小时 → CRITICAL
- 有明确截止时间且 < 24 小时 → HIGH
- maturity > 0.8 且无时间约束 → NORMAL
- 其他 → LOW

### 9.5 配置

```yaml
lifepilot:
  initiative:
    # 每日最大主动对话数
    daily-max-expressions: 3
    # 两次表达之间的最小间隔
    min-interval-minutes: 60
    # 静默时段
    quiet-hours-start: "23:00"
    quiet-hours-end: "08:00"
```

---

## 10. Conversation Initiator（对话发起器）

### 10.1 设计原则

这是新系统与旧系统最大的区别：**主动行为的输出不是一条通知，而是发起一段真正的对话**。

用户收到的不是"【轻提醒】你明天有个会议"，而是系统像朋友一样开口说话，用户可以自然地回应、追问、或者说"知道了"。

### 10.2 对话发起流程

```
Gatekeeper 放行
  │
  ├─ 1. 构建对话上下文
  │     ├─ 想法的 summary + evidence
  │     ├─ 用户相关的记忆片段
  │     └─ 当前时间/场景信息
  │
  ├─ 2. 生成开场白（LLM）
  │     ├─ 语气：温和、建议式、不强迫
  │     ├─ 内容：说清楚"为什么现在跟你说这个"
  │     └─ 结尾：留出对话空间（提问或建议）
  │
  ├─ 3. 构造 AgentRequest
  │     ├─ sessionId = initiative-*
  │     ├─ source = system("initiative:{thoughtId}")
  │     └─ systemPrompt 注入想法上下文
  │
  ├─ 4. 调用 AgentOrchestrator
  │     ├─ 主动开场白作为用户可见输入
  │     ├─ 复用正常 Agent 循环与工具能力
  │     └─ 不在 initiative 内维护独立投递/fallback 分支
  │
  └─ 5. 更新 Thought 状态 → EXPRESSED
        └─ 记录 conversationId，等待后续交互
```

### 10.3 开场白生成

开场白的质量决定了用户是否愿意继续对话。核心要求：

1. **说清楚为什么**：不是突然冒出来，而是有明确的触发原因
2. **语气自然**：像朋友随口提一句，不像系统通知
3. **留出空间**：结尾是开放式的，用户可以选择展开或简单回应
4. **简短**：开场白不超过 3 句话

示例对比：

```
❌ 旧系统：【轻提醒】你明天有个会议，建议提前准备材料。

✅ 新系统：明天上午 10 点有和产品组的周会，上次你提到想讨论 V2 的排期。
          需要我帮你整理一下要点吗？
```

```
❌ 旧系统：【主动提醒】你已经连续 3 天没有运动了。

✅ 新系统：这几天好像比较忙，运动计划暂停了。
          是想调整一下频率，还是等这阵子忙完再恢复？
```

### 10.4 对话后续能力

发起对话后，这个 session 就是一个正常的 Agent 对话，用户可以：

- 追问细节："上次讨论了什么来着？"
- 让 Agent 行动："帮我列个提纲"
- 简单确认："好的，我知道了"
- 拒绝："这个不用提醒我了"
- 延后："晚点再说"

Agent 在这个 session 中拥有完整的工具能力（记忆、Skill、MCP 等），可以真正帮用户做事。

### 10.5 渠道边界

主动层只负责把想法转成一段正常 Agent 对话：`Thought → AgentRequest → AgentOrchestrator`。
具体消息投递、前端展示和外部 IM 适配由现有交互层处理，initiative 包内不保留
`DeliveryStrategy`、fallbackChannel 或专用渠道路由。


---

## 11. 主动执行（暂不实现）

当前主动发起层只做"想法 → 门控 → 主动对话"闭环，不保留后台直接执行路径。

原因：
- 通用型个人助手的主动能力应先建立在轻量、可回应的对话上，避免用户感觉系统在背后自作主张。
- 直接执行需要独立的授权、风险分级、结果追溯和撤销模型；这些能力尚未进入当前实现范围。
- 因此代码中不保留执行器、执行授权或执行记录表，后续若重新引入，应作为独立 spec 重新设计并一次性接通。

---

## 12. 对话结果学习

### 12.1 学习信号

旧系统只有"有用/没用"两个信号。新系统从对话本身提取丰富的学习信号：

| 信号 | 含义 | 权重 |
|------|------|------|
| 用户展开了对话（≥2 轮） | 这个想法有价值 | 强正信号 |
| 用户让 Agent 执行了动作 | 想法转化为行动 | 最强正信号 |
| 用户简单确认（"好的/知道了"） | 有用但不紧急 | 弱正信号 |
| 用户说"不用提醒了" | 这类想法不受欢迎 | 强负信号 |
| 用户说"晚点再说" | 时机不对但内容可以 | 时机负信号 |
| 用户无回应（超时） | 打扰了 | 中等负信号 |
| 用户立刻开始新话题（忽略） | 不感兴趣 | 中等负信号 |

### 12.2 学习应用

学习信号影响三个层面：

1. **意图层**：该 `intentKey` 的冷却期调整
   - 强正信号 → 同类想法未来可以更积极
   - 强负信号 → 同类想法冷却期延长

2. **时机层**：什么时候表达更容易被接受
   - 记录每次表达的时间特征（星期几、几点、什么场景）
   - 正信号的时间特征 → 未来优先在类似时机表达

3. **风格层**：用户喜欢什么样的表达方式
   - 简短 vs 详细
   - 直接 vs 委婉
   - 提问式 vs 陈述式

### 12.3 数据模型

```sql
CREATE TABLE initiative_outcomes (
    id              TEXT PRIMARY KEY,
    thought_id      TEXT NOT NULL REFERENCES initiative_thoughts(id),
    conversation_id TEXT NOT NULL,
    user_turns      INTEGER NOT NULL DEFAULT 0,  -- 用户回复轮数
    agent_actions   INTEGER NOT NULL DEFAULT 0,  -- Agent 执行的动作数
    outcome_type    TEXT NOT NULL,  -- ENGAGED / ACKNOWLEDGED / REJECTED / DEFERRED / IGNORED
    outcome_detail  TEXT,           -- 用户的具体反馈（如有）
    duration_seconds INTEGER,       -- 对话持续时长
    expressed_at    TEXT NOT NULL,
    resolved_at     TEXT,
    
    -- 时间特征（用于时机学习）
    hour_of_day     INTEGER,
    day_of_week     INTEGER,
    was_boundary    INTEGER,  -- 是否在任务边界窗口内
    was_idle        INTEGER   -- 用户是否处于空闲状态
);

CREATE INDEX idx_outcomes_thought ON initiative_outcomes(thought_id);
CREATE INDEX idx_outcomes_type ON initiative_outcomes(outcome_type);
```

---

## 13. 与现有模块的集成

### 13.1 记忆系统

| 集成点 | 方式 | 说明 |
|--------|------|------|
| 读取 L3 语义记忆 | Thinker 空闲思考时查询 | 发现关联、检查目标进展 |
| 读取 L4 程序记忆 | Gatekeeper 判断用户偏好 | 什么时间段用户更愿意被打扰 |
| 订阅 EntityLifecycleChanged | Signal Source | 记忆变化触发想法生成 |
| 写入对话结果 | Outcome → L4 偏好 | 学习用户对主动行为的偏好 |

### 13.2 Agent 引擎

| 集成点 | 方式 | 说明 |
|--------|------|------|
| 发起对话 | 构造 AgentRequest + 调用 AgentOrchestrator | 主动发起的对话走完整 Agent 循环 |
| InteractionSource | `InteractionSource.system("initiative:{thoughtId}")` | 标识来源为主动引擎 |
| 对话结束回调 | 订阅 ConversationCompletedEvent | 收集对话结果用于学习 |

### 13.3 消息网关 / 通知

| 集成点 | 方式 | 说明 |
|--------|------|------|
| Agent 会话链路 | AgentOrchestrator | 主动层不直接投递消息 |
| Web/IM 展示 | 交互层现有机制 | 后续如需多渠道偏好，在交互层统一扩展 |

### 13.4 工作流 / 定时任务

| 集成点 | 方式 | 说明 |
|--------|------|------|
| 工作流完成 | WorkflowCompletedEvent → Signal | 触发 TaskCompleted 信号 |
| 定时任务完成 | CronTaskCompletedEvent → Signal | 同上 |
| 不与 CronScheduler 冲突 | intentKey 排除已有 cron 的主题 | 避免重复 |

---

## 14. 前端交互设计

### 14.1 Web 端

主动发起的对话在前端表现为：

1. **对话列表中出现新会话**：带有特殊标记（如"知微想跟你聊聊"）
2. **气泡通知**：右下角浮窗提示，点击进入对话
3. **对话界面**：与普通对话完全一致，用户可以正常回复

```typescript
// 前端收到的 SSE 事件
interface InitiativeEvent {
  type: 'initiative_conversation';
  sessionId: string;
  thoughtSummary: string;  // 用于气泡预览
  openingMessage: string;  // 开场白
  urgency: 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';
}
```

### 14.2 IM 渠道

在飞书/钉钉/企微中，主动发起的对话就是一条普通消息：

- 不使用卡片/模板消息（避免"系统通知"感）
- 直接以文本形式发送开场白
- 用户回复后自动进入对话模式（与正常对话无异）

### 14.3 用户控制

用户可以在设置中控制主动行为：

```yaml
# 用户可配置项（通过对话或设置页面）
initiative:
  enabled: true
  daily-max: 3
  quiet-hours: "23:00-08:00"
  preferred-channel: "web"  # web / feishu / dingtalk / wecom
  # 按类型开关
  kinds:
    reminder: true
    follow_up: true
    insight: true
    preparation: true
    concern: true
    suggestion: false  # 用户关闭了建议类
```

---

## 15. 配置参考

```yaml
lifepilot:
  initiative:
    # 总开关
    enabled: true

    # 想法池
    max-active-thoughts: 20
    brewing-ttl-hours: 72
    ready-ttl-hours: 48

    # 表达门控
    daily-max-expressions: 3
    min-interval-minutes: 60
    quiet-hours-start: "23:00"
    quiet-hours-end: "08:00"

    # 空闲思考
    idle-thinking-enabled: true
    idle-threshold-minutes: 30
    idle-thinking-max-daily: 4

    # 想法成熟度演化（thought-maturity-evolution）
    maturity:
      ready-threshold: 0.6            # ≥ 则 BREWING→READY
      demote-threshold: 0.5           # READY 且 < 则回退 BREWING（迟滞带）
      dismiss-floor: 0.15             # ≤ 则 DISMISSED
      reinforce-base-gain: 0.15       # 强化基础增益（再乘证据权重与边际递减）
      decay-half-life-hours: 48       # 停滞衰减半衰期
      decay-grace-hours: 24           # 衰减宽限期（期内不衰减）
      deadline-pull-window-hours: 72  # 截止升温窗口

```

---

## 16. 迁移策略

### 16.1 删除范围

整个 `com.lifepilot.agent.task.proactive` 包及其子包全部删除：
- `ProactiveEngine` + 所有行为插件
- `DecisionGate` / `DeliveryEngine` / `DeliveryLevel`
- `HeartbeatRunner` / `ContextPacket`
- `ProactiveMemoryBridge` / `TrustUpgradeService`
- `BoundarySignalCollector` / `FocusStateDetector`
- `ProactiveTrainingReplayService` / `ProactiveFewShotLibrary`
- `GoldilocksWindowCalculator` / `GateThreeReasoner`
- `BehaviorActivationPolicy` / `BehaviorLayer`
- 所有相关测试

保留的组件（移到新包或复用）：
- `ConversationCompletionHook`：保留，作为 Signal Source 的事件来源
- `ConversationCompletedEvent`：保留，Signal Source 订阅它
- 数据库表 `proactive_*`：保留但不再写入，历史数据可查询

### 16.2 新增范围

新包 `com.lifepilot.agent.initiative`：
```
initiative/
├── InitiativeEngine.java          // 主入口，协调所有组件
├── model/
│   ├── Thought.java
│   ├── ThoughtState.java
│   ├── ThoughtKind.java
│   ├── Evidence.java
│   ├── Signal.java                // sealed interface
│   └── ExpressUrgency.java
├── signal/
│   ├── SignalCollector.java        // 信号收集器接口
│   ├── ConversationEndSignal.java  // 订阅对话结束
│   ├── TimeElapsedSignal.java      // 定时检查到期
│   ├── MemoryChangeSignal.java     // 记忆变化
│   ├── IdleDetector.java           // 空闲检测
│   └── UserReturnDetector.java     // 用户回归检测
├── thinker/
│   ├── Thinker.java                // 思考器主类
│   ├── EventResponseThinker.java   // 事件响应模式
│   └── IdleThinker.java            // 空闲思考模式
├── pool/
│   ├── ThoughtPool.java            // 想法池
│   ├── ThoughtRepository.java      // 持久化
│   └── IntentDeduplicator.java     // 意图去重
├── gate/
│   ├── Gatekeeper.java             // 表达门控
│   ├── GatekeeperContext.java
│   └── GatekeeperDecision.java
├── express/
│   ├── ConversationInitiator.java  // 对话发起器
│   ├── OpeningGenerator.java       // 开场白生成
│   └── ChannelRouter.java          // 渠道路由
├── learn/
│   ├── OutcomeCollector.java       // 结果收集
│   ├── OutcomeRepository.java      // 结果持久化
│   └── PreferenceLearner.java      // 偏好学习
└── config/
    ├── InitiativeProperties.java   // 配置属性
    └── InitiativeAutoConfiguration.java
```

### 16.3 数据库迁移

新增 Flyway 迁移脚本：
- `V{next}__create_initiative_thoughts.sql`
- `V{next}__create_initiative_outcomes.sql`

当前只持久化想法与对话结果。

---

## 17. 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 核心抽象 | Thought（想法） | 比"候选提醒"更自然，有生命周期，有成熟度 |
| 触发方式 | 事件驱动 + 空闲思考 | 消除无意义的定时巡检，在正确的时刻响应 |
| 输出形式 | 发起对话 | 对话让用户可以自然回应，符合轻量个人助手定位 |
| 去重粒度 | intentKey（意图级） | 从根本上解决重复打扰 |
| 门控方式 | 纯规则硬约束 | 简单可预测，不依赖 LLM 判断时机 |
| 学习方式 | 对话结果 → 偏好衰减 | 比 LinUCB 简单得多，冷启动友好 |
| 渠道投递 | 复用正常 Agent 会话链路 | 主动层不维护独立渠道/fallback 分支 |
| 与 Agent 引擎关系 | 主动对话 = 正常 AgentRequest | 复用全部 Agent 能力 |
| 旧系统处理 | 完全删除 | 不兼容，不迁移，干净重来 |
| 空闲思考 | 独立 LLM 场景 + token 预算 | 可配置为便宜模型，控制成本 |
| 证据关联 | Evidence 精确指向记忆实体/对话 | Agent 可回溯原始信息，回答用户追问 |

---

## 18. 与旧系统的对比

| 维度 | 旧系统 (ProactiveEngine) | 新系统 (InitiativeEngine) |
|------|--------------------------|---------------------------|
| 代码量 | ~40 个类 + 8 个插件 | ~25 个类，结构更简单 |
| LLM 调用 | 每次心跳可能多次（Gate 3 + 文案生成） | 空闲思考 + 开场白生成（可控） |
| 决策复杂度 | 三级管线 + LinUCB + 评分模型 | 成熟度模型 + 硬规则门控 |
| 用户感知 | 收到通知 → 点有用/没用 | 收到对话 → 自然回应 |
| 重复问题 | 候选级冷却（同一候选可被不同插件重复检测） | 意图级去重（同一件事只说一次） |
| 可调试性 | 复杂（三级管线 + 8 插件 + Bandit） | 简单（想法池可直接查看所有想法状态） |
| 执行能力 | 无（只能通知） | 无（当前只发起对话） |
| 证据追溯 | 弱（文案中提及但无法回溯） | 强（Evidence 精确指向记忆，Agent 可检索原始数据） |

---

## 19. 研究参考

| 来源 | 核心洞察 | 在本设计中的体现 |
|------|---------|----------------|
| Inner Thoughts (arXiv:2501.00383) | 主动 AI 应先形成内心想法，再寻找时机表达 | Thought 生命周期 + 成熟度模型 |
| Re-grounding Proactivity (ICML 2026) | 主动行为需要认知锚定：必须能回答"为什么确信用户需要这个" | Evidence 证据链 + 开场白必须说明原因 |
| JetBrains 田野研究 (arXiv:2601.10253) | 任务边界 52% engagement vs 任务中 62% dismissed | Gatekeeper 的 boundary-window 优先 |
| 14 天纵向研究 (arXiv:2509.24073) | 用户反感：rigidity / premature turn-taking / overpromising | 开场白留出空间 + 不强迫 + 简短 |
| Training Proactive Agents (arXiv:2511.02208) | 三维优化：productivity / proactivity / personalization | 对话后续能力 + 偏好学习 |
| Letta Sleep-time Compute | Agent 空闲时思考，预计算答案 | IdleThinker 空闲思考模式 |
| Meta Proactive Messaging (2025) | 严格约束：5 次互动后才允许主动，且只发一次 | intentKey 冷却 + 每日额度 |
| NeurIPS 2025 Missed User-Signals | 从用户主动提供的信息中学习何时该主动 | OutcomeCollector 从对话结果学习 |
| ContextAgent (NeurIPS 2025) | 预测主动服务的"必要性" | Thought.confidence + maturity 双维度 |
| ProVoice-Bench (arXiv:2604.15037) | 四维评估：时机 / 必要性 / 相关性 / 延续性 | Gatekeeper(时机) + Thinker(必要性+相关性) + 对话(延续性) |

---

## 20. 开放问题（待后续迭代）

1. **多用户支持**：当前设计假设单用户，多用户场景下 Thought Pool 需要按用户隔离
2. **想法之间的关联**：多个想法可能相关（如"明天有会议"和"需要准备材料"），是否合并表达？
3. **主动行为的 A2UI 支持**：开场白是否可以包含 UI 组件（如待办列表、日历卡片）？
4. **语音渠道**：如果未来接入语音，主动发起的对话如何处理？
5. **与工作流的边界**：复杂的主动行为（如每周复盘）是否应该走工作流而非 Initiative？
6. **后台执行边界**：如果未来重新引入非对话式后台执行，需要独立 spec 一次性设计授权、审计和撤销模型。

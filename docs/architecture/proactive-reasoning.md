# 主动推理引擎架构设计

> **文档性质**：深度架构设计文档（Developer-Facing）
> **目标读者**：核心开发者、架构评审者
> **模块归属**：`com.lifepilot.agent.proactive`
> **最后更新**：2026-03
> **从属关系**：本文档从 [ARCHITECTURE.md](../ARCHITECTURE.md) 拆分而来，聚焦主动推理引擎的完整设计。

---

## 目录

- [1. 设计哲学与原则](#1-设计哲学与原则)
- [2. 整体架构](#2-整体架构)
- [3. ProactiveReasoner — 两阶段推理管线](#3-proactivereasoner--两阶段推理管线)
- [4. SignalCollector — 信号采集](#4-signalcollector--信号采集)
- [5. RuleEngine — 规则引擎](#5-ruleengine--规则引擎)
- [6. FrequencyStateManager — 智能降频](#6-frequencystatemanager--智能降频)
- [7. NotificationDispatcher — 通知分发](#7-notificationdispatcher--通知分发)
- [8. ResponseTracker — 响应追踪](#8-responsetracker--响应追踪)
- [9. 通知通道体系](#9-通知通道体系)
- [10. 前沿研究与竞品分析](#10-前沿研究与竞品分析)
- [11. 配置参考](#11-配置参考)

---

## 1. 设计哲学与原则

### 1.1 核心命题：从被动响应到主动关怀

传统 AI 助手是**被动的**——用户提问，助手回答。这种模式有一个根本缺陷：用户必须记得去问。但人类的认知负荷是有限的，真正有价值的助手应该在用户忘记之前主动提醒。

```
被动模式（传统 AI 助手）：
  用户想起来 → 提问 → 助手回答
  问题：用户忘记提问 = 助手无用

主动模式（ZhiWei）：
  信号采集 → 规则过滤 → LLM 评估 → 智能通知
  优势：用户不需要记住任何事，助手主动关怀
```

但主动通知有一个致命风险：**通知疲劳（Notification Fatigue）**。如果助手频繁打扰用户，用户会关闭通知甚至卸载应用。

ZhiWei 的核心设计命题是：**在"主动关怀"和"避免打扰"之间找到精确的平衡点**。

### 1.2 前沿研究基础

#### 1.2.1 通知疲劳与智能抑制

[arxiv:2003.02097 — A Snooze-less User-Aware Notification System](https://ar5iv.labs.arxiv.org/html/2003.02097) 提出了一个关键框架：智能通知系统应基于事件严重性、用户偏好和时间表来发送、抑制或聚合通知，从而减少用户忽略或延后通知的需要。

ZhiWei 的映射：`RuleEngine` 实现了基于规则的快速过滤（免打扰时段、冷却期、类型开关），`FrequencyStateManager` 实现了基于用户反馈的自适应降频。

Content was rephrased for compliance with licensing restrictions.

#### 1.2.2 主动 AI 与预期计算

2025-2026 年 AI Agent 领域的一个重要趋势是从被动响应转向主动预期。[nodemerge.com](https://www.nodemerge.com/blog/ai-agent-predictions-2026) 指出，到 2026 年我们将看到高自主性系统的兴起——主动 AI 观察用户行为、预期需求，并在用户提问之前建议操作。

ZhiWei 的 `ProactiveReasoner` 正是这一趋势的实现：通过信号采集感知用户状态，通过规则引擎快速过滤，通过 LLM 评估决定是否值得打扰。

Content was rephrased for compliance with licensing restrictions.

#### 1.2.3 推拉模型转换

[liminary.io](https://liminary.io/blog/proactive-recall-vs-agentic-research) 提出了一个重要的范式转换：从拉取式检索（用户停下工作去搜索）到推送式交付（AI 在合适时机主动推送相关信息）。

ZhiWei 的主动推理引擎实现了这个转换：用户不需要主动查询待办截止日期或日程冲突，系统会在合适的时机主动推送。

Content was rephrased for compliance with licensing restrictions.

#### 1.2.4 告警疲劳的 AI 解决方案

[IBM — Alert Fatigue Reduction with AI Agents](https://www.ibm.com/think/insights/alert-fatigue-reduction-with-ai-agents) 指出，告警疲劳的核心问题不是数据量，而是数据质量和上下文。AI Agent 可以通过理解上下文来过滤低价值告警，只推送真正需要关注的信息。

ZhiWei 的两阶段架构正是这个思路：Stage 1（规则引擎）过滤明显不需要的通知，Stage 2（LLM 评估）基于用户上下文判断通知价值。

Content was rephrased for compliance with licensing restrictions.

### 1.3 四条核心设计原则

#### 原则 1：两阶段过滤，成本递增

Stage 1（规则引擎，< 10ms）过滤 90% 的无效信号，只有通过规则的候选才进入 Stage 2（LLM 评估，~500ms）。这确保了 LLM 调用成本可控。

#### 原则 2：渐进降频，即时恢复

降频是渐进的（NORMAL → REDUCED → MUTED，每次需要连续忽略 ≥ 3 次），恢复是即时的（用户响应 1 次即恢复 NORMAL）。这避免了"沉默螺旋"——用户一旦开始忽略，系统不会永远沉默。

#### 原则 3：每个类型独立状态

6 种通知类型（DEADLINE_REMINDER、SCHEDULE_REMINDER、HABIT_REMINDER、STREAK_AT_RISK、DAILY_SUMMARY、WEEKLY_REVIEW）各自维护独立的频率状态。用户忽略习惯提醒不影响日程提醒的频率。

#### 原则 4：信号采集容错

4 个信号源（时间、任务、习惯、行为）独立采集，任何一个失败不影响其他信号。这确保了即使某个数据源不可用，主动推理仍能基于可用信号工作。

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                    主动推理引擎架构                                    │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │              ProactiveReasoner（@Scheduled 定时触发）           │  │
│  │                                                               │  │
│  │  ┌─────────────────────────────────────────────────────────┐  │  │
│  │  │ Stage 1: 信号采集 + 规则过滤（< 10ms）                   │  │  │
│  │  │                                                         │  │  │
│  │  │  SignalCollector          RuleEngine                     │  │  │
│  │  │  ├── 时间信号             ├── 免打扰时段检查              │  │  │
│  │  │  ├── 任务信号             ├── 类型开关检查                │  │  │
│  │  │  ├── 习惯信号             ├── 冷却期检查                  │  │  │
│  │  │  └── 行为信号             └── 信号→紧急度映射             │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │                         │ 候选通知列表                         │  │
│  │  ┌─────────────────────────────────────────────────────────┐  │  │
│  │  │ Stage 2: LLM 智能评估（~500ms）                          │  │  │
│  │  │                                                         │  │  │
│  │  │  FrequencyStateManager → 频率状态过滤                    │  │  │
│  │  │  LlmRouter → 综合评估是否值得打扰                        │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │                         │ 最终通知列表                         │  │
│  │  ┌─────────────────────────────────────────────────────────┐  │  │
│  │  │ 通知分发 + 响应追踪                                      │  │  │
│  │  │                                                         │  │  │
│  │  │  NotificationDispatcher    ResponseTracker               │  │  │
│  │  │  ├── HIGH → 主动通道       ├── 关键词匹配                │  │  │
│  │  │  ├── MEDIUM → 主动通道     ├── 超时检测                  │  │  │
│  │  │  └── LOW → 被动队列        └── 更新频率状态              │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    通知通道层                                   │  │
│  │                                                               │  │
│  │  ┌──────────────────┐  ┌──────────────────────────────────┐  │  │
│  │  │ GatewayNotifica- │  │ PassiveNotificationQueue         │  │  │
│  │  │ tionChannel      │  │ （LOW 紧急度通知暂存）             │  │  │
│  │  │ （桥接 Channel-  │  │ → ContextAssembler drain         │  │  │
│  │  │  Adapter 广播）   │  │ → WebChannelAdapter SSE 广播     │  │  │
│  │  └───────┬──────────┘  └──────────────────────────────────┘  │  │
│  │          │                                                    │  │
│  │  ┌───────▼──────────┐  ┌──────────────────────────────────┐  │  │
│  │  │ WebChannelAdapter│  │ LogNotificationChannel           │  │  │
│  │  │ → SSE 通知推送    │  │ （兜底日志输出）                   │  │  │
│  │  │ → SseSession-    │  │                                  │  │  │
│  │  │   Manager 广播   │  │                                  │  │  │
│  │  └──────────────────┘  └──────────────────────────────────┘  │  │
│  │                                                               │  │
│  │  Web UI: GET /api/notifications/stream（SSE 持久通知端点）     │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. ProactiveReasoner — 两阶段推理管线

### 3.1 职责

`ProactiveReasoner` 是主动推理引擎的入口，通过 `@Scheduled` 定时触发（默认每 30 分钟），在 Virtual Thread 上执行两阶段推理管线。

### 3.2 执行流程

```
@Scheduled(fixedDelayString = "${lifepilot.agent.proactive.check-interval-ms:1800000}")
void reason():
  1. SignalCollector.collect() → SignalBundle（4 类信号）
  2. RuleEngine.evaluate(signals) → List<ProactiveCandidate>（候选通知）
  3. 对每个候选：
     a. FrequencyStateManager.shouldSend(type, urgency) → 频率过滤
     b. 紧急度路由：
        - HIGH → 模板渲染（PromptRegistry），跳过 LLM，直接构造通知
        - MEDIUM/LOW → LLM 评估（LlmRouter），综合判断是否值得打扰
     c. 模板渲染失败时降级为 candidate.reason() 作为通知内容
  4. NotificationDispatcher.dispatch(notifications) → 分发通知
  5. ResponseTracker.track(notifications) → 追踪用户响应
```

### 3.3 Virtual Thread 执行

推理管线在 Virtual Thread 上执行，不阻塞平台线程。这对于 Stage 2 的 LLM 调用尤为重要——LLM 调用可能耗时数秒，Virtual Thread 确保不会占用宝贵的平台线程资源。

---

## 4. SignalCollector — 信号采集

### 4.1 四类信号源

| 信号类型 | 数据来源 | 采集内容 | 示例 |
|---------|---------|---------|------|
| 时间信号 | 系统时钟 | 当前时间、星期几、距上次交互时长 | 周五 14:00，距上次交互 2 小时 |
| 任务信号 | TodoRepository | 即将到期待办（24h 内）、逾期待办 | 3 项待办明天截止 |
| 习惯信号 | HabitRepository | 今日未打卡习惯、连续打卡即将中断 | "晨跑"连续 15 天，今日未打卡 |
| 行为信号 | SessionManager | 用户活跃度、最近交互模式 | 最近 1 小时无交互 |

### 4.2 容错设计

每个信号源独立采集，使用独立的 try-catch 包裹。任何一个信号源异常不影响其他信号的采集：

```java
// 伪代码：独立 try-catch 确保容错
SignalBundle collect() {
    var timeSignals = safeCollect("时间信号", this::collectTimeSignals);
    var taskSignals = safeCollect("任务信号", this::collectTaskSignals);
    var habitSignals = safeCollect("习惯信号", this::collectHabitSignals);
    var behaviorSignals = safeCollect("行为信号", this::collectBehaviorSignals);
    return new SignalBundle(timeSignals, taskSignals, habitSignals, behaviorSignals);
}
```

---

## 5. RuleEngine — 规则引擎

### 5.1 四层过滤

```
信号输入
  │
  ├── 1. 免打扰时段检查（quiet hours）
  │     └── 当前时间在 quietHoursStart ~ quietHoursEnd 之间 → 跳过
  │
  ├── 2. 类型开关检查
  │     └── 该 NotificationType 被用户关闭 → 跳过
  │
  ├── 3. 冷却期检查
  │     └── 距上次同类型通知 < cooldownMinutes → 跳过
  │
  └── 4. 信号→紧急度映射
        ├── 待办逾期 → HIGH
        ├── 待办 24h 内截止 → MEDIUM
        ├── 连续打卡即将中断 → MEDIUM
        ├── 日常习惯提醒 → LOW
        └── 每日/每周总结 → LOW
```

### 5.2 紧急度定义

| 紧急度 | 含义 | 通知方式 | 频率状态影响 |
|--------|------|---------|------------|
| HIGH | 需要立即关注 | 主动推送（所有状态均发送） | MUTED 状态也发送 |
| MEDIUM | 值得关注但不紧急 | 主动推送（MUTED 状态不发送） | REDUCED 状态发送 |
| LOW | 信息性通知 | 被动队列（用户主动查看） | 仅 NORMAL 状态发送 |

---

## 6. FrequencyStateManager — 智能降频

### 6.1 三态状态机

```
NORMAL ──[连续忽略 ≥ threshold]──→ REDUCED ──[连续忽略 ≥ threshold]──→ MUTED
  ↑                                   ↑                                  │
  └──[用户确认 1 次]──────────────────┘──[用户确认 1 次]─────────────────┘
```

### 6.2 状态行为矩阵

| 状态 | 允许发送的紧急度 | 冷却期倍数 | 转换条件 |
|------|----------------|-----------|---------|
| NORMAL | HIGH + MEDIUM + LOW | ×1 | 连续忽略 ≥ 3 → REDUCED |
| REDUCED | HIGH + MEDIUM | ×3（可配置） | 连续忽略 ≥ 3 → MUTED |
| MUTED | HIGH only | ×∞（不主动发送） | 用户确认 → NORMAL |

### 6.3 持久化策略

频率状态通过 `ConcurrentHashMap` 缓存在内存中，同时持久化到 SQLite。应用重启后从 SQLite 恢复状态，确保降频记忆不丢失。

```
内存缓存（ConcurrentHashMap<NotificationType, FrequencyStateEntry>）
  │
  ├── 读取：优先内存，miss 时查 SQLite
  ├── 写入：先写内存，异步写 SQLite
  └── 启动：从 SQLite 加载到内存
```

### 6.4 设计决策：为什么不用数据库直接读写？

频率状态的读写频率很高（每次推理周期都要读取所有类型的状态），但数据量极小（6 个 NotificationType）。使用内存缓存 + 异步持久化是最优方案：

- 读取延迟：< 1μs（内存）vs ~1ms（SQLite）
- 写入可靠性：异步写入 SQLite，即使写入失败也不影响当前推理
- 重启恢复：从 SQLite 恢复，最多丢失最后一次未持久化的状态变更

---

## 7. NotificationDispatcher — 通知分发

### 7.1 路由策略

```java
void dispatch(ProactiveNotification notification) {
    switch (notification.urgency()) {
        case HIGH, MEDIUM -> activeChannel.send(notification);  // 主动推送
        case LOW           -> passiveQueue.enqueue(notification); // 被动队列
    }
}
```

### 7.2 通道选择

当前实现：
- 主动通道：`GatewayNotificationChannel`（桥接 NotificationChannel 与 ChannelAdapter，遍历所有已注册通道广播）
- 主动通道：`LogNotificationChannel`（日志输出，作为兜底通道）
- 被动通道：`PassiveNotificationQueue`（ConcurrentLinkedQueue 暂存，ContextAssembler 首次对话时 drain 注入上下文）

通知分发流程：
1. `NotificationDispatcher` 根据紧急度路由：HIGH/MEDIUM → 主动通道，LOW → 被动队列
2. `GatewayNotificationChannel` 将 `ProactiveNotification` 转换为 `GatewayResponse`（metadata 包含 notificationType、urgency、notificationId）
3. 遍历所有 `ChannelAdapter`（WebChannelAdapter、企微、钉钉等）调用 `sendResponse()`
4. `WebChannelAdapter.doSendResponse()` 检测 metadata 中的 notificationType，通过 `SseSessionManager.broadcastNotification()` 推送 SSE 事件
5. 被动队列中的 LOW 紧急度通知在 WebChannelAdapter 处理响应时也会 drain 并通过 SSE 广播

---

## 8. ResponseTracker — 响应追踪

### 8.1 职责

追踪用户对主动通知的响应，更新频率状态机。

### 8.2 响应判定

| 判定方式 | 条件 | 结果 |
|---------|------|------|
| 关键词匹配 | 用户消息包含通知类型的关键词 | 视为"已确认" |
| 超时检测 | 通知发送后 N 分钟内无相关响应 | 视为"已忽略" |

### 8.3 关键词匹配

每个 `NotificationType` 关联一组中文关键词：

| 类型 | 关键词 |
|------|--------|
| DEADLINE_REMINDER | 待办、截止、到期、deadline |
| SCHEDULE_REMINDER | 日程、会议、安排、schedule |
| HABIT_REMINDER | 习惯、打卡、habit |
| STREAK_AT_RISK | 连续、打卡、中断、streak |
| DAILY_SUMMARY | 总结、今天、daily |
| WEEKLY_REVIEW | 回顾、本周、weekly |

---

## 9. 通知通道体系

### 9.1 接口设计

```java
public interface NotificationChannel {
    String id();
    void send(ProactiveNotification notification);
}
```

### 9.2 当前实现

| 通道 | 类 | 说明 |
|------|---|------|
| Gateway 通道 | `GatewayNotificationChannel` | 桥接 NotificationChannel 与 ChannelAdapter，遍历所有通道广播，单通道失败不中断 |
| 日志通道 | `LogNotificationChannel` | 兜底通道，INFO 级别日志输出 |
| 被动队列 | `PassiveNotificationQueue` | ConcurrentLinkedQueue，ContextAssembler 首次对话时 `drainAll()` 注入上下文 |

### 9.3 Web UI SSE 持久通知端点

Web UI 通过 SSE 持久连接接收主动通知推送：

- 端点：`GET /api/notifications/stream`
- 超时：从 `ProactiveConfigProperties.notificationSseTimeoutMs` 读取（默认 30 分钟）
- streamId 命名：`notification-{uuid}` 前缀，与对话 SSE 连接区分
- 事件类型：`notification`（`SseEventType.NOTIFICATION`）
- 事件载荷：`NotificationSseEvent(id, type, urgency, content, timestamp)`

推送链路：
```
GatewayNotificationChannel
  → WebChannelAdapter.doSendResponse()
    → 检测 metadata.notificationType
      → SseSessionManager.broadcastNotification(NotificationSseEvent)
        → 广播到所有 notification- 前缀的 SseEmitter
```

### 9.4 ResponseTracker 反馈闭环集成

`ResponseTracker` 在以下两个集成点接收用户交互信号：

1. `ChatController`：在 `sendMessage()` 和 `sendMessageStream()` 中调用 `responseTracker.onUserInteraction(content)`
2. `DefaultMessageGateway`：在 `process()` 中对 `TextMessage` 类型调用 `responseTracker.onUserInteraction(normalizedContent)`

两个集成点通过幂等检查避免重复调用。异常捕获 WARN 日志，不影响消息处理主流程。

### 9.5 ContextAssembler 被动通知消费

`ContextAssembler` 通过构造函数注入 `@Nullable PassiveNotificationQueue`，在 `buildEnhancedUserPrompt()` 中 drain 被动队列：

- 调用 `passiveNotificationQueue.drainAll()` 获取所有待处理通知
- 格式化为「待处理提醒」上下文段，注入到用户画像之后、对话历史之前
- 空列表不添加上下文段，异常时 WARN 日志静默跳过

### 9.6 扩展点

`NotificationChannel` 是扩展点接口。新增通道只需实现接口并注册为 Spring Bean，`NotificationDispatcher` 自动收集所有 `NotificationChannel` Bean。

---

## 10. 前沿研究与竞品分析

### 10.1 主动推理能力对比

| 产品 | 主动推理 | 降频机制 | 信号源 | 通知通道 |
|------|---------|---------|--------|---------|
| Apple Siri Suggestions | ✅ 基于使用模式 | ❌ 无自适应 | 应用使用、位置、时间 | 系统通知 |
| Google Assistant Proactive | ✅ 基于日历+邮件 | ❌ 固定频率 | 日历、邮件、位置 | 系统通知 |
| Microsoft Cortana (已停) | ✅ 基于 Office 数据 | ❌ 无 | Office 365 数据 | 系统通知 |
| OpenClaw | ❌ 被动响应 | — | — | — |
| AstrBot | ⚠️ 被动模型 | ❌ 无 | 消息平台事件 | 消息平台 |
| ZhiWei | ✅ 两阶段推理 | ✅ 三态自适应 | 时间+任务+习惯+行为 | 可扩展通道 |

### 10.2 ZhiWei 的差异化优势

1. **两阶段架构**：规则引擎快速过滤 + LLM 深度评估，兼顾效率和智能
2. **三态自适应降频**：基于用户反馈自动调整频率，避免通知疲劳
3. **每类型独立状态**：用户对不同类型通知的偏好独立管理
4. **信号容错**：任何信号源故障不影响整体推理
5. **可扩展通道**：通过 `NotificationChannel` 接口支持任意通知通道

### 10.3 与 ROADMAP 中竞品分析的关联

ROADMAP §6（AstrBot 深度分析）指出 AstrBot 采用被动响应模型，不具备主动推理能力。ROADMAP §7（OpenClaw 深度分析）同样确认 OpenClaw 是纯被动架构。ZhiWei 的主动推理引擎是相对于这两个竞品的核心差异化能力。

---

## 11. 配置参考

```yaml
lifepilot:
  agent:
    proactive:
      # 推理检查间隔（毫秒）
      check-interval-ms: 1800000  # 30 分钟
      # 免打扰时段
      quiet-hours-start: "23:00"
      quiet-hours-end: "07:00"
      # 降频阈值（连续忽略次数）
      ignore-threshold: 3
      # REDUCED 状态冷却期倍数
      reduced-multiplier: 3
      # 各类型冷却期（分钟）— 按 NotificationType 独立配置
      cooldown-minutes-per-type:
        DEADLINE_REMINDER: 60
        SCHEDULE_REMINDER: 30
        HABIT_REMINDER: 120
        STREAK_AT_RISK: 240
        DAILY_SUMMARY: 1440
        WEEKLY_REVIEW: 10080
      # 每日总结触发小时（24h 制）
      daily-summary-hour: 21
      # 每周回顾触发星期（1=周一，7=周日）
      weekly-review-day: 7
      # 每周回顾触发小时（24h 制）
      weekly-review-hour: 10
      # SSE 通知连接超时（毫秒）
      notification-sse-timeout-ms: 1800000  # 30 分钟
      # 响应追踪超时（分钟）
      response-timeout-minutes: 30
      # 各类型开关
      enabled-types:
        deadline-reminder: true
        schedule-reminder: true
        habit-reminder: true
        streak-at-risk: true
        daily-summary: true
        weekly-review: true
```

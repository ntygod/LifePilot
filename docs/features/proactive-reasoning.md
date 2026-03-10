# 主动推理引擎 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.agent.proactive`
> **最后更新**：2026-03

## 1. 功能概述

主动推理引擎让知微从"被动应答"进化为"主动关怀"——在用户未发起对话时，自动感知待办截止、日程临近、习惯打卡等场景，生成个性化提醒推送给用户。引擎通过智能降频机制学习用户偏好，避免过度打扰。

架构采用插件化设计：信号源（`SignalSource`）和候选提供者（`CandidateProvider`）均为可扩展接口，内置 Skill 通过 `ProactiveSkillProvider` 自动贡献，第三方插件可通过实现接口扩展新的信号类型和通知类型。通知类型通过 `NotificationTypeRegistry` 动态注册，不再受枚举限制。

## 2. 核心特性

### 2.1 两阶段推理管线

推理过程分为两个阶段：Stage 1 由 PolicyEngine（策略引擎）遍历所有 CandidateProvider 收集候选并执行全局过滤（免打扰时段、类型开关、冷却期）；Stage 2 由 LLM 对候选进行精细评估，判断是否值得发送并生成个性化内容。HIGH 紧急度候选跳过 LLM 直接模板渲染，确保紧急通知零延迟。

### 2.2 插件化信号源与候选提供者

信号采集和候选评估均已插件化：

- **SignalSource 接口**：定义 `id()` 和 `collect()` 方法，返回泛化的 `Signal` 列表
- **CandidateProvider 接口**：定义 `id()` 和 `evaluate(SignalBundle)` 方法，返回候选列表
- 内置 Skill（Todo/Schedule/Habit）通过 `ProactiveSkillProvider` 接口自动贡献信号源和候选提供者
- DailySummary 和 WeeklyReview 作为独立 CandidateProvider Bean，仅依赖时间信号

### 2.3 动态通知类型注册

通知类型通过 `NotificationTypeRegistry` 管理，替代原有的硬编码枚举。预注册 6 种默认类型：

| typeId | 显示名称 | 默认冷却时间 |
|--------|---------|-------------|
| `deadline_reminder` | 待办截止提醒 | 60 分钟 |
| `schedule_reminder` | 日程开始提醒 | 30 分钟 |
| `habit_reminder` | 习惯打卡提醒 | 480 分钟 |
| `streak_at_risk` | 连续打卡风险 | 720 分钟 |
| `daily_summary` | 每日总结 | 1440 分钟 |
| `weekly_review` | 每周回顾 | 10080 分钟 |

第三方插件可通过 `NotificationTypeRegistry.register()` 注册自定义通知类型。

### 2.4 两种主动行为类型

通过 `InitiativeType` 枚举区分：

- **NOTIFICATION**：主动通知，按紧急度分发（HIGH/MEDIUM 广播，LOW 入队被动队列）
- **PASSIVE_HINT**：被动提示，直接入队被动队列，等待用户下次交互时展示

### 2.5 三态智能降频

每个通知类型独立维护频率状态，支持 `typeId + subjectId` 复合粒度（如同一类型的不同待办独立控制）：

- NORMAL：所有紧急度均发送
- REDUCED：连续忽略达到阈值后进入，仅发送 HIGH/MEDIUM，冷却期延长
- MUTED：继续忽略后进入静默，仅发送 HIGH 紧急度

用户一旦确认（回复相关消息），立即恢复到 NORMAL 状态。降频是渐进的，恢复是即时的。

### 2.6 多通道通知分发

通知根据 InitiativeType 和紧急程度路由到不同通道：

- NOTIFICATION + HIGH/MEDIUM：遍历所有已注册通道广播（日志通道 + Gateway 通道）
- NOTIFICATION + LOW：入队被动通知队列
- PASSIVE_HINT：直接入队被动通知队列

Gateway 通道桥接 ChannelAdapter，可将通知推送到企微、钉钉、飞书等外部渠道。

### 2.7 免打扰时段

支持配置免打扰时段（默认 22:00 ~ 08:00），时段内所有推理周期自动跳过。支持跨午夜时段配置。

### 2.8 用户响应追踪

通过 `NotificationTypeRegistry` 查询各通知类型关联的关键词列表进行消息匹配。超过响应窗口未回复的通知标记为忽略，触发降频。

## 3. 使用场景

用户设置了一个明天下午 2 点截止的待办"提交季度报告"。当天上午 10 点，主动推理引擎在定时推理周期中：TodoSignalSource 收集到该信号 → TodoCandidateProvider 评估为 `deadline_reminder` 候选（MEDIUM 紧急度）→ PolicyEngine 通过类型开关和冷却期检查 → LLM 评估后生成个性化提醒："季度报告还有 4 小时截止，建议现在开始整理数据。"→ NotificationDispatcher 通过 Gateway 推送到用户的企微。

用户收到后回复"好的，马上处理"，ResponseTracker 通过 NotificationTypeRegistry 查询 `deadline_reminder` 的关键词匹配成功，将该类型频率状态恢复为 NORMAL。

如果用户连续 3 次忽略习惯打卡提醒，`habit_reminder` 类型自动降频到 REDUCED，之后只在 MEDIUM 以上紧急度时才发送（如 `streak_at_risk`）。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.agent.proactive.enabled` | `true` | 是否启用主动推理 |
| `lifepilot.agent.proactive.interval-ms` | `1800000` | 推理周期间隔（毫秒） |
| `lifepilot.agent.proactive.quiet-hours-start` | `22` | 免打扰开始小时（0-23） |
| `lifepilot.agent.proactive.quiet-hours-end` | `8` | 免打扰结束小时（0-23） |
| `lifepilot.agent.proactive.cooldown-minutes-per-type.*` | 按类型 | 各通知类型独立冷却时间（String typeId 作为键） |
| `lifepilot.agent.proactive.type-enabled.*` | `true` | 各通知类型独立启用开关（String typeId 作为键） |
| `lifepilot.agent.proactive.daily-summary-hour` | `21` | 每日总结触发小时 |
| `lifepilot.agent.proactive.weekly-review-day` | `7` | 每周回顾触发星期（1=周一，7=周日） |
| `lifepilot.agent.proactive.weekly-review-hour` | `10` | 每周回顾触发小时 |
| `lifepilot.agent.proactive.response-window-minutes` | `30` | 用户响应窗口（分钟） |
| `lifepilot.agent.proactive.ignore-threshold` | `3` | 连续忽略降频阈值 |
| `lifepilot.agent.proactive.reduced-multiplier` | `3` | REDUCED 状态冷却期倍数 |
| `lifepilot.agent.proactive.max-content-length` | `100` | 通知内容最大字符数 |

## 5. 限制与未来方向

当前限制：
- 关键词匹配判断用户响应相关性，准确率有限，未来可引入语义匹配
- 被动通知队列为内存队列，重启后丢失，未来可持久化
- CandidateProvider 的评估逻辑目前为确定性规则，未来可支持基于历史数据的概率评估

未来方向：
- 基于用户行为模式学习最佳推送时间
- 支持用户自定义规则（如"每天 9 点提醒我查看邮件"）
- 引入 Idle-Driven 触发机制，在用户空闲时主动推送而非固定间隔
- 第三方插件通过 SignalSource + CandidateProvider + NotificationTypeRegistry 扩展新的通知场景（如外部数据源同步事件）

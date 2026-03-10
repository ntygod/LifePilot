# 主动推理引擎 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.agent.proactive`
> **最后更新**：2026-03

## 1. 功能概述

主动推理引擎让知微从"被动应答"进化为"主动关怀"——在用户未发起对话时，自动感知待办截止、日程临近、习惯打卡等场景，生成个性化提醒推送给用户。引擎通过智能降频机制学习用户偏好，避免过度打扰。

## 2. 核心特性

### 2.1 两阶段推理管线

推理过程分为两个阶段：Stage 1 由规则引擎进行确定性过滤（执行时间 < 10ms），快速筛选出候选提醒；Stage 2 由 LLM 对候选进行精细评估，判断是否值得发送并生成个性化内容。HIGH 紧急度候选跳过 LLM 直接模板渲染，确保紧急通知零延迟。

### 2.2 六种通知类型

| 类型 | 触发条件 | 紧急度 |
|------|---------|--------|
| 待办截止提醒 | 24 小时内到期的 PENDING/IN_PROGRESS 待办 | ≤2h HIGH / ≤12h MEDIUM / 其他 LOW |
| 日程开始提醒 | 30 分钟内开始的日程 | HIGH |
| 习惯打卡提醒 | 今日未打卡的习惯 | LOW |
| 连续打卡风险 | 有连续打卡记录但今日未打卡 | MEDIUM |
| 每日总结 | 每天指定时间触发 | LOW |
| 每周回顾 | 每周指定日期和时间触发 | LOW |

### 2.3 三态智能降频

每个通知类型独立维护频率状态，根据用户响应行为自动调整：

- NORMAL：所有紧急度均发送
- REDUCED：连续忽略达到阈值后进入，仅发送 HIGH/MEDIUM，冷却期延长
- MUTED：继续忽略后进入静默，仅发送 HIGH 紧急度

用户一旦确认（回复相关消息），立即恢复到 NORMAL 状态。降频是渐进的，恢复是即时的。

### 2.4 多通道通知分发

通知根据紧急程度路由到不同通道：

- HIGH/MEDIUM：遍历所有已注册通道广播（日志通道 + Gateway 通道）
- LOW：入队被动通知队列，等待用户下次交互时附带展示

Gateway 通道桥接 ChannelAdapter，可将通知推送到企微、钉钉、飞书等外部渠道。

### 2.5 免打扰时段

支持配置免打扰时段（默认 22:00 ~ 08:00），时段内所有推理周期自动跳过。支持跨午夜时段配置。

### 2.6 用户响应追踪

通过关键词匹配判断用户消息是否与已发送通知相关。每种通知类型关联一组中文/英文关键词（如待办截止关联"待办""截止""到期""deadline"）。超过响应窗口未回复的通知标记为忽略，触发降频。

## 3. 使用场景

用户设置了一个明天下午 2 点截止的待办"提交季度报告"。当天上午 10 点，主动推理引擎在定时推理周期中收集到该信号，规则引擎判定距截止 4 小时，紧急度为 MEDIUM。LLM 评估后生成个性化提醒："季度报告还有 4 小时截止，建议现在开始整理数据。"通知通过 Gateway 推送到用户的企微。

用户收到后回复"好的，马上处理"，ResponseTracker 检测到关键词"待办"匹配，将该类型频率状态恢复为 NORMAL。

如果用户连续 3 次忽略习惯打卡提醒，该类型自动降频到 REDUCED，之后只在 MEDIUM 以上紧急度时才发送（如连续打卡风险）。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.agent.proactive.enabled` | `true` | 是否启用主动推理 |
| `lifepilot.agent.proactive.interval-ms` | `1800000` | 推理周期间隔（毫秒） |
| `lifepilot.agent.proactive.quiet-hours-start` | `22` | 免打扰开始小时（0-23） |
| `lifepilot.agent.proactive.quiet-hours-end` | `8` | 免打扰结束小时（0-23） |
| `lifepilot.agent.proactive.cooldown-minutes-per-type.*` | 按类型 | 各通知类型独立冷却时间（分钟） |
| `lifepilot.agent.proactive.daily-summary-hour` | `21` | 每日总结触发小时 |
| `lifepilot.agent.proactive.weekly-review-day` | `7` | 每周回顾触发星期（1=周一，7=周日） |
| `lifepilot.agent.proactive.weekly-review-hour` | `10` | 每周回顾触发小时 |
| `lifepilot.agent.proactive.response-window-minutes` | `30` | 用户响应窗口（分钟） |
| `lifepilot.agent.proactive.ignore-threshold` | `3` | 连续忽略降频阈值 |
| `lifepilot.agent.proactive.reduced-multiplier` | `3` | REDUCED 状态冷却期倍数 |
| `lifepilot.agent.proactive.max-content-length` | `100` | 通知内容最大字符数 |
| `lifepilot.agent.proactive.type-enabled.*` | `true` | 各通知类型独立启用开关 |

## 5. 限制与未来方向

当前限制：
- 关键词匹配判断用户响应相关性，准确率有限，未来可引入语义匹配
- 信号源仅覆盖内置技能（待办/日程/习惯），未来可扩展到外部数据源同步的事件
- 被动通知队列为内存队列，重启后丢失，未来可持久化

未来方向：
- 基于用户行为模式学习最佳推送时间
- 支持用户自定义规则（如"每天 9 点提醒我查看邮件"）
- 引入 Idle-Driven 触发机制，在用户空闲时主动推送而非固定间隔

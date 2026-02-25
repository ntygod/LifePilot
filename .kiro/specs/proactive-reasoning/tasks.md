# Implementation Plan: proactive-reasoning

## Overview

基于已完成的 Agent 引擎、Skill 系统（含内置技能）、LLM Router 和记忆系统，实现主动推理引擎。实现顺序：配置属性 → 枚举与数据模型 → Flyway 迁移 → FrequencyState 状态机 → FrequencyStateManager → SignalCollector → RuleEngine → 通知通道 → NotificationDispatcher → ResponseTracker → ProactiveReasoner → AutoConfiguration → 集成验证。

## Tasks

- [ ] 1. 配置属性与 application.yml
  - [ ] 1.1 实现 ProactiveConfigProperties 配置属性类
    - 使用 `@ConfigurationProperties(prefix = "lifepilot.agent.proactive")` 注解
    - 包含字段：enabled、intervalMs、quietHoursStart、quietHoursEnd、cooldownMinutes、responseWindowMinutes、ignoreThreshold、reducedMultiplier、maxContentLength、typeEnabled（EnumMap）
    - 默认值与 design 文档一致
    - 类级别 Javadoc 包含 @author zsg 和 @since 2026-03-15
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9_

  - [ ] 1.2 在 application.yml 中声明所有主动推理配置项及默认值
    - 配置前缀 `lifepilot.agent.proactive`，键名使用 kebab-case
    - _Requirements: 8.10_

- [ ] 2. 枚举与数据模型
  - [ ] 2.1 实现 NotificationType 枚举
    - 包含 DEADLINE_REMINDER、SCHEDULE_REMINDER、HABIT_REMINDER、STREAK_AT_RISK、DAILY_SUMMARY、WEEKLY_REVIEW
    - 每个枚举值关联中文关键词集合（用于 ResponseTracker 相关性判断）
    - _Requirements: 6.4_

  - [ ] 2.2 实现 Urgency 枚举
    - 包含 HIGH、MEDIUM、LOW
    - _Requirements: 2.4, 2.5, 2.6_

  - [ ] 2.3 实现 FrequencyState 枚举（含状态机逻辑）
    - 包含 NORMAL、REDUCED、MUTED
    - 实现 onIgnored(int consecutiveIgnoreCount, int threshold) 状态转换方法
    - 实现 onAcknowledged() 即时恢复方法
    - 实现 shouldSend(Urgency urgency) 紧急度过滤方法
    - 实现 intervalMultiplier(int reducedMultiplier) 冷却期倍数方法
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_

  - [ ]* 2.4 编写 FrequencyState 属性测试 — 忽略降频转换
    - **Property 9: FrequencyState 忽略降频转换**
    - **Validates: Requirements 4.2, 4.3**

  - [ ]* 2.5 编写 FrequencyState 属性测试 — 确认即时恢复
    - **Property 10: FrequencyState 确认即时恢复**
    - **Validates: Requirements 4.4**

  - [ ]* 2.6 编写 FrequencyState 属性测试 — 紧急程度过滤
    - **Property 11: FrequencyState 紧急程度过滤**
    - **Validates: Requirements 4.5, 4.6, 4.7**

  - [ ] 2.7 实现 SignalBundle record
    - 包含时间信号、任务信号、日程信号、习惯信号、连续打卡风险信号、行为信号
    - 使用 @Builder(toBuilder = true)，所有集合字段使用 List.copyOf()
    - _Requirements: 1.8_

  - [ ] 2.8 实现 ProactiveCandidate、ProactiveNotification、FrequencyStateEntry、TrackingEntry record
    - ProactiveCandidate：type、urgency、reason
    - ProactiveNotification：id、type、urgency、content、channel、responseStatus、sentAt
    - FrequencyStateEntry：state、consecutiveIgnoreCount、lastNotifiedAt，含 initial() 工厂方法
    - TrackingEntry：type、sentAt
    - _Requirements: 2.11, 4.9, 9.1, 9.2_

- [ ] 3. Checkpoint — 确认配置和数据模型编译通过
  - 确保 ProactiveConfigProperties、所有枚举和 record 编译通过，getDiagnostics 无错误，ask the user if questions arise.

- [ ] 4. 数据库迁移与 FrequencyStateManager
  - [ ] 4.1 创建 Flyway 迁移脚本 V11__create_proactive_reasoning_tables.sql
    - 创建 frequency_states 表：notification_type (TEXT PK)、state、consecutive_ignore_count、last_notified_at、created_at、updated_at
    - 创建 proactive_notifications 表：id (TEXT PK)、notification_type、urgency、content、channel、response_status、sent_at、responded_at、created_at
    - 创建索引 idx_proactive_notifications_type 和 idx_proactive_notifications_sent
    - _Requirements: 9.1, 9.2_

  - [ ] 4.2 实现 FrequencyStateManager
    - 构造函数注入 JdbcTemplate 和 ProactiveConfigProperties
    - ConcurrentHashMap 运行时缓存 + SQLite 持久化
    - loadPersistedStates()：启动时从 frequency_states 表加载状态到 ConcurrentHashMap
    - getState()：获取指定类型的频率状态，不存在时返回 NORMAL
    - shouldSend()：结合 FrequencyState.shouldSend() 判断是否允许发送
    - isInCooldown()：判断指定类型是否在冷却期内（考虑 REDUCED 状态的倍数）
    - recordIgnored()：递增连续忽略计数，触发状态转换，持久化
    - recordAcknowledged()：重置为 NORMAL，清零忽略计数，持久化
    - 持久化失败时 WARN 日志，内存状态仍有效
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 9.3, 9.4_

  - [ ]* 4.3 编写 FrequencyStateManager 属性测试 — 持久化往返
    - **Property 13: 频率状态持久化往返**
    - **Validates: Requirements 4.9, 9.3, 9.4**

- [ ] 5. SignalCollector
  - [ ] 5.1 实现 SignalCollector
    - 构造函数注入 TodoRepository、ScheduleRepository、HabitRepository、EpisodicMemory、ProactiveConfigProperties
    - collect() 方法收集所有信号，返回不可变 SignalBundle
    - 时间信号：当前时间、星期几、距上次交互时长
    - 任务信号：查询 PENDING/IN_PROGRESS 状态且 24 小时内到期的待办
    - 日程信号：查询 2 小时内开始的日程
    - 习惯信号：查询今天未打卡的习惯
    - 连续打卡风险：current_streak > 0 且今天未打卡
    - 行为信号：最近 24 小时对话数量
    - 每个数据源独立 try-catch，失败返回空列表
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8_

  - [ ]* 5.2 编写 SignalCollector 属性测试 — 待办过滤
    - **Property 1: SignalCollector 正确过滤待办信号**
    - **Validates: Requirements 1.2**

  - [ ]* 5.3 编写 SignalCollector 属性测试 — 日程过滤
    - **Property 2: SignalCollector 正确过滤日程信号**
    - **Validates: Requirements 1.3**

  - [ ]* 5.4 编写 SignalCollector 属性测试 — 习惯和连续打卡风险过滤
    - **Property 3: SignalCollector 正确过滤习惯和连续打卡风险信号**
    - **Validates: Requirements 1.4, 1.5**

- [ ] 6. RuleEngine
  - [ ] 6.1 实现 RuleEngine
    - 构造函数注入 FrequencyStateManager 和 ProactiveConfigProperties
    - evaluate(SignalBundle signals) 方法执行过滤规则链：
      1. 免打扰时段检查（quietHoursStart ~ quietHoursEnd）→ 全部过滤
      2. 遍历信号生成候选：待办 → 紧急程度映射（≤2h HIGH, 2-12h MEDIUM, 12-24h LOW）
      3. 日程 → ≤30min HIGH
      4. 习惯未打卡 → LOW，连续打卡风险 → MEDIUM
      5. 逐候选过滤：类型禁用 → 排除，冷却期内 → 排除
    - 返回不可变 List<ProactiveCandidate>
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11_

  - [ ]* 6.2 编写 RuleEngine 属性测试 — 免打扰时段全局过滤
    - **Property 4: RuleEngine 免打扰时段全局过滤**
    - **Validates: Requirements 2.1**

  - [ ]* 6.3 编写 RuleEngine 属性测试 — 排除已禁用类型和冷却期内类型
    - **Property 5: RuleEngine 排除已禁用类型和冷却期内类型**
    - **Validates: Requirements 2.2, 2.3**

  - [ ]* 6.4 编写 RuleEngine 属性测试 — 待办紧急程度映射
    - **Property 6: RuleEngine 待办紧急程度映射**
    - **Validates: Requirements 2.4, 2.5, 2.6**

  - [ ]* 6.5 编写 RuleEngine 属性测试 — 日程紧急程度映射
    - **Property 7: RuleEngine 日程紧急程度映射**
    - **Validates: Requirements 2.7**

  - [ ]* 6.6 编写 RuleEngine 属性测试 — 习惯紧急程度映射
    - **Property 8: RuleEngine 习惯紧急程度映射**
    - **Validates: Requirements 2.8, 2.9**

- [ ] 7. Checkpoint — 确认 SignalCollector 和 RuleEngine 编译通过
  - 确保 SignalCollector、RuleEngine 编译通过，单元测试通过，ask the user if questions arise.

- [ ] 8. 通知通道与 NotificationDispatcher
  - [ ] 8.1 实现 NotificationChannel 接口
    - 定义 id() 和 send(ProactiveNotification) 方法
    - 放置在 channel/ 子包
    - _Requirements: 5.5_

  - [ ] 8.2 实现 LogNotificationChannel
    - 实现 NotificationChannel 接口，id() 返回 "log"
    - send() 方法以 INFO 级别日志输出通知内容（类型、紧急程度、内容）
    - 占位实现，未来替换为系统托盘/Web UI 通道
    - _Requirements: 5.1, 5.2, 5.4_

  - [ ] 8.3 实现 PassiveNotificationQueue
    - 使用 ConcurrentLinkedQueue 存储 LOW 紧急度通知
    - 提供 enqueue() 和 drainAll() 方法
    - drainAll() 返回并清空队列中所有通知
    - _Requirements: 5.3_

  - [ ] 8.4 实现 NotificationDispatcher
    - 构造函数注入 NotificationChannel（logChannel）、PassiveNotificationQueue、JdbcTemplate
    - dispatch()：HIGH/MEDIUM → logChannel.send()，LOW → passiveQueue.enqueue()
    - 同时持久化到 proactive_notifications 表
    - 持久化失败 WARN 日志，不阻塞通知发送
    - INFO 级别日志记录每次分发（类型、紧急程度、通道）
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 9.5_

  - [ ]* 8.5 编写 NotificationDispatcher 属性测试 — 通知通道路由
    - **Property 12: 通知通道路由**
    - **Validates: Requirements 5.1, 5.2, 5.3**

  - [ ]* 8.6 编写 NotificationDispatcher 属性测试 — 通知持久化往返
    - **Property 14: 通知记录持久化往返**
    - **Validates: Requirements 9.5**

- [ ] 9. ResponseTracker
  - [ ] 9.1 实现 ResponseTracker
    - 构造函数注入 FrequencyStateManager 和 ProactiveConfigProperties
    - ConcurrentHashMap 存储待追踪条目（NotificationType → TrackingEntry）
    - track(NotificationType type)：记录通知类型和发送时间
    - onUserInteraction(String userMessage)：遍历待追踪条目，检查关键词相关性
      - 相关 → FrequencyStateManager.recordAcknowledged()，移除条目
      - 无关 → 保留条目等待超时
    - cleanupExpired()：超过 responseWindowMinutes 的条目标记为忽略
      - 调用 FrequencyStateManager.recordIgnored()，移除条目
    - 关键词匹配：NotificationType 关联的中文关键词集合，message.contains() 判断
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [ ]* 9.2 编写 ResponseTracker 属性测试 — 关键词相关性判断
    - **Property 16: ResponseTracker 关键词相关性判断**
    - **Validates: Requirements 6.4**

  - [ ]* 9.3 编写 ResponseTracker 属性测试 — 响应结果判定
    - **Property 17: ResponseTracker 响应结果判定**
    - **Validates: Requirements 6.2, 6.3, 6.6**

- [ ] 10. Checkpoint — 确认通知和追踪模块编译通过
  - 确保 NotificationChannel、LogNotificationChannel、PassiveNotificationQueue、NotificationDispatcher、ResponseTracker 编译通过，单元测试通过，ask the user if questions arise.

- [ ] 11. ProactiveReasoner 与 AutoConfiguration
  - [ ] 11.1 实现 ProactiveReasoner
    - 构造函数注入 SignalCollector、RuleEngine、FrequencyStateManager、NotificationDispatcher、ResponseTracker、LlmRouter、ProactiveConfigProperties
    - reason() 方法编排两阶段管线：
      1. SignalCollector.collect() 收集信号
      2. RuleEngine.evaluate() 生成候选列表
      3. 逐候选：FrequencyStateManager.shouldSend() 频率控制
      4. 通过频率控制的候选：LlmRouter.getChatClient("proactive-reasoning") 评估
      5. LLM 判定值得发送：构造 ProactiveNotification，内容截断至 maxContentLength
      6. NotificationDispatcher.dispatch() 分发通知
      7. ResponseTracker.track() 开始追踪
      8. ResponseTracker.cleanupExpired() 清理过期追踪
    - @Scheduled(fixedDelayString = "${lifepilot.agent.proactive.interval-ms:1800000}")
    - 使用 Virtual Thread 执行推理周期
    - LlmUnavailableException → WARN 日志，跳过当前候选
    - 任何未预期异常 → WARN 日志，不抛出（避免 @Scheduled 终止）
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 7.1, 7.2, 7.3, 7.4_

  - [ ]* 11.2 编写 ProactiveReasoner 属性测试 — LLM 内容截断
    - **Property 15: LLM 通知内容截断**
    - **Validates: Requirements 3.2**

  - [ ] 11.3 实现 ProactiveAutoConfiguration
    - 注册 @Bean：ProactiveConfigProperties、SignalCollector、RuleEngine、FrequencyStateManager、NotificationDispatcher、ResponseTracker、ProactiveReasoner、LogNotificationChannel、PassiveNotificationQueue
    - @ConditionalOnProperty(name = "lifepilot.agent.proactive.enabled", matchIfMissing = true)
    - 注入已有模块依赖：LlmRouter、TodoRepository、ScheduleRepository、HabitRepository、EpisodicMemory、JdbcTemplate
    - FrequencyStateManager Bean 初始化后调用 loadPersistedStates()
    - _Requirements: 10.1, 10.2, 10.3_

  - [ ] 11.4 在 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 中注册 ProactiveAutoConfiguration
    - _Requirements: 10.4_

- [ ] 12. 集成测试
  - [ ]* 12.1 编写 ProactiveReasoner 集成测试
    - 完整两阶段管线端到端：信号收集 → 规则过滤 → LLM 评估（Mock）→ 通知发送 → 响应追踪
    - Mock LlmRouter 避免真实 LLM 调用
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 7.1_

  - [ ]* 12.2 编写 FrequencyStateManager 持久化集成测试
    - SQLite 持久化往返：写入 → 重新加载 → 验证一致性
    - 使用 @SpringBootTest + 内存 SQLite
    - _Requirements: 4.9, 9.3, 9.4_

  - [ ]* 12.3 编写 ProactiveAutoConfiguration 集成测试
    - 验证所有 Bean 正确注册
    - 验证 @ConditionalOnProperty 条件（enabled=false 时不注册）
    - _Requirements: 10.1, 10.2, 10.3, 10.4_

- [ ] 13. Final checkpoint — 确保所有测试通过
  - 确保所有编译通过，所有单元测试和集成测试通过，ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 每个子任务完成后独立 git commit，遵循 `<type>(<scope>): <中文描述>` 格式
- 所有代码遵循编码规范：中文注释/Javadoc/日志/异常消息/测试方法名，英文类名/方法名/变量名
- 所有类级别 Javadoc 包含 @author zsg 和 @since 2026-03-15
- 所有服务通过 @Bean 注册在 ProactiveAutoConfiguration 中，不使用 @Service/@Component
- 属性测试使用 jqwik，每个属性测试标注对应的设计属性编号
- 每个 Correctness Property 对应一个独立的属性测试子任务
- 通知通道为占位实现（日志 + 被动队列），真实通道随 Gateway 模块实现
- FrequencyState 持久化使用 JdbcTemplate 直接操作，不引入 ORM

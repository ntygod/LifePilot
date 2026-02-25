# Requirements Document

## Introduction

主动推理引擎（ProactiveReasoner）是 LifePilot 区别于传统 AI 助手的核心差异化能力。传统助手是被动的——用户问什么答什么。主动推理引擎能够主动观察用户的生活模式，在合适的时机提供有价值的建议和提醒。

本模块实现两阶段推理管线：Stage 1 使用确定性规则引擎快速过滤（< 10ms），Stage 2 使用 LLM 精细评估（~500ms）。通过智能降频状态机（FrequencyStateMachine）避免过度打扰用户，并通过用户响应追踪形成反馈闭环。

参考文档：
- 架构设计：#[[file:docs/architecture/agent-engine.md]] §5
- 特性设计：#[[file:docs/features/proactive-reasoning.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## Glossary

- **ProactiveReasoner**: 主动推理引擎，负责定时触发两阶段推理管线，决定是否向用户发送主动提醒
- **SignalCollector**: 信号收集器，从时间、任务、习惯、行为等数据源收集主动推理所需的信号
- **SignalBundle**: 信号包，SignalCollector 收集的所有信号的不可变数据载体
- **RuleEngine**: 规则引擎，Stage 1 确定性过滤器，使用纯规则逻辑快速过滤不需要发送的提醒
- **ProactiveCandidate**: 候选提醒，通过 RuleEngine 过滤后进入 LLM 评估的提醒候选
- **FrequencyStateMachine**: 频率状态机，每个提醒类型独立维护的三态状态机（NORMAL / REDUCED / MUTED）
- **FrequencyStateManager**: 频率状态管理器，管理所有提醒类型的 FrequencyStateMachine 实例和冷却期
- **NotificationDispatcher**: 通知分发器，根据紧急程度选择通知通道并发送提醒
- **ResponseTracker**: 用户响应追踪器，追踪用户对主动提醒的响应行为并反馈给 FrequencyStateManager
- **NotificationType**: 提醒类型枚举，包括 DEADLINE_REMINDER、SCHEDULE_REMINDER、HABIT_REMINDER、STREAK_AT_RISK、DAILY_SUMMARY、WEEKLY_REVIEW
- **Urgency**: 紧急程度枚举，包括 HIGH、MEDIUM、LOW
- **FrequencyState**: 频率状态枚举，包括 NORMAL、REDUCED、MUTED
- **ProactiveConfigProperties**: 主动推理配置属性类，外部化所有业务可调参数
- **LlmRouter**: LLM 路由器，已完成模块，提供 getChatClient(String scene) 方法获取 ChatClient
- **TodoRepository**: 待办仓储，已完成模块，提供待办事项的查询能力
- **ScheduleRepository**: 日程仓储，已完成模块，提供日程的查询和冲突检测能力
- **HabitRepository**: 习惯仓储，已完成模块，提供习惯查询和连续打卡天数计算能力
- **EpisodicMemory**: 情景记忆，已完成模块，提供对话历史查询能力
- **QuietHours**: 免打扰时段，默认 22:00-08:00，在此时段内不发送任何提醒

## Requirements

### Requirement 1: 信号收集

**User Story:** As a LifePilot user, I want the system to automatically collect signals from my tasks, schedules, habits, and interaction history, so that the system can proactively identify situations that need my attention.

#### Acceptance Criteria

1. WHEN a proactive reasoning cycle is triggered, THE SignalCollector SHALL collect time signals including current time, day of week, and duration since last user interaction
2. WHEN a proactive reasoning cycle is triggered, THE SignalCollector SHALL collect task signals by querying TodoRepository for todos with status PENDING or IN_PROGRESS that have a due_date within the next 24 hours
3. WHEN a proactive reasoning cycle is triggered, THE SignalCollector SHALL collect schedule signals by querying ScheduleRepository for schedules with start_time within the next 2 hours
4. WHEN a proactive reasoning cycle is triggered, THE SignalCollector SHALL collect habit signals by querying HabitRepository for habits that have not been checked in today
5. WHEN a proactive reasoning cycle is triggered, THE SignalCollector SHALL collect streak risk signals by querying HabitRepository for habits where current_streak is greater than 0 and today's check-in is missing
6. WHEN a proactive reasoning cycle is triggered, THE SignalCollector SHALL collect behavior signals including user activity level derived from EpisodicMemory recent conversation count within the last 24 hours
7. THE SignalCollector SHALL complete all signal collection within 50 milliseconds
8. THE SignalCollector SHALL return a SignalBundle record containing all collected signals as an immutable data carrier

### Requirement 2: 规则引擎过滤（Stage 1）

**User Story:** As a LifePilot user, I want the system to use fast deterministic rules to filter out unnecessary notifications before invoking the LLM, so that system resources are conserved and I am not bothered unnecessarily.

#### Acceptance Criteria

1. WHILE the current time is within QuietHours (default 22:00-08:00), THE RuleEngine SHALL filter out all candidates and return an empty list
2. WHEN a signal produces a candidate of a NotificationType that the user has disabled, THE RuleEngine SHALL exclude that candidate from the result list
3. WHEN a signal produces a candidate of a NotificationType that is within its cooldown period (default minimum 2 hours since last notification of the same type), THE RuleEngine SHALL exclude that candidate from the result list
4. WHEN a TodoRepository signal indicates a todo with due_date within 2 hours, THE RuleEngine SHALL generate a ProactiveCandidate with urgency HIGH
5. WHEN a TodoRepository signal indicates a todo with due_date between 2 and 12 hours, THE RuleEngine SHALL generate a ProactiveCandidate with urgency MEDIUM
6. WHEN a TodoRepository signal indicates a todo with due_date between 12 and 24 hours, THE RuleEngine SHALL generate a ProactiveCandidate with urgency LOW
7. WHEN a ScheduleRepository signal indicates a schedule starting within 30 minutes, THE RuleEngine SHALL generate a ProactiveCandidate with urgency HIGH
8. WHEN a HabitRepository signal indicates a pending habit check-in, THE RuleEngine SHALL generate a ProactiveCandidate with urgency LOW
9. WHEN a HabitRepository signal indicates a streak at risk (current_streak > 0 and today not checked in), THE RuleEngine SHALL generate a ProactiveCandidate with urgency MEDIUM
10. THE RuleEngine SHALL complete all rule evaluation within 10 milliseconds
11. THE RuleEngine SHALL return an immutable list of ProactiveCandidate records

### Requirement 3: LLM 智能评估（Stage 2）

**User Story:** As a LifePilot user, I want the system to use LLM to evaluate whether a candidate notification is truly worth sending, so that I only receive relevant and actionable reminders.

#### Acceptance Criteria

1. WHEN a ProactiveCandidate passes Stage 1 filtering, THE ProactiveReasoner SHALL invoke LlmRouter.getChatClient with scene "proactive_reasoning" to evaluate the candidate
2. WHEN the LLM evaluation determines the candidate is worth sending, THE ProactiveReasoner SHALL use the LLM-generated content as the notification body (limited to 100 characters)
3. WHEN the LLM evaluation determines the candidate is not worth sending, THE ProactiveReasoner SHALL skip the candidate and log the skip reason at DEBUG level
4. IF the LLM call fails due to LlmUnavailableException, THEN THE ProactiveReasoner SHALL log the error at WARN level and skip the candidate without affecting other candidates or normal system functionality
5. THE ProactiveReasoner SHALL pass the candidate type, urgency, trigger reason, current time, time since last interaction, and user activity level to the LLM as evaluation context

### Requirement 4: 频率状态机

**User Story:** As a LifePilot user, I want the system to automatically reduce notification frequency when I consistently ignore a type of reminder, so that I am not repeatedly bothered by notifications I do not find useful.

#### Acceptance Criteria

1. THE FrequencyStateMachine SHALL maintain three states: NORMAL, REDUCED, and MUTED for each NotificationType independently
2. WHEN a user ignores a notification type 3 consecutive times while in NORMAL state, THE FrequencyStateMachine SHALL transition to REDUCED state for that type
3. WHEN a user ignores a notification type 3 consecutive times while in REDUCED state, THE FrequencyStateMachine SHALL transition to MUTED state for that type
4. WHEN a user acknowledges a notification of any type, THE FrequencyStateMachine SHALL immediately transition that type back to NORMAL state regardless of current state
5. WHILE in NORMAL state, THE FrequencyStateManager SHALL allow sending notifications of all urgency levels (HIGH, MEDIUM, LOW)
6. WHILE in REDUCED state, THE FrequencyStateManager SHALL allow sending notifications only with urgency HIGH or MEDIUM, and apply a 3x interval multiplier to the cooldown period
7. WHILE in MUTED state, THE FrequencyStateManager SHALL allow sending notifications only with urgency HIGH
8. THE FrequencyStateManager SHALL use ConcurrentHashMap for thread-safe runtime state storage
9. THE FrequencyStateManager SHALL persist frequency state entries to SQLite for recovery across restarts

### Requirement 5: 通知分发

**User Story:** As a LifePilot user, I want notifications to be delivered through appropriate channels based on their urgency, so that urgent matters get my immediate attention while less urgent ones wait for a natural interaction point.

#### Acceptance Criteria

1. WHEN a notification has urgency HIGH, THE NotificationDispatcher SHALL dispatch it to the log output channel (placeholder for future system tray integration via Gateway module)
2. WHEN a notification has urgency MEDIUM, THE NotificationDispatcher SHALL dispatch it to the log output channel (placeholder for future Web UI notification integration)
3. WHEN a notification has urgency LOW, THE NotificationDispatcher SHALL enqueue it as a passive notification to be mentioned during the next user interaction
4. THE NotificationDispatcher SHALL log every dispatched notification at INFO level including type, urgency, and channel
5. THE NotificationDispatcher SHALL define a NotificationChannel interface to allow future channel implementations (system tray, Web UI, messaging platforms) when Gateway module is completed

### Requirement 6: 用户响应追踪

**User Story:** As a LifePilot user, I want the system to track whether I respond to notifications, so that the frequency state machine can learn my preferences and adjust notification behavior accordingly.

#### Acceptance Criteria

1. WHEN a notification is sent, THE ResponseTracker SHALL record the notification type and sent timestamp in a pending tracking map
2. WHEN a user interacts with the system within 30 minutes of a notification and the interaction content is related to the notification type, THE ResponseTracker SHALL report the notification as acknowledged to FrequencyStateManager
3. WHEN 30 minutes elapse after a notification without a related user interaction, THE ResponseTracker SHALL report the notification as ignored to FrequencyStateManager
4. THE ResponseTracker SHALL determine relatedness by checking if the user message contains keywords associated with the notification type (e.g., "待办" or "截止" for DEADLINE_REMINDER)
5. THE ResponseTracker SHALL use ConcurrentHashMap for thread-safe tracking of pending notifications
6. WHEN a user interaction occurs, THE ResponseTracker SHALL evaluate all pending tracking entries and remove expired or matched entries

### Requirement 7: 定时触发与生命周期

**User Story:** As a LifePilot user, I want the proactive reasoning engine to run automatically at configurable intervals, so that I receive timely reminders without manual intervention.

#### Acceptance Criteria

1. THE ProactiveReasoner SHALL execute the two-stage reasoning pipeline at a configurable interval (default 30 minutes) using Spring @Scheduled
2. WHERE the user has set lifepilot.agent.proactive.enabled to false, THE ProactiveReasoner SHALL not execute any reasoning cycles
3. IF an exception occurs during a reasoning cycle, THEN THE ProactiveReasoner SHALL log the error at WARN level and continue scheduling future cycles without interruption
4. THE ProactiveReasoner SHALL execute the reasoning cycle on a Virtual Thread to avoid blocking the main scheduling thread

### Requirement 8: 配置外部化

**User Story:** As a LifePilot administrator, I want all business-tunable parameters of the proactive reasoning engine to be externalized to configuration, so that behavior can be adjusted without code changes.

#### Acceptance Criteria

1. THE ProactiveConfigProperties SHALL externalize the reasoning interval with configuration key lifepilot.agent.proactive.interval-ms and default value 1800000 (30 minutes)
2. THE ProactiveConfigProperties SHALL externalize quiet hours start with configuration key lifepilot.agent.proactive.quiet-hours-start and default value 22
3. THE ProactiveConfigProperties SHALL externalize quiet hours end with configuration key lifepilot.agent.proactive.quiet-hours-end and default value 8
4. THE ProactiveConfigProperties SHALL externalize the cooldown duration with configuration key lifepilot.agent.proactive.cooldown-minutes and default value 120
5. THE ProactiveConfigProperties SHALL externalize the response window duration with configuration key lifepilot.agent.proactive.response-window-minutes and default value 30
6. THE ProactiveConfigProperties SHALL externalize the enable/disable toggle with configuration key lifepilot.agent.proactive.enabled and default value true
7. THE ProactiveConfigProperties SHALL externalize the consecutive ignore threshold for frequency downgrade with configuration key lifepilot.agent.proactive.ignore-threshold and default value 3
8. THE ProactiveConfigProperties SHALL externalize the reduced state interval multiplier with configuration key lifepilot.agent.proactive.reduced-multiplier and default value 3
9. THE ProactiveConfigProperties SHALL be registered as a Spring Bean via @ConfigurationProperties prefix "lifepilot.agent.proactive"
10. THE application.yml SHALL declare all proactive reasoning configuration keys with their default values

### Requirement 9: 数据库持久化

**User Story:** As a LifePilot user, I want the frequency state and notification history to survive application restarts, so that the system remembers my notification preferences.

#### Acceptance Criteria

1. THE Flyway migration script SHALL create a frequency_states table with columns: notification_type (TEXT, PRIMARY KEY), state (TEXT), consecutive_ignore_count (INTEGER), last_notified_at (TEXT), created_at (TEXT), updated_at (TEXT)
2. THE Flyway migration script SHALL create a proactive_notifications table with columns: id (TEXT, PRIMARY KEY), notification_type (TEXT), urgency (TEXT), content (TEXT), channel (TEXT), response_status (TEXT), sent_at (TEXT), responded_at (TEXT), created_at (TEXT)
3. WHEN the application starts, THE FrequencyStateManager SHALL load persisted frequency states from the frequency_states table into the ConcurrentHashMap
4. WHEN a frequency state changes, THE FrequencyStateManager SHALL persist the updated state to the frequency_states table
5. WHEN a notification is sent, THE NotificationDispatcher SHALL persist the notification record to the proactive_notifications table

### Requirement 10: Spring AutoConfiguration 与 Bean 注册

**User Story:** As a developer, I want all proactive reasoning components to be registered as Spring Beans via AutoConfiguration, so that the module integrates cleanly with the LifePilot Spring Boot application.

#### Acceptance Criteria

1. THE ProactiveAutoConfiguration SHALL register ProactiveReasoner, SignalCollector, RuleEngine, FrequencyStateManager, NotificationDispatcher, and ResponseTracker as Spring Beans via @Bean methods
2. THE ProactiveAutoConfiguration SHALL be conditional on lifepilot.agent.proactive.enabled being true (default)
3. THE ProactiveAutoConfiguration SHALL inject dependencies from existing modules: LlmRouter, TodoRepository, ScheduleRepository, HabitRepository, EpisodicMemory, and JdbcTemplate
4. THE ProactiveAutoConfiguration SHALL be registered in META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

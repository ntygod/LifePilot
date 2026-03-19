# 工作流编排引擎 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.workflow`
> **最后更新**：2026-03

## 1. 功能概述

工作流模块让用户通过 YAML 文件定义自动化任务编排，将多个 Skill、Tool、LLM 调用组合为可复用的自动化流程。引擎负责解析 YAML 定义、按 DAG 依赖调度执行、管理实例状态、持久化执行进度，并在崩溃后自动恢复中断的实例。

## 2. 核心特性

### 2.1 YAML 声明式工作流定义

通过 YAML 文件描述工作流蓝图，包含步骤列表、触发器、输入参数和元数据。YAML 文件放置在 `~/.zhiwei/workflows` 目录下，引擎定时扫描并自动加载变更。

### 2.2 十一种步骤类型

| 步骤类型 | 说明 |
|---------|------|
| SkillStep | 调用已注册的 Skill |
| ToolStep | 调用已注册的 Tool |
| LlmStep | 调用 LLM 生成内容，支持结构化输出 |
| ConditionStep | 条件分支，根据表达式选择 then/else 路径 |
| LoopStep | 循环遍历集合，对每个元素执行 body 步骤 |
| ParallelStep | Virtual Thread 并行执行多个分支 |
| SubWorkflowStep | 调用子工作流，支持嵌套编排 |
| WaitStep | 等待指定时长，持久化 wakeUpAt 到数据库，由 WakeupScheduler 到时自动唤醒恢复执行 |
| ApprovalStep | 人工审批，暂停工作流等待决策；支持运行时超时检测，超时后根据配置自动批准或标记失败 |
| NoopStep | 空操作，直接跳过 |
| **NotifyStep** | **通过 NotificationService 发送通知，支持 TEXT/MARKDOWN/CARD 三种内容类型和紧急程度配置** |

### 2.3 三种触发方式

- Cron 定时触发：按 Cron 表达式周期性执行，同一工作流有 RUNNING 实例时自动跳过。触发器在工作流注册/启用/禁用/热加载时实时注册或注销，无需重启
- 事件触发：监听 Spring ApplicationEvent，事件发生时自动执行。触发器生命周期与 Cron 一致
- 手动触发：通过 `WorkflowCommandService.start()` 异步调用

### 2.4 DAG 依赖调度

步骤通过 `dependsOn` 声明前置依赖，引擎使用 Kahn 拓扑排序确定执行顺序。无依赖的步骤可并行执行。当所有步骤均无 `dependsOn` 时，退化为按列表顺序串行执行。自动检测环依赖并报错。

### 2.5 四种错误处理策略

每个步骤可独立配置错误处理策略：
- Retry：指数退避重试，耗尽后回退到 Fail
- Skip：跳过当前步骤，记录原因，继续执行后续步骤
- Fail：终止工作流，标记为 FAILED
- Compensate：执行补偿步骤后标记失败（Saga Pattern）

### 2.6 表达式引擎

步骤参数支持 `${variable.path}` 变量替换，从工作流上下文中解析嵌套路径。条件步骤支持比较运算符和逻辑运算符组合的条件表达式。

### 2.7 人工审批

ApprovalStep 暂停工作流等待审批决策。支持配置审批人列表、超时时间和超时自动批准。审批决策通过 `WorkflowEngine.approve()` 提交。WakeupScheduler 在运行时持续检测审批超时，超时后根据 `auto-approve-on-timeout` 配置自动批准或标记实例失败，不再仅依赖重启恢复。

### 2.8 NotifyStep 通知步骤

NotifyStep 是工作流内置的通知步骤，通过 NotificationService 发送通知。与旧的 `builtin.interact.notify` 工具相比，NotifyStep 更简洁且支持紧急程度路由。

```yaml
- id: send-notification
  name: 发送通知
  type: notify
  targetUserId: owner
  content: |
    工作流执行完成！
    结果：${steps.previous.output.result}
  contentType: MARKDOWN
  urgency: MEDIUM
```

**参数说明**：
- `targetUserId`：目标用户 ID，支持 `${}` 表达式
- `content`：通知内容模板，支持变量替换
- `contentType`：内容类型，可选 TEXT / MARKDOWN / CARD
- `urgency`：紧急程度，可选 HIGH / MEDIUM / LOW

### 2.8 异步非阻塞执行

WorkflowCommandService 作为外部调用的首选入口，`start()` 方法创建实例后立即返回 instanceId，工作流在 Virtual Thread 上由 WorkflowRunner 异步执行。调用方不会被工作流执行阻塞，可通过 `getStatus()` 查询执行进度。触发器、API、定时器均通过 CommandService 提交，不再直接调用 WorkflowEngine。

### 2.9 阻塞实例自动唤醒

WakeupScheduler 定时扫描数据库中 wakeUpAt 已过期的 WAITING/PAUSED 实例：
- WAITING 实例（WaitStep）：到达唤醒时间后自动恢复执行
- PAUSED 实例（ApprovalStep）：超时后根据配置自动批准或标记失败
- 扫描间隔通过 `lifepilot.workflow.wakeup.scan-interval-seconds` 配置，默认 10 秒

### 2.10 状态持久化与崩溃恢复

工作流实例状态、已完成步骤集合、变量上下文、阻塞信息（wakeUpAt/blockedStepId/blockedReason）全部持久化到 SQLite。应用重启后自动检测中断的实例（RUNNING/WAITING/PAUSED 状态），从断点恢复执行。

### 2.11 YAML 热加载

WorkflowRegistry 定时扫描工作流目录，自动检测新增、修改和删除的 YAML 文件。修改后的工作流定义自动更新注册，无需重启应用。

### 2.12 审计事件追踪

记录工作流执行全过程的审计事件（实例创建/状态变更、步骤开始/完成/失败/跳过、审批请求/决策），支持按实例查询事件时间线，过期事件自动清理。

## 3. 使用场景

### 入门场景
- **每日待办提醒**：Cron 定时触发早上 8 点执行，获取任务列表后筛选紧急任务，通过 NotifyStep 发送通知
- **周报自动生成**：Cron 每周五触发，并行调用记忆检索和任务统计获取数据，LLM 生成周报后保存到知识库

### 中级场景
- **定时知识采集**：每天早上从网络采集指定主题的最新信息，经过去重和摘要后自动保存到记忆
- **批量任务处理**：从任务队列循环获取待处理任务，根据任务类型路由到不同处理逻辑，支持错误重试

### 高级场景
- **多源数据分析**：并行从多个数据源获取数据，LLM 综合分析生成数据洞察报告
- **智能内容审核**：多维度 LLM 风险分析，高风险内容进入人工审批，低风险自动通过
- **调研助手**：多源搜索 + LLM 分析 + 人工审批 + 自动发布到知识库

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.workflow.enabled` | `true` | 工作流引擎总开关 |
| `lifepilot.workflow.definitions-dir` | `~/.zhiwei/workflows` | YAML 定义文件目录 |
| `lifepilot.workflow.default-step-timeout-seconds` | `300` | 步骤默认超时时间（秒） |
| `lifepilot.workflow.max-parallel-branches` | `10` | 最大并行分支数 |
| `lifepilot.workflow.max-nesting-depth` | `3` | 最大子工作流嵌套深度 |
| `lifepilot.workflow.max-loop-iterations` | `100` | 最大循环迭代次数 |
| `lifepilot.workflow.crash-recovery-enabled` | `true` | 崩溃恢复开关 |
| `lifepilot.workflow.scan-interval-seconds` | `30` | YAML 文件扫描间隔（秒） |
| `lifepilot.workflow.seed-builtin-workflows` | `true` | 启动时释放内置工作流模板 |
| `lifepilot.workflow.retry.initial-delay-ms` | `500` | 重试初始延迟（毫秒） |
| `lifepilot.workflow.retry.max-delay-ms` | `5000` | 重试最大延迟（毫秒） |
| `lifepilot.workflow.retry.max-attempts` | `3` | 最大重试次数 |
| `lifepilot.workflow.approval.default-timeout-seconds` | `86400` | 审批默认超时（24 小时） |
| `lifepilot.workflow.approval.auto-approve-on-timeout` | `false` | 超时后是否自动批准 |
| `lifepilot.workflow.wakeup.scan-interval-seconds` | `10` | WakeupScheduler 扫描间隔（秒） |
| `lifepilot.workflow.event-audit.enabled` | `true` | 审计事件记录开关 |
| `lifepilot.workflow.event-audit.retention-days` | `90` | 审计事件保留天数 |

## 5. 限制与未来方向

当前限制：
- 工作流定义仅支持 YAML 文件，不支持 Web UI 可视化编辑
- 并行步骤的分支间不支持数据共享，各分支独立执行
- 子工作流嵌套深度限制为 3 层
- 表达式引擎仅支持基础比较和逻辑运算，不支持函数调用

未来方向：
- Web UI 工作流可视化编辑器（拖拽式）
- 工作流模板市场，支持社区共享
- 更丰富的表达式函数（字符串处理、日期计算等）
- 工作流版本管理和回滚

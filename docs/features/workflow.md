# 工作流/自动化编排

> 本文档从 [FEATURES.md](../FEATURES.md) 拆分而来，对应 Phase 4 模块 15。

> 📋 工作流引擎 spec 已创建（Phase 4）。详细架构设计参见 [architecture/workflow.md](../architecture/workflow.md)。

## 1. 概述

ZhiWei 工作流引擎让你通过 YAML 文件定义多步骤自动化流程，无需编写任何代码。把 YAML 文件放到指定目录，引擎自动检测并注册——即写即用。

核心能力：
- 9 种步骤类型覆盖常见自动化场景
- 表达式引擎支持变量传递和条件判断
- 定时 / 事件 / 手动三种触发方式
- 崩溃恢复，长时间工作流不会因重启丢失
- 热加载，修改 YAML 无需重启应用

## 2. 快速开始

### 2.1 创建你的第一个工作流

在 `~/.lifepilot/workflows/` 目录下创建 YAML 文件：

```yaml
# ~/.lifepilot/workflows/daily-review.yml
id: daily-review
name: 每日回顾
description: 每天晚上自动生成当日任务回顾
version: "1.0"

triggers:
  - type: cron
    cron: "0 21 * * *"    # 每天 21:00

inputs:
  userId:
    type: string
    required: true

steps:
  - id: fetch-tasks
    name: 获取今日任务
    type: skill
    skillId: todo.list
    params:
      filter: "today"
      userId: "${inputs.userId}"

  - id: generate-review
    name: 生成回顾
    type: llm
    scene: chat
    prompt: |
      请根据以下任务列表生成简洁的每日回顾：
      ${steps.fetch-tasks.output.tasks}

  - id: notify
    name: 发送通知
    type: tool
    toolId: notification.send
    params:
      message: "${steps.generate-review.output.content}"
    errorStrategy:
      type: skip
      reason: "通知发送失败不影响主流程"
```

保存后 ZhiWei 会在 30 秒内自动检测并注册这个工作流。

## 3. 步骤类型

### 3.1 步骤类型一览

| 类型 | 用途 | 示例场景 |
|------|------|---------|
| skill | 调用已注册的 Skill | 查询待办、创建日程、打卡习惯 |
| tool | 调用已注册的工具 | 发送通知、HTTP 请求、文件操作 |
| llm | 调用 LLM 生成内容 | 生成摘要、分析数据、翻译文本 |
| condition | 条件分支 | 根据任务数量决定是否生成报告 |
| loop | 循环遍历 | 对每个待办项逐一处理 |
| parallel | 并行执行 | 同时查询多个数据源 |
| sub-workflow | 调用子工作流 | 复用已有的工作流定义 |
| wait | 等待指定时间 | 发送提醒后等待 5 分钟再检查 |
| noop | 空操作占位 | 条件分支的 else 不需要操作时 |

### 3.2 Skill 步骤

调用 ZhiWei 已注册的 Skill（内置或 YAML 自定义 Skill）：

```yaml
- id: create-todo
  name: 创建待办
  type: skill
  skillId: todo.create
  params:
    title: "周报：${inputs.weekNumber}"
    priority: high
```

### 3.3 Tool 步骤

调用已注册的工具（内置工具或 MCP 工具）：

```yaml
- id: send-message
  name: 发送消息
  type: tool
  toolId: notification.send
  params:
    channel: wecom
    message: "${steps.generate.output.content}"
```

### 3.4 LLM 步骤

调用 LLM 进行推理或生成：

```yaml
- id: summarize
  name: 生成摘要
  type: llm
  scene: chat
  prompt: "请用 3 句话总结以下内容：${steps.fetch-data.output.text}"
```

### 3.5 条件步骤

根据表达式结果执行不同分支：

```yaml
- id: check-count
  name: 检查任务数量
  type: condition
  condition: "${steps.fetch-tasks.output.count} > 0"
  then:
    - id: process
      name: 处理任务
      type: skill
      skillId: todo.process
      params:
        tasks: "${steps.fetch-tasks.output.tasks}"
  else:
    - id: skip
      name: 无任务
      type: noop
```

### 3.6 循环步骤

遍历集合，对每个元素执行一组步骤：

```yaml
- id: process-each
  name: 逐项处理
  type: loop
  items: "${steps.fetch-tasks.output.tasks}"
  loopVar: task
  body:
    - id: analyze
      name: 分析任务
      type: llm
      scene: chat
      prompt: "分析这个任务的优先级：${task}"
```

循环中可通过 `${task}` 访问当前元素，`${task_index}` 访问当前索引。

### 3.7 并行步骤

同时执行多个分支，所有分支完成后继续：

```yaml
- id: parallel-fetch
  name: 并行获取数据
  type: parallel
  branches:
    - - id: fetch-todos
        name: 获取待办
        type: skill
        skillId: todo.list
        params:
          filter: "this-week"
    - - id: fetch-habits
        name: 获取习惯
        type: skill
        skillId: habit.list
        params:
          filter: "active"
```

### 3.8 子工作流步骤

调用另一个已注册的工作流：

```yaml
- id: run-cleanup
  name: 执行清理
  type: sub-workflow
  workflowId: data-cleanup
  params:
    scope: "${inputs.scope}"
```

子工作流最大嵌套深度为 3 层（可配置），防止无限递归。

## 4. 表达式语法

工作流使用 `${...}` 语法在步骤之间传递数据和做条件判断。

### 4.1 变量引用

```yaml
# 引用输入参数
"${inputs.userId}"

# 引用前一步骤的输出（支持嵌套路径）
"${steps.fetch-tasks.output.items}"
"${steps.fetch-tasks.output.items[0].title}"

# 引用循环变量
"${task}"
"${task_index}"
```

### 4.2 条件表达式

用于 condition 步骤的条件判断：

```yaml
# 比较运算
"${steps.check.output.count} > 0"
"${inputs.mode} == 'auto'"
"${steps.score.output.value} >= 80"

# 逻辑运算
"${steps.a.output.ok} == true && ${steps.b.output.ok} == true"
"${steps.check.output.count} > 0 || ${inputs.force} == true"
```

支持的运算符：`==`、`!=`、`>`、`<`、`>=`、`<=`、`&&`、`||`、`!`

## 5. 触发器

### 5.1 定时触发（Cron）

按 cron 表达式定时执行：

```yaml
triggers:
  - type: cron
    cron: "0 21 * * *"      # 每天 21:00
  - type: cron
    cron: "0 9 * * MON"     # 每周一 09:00
```

如果上一次执行尚未完成，新的定时触发会被跳过，避免重复执行。

### 5.2 事件触发

监听 Spring ApplicationEvent，事件发布时自动执行：

```yaml
triggers:
  - type: event
    eventType: "TodoCreatedEvent"
```

事件的 payload 数据会作为工作流输入参数传入。

### 5.3 手动触发

只能通过 API 或 CLI 手动执行：

```yaml
triggers:
  - type: manual
```

一个工作流可以同时配置多种触发器。

## 6. 错误处理

每个步骤可以独立配置错误处理策略：

### 6.1 重试（Retry）

```yaml
errorStrategy:
  type: retry
  maxAttempts: 3
  initialDelayMs: 500
  maxDelayMs: 5000
```

使用指数退避重试，所有重试耗尽后工作流标记为失败。

### 6.2 跳过（Skip）

```yaml
errorStrategy:
  type: skip
  reason: "通知发送失败不影响主流程"
```

步骤失败时跳过，继续执行后续步骤。适用于非关键步骤。

### 6.3 失败（Fail）

```yaml
errorStrategy:
  type: fail
```

步骤失败时立即终止工作流。这是未配置策略时的默认行为。

### 6.4 补偿（Compensate）

```yaml
errorStrategy:
  type: compensate
  compensationStep:
    id: rollback-order
    name: 撤销订单
    type: tool
    toolId: order.cancel
    params:
      orderId: "${steps.create-order.output.orderId}"
```

步骤失败时执行补偿操作（如撤销 API 调用），然后标记工作流为失败。借鉴 Saga 模式。

## 7. 崩溃恢复

工作流引擎在每个步骤执行后自动保存执行快照（当前状态、变量上下文、执行位置）。如果应用意外重启：

- 正在执行的工作流会从上次完成的步骤继续执行
- 正在等待的工作流会检查等待时间是否已过期，过期则继续，未过期则重新等待
- 上下文数据损坏的工作流会被标记为失败并记录错误日志

恢复过程在应用启动时自动执行，不阻塞其他功能的初始化。

## 8. 热加载

工作流引擎每 30 秒（可配置）扫描一次工作流定义目录：

- 新增 YAML 文件 → 自动解析并注册
- 修改 YAML 文件 → 自动更新注册
- 删除 YAML 文件 → 禁用对应工作流（保留已有实例历史）
- 解析失败 → 跳过该文件，不影响其他工作流

无需重启应用，修改 YAML 文件后等待片刻即可生效。

## 9. 完整示例：周报自动化

```yaml
id: weekly-report
name: 周报自动化
description: 每周五下午自动收集数据并生成周报
version: "1.0"

triggers:
  - type: cron
    cron: "0 16 * * FRI"    # 每周五 16:00

inputs:
  userId:
    type: string
    required: true
    description: 用户 ID

steps:
  # 并行获取本周数据
  - id: fetch-data
    name: 并行获取数据
    type: parallel
    branches:
      - - id: fetch-todos
          name: 获取本周待办
          type: skill
          skillId: todo.list
          params:
            filter: "this-week"
            userId: "${inputs.userId}"
      - - id: fetch-habits
          name: 获取本周习惯
          type: skill
          skillId: habit.summary
          params:
            period: "this-week"
            userId: "${inputs.userId}"

  # 检查是否有数据
  - id: check-data
    name: 检查数据
    type: condition
    condition: "${steps.fetch-todos.output.count} > 0"
    then:
      # 用 LLM 生成周报
      - id: generate-report
        name: 生成周报
        type: llm
        scene: chat
        prompt: |
          请根据以下数据生成本周工作周报：
          
          待办完成情况：${steps.fetch-todos.output.tasks}
          习惯打卡情况：${steps.fetch-habits.output.summary}
          
          要求：简洁明了，分为"本周完成"和"下周计划"两部分。
    else:
      - id: no-data
        name: 无数据
        type: noop

  # 发送通知
  - id: notify
    name: 发送周报
    type: tool
    toolId: notification.send
    params:
      title: "本周周报已生成"
      message: "${steps.generate-report.output.content}"
    errorStrategy:
      type: skip
      reason: "通知失败不影响周报生成"
```

## 10. 配置

```yaml
lifepilot:
  workflow:
    enabled: true                          # 是否启用工作流引擎
    definitions-dir: "~/.lifepilot/workflows"  # YAML 文件目录
    default-step-timeout-seconds: 300      # 默认步骤超时
    max-parallel-branches: 10              # 最大并行分支数
    max-nesting-depth: 3                   # 子工作流最大嵌套深度
    max-loop-iterations: 100               # 循环最大迭代次数
    crash-recovery-enabled: true           # 崩溃恢复开关
    scan-interval-seconds: 30              # 热加载扫描间隔
    retry:
      initial-delay-ms: 500                # 重试初始延迟
      max-delay-ms: 5000                   # 重试最大延迟
      max-attempts: 3                      # 最大重试次数
```
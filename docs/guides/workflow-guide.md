# 知微工作流指南

> 本文档面向知微（ZhiWei）用户，介绍如何使用工作流引擎创建和管理自动化流程。
> 所有示例均来自知微内置工作流模板，首次启动时自动释放到 `~/.zhiwei/workflows/` 目录。

---

## 目录

- [快速入门](#快速入门)
- [工作流结构](#工作流结构)
- [步骤类型详解](#步骤类型详解)
- [表达式语法](#表达式语法)
- [触发器](#触发器)
- [输入参数](#输入参数)
- [错误处理](#错误处理)
- [内置工作流模板](#内置工作流模板)
- [进阶用法](#进阶用法)
- [配置参考](#配置参考)
- [常见问题](#常见问题)

---

## 快速入门

知微工作流引擎让你通过 YAML 文件定义多步骤自动化流程，无需编写任何代码。

### 三步上手

1. 在 `~/.zhiwei/workflows/` 目录下创建一个 `.yml` 文件
2. 按照下面的格式编写工作流定义
3. 保存文件，知微会在 30 秒内自动检测并注册

就这么简单。不需要编译、部署或重启应用。

### 最简示例

```yaml
id: hello-workflow
name: 你好工作流
description: 最简单的工作流示例
version: "1.0"
triggers:
  - type: manual
steps:
  - id: greet
    name: 打招呼
    type: llm
    scene: workflow
    prompt: "请用一句话问候用户，今天是美好的一天。"
```

这个工作流只有一个步骤：调用 LLM 生成一句问候语。通过手动触发执行。

---

## 工作流结构

每个工作流 YAML 文件包含以下顶层字段：

```yaml
id: workflow-id          # 唯一标识符（必填）
name: 工作流名称          # 显示名称（必填）
description: 工作流描述   # 功能说明（可选）
version: "1.0"           # 版本号（可选）
triggers:                # 触发器列表（可选）
  - type: manual
inputs:                  # 输入参数定义（可选）
  paramName:
    type: string
    required: true
steps:                   # 步骤列表（必填，至少一个步骤）
  - id: step-id
    name: 步骤名称
    type: skill
    # ... 步骤配置
```

| 字段 | 必填 | 说明 |
|------|------|------|
| id | ✅ | 工作流唯一标识，建议用英文 kebab-case |
| name | ✅ | 显示名称，支持中文 |
| description | ❌ | 功能描述 |
| version | ❌ | 版本号，字符串格式 |
| triggers | ❌ | 触发器列表，不配置则只能手动执行 |
| inputs | ❌ | 输入参数定义 |
| steps | ✅ | 步骤列表，按顺序执行 |

---

## 步骤类型详解

知微支持 9 种步骤类型，覆盖常见自动化场景。

### 通用字段

每个步骤都必须包含以下字段：

```yaml
- id: step-id        # 步骤唯一标识（必填，同一工作流内不重复）
  name: 步骤名称      # 显示名称（必填）
  type: skill         # 步骤类型（必填）
  errorStrategy:      # 错误处理策略（可选）
    type: skip
```

### Skill 步骤（skill）

调用知微已注册的 Skill（内置 Skill 或 YAML 自定义 Skill）。

```yaml
- id: fetch-todos
  name: 获取今日待办
  type: skill
  skillId: todo.list       # Skill 标识（必填）
  params:                  # 参数（可选）
    filter: "today"
```

| 字段 | 必填 | 说明 |
|------|------|------|
| skillId | ✅ | 已注册的 Skill ID |
| params | ❌ | 传递给 Skill 的参数，键值对格式 |

**实际案例** — 内置「晨间简报」工作流中获取今日待办和日程：

```yaml
steps:
  - id: fetch-todos
    name: 获取今日待办
    type: skill
    skillId: todo.list
    params:
      filter: "today"
  - id: fetch-schedule
    name: 获取今日日程
    type: skill
    skillId: schedule.today
    params: {}
```

### Tool 步骤（tool）

调用已注册的工具（内置工具或 MCP 工具）。

```yaml
- id: fetch-page
  name: 抓取网页内容
  type: tool
  toolId: web.fetch        # 工具标识（必填）
  params:                  # 参数（可选）
    url: "${url}"
```

| 字段 | 必填 | 说明 |
|------|------|------|
| toolId | ✅ | 已注册的工具 ID |
| params | ❌ | 传递给工具的参数 |

**实际案例** — 内置「调研报告」工作流中搜索信息：

```yaml
- id: search-info
  name: 搜索相关信息
  type: tool
  toolId: web.search
  params:
    query: "${inputs.topic}"
```

### LLM 步骤（llm）

调用大语言模型进行推理或生成内容。这是知微工作流最强大的步骤类型——让 AI 处理复杂的分析、总结、生成任务。

```yaml
- id: generate-briefing
  name: 生成晨间简报
  type: llm
  scene: workflow          # 场景标识（必填）
  prompt: |                # 提示词（必填，支持表达式）
    请根据以下信息生成简报：
    ${steps.fetch-todos.output.result}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| scene | ✅ | LLM 路由场景（如 workflow、chat） |
| prompt | ✅ | 提示词模板，支持 `${...}` 表达式引用上下文数据 |
| outputSchema | ❌ | 结构化输出的 JSON Schema |

**实际案例** — 内置「会议纪要」工作流中的两步 LLM 链式调用：

```yaml
steps:
  - id: generate-notes
    name: 生成结构化纪要
    type: llm
    scene: workflow
    prompt: |
      请根据以下会议记录生成结构化会议纪要：

      ## 会议标题
      ${inputs.meetingTitle}

      ## 会议记录
      ${inputs.transcript}

      请按以下格式输出：
      1. **会议摘要**：一段话概括会议核心内容
      2. **讨论要点**：按主题分类列出讨论内容
      3. **决议事项**：明确列出达成的决定
      4. **待办事项**：提取所有 action item
      5. **遗留问题**：列出未解决的问题

  - id: extract-todos
    name: 提取待办事项
    type: llm
    scene: workflow
    prompt: |
      从以下会议纪要中提取所有待办事项：
      ${steps.generate-notes.output.result}

      格式：- [负责人] 待办内容
    errorStrategy:
      type: skip
```

这个例子展示了 LLM 步骤的链式调用：第一步生成完整纪要，第二步从纪要中提取待办。第二步引用了第一步的输出 `${steps.generate-notes.output.result}`。

### 并行步骤（parallel）

同时执行多个分支，所有分支完成后继续。适合需要从多个数据源获取数据的场景。

```yaml
- id: gather-data
  name: 并行获取数据
  type: parallel
  branches:                # 分支列表（必填）
    - - id: branch-a       # 每个分支是一个步骤列表
        name: 分支 A
        type: skill
        skillId: some.skill
    - - id: branch-b
        name: 分支 B
        type: skill
        skillId: other.skill
```

注意 `branches` 的格式：外层列表的每个元素是一个分支，每个分支本身是一个步骤列表（所以有两层 `-`）。

**实际案例** — 内置「周报生成」工作流中并行获取待办和习惯数据：

```yaml
- id: gather-data
  name: 并行获取本周数据
  type: parallel
  branches:
    - - id: fetch-weekly-todos
        name: 获取本周待办
        type: skill
        skillId: todo.list
        params:
          filter: "this-week"
    - - id: fetch-weekly-habits
        name: 获取本周习惯数据
        type: skill
        skillId: habit.weekly-stats
        params: {}
```

两个分支同时执行，比顺序执行快一倍。后续步骤可以通过 `${steps.fetch-weekly-todos.output.result}` 和 `${steps.fetch-weekly-habits.output.result}` 分别引用各分支的输出。

### 循环步骤（loop）

遍历集合，对每个元素执行一组步骤。

```yaml
- id: process-items
  name: 遍历处理
  type: loop
  items: "${inputs.urls}"  # 要遍历的集合（必填，表达式）
  loopVar: item            # 循环变量名（必填）
  body:                    # 循环体步骤列表（必填）
    - id: process
      name: 处理单项
      type: tool
      toolId: some.tool
      params:
        data: "${item}"
```

| 字段 | 必填 | 说明 |
|------|------|------|
| items | ✅ | 要遍历的集合，通常是表达式 |
| loopVar | ✅ | 循环变量名，在 body 中通过 `${loopVar}` 引用当前元素 |
| body | ✅ | 每次迭代执行的步骤列表 |

循环中可通过 `${loopVar}` 访问当前元素，`${loopVar_index}` 访问当前索引（从 0 开始）。

**实际案例** — 内置「网页摘要」工作流中遍历 URL 列表抓取网页：

```yaml
- id: crawl-pages
  name: 遍历抓取网页
  type: loop
  items: "${inputs.urls}"
  loopVar: url
  body:
    - id: fetch-page
      name: 抓取网页内容
      type: tool
      toolId: web.fetch
      params:
        url: "${url}"
      errorStrategy:
        type: skip
```

这里 `loopVar: url` 定义了循环变量名为 `url`，在 body 中通过 `${url}` 引用当前正在处理的 URL。`errorStrategy: skip` 确保单个网页抓取失败不会中断整个循环。

### 条件步骤（condition）

根据表达式结果执行不同分支。

```yaml
- id: check
  name: 条件判断
  type: condition
  condition: "${steps.fetch.output.count} > 0"  # 条件表达式（必填）
  then:                    # 条件为真时执行（步骤列表）
    - id: process
      name: 处理数据
      type: skill
      skillId: some.skill
  else:                    # 条件为假时执行（步骤列表，可选）
    - id: skip
      name: 跳过
      type: noop
```

### 子工作流步骤（sub-workflow）

调用另一个已注册的工作流，实现工作流复用。

```yaml
- id: run-sub
  name: 执行子工作流
  type: sub-workflow
  workflowId: other-workflow  # 子工作流 ID（必填）
  params:                     # 传递给子工作流的参数（可选）
    key: "${some.value}"
```

子工作流最大嵌套深度为 3 层（可配置），防止无限递归。

### 等待步骤（wait）

暂停执行指定时间。工作流实例状态转为 WAITING，不占用线程资源。

```yaml
- id: pause
  name: 等待 5 分钟
  type: wait
  durationSeconds: 300
```

### 空操作步骤（noop）

不执行任何操作，通常用于条件分支的 else 占位。

```yaml
- id: do-nothing
  name: 无操作
  type: noop
```

---

## 表达式语法

工作流使用 `${...}` 语法在步骤之间传递数据和做条件判断。

### 变量引用

```yaml
# 引用输入参数
"${inputs.userId}"
"${inputs.topic}"

# 引用前一步骤的输出（支持嵌套路径）
"${steps.fetch-todos.output.result}"
"${steps.search-info.output.items[0].title}"

# 引用循环变量
"${url}"              # 当前元素
"${url_index}"        # 当前索引（从 0 开始）
```

### 在提示词中使用

LLM 步骤的 prompt 中可以直接嵌入表达式，引擎会在执行前自动替换：

```yaml
prompt: |
  请针对主题「${inputs.topic}」生成调研报告。
  调研深度：${inputs.depth}

  ## 搜索结果
  ${steps.search-info.output.result}
```

### 条件表达式

用于 condition 步骤的条件判断：

```yaml
# 比较运算
"${steps.check.output.count} > 0"
"${inputs.mode} == 'auto'"
"${steps.score.output.value} >= 80"

# 逻辑运算
"${steps.a.output.ok} == true && ${steps.b.output.ok} == true"
"${steps.check.output.count} > 0 || ${inputs.force} == true"
"!${steps.validate.output.hasError}"
```

支持的运算符：`==`、`!=`、`>`、`<`、`>=`、`<=`、`&&`、`||`、`!`

---

## 触发器

工作流支持三种触发方式，可以同时配置多种触发器。

### 定时触发（cron）

按 cron 表达式定时执行。使用 6 位 cron 格式：`秒 分 时 日 月 周`。

```yaml
triggers:
  - type: cron
    cron: "0 0 8 * * *"      # 每天 08:00
```

**内置工作流中的 cron 示例：**

| 工作流 | cron 表达式 | 含义 |
|--------|-----------|------|
| 晨间简报 | `0 0 8 * * *` | 每天早上 8 点 |
| 周报生成 | `0 0 20 * * 0` | 每周日晚上 8 点 |

如果上一次执行尚未完成，新的定时触发会被跳过，避免重复执行。

### 事件触发（event）

监听 Spring ApplicationEvent，事件发布时自动执行：

```yaml
triggers:
  - type: event
    eventType: "TodoCreatedEvent"
```

事件的 payload 数据会作为工作流输入参数传入。

### 手动触发（manual）

只能通过 API 或 CLI 手动执行：

```yaml
triggers:
  - type: manual
```

内置的「网页摘要」「会议纪要」「调研报告」三个工作流都使用手动触发，因为它们需要用户提供输入数据。

---

## 输入参数

手动触发的工作流通常需要用户提供输入数据。通过 `inputs` 字段定义参数：

```yaml
inputs:
  paramName:
    type: string           # 参数类型：string / list / number / boolean
    required: true          # 是否必填
    defaultValue: "默认值"  # 默认值（可选）
    description: 参数说明   # 描述（可选）
```

**实际案例** — 内置「调研报告」工作流的输入参数：

```yaml
inputs:
  topic:
    type: string
    required: true
    description: 调研主题
  depth:
    type: string
    required: false
    defaultValue: "standard"
    description: 调研深度（brief / standard / deep）
```

`topic` 是必填参数，`depth` 是可选参数，默认值为 `"standard"`。在步骤中通过 `${inputs.topic}` 和 `${inputs.depth}` 引用。

**实际案例** — 内置「网页摘要」工作流的列表类型输入：

```yaml
inputs:
  urls:
    type: list
    required: true
    description: 需要摘要的网页 URL 列表
```

列表类型的输入可以在 loop 步骤中遍历：`items: "${inputs.urls}"`。

---

## 错误处理

每个步骤可以独立配置错误处理策略。不配置时默认为 `fail`（步骤失败则工作流终止）。

### 跳过（skip）

步骤失败时跳过，继续执行后续步骤。适用于非关键步骤。

```yaml
errorStrategy:
  type: skip
```

**实际案例** — 内置「网页摘要」工作流中，单个网页抓取失败不影响其他网页：

```yaml
- id: fetch-page
  name: 抓取网页内容
  type: tool
  toolId: web.fetch
  params:
    url: "${url}"
  errorStrategy:
    type: skip
```

内置「会议纪要」工作流中，待办提取失败不影响纪要生成：

```yaml
- id: extract-todos
  name: 提取待办事项
  type: llm
  scene: workflow
  prompt: ...
  errorStrategy:
    type: skip
```

### 重试（retry）

使用指数退避重试，所有重试耗尽后工作流标记为失败。

```yaml
errorStrategy:
  type: retry
  maxAttempts: 3           # 最大重试次数
  initialDelayMs: 500      # 初始延迟（毫秒）
  maxDelayMs: 5000         # 最大延迟（毫秒）
```

适用于网络抖动、临时不可用等可恢复错误。

### 失败（fail）

步骤失败时立即终止工作流。这是默认行为。

```yaml
errorStrategy:
  type: fail
```

### 补偿（compensate）

步骤失败时执行补偿操作（如撤销 API 调用），然后标记工作流为失败。借鉴 Saga 模式。

```yaml
errorStrategy:
  type: compensate
  compensationStep:
    id: rollback
    name: 回滚操作
    type: tool
    toolId: order.cancel
    params:
      orderId: "${steps.create-order.output.orderId}"
```

---

## 内置工作流模板

知微首次启动时会自动释放 5 个内置工作流模板到 `~/.zhiwei/workflows/` 目录。你可以直接使用、修改或删除它们。删除后重启应用会重新释放。

这些模板覆盖了工作流引擎的所有高级特性：ConditionStep 条件分支、LoopStep 循环处理、ParallelStep 并行执行、ApprovalStep 人工审批、SubWorkflowStep 子工作流、WaitStep 等待、ErrorStrategy 错误策略、DAG dependsOn 依赖编排。

### 1. 智能内容审核（content-review.yml）

**触发方式：** 事件触发（content.submitted）或手动触发

**功能：** 使用 LLM 分析内容风险等级，高风险内容进入人工审批，低风险自动通过，最终生成审核报告并发送通知。

**步骤流程：**
```
ToolStep(预处理) → [LlmStep(风险分析) ∥ ToolStep(关键词扫描)]（DAG 并行）
  → ConditionStep(风险判定)
    → then: ApprovalStep(人工审批) → ConditionStep(审批结果)
    → else: ToolStep(自动通过)
  → LlmStep(生成报告) → ToolStep(发送通知)
```

**涉及的步骤类型：** tool、llm、condition（嵌套）、approval

**适合学习：** DAG 依赖编排（dependsOn）、条件分支嵌套、人工审批流程、错误重试策略。

### 2. 多源数据聚合报告（data-aggregation.yml）

**触发方式：** 每周一早上 9:00 自动执行，或手动触发

**功能：** 并行从多个数据源（待办、日程、习惯）采集信息并分析，LLM 综合生成聚合报告，格式化后保存并发送。

**步骤流程：**
```
ParallelStep(3 个分支各含 SkillStep + LlmStep 链)
  → ToolStep(获取外部数据, errorStrategy=skip)
  → LlmStep(综合分析) → ToolStep(格式化) → [ToolStep(保存) ∥ ToolStep(发送)]
```

**涉及的步骤类型：** parallel（分支内含多步骤链）、skill、llm、tool

**适合学习：** 并行分支内的多步骤串联、DAG 多依赖汇聚、skip 错误策略、retry 错误策略。

### 3. 批量任务处理（batch-processing.yml）

**触发方式：** 每天凌晨 2:00 自动执行，或手动触发

**功能：** 获取待处理任务列表，LLM 校验数据完整性，循环逐条处理每个任务（按类型路由到不同处理器），最终生成处理汇总报告。

**步骤流程：**
```
ToolStep(获取任务列表) → LlmStep(校验数据)
  → LoopStep(逐条处理)
    → ConditionStep(任务分类路由)
      → then: ToolStep(数据同步, retry)
      → else: ToolStep(通用任务, retry)
    → ToolStep(更新状态, skip)
  → ToolStep(汇总结果) → LlmStep(生成报告) → ToolStep(发送通知)
```

**涉及的步骤类型：** loop（含嵌套 condition）、condition、tool、llm

**适合学习：** 循环步骤 + 嵌套条件分支、循环变量引用（loopVar）、多种错误策略组合（retry + skip）。

### 4. 定时巡检与告警（scheduled-inspection.yml）

**触发方式：** 每 30 分钟自动执行

**功能：** 执行系统健康检查（系统、LLM、数据库），LLM 分析巡检结果，根据严重程度分级告警，紧急情况等待冷却后触发自动修复子工作流。

**步骤流程：**
```
[ToolStep(健康检查) ∥ ToolStep(LLM 状态) ∥ ToolStep(数据库状态)]（DAG 并行）
  → LlmStep(分析结果)
  → ConditionStep(健康判定)
    → then: ConditionStep(严重程度)
      → critical: ToolStep(紧急告警) → WaitStep(冷却 60s) → SubWorkflowStep(自动修复) → ToolStep(验证)
      → degraded: ToolStep(降级告警)
    → else: NoopStep(记录健康)
  → ToolStep(保存巡检记录)
```

**涉及的步骤类型：** tool、llm、condition（嵌套）、wait、sub-workflow、noop

**适合学习：** WaitStep 冷却等待、SubWorkflowStep 子工作流调用、嵌套条件分支、skip 错误策略。

### 5. 多步骤调研与审批（research-approval.yml）

**触发方式：** 手动触发，需要输入调研主题

**功能：** 多源信息搜索（网络 + 知识库 + 记忆），LLM 深度分析并质量评审，经人工审批后发布调研成果，失败时自动执行补偿回滚。

**步骤流程：**
```
[ToolStep(搜索网络) ∥ ToolStep(搜索知识库) ∥ SkillStep(搜索记忆)]（DAG 并行）
  → LlmStep(深度分析) → LlmStep(质量评审)
  → ApprovalStep(成果审批)
  → ToolStep(发布, errorStrategy=compensate → 回滚)
  → [ToolStep(通知) ∥ SkillStep(存入记忆)]（DAG 并行）
```

**涉及的步骤类型：** tool、skill、llm、approval

**适合学习：** compensate 补偿错误策略（发布失败自动回滚）、DAG 多源并行汇聚、审批流程、skip 降级策略。

### 自定义内置工作流

这些模板释放到用户目录后就是普通的 YAML 文件，你可以自由编辑：

- 修改 cron 表达式调整执行时间
- 修改 prompt 调整 AI 输出风格
- 添加或删除步骤
- 修改后无需重启，热加载会自动检测变更

如果不需要某个内置工作流，直接删除对应的 YAML 文件即可。如果想恢复，重启应用会重新释放。

也可以在 `application.yml` 中关闭自动释放：

```yaml
lifepilot:
  workflow:
    seed-builtin-workflows: false
```

---

## 进阶用法

### 组合模式

内置工作流展示了几种常见的组合模式，你可以在自己的工作流中灵活运用：

**模式 1：DAG 并行汇聚 → 分析**（内容审核、调研审批）

多个步骤通过 dependsOn 声明依赖关系，引擎自动并行执行无依赖的步骤，在汇聚点合并结果。

```yaml
steps:
  - id: source-a
    type: tool
  - id: source-b
    type: tool
  - id: analyze
    type: llm
    prompt: |
      数据 A：${steps.source-a.output.result}
      数据 B：${steps.source-b.output.result}
    dependsOn:
      - source-a
      - source-b
```

**模式 2：并行分支内多步骤链**（数据聚合报告）

parallel 分支内可包含多个串联步骤，每个分支独立执行采集 + 分析链。

```yaml
steps:
  - id: gather
    type: parallel
    branches:
      - - id: fetch-data
          type: skill
        - id: analyze-data
          type: llm
          prompt: "分析：${steps.fetch-data.output.result}"
      - - id: fetch-other
          type: skill
```

**模式 3：循环 + 嵌套条件路由**（批量任务处理）

对集合中的每个元素执行操作，循环体内按条件路由到不同处理器。

```yaml
steps:
  - id: process-each
    type: loop
    items: "${steps.fetch.output.result}"
    loopVar: item
    body:
      - id: route
        type: condition
        condition: "${item.type} == 'special'"
        then:
          - id: special-handler
            type: tool
        else:
          - id: default-handler
            type: tool
```

**模式 4：条件分支 + 审批 + 等待 + 子工作流**（定时巡检、内容审核）

嵌套条件分支实现多级决策，结合审批、等待冷却、子工作流调用等高级步骤。

```yaml
steps:
  - id: decision
    type: condition
    condition: "${steps.analyze.output.result.status} == 'critical'"
    then:
      - id: alert
        type: tool
      - id: cooldown
        type: wait
        durationSeconds: 60
      - id: auto-fix
        type: sub-workflow
        workflowId: remediation
    else:
      - id: log-ok
        type: noop
```

**模式 5：补偿错误策略**（调研审批）

发布操作失败时自动执行补偿步骤回滚，保证数据一致性。

```yaml
steps:
  - id: publish
    type: tool
    toolId: knowledge.ingest
    errorStrategy:
      type: compensate
      compensationStep:
        id: rollback
        name: 回滚发布
        type: tool
        toolId: knowledge.delete
```

### 创建自己的工作流

以内置模板为起点，创建你自己的工作流：

**示例：每日新闻摘要**

```yaml
id: daily-news
name: 每日新闻摘要
description: 每天早上搜索热点新闻并生成摘要
version: "1.0"
triggers:
  - type: cron
    cron: "0 30 7 * * *"    # 每天 07:30
steps:
  - id: search-news
    name: 搜索今日新闻
    type: tool
    toolId: web.search
    params:
      query: "今日热点新闻"
  - id: generate-digest
    name: 生成新闻摘要
    type: llm
    scene: workflow
    prompt: |
      请根据以下搜索结果生成今日新闻摘要：

      ${steps.search-news.output.result}

      要求：
      1. 选取最重要的 5 条新闻
      2. 每条新闻用 2-3 句话概括
      3. 按重要性排序
```

**示例：批量文件处理**

```yaml
id: batch-translate
name: 批量翻译
description: 输入文本列表，逐条翻译
version: "1.0"
triggers:
  - type: manual
inputs:
  texts:
    type: list
    required: true
    description: 需要翻译的文本列表
  targetLang:
    type: string
    required: false
    defaultValue: "英文"
    description: 目标语言
steps:
  - id: translate-each
    name: 逐条翻译
    type: loop
    items: "${inputs.texts}"
    loopVar: text
    body:
      - id: translate
        name: 翻译
        type: llm
        scene: workflow
        prompt: "请将以下内容翻译为${inputs.targetLang}：${text}"
        errorStrategy:
          type: skip
```

---

## 配置参考

在 `application.yml` 中配置工作流引擎：

```yaml
lifepilot:
  workflow:
    enabled: true                           # 是否启用工作流引擎
    definitions-dir: "~/.zhiwei/workflows"  # YAML 文件目录
    seed-builtin-workflows: true            # 是否释放内置工作流模板
    default-step-timeout-seconds: 300       # 默认步骤超时（秒）
    max-parallel-branches: 10               # 最大并行分支数
    max-nesting-depth: 3                    # 子工作流最大嵌套深度
    max-loop-iterations: 100                # 循环最大迭代次数
    crash-recovery-enabled: true            # 崩溃恢复开关
    scan-interval-seconds: 30               # 热加载扫描间隔（秒）
    retry:
      initial-delay-ms: 500                 # 重试初始延迟（毫秒）
      max-delay-ms: 5000                    # 重试最大延迟（毫秒）
      max-attempts: 3                       # 最大重试次数
```

---

## 常见问题

### 工作流文件放在哪里？

默认目录是 `~/.zhiwei/workflows/`，可以通过 `lifepilot.workflow.definitions-dir` 配置修改。

### 修改 YAML 后需要重启吗？

不需要。引擎每 30 秒扫描一次目录，自动检测文件变更。

### 工作流执行失败了怎么办？

- 检查步骤的 `errorStrategy` 配置，非关键步骤建议设为 `skip`
- 查看应用日志，搜索工作流 ID 查看详细错误信息
- 网络相关的步骤建议配置 `retry` 策略

### 应用崩溃后正在执行的工作流会丢失吗？

不会。引擎在每个步骤执行后自动保存快照，重启后从上次完成的步骤继续执行。

### 如何禁用某个内置工作流？

直接删除 `~/.zhiwei/workflows/` 目录下对应的 YAML 文件即可。如果不想重启后重新释放，可以将文件内容中的 `enabled` 设为 `false`（或在 `application.yml` 中关闭 `seed-builtin-workflows`）。

### 并行步骤有数量限制吗？

默认最多 10 个并行分支，可通过 `max-parallel-branches` 配置调整。

### 循环步骤有迭代次数限制吗？

默认最多 100 次迭代，可通过 `max-loop-iterations` 配置调整。

# 知微工作流指南

> 本文档面向知微（ZhiWei）用户，介绍如何使用工作流引擎创建和管理自动化流程。
> 所有示例均来自知微内置工作流模板，首次启动时自动释放到 `~/.zhiwei/workflows/` 目录。

---

## 目录

- [快速入门](#快速入门)
- [工作流结构](#工作流结构)
- [步骤类型详解](#步骤类型详解)
- [表达式语法与函数](#表达式语法与函数)
- [触发器](#触发器)
- [工作流变量与常量](#工作流变量与常量)
- [工作流标签](#工作流标签)
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
| variables | ❌ | 工作流级变量定义 |
| tags | ❌ | 工作流标签列表，用于分类和筛选 |
| steps | ✅ | 步骤列表，按顺序执行 |

---

## 步骤类型详解

知微支持 10 种步骤类型，覆盖常见自动化场景。

### 通用字段

每个步骤都必须包含以下字段：

```yaml
- id: step-id        # 步骤唯一标识（必填，同一工作流内不重复）
  name: 步骤名称      # 显示名称（必填）
  type: skill         # 步骤类型（必填）
  timeoutSeconds: 300 # 步骤超时时间（可选，单位：秒）
  errorStrategy:      # 错误处理策略（可选）
    type: skip
```

|| 字段 | 必填 | 说明 |
||------|------|------|
|| id | ✅ | 步骤唯一标识，同一工作流内不重复 |
|| name | ✅ | 显示名称 |
|| type | ✅ | 步骤类型 |
|| timeoutSeconds | ❌ | 步骤级超时时间（秒），默认使用全局配置（300秒） |
|| errorStrategy | ❌ | 错误处理策略 |

### Skill 步骤（skill）

调用知微已注册的 Skill（内置 Skill 或 YAML 自定义 Skill）。

```yaml
- id: search-memory
  name: 搜索相关记忆
  type: skill
  skillId: memory.search   # Skill 标识（必填）
  params:                  # 参数（可选）
    query: "今日计划"
```

| 字段 | 必填 | 说明 |
|------|------|------|
| skillId | ✅ | 已注册的 Skill ID |
| params | ❌ | 传递给 Skill 的参数，键值对格式 |

**实际案例** — 内置「晨间简报」工作流中搜索记忆和获取任务：

```yaml
steps:
  - id: search-memory
    name: 搜索今日相关记忆
    type: skill
    skillId: memory.search
    params:
      query: "今日计划"
  - id: list-tasks
    name: 获取自主任务列表
    type: skill
    skillId: builtin.task.list
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
|  scene | ✅ | LLM 路由场景（如 workflow、chat） |
|  prompt | ✅ | 提示词模板，支持 `${...}` 表达式引用上下文数据 |
|  outputSchema | ❌ | 结构化输出的 JSON Schema |
|  capability | ❌ | LLM 能力：CHAT / STRUCTURED_OUTPUT / VISION / FUNCTION_CALLING |
|  modelName | ❌ | 指定模型名称 |
|  preferredProviderId | ❌ | 首选 Provider ID |
|  media | ❌ | 媒体输入列表 |

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

### LLM 增强能力

#### capability 能力字段

`capability` 字段指定 LLM 步骤的具体能力：

```yaml
- id: analyze-image
  name: 分析图片
  type: llm
  scene: workflow
  capability: VISION                    # 视觉理解能力
  prompt: "描述这张图片的内容"
  media:
    - type: image
      url: "${inputs.imageUrl}"
```

**可选值：**

- `CHAT`：默认能力，进行对话
- `STRUCTURED_OUTPUT`：结构化输出，按 outputSchema 输出 JSON
- `VISION`：视觉理解，分析图片内容
- `FUNCTION_CALLING`：函数调用，让 LLM 决定调用哪些工具

#### 指定模型和 Provider

```yaml
- id: generate-report
  name: 生成专业报告
  type: llm
  scene: workflow
  modelName: gpt-4o                     # 指定使用 GPT-4o
  preferredProviderId: openai           # 指定 Provider
  prompt: "生成详细报告"
```

#### 媒体输入（Vision）

```yaml
- id: extract-from-image
  name: 从图片提取信息
  type: llm
  scene: workflow
  capability: VISION
  prompt: "从这张发票图片中提取金额、日期、开票方信息"
  media:
    - type: image
      url: "${steps.download.output.imageUrl}"
```

`media` 支持的图片来源：
- `url`：直接图片 URL
- `base64`：Base64 编码图片数据
- `fileId`：知微文件系统中的文件 ID

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

**实际案例** — 内置「周报生成」工作流中并行获取记忆和任务数据：

```yaml
- id: gather-data
  name: 并行获取本周数据
  type: parallel
  branches:
    - - id: search-weekly-memory
        name: 搜索本周记忆
        type: skill
        skillId: memory.search
        params:
          query: "本周总结"
    - - id: fetch-weekly-tasks
        name: 获取本周任务执行记录
        type: skill
        skillId: builtin.task.list
        params: {}
```

两个分支同时执行，比顺序执行快一倍。后续步骤可以通过 `${steps.search-weekly-memory.output.result}` 和 `${steps.fetch-weekly-tasks.output.result}` 分别引用各分支的输出。

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

### 人工审批步骤（approval）

暂停工作流等待人工审批决策。引擎遇到 ApprovalStep 时将工作流状态从 RUNNING 转换为 PAUSED，等待外部通过 API 提交审批决策。

```yaml
- id: review
  name: 人工审核
  type: approval
  message: "请审核以下内容是否可以发布"  # 审批消息（必填）
  approvers:                              # 审批人列表（可选）
    - admin
  timeoutSeconds: 86400                   # 超时时间，默认 24 小时（可选）
  autoApproveOnTimeout: false             # 超时后是否自动批准（可选）
```

| 字段 | 必填 | 说明 |
|------|------|------|
| message | ✅ | 展示给审批人的消息 |
| approvers | ❌ | 审批人列表 |
| timeoutSeconds | ❌ | 审批超时时间（秒），默认 86400（24 小时） |
| autoApproveOnTimeout | ❌ | 超时后是否自动批准，默认 false |

**实际案例** — 内置「智能内容审核」工作流中的人工审批：

```yaml
- id: human-review
  name: 人工审核
  type: approval
  message: "内容风险等级较高，请人工审核"
  approvers:
    - content-admin
  timeoutSeconds: 3600
  autoApproveOnTimeout: false
```

审批通过后工作流继续执行后续步骤，审批拒绝则工作流终止。可通过 `POST /api/workflows/executions/{instanceId}/steps/{stepId}/approve` 提交审批决策。

### 通知步骤（notify）

发送通知给指定用户。适合“有结果需要告知用户”的场景。

```yaml
- id: send-notification
  name: 发送通知
  type: notify
  targetUserId: "${inputs.userId}"    # 目标用户ID（可选）
  content: "任务已完成"                # 通知内容（必填）
  contentType: CARD                   # 内容类型：TEXT / MARKDOWN / CARD
```

|| 字段 | 必填 | 说明 |
||------|------|------|
|| targetUserId | ❌ | 目标用户ID，默认发送给当前用户 |
|| content | ✅ | 通知内容 |
|| contentType | ❌ | 内容类型：TEXT（纯文本）、MARKDOWN（Markdown格式）、CARD（卡片样式） |

**contentType 说明：**

- `TEXT`：纯文本，适合简单消息
- `MARKDOWN`：支持 Markdown 格式渲染
- `CARD`：卡片样式，包含标题、正文、底部按钮，适合需要用户操作的场景

**使用约定：**

- 有明确结果、异常或结论要告知用户时使用 `notify`
- 没有结果时优先用 `condition + noop` 静默结束
- 不再区分通知等级

**实际案例 — 内置「每日任务提醒」工作流中的通知：**

```yaml
- id: send-reminder
  name: 发送提醒通知
  type: notify
  targetUserId: "${inputs.userId}"
  content: |
    ## 任务提醒

    您有 **${steps.filter.output.count}** 项任务即将执行：

    ${steps.format.output.result}
  contentType: MARKDOWN
```

---

## 表达式语法与函数

工作流使用 `${...}` 语法在步骤之间传递数据和做条件判断。除基础变量引用外，还支持丰富的内置函数。

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

# 引用工作流级变量（需在 variables 中定义）
"${vars.apiEndpoint}"
"${vars.maxRetries}"
```

### 内置函数

表达式引擎提供四类内置函数，可直接在 `${...}` 中调用。

#### 字符串函数

| 函数 | 说明 | 示例 |
|------|------|------|
| `len(str)` | 返回字符串长度 | `${len("hello")}` → 5 |
| `upper(str)` | 转为大写 | `${upper("hello")}` → "HELLO" |
| `lower(str)` | 转为小写 | `${lower("HELLO")}` → "hello" |
| `trim(str)` | 去除首尾空白 | `${trim("  hello  ")}` → "hello" |
| `substring(str, start, end?)` | 截取子串 | `${substring("hello", 1, 3)}` → "el" |
| `replace(str, target, replacement)` | 替换内容 | `${replace("a-b", "-", "_")}` → "a_b" |
| `contains(str, search)` | 是否包含 | `${contains("hello", "ll")}` → true |
| `split(str, delimiter)` | 分割为列表 | `${split("a,b,c", ",")}` → ["a","b","c"] |
| `join(list, delimiter)` | 列表拼接 | `${join(["a","b"], "-")}` → "a-b" |

#### 日期函数

| 函数 | 说明 | 示例 |
|------|------|------|
| `now()` | 当前时间（ISO 8601） | `${now()}` → "2026-03-13T10:30:00Z" |
| `formatDate(date, pattern)` | 格式化日期 | `${formatDate("2026-03-13", "yyyy年MM月dd日")}` → "2026年03月13日" |
| `parseDate(dateStr)` | 解析为 ISO 格式 | `${parseDate("2026-03-13")}` → "2026-03-13T00:00:00" |
| `addDays(date, days)` | 增加天数 | `${addDays("2026-03-13", 7)}` → "2026-03-20T00:00:00" |
| `addHours(date, hours)` | 增加小时数 | `${addHours("2026-03-13T10:00", 2)}` → "2026-03-13T12:00:00" |
| `daysBetween(date1, date2)` | 计算天数差 | `${daysBetween("2026-03-13", "2026-03-20")}` → 7 |

#### 集合函数

| 函数 | 说明 | 示例 |
|------|------|------|
| `size(collection)` | 返回集合大小 | `${size([1,2,3])}` → 3 |
| `first(list)` | 返回第一个元素 | `${first([1,2,3])}` → 1 |
| `last(list)` | 返回最后一个元素 | `${last([1,2,3])}` → 3 |
| `flatten(list)` | 展平嵌套列表 | `${flatten([[1,2],[3]])}` → [1,2,3] |
| `distinct(list)` | 去重 | `${distinct([1,2,2,3])}` → [1,2,3] |

#### 数学函数

| 函数 | 说明 | 示例 |
|------|------|------|
| `min(a, b)` | 最小值 | `${min(3, 5)}` → 3 |
| `max(a, b)` | 最大值 | `${max(3, 5)}` → 5 |
| `abs(n)` | 绝对值 | `${abs(-5)}` → 5 |
| `round(n)` | 四舍五入 | `${round(3.6)}` → 4 |
| `ceil(n)` | 向上取整 | `${ceil(3.1)}` → 4 |
| `floor(n)` | 向下取整 | `${floor(3.9)}` → 3 |

### 函数嵌套调用

函数可以嵌套使用，引擎会从内到外依次计算：

```yaml
# 字符串处理链
"${upper(trim(${steps.input.output.text}))}"

# 集合操作链
"${join(distinct(flatten(${steps.multiList.output.result})), ", ")}"

# 日期计算链
"${formatDate(addDays(${inputs.startDate}, ${inputs.days}), "yyyy-MM-dd")}"

# 条件表达式中使用函数
"${size(${steps.items.output.result}) > 0 && contains(${steps.status.output.result}, "ok")}"
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

工作流支持四种触发方式，可以同时配置多种触发器。

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

### Webhook 触发（webhook）

外部系统（如 GitHub、飞书机器人等）可通过 Webhook 触发工作流执行：

```yaml
triggers:
  - type: webhook
    secret: "your-webhook-secret"  # 可选，签名密钥
```

**调用方式：**

```bash
curl -X POST http://localhost:8080/api/workflows/{workflowId}/webhook \
  -H "Content-Type: application/json" \
  -d '{"key": "value"}'
```

**签名验证（可选）：**

如果配置了 `secret`，请求头需包含签名：

```bash
# 生成签名（HMAC-SHA256）
signature=$(echo -n '{"key":"value"}' | openssl dgst -sha256 -hmac "your-webhook-secret" | cut -d' ' -f2)

curl -X POST http://localhost:8080/api/workflows/{workflowId}/webhook \
  -H "Content-Type: application/json" \
  -H "X-Webhook-Signature: ${signature}" \
  -d '{"key": "value"}'
```

Webhook 触发器适合与外部系统集成，如接收 GitHub Push 事件、飞书消息卡片回调等。

### 手动触发（manual）

只能通过 API 或 CLI 手动执行：

```yaml
triggers:
  - type: manual
```

内置的「网页摘要」「会议纪要」「调研报告」三个工作流都使用手动触发，因为它们需要用户提供输入数据。

---

## 工作流变量与常量

工作流支持定义级变量，用于存储工作流级别的常量值，避免在多个步骤中硬编码。

### 定义变量

```yaml
variables:
  apiEndpoint: "https://api.example.com"
  maxRetries: 3
  timeout: 30
```

### 引用变量

在步骤中通过 `${vars.key}` 语法引用：

```yaml
steps:
  - id: call-api
    name: 调用 API
    type: tool
    toolId: http.request
    params:
      url: "${vars.apiEndpoint}/users"
      timeout: "${vars.timeout}"
```

### 变量与输入参数的区别

| 特性 | variables | inputs |
|------|-----------|-------|
| 用途 | 工作流内部的常量 | 用户提供的输入数据 |
| 来源 | 工作流定义中硬编码 | 触发时外部传入 |
| 变化频率 | 固定不变 | 每次执行可能不同 |
| 引用方式 | `${vars.key}` | `${inputs.key}` |

### 使用场景

- 存储 API 端点、认证令牌等配置
- 定义业务规则阈值
- 集中管理超时、重试次数等策略参数

---

## 工作流标签

工作流支持添加标签，用于分类和筛选。

### 定义标签

```yaml
tags:
  - "自动化"
  - "每日"
  - "通知"
```

### 标签筛选用法

在列表接口中通过标签筛选工作流：

```bash
GET /api/workflows?tags=自动化,每日
```

返回同时包含「自动化」和「每日」标签的工作流。

### 动态标签

支持在步骤中使用表达式动态生成标签：

```yaml
tags:
  - "工作流"
  - "${inputs.category}"
```

第二个标签会根据输入参数 `category` 的值动态生成。

### 使用场景

- 按业务场景分类工作流（如「财务」「运营」「技术」）
- 按触发方式分类（如「定时」「手动」「事件」）
- 按复杂程度分类（如「简单」「中等」「复杂」）

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

### 参数元数据（引导式配置）

输入参数支持元数据字段，用于前端引导式配置：

```yaml
inputs:
  category:
    type: string
    required: true
    description: 选择分类
    inputType: select                    # 前端渲染为下拉选择器
    options:                             # 下拉选项
      - value: "tech"
        label: "技术"
      - value: "life"
        label: "生活"
    placeholder: "请选择分类"            # 输入提示
  keyword:
    type: string
    required: false
    description: 搜索关键词
    placeholder: "输入关键词"             # 占位符
    example: "人工智能"                    # 示例值
```

|| 元数据字段 | 说明 |
||-----------|------|
|| inputType | 前端控件类型：select / text / number / boolean / textarea / date |
|| options | 下拉选项列表，仅 inputType 为 select 时有效 |
|| placeholder | 输入框占位提示 |
|| example | 示例值，帮助用户理解 |

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
  initialDelayMs: 500     # 初始延迟（毫秒）
  maxDelayMs: 5000        # 最大延迟（毫秒）
```

适用于网络抖动、临时不可用等可恢复错误。

**步骤级配置与全局配置的优先级：**

步骤级 `errorStrategy.retry` 配置会覆盖全局的重试配置。全局默认配置在 `application.yml` 中：

```yaml
lifepilot:
  workflow:
    retry:
      initial-delay-ms: 500   # 默认初始延迟
      max-delay-ms: 5000     # 默认最大延迟
      max-attempts: 3        # 默认最大重试次数
```

在步骤中单独配置后，以步骤级配置为准。

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

知微首次启动时会自动释放 6 个内置工作流模板到 `~/.zhiwei/workflows/` 目录。你可以直接使用、修改或删除它们。删除后重启应用会重新释放。

这些模板覆盖了工作流引擎的核心特性：ConditionStep 条件分支、LoopStep 循环处理、ParallelStep 并行执行、ApprovalStep 人工审批、ErrorStrategy 错误策略、DAG dependsOn 依赖编排。从简单到复杂递进，既可直接使用，也是学习工作流语法的参考。

### 1. 每日晨报（daily-briefing.yml）⭐ 入门

**触发方式：** 每天早上 8:00 自动执行

**功能：** 汇总今日记忆和任务提醒，根据是否有紧急事项选择不同的晨报内容，一条消息掌握全天计划。

**步骤流程：**
```
[SkillStep(记忆搜索) ∥ SkillStep(任务列表)]（DAG 并行）
  → LlmStep(生成晨报) + LlmStep(检查紧急事项)
  → ConditionStep(紧急判定)
    → then: NotifyStep(重点晨报)
    → else: NotifyStep(常规晨报)
```

**涉及的步骤类型：** skill、llm（结构化输出）、condition、notify

**适合学习：** DAG 并行数据获取、条件分支、按结果分流通知内容。

### 2. 周报生成（weekly-summary.yml）⭐ 入门

**触发方式：** 每周五下午 6:00 自动执行，或手动触发

**功能：** 汇总本周记忆和任务数据，生成周报并保存到记忆系统。

**步骤流程：**
```
[SkillStep(记忆检索) ∥ SkillStep(任务统计)]（DAG 并行）
  → LlmStep(生成周报) → SkillStep(保存到记忆)
  → NotifyStep(发送周报, skip)
```

**涉及的步骤类型：** skill、llm、notify

**适合学习：** DAG 多源并行汇聚、SkillStep 调用、skip 降级策略。

### 3. 定时知识采集（knowledge-collect.yml）⭐ 中级

**触发方式：** 每天早上 7:00 自动执行，或手动触发，需要输入采集主题

**功能：** 按主题循环搜索网络信息，去重摘要后保存到知识库，持续追踪感兴趣的领域。

**步骤流程：**
```
LlmStep(解析主题) → LoopStep(逐主题采集)
  → ToolStep(网络搜索, retry) → LlmStep(去重摘要)
  → SkillStep(保存到知识库, skip)
→ NotifyStep(通知完成)
```

**涉及的步骤类型：** loop、tool、llm、skill、notify

**适合学习：** LoopStep 循环处理、循环变量引用（loopVar）、retry + skip 错误策略组合。

### 4. 调研助手（research-assistant.yml）⭐ 中级

**触发方式：** 手动触发，需要输入调研主题和深度

**功能：** 多源并行搜索（网络 + 记忆），LLM 深度分析并质量评分，经人工审批后发布成果。

**步骤流程：**
```
[ToolStep(网络搜索, retry) ∥ SkillStep(记忆搜索, skip)]（DAG 并行）
  → LlmStep(深度分析) → LlmStep(质量检查, 结构化输出)
  → ApprovalStep(审批发布)
  → SkillStep(保存成果, skip) → NotifyStep(通知完成, skip)
```

**涉及的步骤类型：** tool、skill、llm（结构化输出）、approval、notify

**适合学习：** DAG 多源并行汇聚、人工审批流程、skip 降级策略。

### 5. 内容创作助手（content-creator.yml）⭐ 中级

**触发方式：** 手动触发，需要输入主题、内容类型、写作风格和目标字数

**功能：** 搜索素材和个人笔记，生成大纲，根据大纲复杂度选择分段精写或一次成文策略。

**步骤流程：**
```
[ToolStep(搜索素材, skip) ∥ SkillStep(搜索笔记, skip)]（DAG 并行）
  → LlmStep(生成大纲, 结构化输出) → LlmStep(复杂度判断)
  → ConditionStep(选择写作策略)
    → then: LlmStep(分段精写)
    → else: LlmStep(一次成文)
  → SkillStep(保存内容, skip) → NotifyStep(通知完成, skip)
```

**涉及的步骤类型：** tool、skill、llm（结构化输出）、condition、notify

**适合学习：** 多输入参数设计、LLM 结构化输出驱动条件分支、写作策略路由。

### 6. 目标复盘与计划（goal-review-planner.yml）⭐ 进阶

**触发方式：** 每周日晚上 8:00 自动执行，或手动触发，需要输入目标列表

**功能：** 并行获取多源进度数据，逐目标分析偏差，严重偏离时生成调整建议并请求用户审批确认，自动创建下周行动计划。这是最复杂的预置工作流，展示了工作流引擎的全部高级特性。

**步骤流程：**
```
ParallelStep(并行获取数据)
  分支1: [SkillStep(任务列表) → SkillStep(记忆检索)]
  分支2: [SkillStep(知识库搜索) → SkillStep(记忆笔记)]
→ LlmStep(解析目标列表, 结构化输出)
→ LoopStep(逐目标复盘)
  → LlmStep(分析偏差, 结构化输出)
  → ConditionStep(偏差判定)
    → 偏差 > 阈值:
      LlmStep(生成调整建议) → ApprovalStep(用户确认)
      → ConditionStep(审批结果)
        → 批准: SkillStep(创建调整后任务)
        → 拒绝: NoopStep(保持原计划)
    → 偏差 ≤ 阈值:
      LlmStep(生成鼓励) → SkillStep(创建下周任务)
→ LlmStep(汇总复盘报告)
→ SkillStep(保存报告) → NotifyStep(发送通知)
```

**涉及的步骤类型：** parallel、loop（含嵌套 condition + approval）、condition（多层嵌套）、approval、skill、llm（结构化输出）、notify、noop

**适合学习：** 这是一个综合性的进阶案例，覆盖了：
- ParallelStep 并行数据获取
- LoopStep 内嵌套 ConditionStep + ApprovalStep
- 多层条件分支（偏差判定 → 审批结果判定）
- 人工审批与自动化的结合
- 输入参数驱动行为（deviationThreshold 控制审批触发阈值）
- 多种错误策略（skip 降级）
- Skill 联动（分析结果自动创建任务）

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
    max-loop-iterations: 100               # 循环最大迭代次数
    crash-recovery-enabled: true            # 崩溃恢复开关
    scan-interval-seconds: 30               # 热加载扫描间隔（秒）
    retry:
      initial-delay-ms: 500                 # 重试初始延迟（毫秒）
      max-delay-ms: 5000                    # 重试最大延迟（毫秒）
      max-attempts: 3                       # 最大重试次数
    approval:
      default-timeout-seconds: 86400        # 审批默认超时（秒，24 小时）
      auto-approve-on-timeout: false        # 超时后是否自动批准
    event-audit:
      enabled: true                         # 是否启用事件审计记录
      retention-days: 90                    # 事件保留天数
```

---

## DAG 依赖图数据

工作流引擎提供 DAG（有向无环图）数据接口，用于前端可视化展示步骤之间的依赖关系。

### 获取 DAG 数据

```bash
GET /api/workflows/{id}/dag
```

**响应示例：**

```json
{
  "nodes": [
    {"id": "step-1", "name": "获取数据", "type": "skill"},
    {"id": "step-2", "name": "处理数据", "type": "llm"},
    {"id": "step-3", "name": "发送通知", "type": "notify"}
  ],
  "edges": [
    {"from": "step-1", "to": "step-2"},
    {"from": "step-2", "to": "step-3"}
  ]
}
```

- `nodes`：工作流中的所有步骤
- `edges`：步骤之间的依赖关系（from → to）

此接口主要用于前端工作流可视化编辑器，展示步骤的执行顺序和依赖关系。

---

## 试运行（Dry-Run）模式

在正式执行工作流之前，可以先进行试运行（Dry-Run）测试，模拟执行流程但不实际调用外部服务。

### 试运行接口

```bash
POST /api/workflows/{id}/dry-run
Content-Type: application/json

{
  "inputs": {
    "topic": "测试主题"
  }
}
```

**响应示例：**

```json
{
  "workflowId": "research-assistant",
  "dagValid": true,
  "traces": [
    {
      "stepId": "search",
      "stepName": "搜索信息",
      "status": "SIMULATED",
      "output": "（模拟输出）将搜索「测试主题」相关内容"
    },
    {
      "stepId": "analyze",
      "stepName": "分析内容",
      "status": "PENDING",
      "output": null
    }
  ],
  "warnings": []
}
```

**响应字段说明：**

- `dagValid`：DAG 是否有效（无环）
- `traces`：每个步骤的模拟执行轨迹
  - `status`：SIMULATED（已模拟）、PENDING（待执行）
  - `output`：模拟的输出内容
- `warnings`：执行警告信息（如缺失的输入参数）

试运行模式适合：
- 测试工作流逻辑是否正确
- 验证表达式是否能正确解析
- 预览步骤执行顺序

---

## 执行统计与指标

工作流引擎提供执行统计接口，帮你了解工作流的运行状况。

### 工作流执行统计

```bash
GET /api/workflows/{id}/stats
```

**响应示例：**

```json
{
  "workflowId": "daily-briefing",
  "totalExecutions": 150,
  "successCount": 142,
  "failedCount": 8,
  "successRate": 0.947,
  "avgDurationSeconds": 12.5,
  "lastExecutionTime": "2026-03-13T08:00:00Z"
}
```

### 步骤执行统计

```bash
GET /api/workflows/{id}/step-stats
```

**响应示例：**

```json
{
  "workflowId": "daily-briefing",
  "stepStats": [
    {
      "stepId": "search-memory",
      "stepName": "搜索记忆",
      "executionCount": 150,
      "successCount": 150,
      "avgDurationMs": 120,
      "errorCount": 0
    },
    {
      "stepId": "send-notify",
      "stepName": "发送通知",
      "executionCount": 145,
      "successCount": 142,
      "avgDurationMs": 350,
      "errorCount": 3
    }
  ]
}
```

这些统计信息有助于：
- 监控工作流健康状况
- 识别执行瓶颈
- 优化工作流性能

---

## 实例上下文查看

工作流执行过程中，每个步骤的输出都会保存在实例上下文中。可以通过 API 查看执行详情和步骤输出。

### 查看实例上下文

```bash
GET /api/workflows/executions/{instanceId}/context
```

**响应示例：**

```json
{
  "inputs": {
    "topic": "AI发展趋势"
  },
  "vars": {
    "apiEndpoint": "https://api.example.com"
  },
  "steps": {
    "search": {
      "output": {
        "result": "搜索到 10 条相关内容"
      },
      "status": "COMPLETED"
    },
    "analyze": {
      "output": null,
      "status": "PENDING"
    }
  }
}
```

### 查看单个步骤输出

```bash
GET /api/workflows/executions/{instanceId}/steps/{stepId}/output
```

**响应示例：**

```json
{
  "stepId": "search",
  "stepName": "搜索信息",
  "status": "COMPLETED",
  "output": {
    "result": "搜索到 10 条相关内容",
    "count": 10,
    "items": [...]
  }
}
```

**使用场景：**

- 调试工作流执行问题
- 检查某个步骤的输出是否符合预期
- 追溯工作流执行历史

---

## 工作流导入导出

支持将工作流导出为 YAML 文件，或从 YAML 文件导入工作流。

### 导出单个工作流

```bash
GET /api/workflows/{id}/export
```

返回 YAML 格式的工作流定义。

### 批量导出工作流

```bash
GET /api/workflows/export?ids=workflow-1,workflow-2,workflow-3
```

返回 ZIP 压缩包，包含多个 YAML 文件。

### 导入工作流

```bash
POST /api/workflows/import
Content-Type: application/json

{
  "yaml": "id: my-workflow\nname: 我的工作流\n...",
  "overwrite": false
}
```

- `yaml`：工作流的 YAML 内容
- `overwrite`：是否覆盖已存在的工作流（默认 false）

导入成功后，工作流会自动注册到系统中。

---

## YAML 校验增强

工作流引擎提供 YAML 语法校验功能，在导入或保存工作流前进行验证。

### 校验接口

```bash
POST /api/workflows/validate
Content-Type: application/json

{
  "yaml": "id: my-workflow\nname: 测试工作流\n..."
}
```

**响应示例（校验通过）：**

```json
{
  "valid": true,
  "errors": [],
  "warnings": [
    {
      "stepId": "step-1",
      "message": "步骤未配置 timeoutSeconds，建议为长时间运行的步骤配置超时时间"
    }
  ]
}
```

**响应示例（校验失败）：**

```json
{
  "valid": false,
  "errors": [
    {
      "stepId": "step-2",
      "message": "步骤类型「llm」缺少必填字段「scene」"
    }
  ],
  "warnings": []
}
```

**响应字段说明：**

- `valid`：校验是否通过
- `errors`：错误列表（导致校验失败）
- `warnings`：警告列表（不影响校验通过，但建议修复）

校验内容包括：
- YAML 语法正确性
- 必填字段是否缺失
- 步骤类型是否正确
- 表达式语法是否有效
- DAG 是否有环

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

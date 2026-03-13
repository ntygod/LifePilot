# WorkflowCreator Skill

> 本 Skill 帮助用户通过对话方式创建完整的工作流定义，并自动注册到知微系统。

## 触发条件

用户请求以下内容时自动触发：
- 创建工作流
- 新建自动化流程
- 创建一个 Workflow
- 定义一个业务流程

## 功能概述

WorkflowCreator 是一个对话式工作流创建助手，通过引导用户完成以下步骤来创建工作流：

1. 收集基本信息（名称、描述、用途）
2. 确定触发方式（手动、定时、事件、Webhook）
3. 设计步骤流程（步骤类型、依赖关系）
4. 配置输入参数
5. 配置高级选项（错误处理、超时、重试）
6. 生成并注册工作流

## 对话流程

### 步骤 1：收集基本信息

询问用户以下问题：
- 工作流名称（name）：用户希望如何称呼这个工作流？
- 工作流描述（description）：这个工作流是用来做什么的？
- 使用场景：什么时候会用到这个工作流？

### 步骤 2：确定触发方式

询问用户工作流应该如何触发：

**选项 A：手动触发**
- 适用场景：需要用户主动启动的工作流
- 需要确定：需要哪些输入参数？

**选项 B：定时触发（cron）**
- 适用场景：周期性执行的任务
- 需要确定：
  - 执行频率（每天/每周/每月）
  - 具体时间点
  - 提供 cron 表达式示例供用户参考

**选项 C：事件触发（event）**
- 适用场景：响应系统事件
- 需要确定：监听什么事件？（如 TodoCreatedEvent）

**选项 D：Webhook 触发**
- 适用场景：与外部系统集成
- 需要确定：是否需要签名验证？

### 步骤 3：设计步骤流程

询问用户工作流需要哪些步骤：

1. **需要几个步骤？**
2. **每个步骤做什么？**
   - 调用 Skill（skill）
   - 调用工具（tool）
   - 调用 LLM 生成内容（llm）
   - 判断条件（condition）
   - 循环处理（loop）
   - 并行执行（parallel）
   - 发送通知（notify）
   - 人工审批（approval）
   - 等待一段时间（wait）

3. **步骤之间的执行顺序？**
   - 顺序执行
   - 有条件分支
   - 需要并行处理

**推荐使用内置工作流模板：**
- 如果用户不确定如何设计，可以推荐使用内置模板：
  - 智能内容审核（content-review.yml）
  - 批量任务处理（batch-task.yml）
  - 每日待办提醒（daily-reminder.yml）
  - 定时知识采集（knowledge-collect.yml）
  - 调研助手（research-assistant.yml）
  - 多源数据分析（data-insight.yml）
  - 周报自动生成（weekly-summary.yml）
  - 习惯追踪周报（habit-tracker.yml）

### 步骤 4：配置输入参数

如果选择手动触发或定时触发，需要定义输入参数：

询问每个参数：
- 参数名称
- 参数类型（string / list / number / boolean）
- 是否必填
- 默认值（可选）
- 参数描述

**高级选项：**
- inputType：前端控件类型（select / text / number / textarea）
- options：下拉选项（当 inputType 为 select 时）
- placeholder：输入提示
- example：示例值

### 步骤 5：配置高级选项

**错误处理策略：**
- fail：失败时终止工作流（默认）
- skip：失败时跳过继续执行
- retry：失败时重试
- compensate：失败时执行补偿操作

**超时配置：**
- 步骤级超时：单个步骤的最大执行时间
- 全局默认超时：在 application.yml 中配置

**重试策略（当 errorStrategy 为 retry 时）：**
- maxAttempts：最大重试次数
- initialDelayMs：初始延迟（毫秒）
- maxDelayMs：最大延迟（毫秒）

### 步骤 6：生成工作流

根据收集的信息生成 YAML 文件：

```yaml
id: ${workflowId}
name: ${workflowName}
description: ${description}
version: "1.0"
triggers:
  - type: ${triggerType}
    ${triggerConfig}
inputs:
${inputsConfig}
variables:
${variablesConfig}
metadata:
  category: ${category}
  complexity: ${complexity}
steps:
${stepsConfig}
```

### 步骤 7：保存并注册

1. 将生成的 YAML 保存到 `~/.zhiwei/workflows/${workflowId}.yml`
2. 调用校验接口验证语法：`POST /api/workflows/validate`
3. 通知用户工作流已创建成功
4. 提供执行方式说明

## 输出格式

生成的 YAML 文件示例：

```yaml
id: daily-news-digest
name: 每日新闻摘要
description: 每天自动搜索热点新闻并生成摘要
version: "1.0"
triggers:
  - type: cron
    cron: "0 30 7 * * *"
inputs:
  topic:
    type: string
    required: false
    defaultValue: "今日热点新闻"
    description: 搜索主题
  maxResults:
    type: number
    required: false
    defaultValue: 10
    description: 最大结果数
tags:
  - "自动化"
  - "每日"
  - "资讯"
steps:
  - id: search-news
    name: 搜索新闻
    type: tool
    toolId: web.search
    params:
      query: "${inputs.topic}"
      maxResults: "${inputs.maxResults}"
  - id: generate-summary
    name: 生成摘要
    type: llm
    scene: workflow
    prompt: |
      请根据以下搜索结果生成新闻摘要：

      ${steps.search-news.output.result}

      要求：
      1. 选取最重要的 ${inputs.maxResults} 条新闻
      2. 每条新闻用 2-3 句话概括
      3. 按重要性排序
  - id: send-notification
    name: 发送通知
    type: notify
    targetUserId: "${inputs.userId}"
    content: "${steps.generate-summary.output.result}"
    contentType: MARKDOWN
    urgency: NORMAL
    errorStrategy:
      type: skip
```

## 错误处理

如果用户提供的需求不完整或存在冲突：
- 明确指出问题所在
- 提供具体的修改建议
- 不要生成可能产生错误的工作流

## 参考资料

创建工作流时，可以参考知微工作流用户指南：
- 文档位置：`docs/guides/workflow-guide.md`
- 包含内容：
  - 10 种步骤类型详解
  - 表达式语法与函数
  - 触发器配置
  - 错误处理策略
  - 高级特性

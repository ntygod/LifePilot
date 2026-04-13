---
id: workflow-creator
name: "工作流创建助手"
description: "对话式工作流 YAML 编排。用户说「创建工作流」「自动化流程」「编排任务」「工作流」「YAML」「每天检查…并…」「条件分支」时使用。简单定时任务用 cron-scheduler，单步操作直接执行不需要工作流。"
version: "2.0.0"
suggested-tools:
  - workflow
  - file.write
---

# 工作流创建指南

通过对话引导用户完成工作流定义，生成 YAML 文件并保存。

## 适用场景

- 多步骤自动化（如"每天检查待办并发通知"）
- 条件分支流程
- 定时触发任务编排
- 需要编排多个工具/Skill 的复合任务

## 不适用场景

- 简单定时任务 → 用 cron-scheduler
- 单步操作 → 直接执行
- 代码级自动化 → 用 code-assistant
- 单条提醒/待办 → 直接记录

## 适用性判断

在创建前先判断：是否涉及 2 个以上步骤的编排、条件判断或周期性触发？不是则引导到更简单的方案。

## 工作流

### 1. 对话引导收集信息

按以下顺序，每轮只问一个问题：

1. **目标**：用户想自动化什么？
2. **触发方式**：手动 / 定时 / 事件 / Webhook？
3. **步骤设计**：需要哪些步骤？
4. **输入参数**：是否需要用户提供输入？

用户描述足够清晰时可跳过逐步询问，直接生成。

### 2. 生成 YAML

基本结构：

```yaml
id: workflow-id          # kebab-case
name: 中文显示名称
description: 功能描述
version: "1.0"
triggers:
  - type: manual         # manual / cron / event / webhook
inputs:
  paramName:
    type: string
    required: true
    description: 参数说明
steps:
  - id: step-id
    name: 步骤名称
    type: skill           # 见步骤类型
```

### 3. 校验（推荐）

```bash
shell.exec(command="curl -s -X POST http://localhost:8080/api/workflows/validate -H 'Content-Type: application/json' -d '{\"yamlContent\": \"YAML内容\"}'")
```

### 4. 保存

```
file.write(path="~/.zhiwei/workflows/{workflowId}.yml", content="YAML内容")
```

知微会在 30 秒内自动检测并注册。

## 步骤类型速查

| 类型 | 用途 | 关键字段 |
|------|------|---------|
| skill | 调用 Skill | skillId, params |
| tool | 调用工具 | toolId, params |
| llm | LLM 生成/分析 | scene, prompt, capability |
| condition | 条件分支 | condition, then, else |
| loop | 循环遍历 | items, loopVar, body |
| parallel | 并行执行 | branches（双层列表） |
| notify | 发送通知 | targetUserId, content |
| approval | 人工审批 | message, approvers, timeoutSeconds |
| wait | 等待 | durationSeconds |
| sub-workflow | 子工作流 | workflowId, params |

## 表达式语法

- `${inputs.paramName}` — 输入参数
- `${steps.stepId.output.result}` — 步骤输出
- `${loopVar}` / `${loopVar_index}` — 循环变量
- 内置函数：`len()`, `upper()`, `now()`, `size()`, `min()`

## 关键 YAML 模式

### condition 分支

```yaml
- id: check-risk
  type: condition
  condition: "${steps.risk-analysis.output.result.riskLevel} == 'high'"
  dependsOn: [risk-analysis]
  then:
    - id: need-approval
      type: approval
      message: "高风险内容需要审批"
      approvers: [admin]
      timeoutSeconds: 86400
  else:
    - id: auto-pass
      type: noop
```

### loop 循环

```yaml
- id: process-items
  type: loop
  items: "${steps.fetch-data.output.result}"
  loopVar: item
  body:
    - id: handle-item
      type: llm
      scene: agent_reasoning
      prompt: "处理：${item}"
```

### parallel 并行

```yaml
- id: parallel-analysis
  type: parallel
  branches:
    - - id: branch-a
        type: llm
        prompt: "情感分析：${inputs.content}"
    - - id: branch-b
        type: llm
        prompt: "关键词提取：${inputs.content}"
```

## 规则

- 工作流 ID 使用英文 kebab-case，显示名称使用中文
- cron 表达式 6 位格式（含秒）
- 子工作流最大嵌套 3 层，循环最大迭代 100 次，步骤默认超时 300 秒
- `notify` 仅在有结果需要告知用户时使用，无结果时用 `condition + noop` 静默结束
- 非嵌套步骤间通过 `dependsOn` 控制执行顺序
- 详细语法参考：`docs/guides/workflow-guide.md`

## 常见错误处理

- **YAML 语法错误** → 先用校验接口检查，再修正
- **步骤依赖循环** → 检查 dependsOn 关系，确保无环
- **表达式解析失败** → 确认引用的 stepId 和字段路径正确

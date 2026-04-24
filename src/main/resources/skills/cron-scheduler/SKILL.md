---
name: cron-scheduler
description: 当用户要创建精确时间调度的定时任务（每天/每周/每小时）、周期性提醒或重复执行的 Agent 任务时使用。关键词：每天早上、每周一、定时提醒、定时任务、每小时、定时搜索、cron、定期执行。模糊持续关注类需求记到记忆，一次性任务直接执行，工作流编排用 workflow-creator。
version: 2.0.0
metadata:
  zhiwei:
    category: automation
    priority: normal
    tags:
      - cron
      - schedule
      - reminder
      - timer
      - recurring
    suggested_tools:
      - cron
---

# 定时任务调度指南

创建和管理 Cron 定时任务。

## 适用场景

- 精确时间调度："每天早上 8 点"、"每周一"、"每小时"
- 定时提醒："提醒我每天…"
- 周期性任务："每天搜索最新 AI 资讯"、"每周生成周报"

## 不适用场景

- 模糊关注类需求 → 记录到记忆或工作区，不强行创建 cron
- 一次性任务 → 直接执行
- 工作流编排 → 用 workflow-creator

## 工作流

### 创建定时任务

```
cron(action="create", name="每日AI资讯", schedule="0 0 8 * * *", instruction="搜索最新AI资讯并发送摘要")
```

- `name`：任务名称（中文）
- `schedule`：Spring 6 位 Cron（秒 分 时 日 月 周）
- `instruction`：Agent 执行时的 prompt 指令

### 常用 Cron 表达式

| 表达式 | 含义 |
|--------|------|
| `0 0 8 * * *` | 每天早上 8 点 |
| `0 30 9 * * MON-FRI` | 工作日 9:30 |
| `0 0 */2 * * *` | 每 2 小时 |
| `0 0 8 * * MON` | 每周一早上 8 点 |
| `0 0 8 1 * *` | 每月 1 号早上 8 点 |

### 查看任务

```
cron(action="list")
cron(action="list", status="active")
```

### 修改任务

```
cron(action="update", taskId="task-xxx", schedule="0 0 9 * * *")
cron(action="update", taskId="task-xxx", status="paused")
```

### 删除任务

```
cron(action="remove", taskId="task-xxx")
```

### 静默协议

任务执行后如果没有需要汇报的内容（例行检查一切正常），回复 `TASK_SILENT`（必须出现在回复的开头或结尾）。

## 规则

- Cron 表达式使用 Spring 6 位格式（含秒），不是 Linux 5 位格式
- 任务如需执行高风险操作（删文件、联网、浏览器自动化），创建时触发预授权
- 暂停用 `status="paused"`，恢复用 `status="active"`
- 删除任务会同时删除执行日志，执行前确认

## 常见错误处理

- **Cron 表达式错误** → 确认是 6 位格式，检查秒/分/时顺序
- **任务不触发** → 确认 status 是 active，检查 schedule 是否正确
- **任务执行失败** → 查看执行日志，检查 instruction 是否清晰

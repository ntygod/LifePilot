---
name: cron-scheduler
description: 当用户要创建精确时间调度的定时任务（每天/每周/每小时）、周期性提醒或重复执行的 Agent 任务时使用。关键词：每天早上、每周一、定时提醒、定时任务、每小时、定时搜索、cron、定期执行、定期提醒、自动跑。模糊持续关注类需求记到记忆，一次性任务直接执行。
version: 2.1.0
metadata:
  zhiwei:
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

通过 `cron` 工具的 `action` 参数路由所有调度操作。

## 适用场景

- 精确时间调度（"每天早上 8 点"、"每周一 10 点"）
- 周期性提醒（"每隔 2 小时提醒我喝水"）
- 周期性 Agent 任务（"每天搜索最新 AI 资讯"、"每周生成周报"）
- 暂停 / 恢复 / 查询 / 删除既有任务

## 不适用场景

- 模糊持续关注（"留意 X 这事"）→ `memory(action="create")` 记到记忆，由主动提醒引擎处理
- 一次性任务（"等会提醒我做 X"）→ 直接执行或用工作区登记，不创 cron
- 一次性较复杂任务的多步执行 → daily-manager 协调，不创 cron

## 工作流（按用户表达分流）

| 用户表达 | action |
|---|---|
| "每天/每周/每小时 X" | `create` |
| "看看我有哪些定时任务" | `list` |
| "X 任务先不跑了 / 暂停" | `update` 配 `status="paused"` |
| "X 任务恢复" | `update` 配 `status="active"` |
| "改下 X 的时间 / 内容" | `update` |
| "把 X 这个定时任务删了" | `remove` |

各路径要点：

- **任务指令清楚化**：`instruction` 写清触发后做什么，避免歧义
- **高风险动作预声明**：任务涉及删文件、联网下载、桌面自动化时，创建时让用户预授权
- **暂停优先于删除**：用户说"先不跑了"用 `update` 配 `status="paused"`，不要直接 `remove`
- **删除会清执行日志**：告诉用户后再执行

## 静默协议（独有）

任务被定时调度自动触发执行后，无内容要汇报（例行检查一切正常）时回复 `TASK_SILENT`（出现在回复开头或结尾），系统不推消息给用户。仅自动触发场景生效，用户在会话中显式问任务结果时正常回答。

## 详细参考

- 各 action 命令模板 + Cron 表达式速查 + 错误处理：`{skill_dir}/references/cron-reference.md`

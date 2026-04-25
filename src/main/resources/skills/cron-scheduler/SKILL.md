---
name: cron-scheduler
description: 当用户要创建精确时间调度的定时任务（每天/每周/每小时）、周期性提醒或重复执行的 Agent 任务时使用。关键词：每天早上、每周一、定时提醒、定时任务、每小时、定时搜索、cron、定期执行。模糊持续关注类需求记到记忆，一次性任务直接执行。
version: 2.0.0
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

创建和管理 Cron 定时任务。所有操作通过 `cron` 工具的 `action` 参数路由。

## 适用场景

- 精确时间调度："每天早上 8 点"、"每周一"、"每小时"
- 定时提醒："提醒我每天…"
- 周期性任务："每天搜索最新 AI 资讯"、"每周生成周报"

## 不适用场景

- 模糊关注类需求 → 记录到记忆或工作区，不强行创建 cron
- 一次性任务 → 直接执行

## 工作流

1. **选 action**：`create` / `list` / `update` / `remove`
2. **Cron 表达式**：Spring **6 位**格式（秒 分 时 日 月 周），不是 Linux 5 位
3. **高风险操作预授权**：任务涉及删文件、联网、浏览器自动化时，创建时触发授权
4. **暂停 / 恢复**：`update` 配 `status="paused"` / `"active"`，不用 remove
5. **删除前确认**：会同时清执行日志
6. **静默协议**：任务无新内容要报时，回复 `TASK_SILENT`

## 详细参考

- 创建 / 查询 / 修改 / 删除的命令模板 + Cron 表达式速查 + 错误处理：`{skill_dir}/references/cron-reference.md`
</content>
</invoke>
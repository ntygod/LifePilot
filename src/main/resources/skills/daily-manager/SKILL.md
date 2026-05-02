---
name: daily-manager
description: 当用户要做多步任务规划、优先级排序、跨 Skill 协调执行、生成日报/周报/月报或汇总多个领域信息时使用。
version: 3.0.0
metadata:
  zhiwei:
    priority: normal
    tags:
      - planning
      - task-management
      - priority
      - daily-report
      - weekly-report
      - monthly-report
      - coordination
    suggested_tools:
      - memory
      - skill_load
      - notify
      - web_search
      - file_read
      - file_write
---

# 日常管理指南

按用户表达分流到不同路径，把通用计划/进度/协调收敛在这里。

## 适用场景

- 主动规划：当天 / 一周 / 一月做什么、按优先级排
- 调整既有：把任务挪到别的时间、改优先级、删 / 撤一项
- 进度回顾：现在做到哪、昨天/上周完成了什么
- 跨领域协作：一句指令涉及多个专业 Skill（调研→写作、读数据→画图）
- 报告生成：日报 / 周报 / 月报 / 多源信息汇总

## 不适用场景

- 精确时间表达式（"每天早上 8 点"）→ cron-scheduler
- 单一专业领域任务（只写文章 / 只查数据库 / 只调试 API）→ 直接加载对应 Skill，不绕本 Skill
- 闲谈式表达意图（"我打算 X"）→ memory(action="create") 记一笔，不规划

## 工作流（按用户表达分流）

| 用户表达 | 路径 |
|---|---|
| 主动规划（今天/本周做什么） | 拆 → 排 → 写入 |
| 调整既有（挪、改、撤） | 查 → 改 / 撤 |
| 进度回顾（做到哪、昨天做了啥） | 查 → 汇总，不写入 |
| 跨领域协作（A 然后 B） | 加载多 Skill 串行 |
| 报告生成（日 / 周 / 月报） | 查 → 汇总 → 落盘 |

## 各路径要点

- **拆 + 排**：列任务，按紧急-重要分四档（紧急且重要 / 重要不紧急 / 紧急不重要 / 可暂缓）；列完先跟用户对一下再写入
- **查**：`memory(action="search")` 找事实实体，`memory(action="recall")` 取历史对话片段，组合使用更全
- **写 / 改 / 撤**：`memory(action="create" / "update" / "delete")`；写入要带截止日、优先级、依赖
- **跨 Skill 协调**：`skill_load(names=[...])` 一次 ≤ 3 个，按依赖串行；前序输出作为后序输入
- **落盘**：长期产物（周报、汇总文档）`file_write` 到工作区或用户指定路径
- **异步通知**：用户已离开会话、任务跨多轮才用 `notify`，会话内回复直接说

## 协作原则（仅本 Skill 强调）

- 决策节点向用户问，不替用户拍板
- 信息缺失时主动追问，不假设
- 跨 Skill 协调失败（依赖 Skill 不可用 / 中途出错）→ 降级为手动步骤指导

## 详细参考

- 5 种路径的真实场景示例与分流判据：`{skill_dir}/references/coordination-patterns.md`

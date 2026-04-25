---
name: daily-manager
description: 当用户要做多步任务规划、优先级排序、跨 Skill 协调执行、生成日报/周报/月报或汇总多个领域信息时使用。关键词：安排一下、今天做什么、今天的事、帮我规划、任务排优先级、整理待办、列一下、生成日报、生成周报、生成月报、汇总一下、任务分解。定时任务管理用 cron-scheduler，单一领域深度任务用对应专业 Skill，模糊持续关注记到记忆。
version: 2.1.0
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
      - skill.load
      - notify.send_message
      - web.search
      - file.read
      - file.write
---

# 日常管理指南

协调多个 Skill 和工具帮用户管理日常事务、规划任务、整理信息。本 Skill 是协调者，**复杂业务下沉到对应专业 Skill**。

## 适用场景

- 当天 / 一周 / 一月任务规划与优先级排序
- 多步骤跨领域任务的拆解 + 串接（例如调研→写作→发送）
- 日报 / 周报 / 月报生成
- 多源信息汇总成一份产物

## 不适用场景

- 用户给的是精确时间表达式（"每天早上 8 点"）→ cron-scheduler
- 用户只在做一件专业事（写文章 / 查数据库 / 调试 API）→ 直接加载对应专业 Skill，不要绕本 Skill
- 用户只是聊"我接下来打算 X"等闲谈类表达 → 记到 memory，不规划

## 工作流

1. **取上下文**：`memory(action="search")` 拿用户当前的目标 / 进度 / 偏好
2. **拆解 + 排序**：列任务，按紧急-重要分档，**给用户看 1-3 句的拆解结果，等他点头再继续**
3. **多 Skill 协调**：涉及专业领域时 `skill.load(names=["x","y"])` 加载（一次最多 3 个），按依赖顺序串起来
4. **执行 + 汇报**：每完成一个子步骤汇报进展，不闷头一口气做完
5. **沉淀**：关键决定 / 完成的项 `memory(action="create")` 记下来；最终产物 `file.write` 落盘
6. **可选通知**：用户离开了或任务跨多轮时 `notify.send_message` 推消息

## 协作原则

- 决策节点向用户问，不自行替用户拍板
- 信息缺失时主动追问，不假设
- 跨 Skill 协调失败（依赖 Skill 不可用 / 中途出错）时降级为手动步骤指导

## 详细参考

- 三种协调模式（任务规划 / 跨 Skill / 信息汇总）的真实场景示例：`{skill_dir}/references/coordination-patterns.md`

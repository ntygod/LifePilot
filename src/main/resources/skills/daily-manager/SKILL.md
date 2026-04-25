---
name: daily-manager
description: 当用户要做多步任务规划、优先级排序、跨 Skill 协调执行、生成日报/周报或汇总多个领域的信息时使用。关键词：安排一下、今天做什么、帮我规划、任务排优先级、整理待办、生成周报、汇总一下、任务分解。定时任务管理用 cron-scheduler，单一领域深度任务用对应专业 Skill，模糊持续关注记到记忆。
version: 2.0.0
metadata:
  zhiwei:
    category: automation
    priority: normal
    tags:
      - planning
      - task-management
      - priority
      - weekly-report
      - coordination
    suggested_tools:
      - memory
      - notify
      - web.search
      - file.read
      - file.write
---

# 日常管理指南

协调多个 Skill 和工具，帮助用户管理日常事务、规划任务和整理信息。

## 适用场景

- 每日任务规划和优先级排序
- 多步骤复杂任务的分解和协调
- 信息汇总和日报/周报生成
- 跨 Skill 协作（如调研 + 写作 + 发送）

## 不适用场景

- 定时任务管理 → 用 cron-scheduler
- 模糊持续关注类需求 → 记录到记忆
- 单一领域的深度任务 → 用对应专业 Skill

## 工作流

1. **任务规划**：`memory.search` 取上下文 → 和用户确认 → 按紧急-重要排序 → `memory.create` 记录
2. **多 Skill 协调**：识别涉及 Skill → 确定依赖顺序 → 逐步加载执行 → 汇总结果 → `notify` 通知
3. **信息汇总**：`memory.search` + `web.search` + `file.read` → `file.write` 写汇总
4. **分步汇报**：每个子步骤完成后向用户汇报进展，不闷头一口气做完
5. **决策节点**：遇到需要用户决策的节点主动询问，不自行决定

## 详细参考

- 三种协调模式（任务规划 / 多 Skill / 信息汇总）示例与错误处理：`{skill_dir}/references/coordination-patterns.md`
</content>
</invoke>
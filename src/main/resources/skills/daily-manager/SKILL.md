---
id: daily-manager
name: "日常管理"
description: "多步任务规划、优先级排序与跨 Skill 协调。用户说「安排一下」「今天做什么」「帮我规划」「任务排优先级」「整理一下待办」「生成周报」「汇总一下」时使用。不适用于定时任务（用 cron-scheduler）或单一领域深度任务（用对应专业 Skill）。"
version: "2.0.0"
suggested-tools:
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

### 任务规划模式

1. 用 `memory(action="search")` 获取当前上下文和已有待办
2. 与用户确认任务列表
3. 按紧急-重要矩阵排序，分解子步骤
4. 用 `memory(action="create")` 记录任务计划

### 多 Skill 协调模式

当用户需求涉及多个领域时：

1. 分析需求，识别涉及的 Skill
2. 确定执行顺序（依赖关系）
3. 逐步加载对应 Skill 执行
4. 汇总各步骤结果
5. 用 `notify` 通知用户完成情况

示例：「帮我调研 X 技术，写一份报告，保存到文件」
→ 加载 research-assistant 调研 → 加载 content-creator 撰写 → `file.write` 保存 → `notify` 通知

### 信息汇总模式

```
memory(action="search", query="本周完成事项")
web.search(query="补充信息关键词")
file.read(path="notes/weekly.md")
file.write(path="reports/weekly-summary.md", content="汇总内容")
```

## 规则

- 复杂任务先分解为可执行的子步骤，每步完成后向用户汇报进展
- 遇到需要用户决策的节点主动询问，不自行决定
- 重要信息记录到记忆系统，避免跨会话遗忘
- 协调多个 Skill 时按依赖顺序逐个执行，不跳步

## 常见错误处理

- **任务冲突** → 提示用户调整优先级
- **依赖 Skill 不可用** → 降级为手动步骤指导
- **信息不足** → 主动向用户询问缺失信息

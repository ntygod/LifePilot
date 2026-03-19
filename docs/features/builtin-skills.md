# 内置 Skill 插件 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.skill.builtin`
> **最后更新**：2026-03

## 1. 功能概述

知微预装核心内置 Skill，覆盖记忆管理和自主任务执行场景。Agent 通过 `skills` 工具发现和激活这些 Skill，获取专业指令和工具列表后精准完成任务。内置 Skill 的工具直接操作本地数据，执行确定性强、响应快速。

> **变更说明**：原有的 Todo、Schedule、Habit 三个内置 Skill 已废弃并删除，其功能由自主任务执行模块（`com.lifepilot.agent.task`）替代。自主任务执行基于 TASKS.md 文件管理，支持 cron 定时和条件触发。

## 2. 核心特性

### 2.1 记忆管理（Memory Skill）

管理长期记忆，支持混合检索、实体创建、关系标签、时间线查询和关联查询。

| 工具 | 说明 | 关键参数 |
|------|------|---------|
| `builtin.memory.search` | 搜索记忆 | query（混合检索：向量+FTS5+图遍历） |
| `builtin.memory.create` | 创建记忆 | name、entityType（11 种类型） |
| `builtin.memory.tag` | 添加标签 | sourceEntityId、targetEntityId、relationType |
| `builtin.memory.timeline` | 时间线查询 | timePoint（默认当前时间） |
| `builtin.memory.relate` | 关联查询 | entityId、maxDepth（默认 2 跳） |

Memory Skill 是唯一直接操作记忆系统的内置 Skill，条件装配依赖 HybridRetriever 和 SemanticMemory 可用。

### 2.2 自主任务执行（Task Skill）

管理自主执行的定时和条件任务，基于 `~/.zhiwei/TASKS.md` 文件。

| 工具 | 说明 | 关键参数 |
|------|------|---------|
| `builtin.task.create` | 创建任务 | name、trigger（cron/conditional）、schedule/condition、instruction |
| `builtin.task.list` | 查询任务列表 | — |
| `builtin.task.update` | 更新任务 | taskId + 任意可更新字段 |
| `builtin.task.remove` | 删除任务 | taskId |

任务支持两种触发方式：cron 定时触发（精确到秒级）和 conditional 条件触发（周期性检查条件是否满足）。任务执行采用 TASK_SILENT 协议静默运行，仅在需要时通过通知系统告知用户。

## 3. 使用场景

**场景一：记忆关联查询**

用户说"张总上次提到了什么"，Agent 调用 `builtin.memory.search` 搜索相关记忆，再调用 `builtin.memory.relate` 查找关联实体，提供完整的上下文信息。

**场景二：创建定时任务**

用户说"每天早上 8 点帮我查看天气并总结"，Agent 调用 `builtin.task.create` 创建 cron 任务，HeartbeatScheduler 自动注册精确定时器，到时间后静默执行并通知用户。

**场景三：条件触发任务**

用户说"当 GitHub 仓库有新 issue 时通知我"，Agent 创建 conditional 任务，HeartbeatScheduler 周期性检查条件，满足时自动执行。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.skills.enabled` | `true` | 控制所有内置 Skill 的注册 |
| `lifepilot.agent.task.enabled` | `true` | 自主任务执行总开关 |

MemorySkillProvider 始终注册，但依赖记忆系统 Bean 的可用性（`@ConditionalOnBean`）。

## 5. 限制与未来方向

**当前限制**：
- 内置 Skill 之间无直接协作机制，跨 Skill 协作依赖 Agent 层面的工具调用编排
- 自主任务的条件评估依赖 LLM，LLM 不可用时条件任务暂停

**未来方向**：
- 更多内置 Skill：笔记管理、财务记账等
- 任务执行结果的智能摘要和趋势分析

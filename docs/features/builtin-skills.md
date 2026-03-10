# 内置 Skill 插件 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.skill.builtin`
> **最后更新**：2026-03

## 1. 功能概述

知微预装四个核心内置 Skill，覆盖个人生产力的核心场景。Agent 通过 `skills` 工具发现和激活这些 Skill，获取专业指令和工具列表后精准完成任务。内置 Skill 的工具直接操作 SQLite 数据库，执行确定性强、响应快速。

其中 Todo、Schedule、Habit 三个 Skill 实现 `ProactiveSkillProvider` 接口，自动向主动推理引擎贡献信号源和候选提供者，实现"Skill 自包含主动推理能力"的插件化架构。每个内置 Skill 支持独立的启用/禁用配置开关。

## 2. 核心特性

### 2.1 待办管理（Todo Skill）

管理待办事项的完整生命周期，支持创建、查询、更新、删除和完成操作。

| 工具 | 说明 | 关键参数 |
|------|------|---------|
| `builtin.todo.create` | 创建待办 | title（必填）、priority、dueDate、tags |
| `builtin.todo.list` | 查询列表 | status、priority 过滤 |
| `builtin.todo.get` | 查询详情 | id |
| `builtin.todo.update` | 更新待办 | id + 任意可更新字段（部分更新） |
| `builtin.todo.delete` | 删除待办 | id |
| `builtin.todo.complete` | 完成待办 | id |

待办支持三级优先级（HIGH/MEDIUM/LOW）和三种状态（PENDING → IN_PROGRESS → COMPLETED），状态转换有合法性校验。

### 2.2 日程管理（Schedule Skill）

管理日程安排，支持创建、查询、更新、删除和冲突检测。

| 工具 | 说明 | 关键参数 |
|------|------|---------|
| `builtin.schedule.create` | 创建日程 | title、startTime、endTime（必填）、location、notes |
| `builtin.schedule.list` | 查询列表 | 按开始时间升序 |
| `builtin.schedule.get` | 查询详情 | id |
| `builtin.schedule.update` | 更新日程 | id + 任意可更新字段 |
| `builtin.schedule.delete` | 删除日程 | id |
| `builtin.schedule.conflicts` | 冲突检测 | startTime、endTime |

冲突检测是日程 Skill 的差异化能力，Agent 在创建新日程前可先检测时间冲突，主动提醒用户。

### 2.3 习惯养成（Habit Skill）

追踪和养成好习惯，支持打卡、连续天数统计和完成率计算。

| 工具 | 说明 | 关键参数 |
|------|------|---------|
| `builtin.habit.create` | 创建习惯 | name、frequency（DAILY/WEEKLY）、targetTime |
| `builtin.habit.list` | 查询列表 | 按创建时间降序 |
| `builtin.habit.get` | 查询详情 | id |
| `builtin.habit.update` | 更新习惯 | id + 任意可更新字段 |
| `builtin.habit.checkin` | 习惯打卡 | habitId |
| `builtin.habit.streak` | 连续打卡天数 | habitId |
| `builtin.habit.completion-rate` | 完成率 | habitId、from、to |

连续打卡天数（streak）是核心激励机制，打卡时自动更新。完成率按频率计算：DAILY 习惯为实际打卡天数/总天数，WEEKLY 习惯为实际打卡次数/总周数。

### 2.4 记忆管理（Memory Skill）

管理长期记忆，支持混合检索、实体创建、关系标签、时间线查询和关联查询。

| 工具 | 说明 | 关键参数 |
|------|------|---------|
| `builtin.memory.search` | 搜索记忆 | query（混合检索：向量+FTS5+图遍历） |
| `builtin.memory.create` | 创建记忆 | name、entityType（11 种类型） |
| `builtin.memory.tag` | 添加标签 | sourceEntityId、targetEntityId、relationType |
| `builtin.memory.timeline` | 时间线查询 | timePoint（默认当前时间） |
| `builtin.memory.relate` | 关联查询 | entityId、maxDepth（默认 2 跳） |

Memory Skill 是唯一直接操作记忆系统的内置 Skill，条件装配依赖 HybridRetriever 和 SemanticMemory 可用。

## 3. 使用场景

**场景一：待办与日程联动**

用户说"帮我列一下这周还没完成的事情"，Agent 激活 Todo Skill 调用 `builtin.todo.list` 按状态过滤未完成项，同时可激活 Schedule Skill 检查关联日程，提供综合建议。

**场景二：日程冲突检测**

用户说"明天下午3点和李总开会"，Agent 先调用 `builtin.schedule.conflicts` 检测时间冲突，发现与已有日程重叠后主动提醒并建议替代时间。

**场景三：习惯打卡与激励**

用户说"打卡晨跑"，Agent 调用 `builtin.habit.checkin` 记录打卡，自动更新连续天数，并调用 `builtin.habit.completion-rate` 展示本月完成率，提供正向激励。

**场景四：记忆关联查询**

用户说"张总上次提到了什么"，Agent 调用 `builtin.memory.search` 搜索相关记忆，再调用 `builtin.memory.relate` 查找关联实体，提供完整的上下文信息。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.skills.enabled` | `true` | 控制所有内置 Skill 的注册 |
| `lifepilot.skills.builtin.todo.enabled` | `true` | Todo Skill 启用开关 |
| `lifepilot.skills.builtin.schedule.enabled` | `true` | Schedule Skill 启用开关 |
| `lifepilot.skills.builtin.habit.enabled` | `true` | Habit Skill 启用开关 |

MemorySkillProvider 始终注册（不受 `builtin.{id}.enabled` 控制），但依赖记忆系统 Bean 的可用性（`@ConditionalOnBean`）。禁用某个 Skill 时，其工具注册、蓝图注册和主动推理贡献均被跳过。

## 5. 限制与未来方向

**当前限制**：
- 内置 Skill 之间无直接协作机制，跨 Skill 协作依赖 Agent 层面的工具调用编排
- 习惯打卡无防重复机制，同一天可多次打卡

**未来方向**：
- 跨 Skill 智能关联：待办截止提醒自动关联日程冲突检测
- 习惯数据可视化：通过 Web UI 展示打卡趋势图
- 更多内置 Skill：笔记管理、财务记账等
- 第三方 Skill 通过实现 ProactiveSkillProvider 扩展主动推理能力

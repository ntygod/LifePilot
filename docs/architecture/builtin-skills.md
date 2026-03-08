# 内置技能插件架构设计

> **文档性质**：深度架构设计文档（Developer-Facing）
> **目标读者**：核心开发者、架构评审者
> **模块归属**：`com.lifepilot.skill.builtin`
> **最后更新**：2026-02
> **从属关系**：本文档从 [ARCHITECTURE.md](../ARCHITECTURE.md) 拆分而来，聚焦内置技能插件的完整设计。

---

## 目录

- [1. 设计哲学与原则](#1-设计哲学与原则)
- [2. 整体架构](#2-整体架构)
- [3. BuiltinSkillProvider — 内置技能提供者模式](#3-builtinskillprovider--内置技能提供者模式)
- [4. TodoSkillProvider — 待办管理](#4-todoskillprovider--待办管理)
- [5. ScheduleSkillProvider — 日程管理](#5-scheduleskillprovider--日程管理)
- [6. HabitSkillProvider — 习惯养成](#6-habitskillprovider--习惯养成)
- [7. MemorySkillProvider — 记忆管理](#7-memoryskillprovider--记忆管理)
- [8. 前沿研究与竞品分析](#8-前沿研究与竞品分析)
- [9. 工具注册与风险分级](#9-工具注册与风险分级)

---

## 1. 设计哲学与原则

### 1.1 核心命题：Skill 是 Agent 的能力单元，不是工具别名

传统 AI 助手的"技能"通常是工具的简单包装——一个函数调用加一段描述。ZhiWei 的 Skill 是一个完整的**能力单元**，包含：

| 组成部分 | 说明 | 内置 Skill 示例 |
|---------|------|----------------|
| System Prompt | 专业人格和行为指导 | "你是一个专业的待办事项管理助手" |
| 工具子集 | 最小权限的工具列表 | `builtin.todo.{create,list,get,update,delete,complete}` |
| 执行策略 | 步骤限制、超时、确认要求 | `ExecutionStrategy.DEFAULT` |
| 记忆访问权限 | 可读写的记忆层和实体类型 | Todo/Schedule/Habit: 无记忆访问；Memory: 全层读写 |
| 预算约束 | Token / 步骤 / 时间预算 | `SkillBudget.LIGHTWEIGHT` vs 自定义预算 |

### 1.2 前沿研究基础

#### 1.2.1 AI 个人生产力工具的演进

2025-2026 年个人生产力工具正在经历从"工具"到"助手"的转变。[skywork.ai](https://skywork.ai/blog/ai-agent/best-ai-agents-personal-productivity/) 分析了最佳 AI 个人生产力 Agent 的特征：端到端覆盖（收件箱 → 日历 → 任务 → 文档 → 跟进）。

ZhiWei 的四个内置 Skill（Todo / Schedule / Habit / Memory）覆盖了个人生产力的核心场景，并通过 Agent 引擎实现了跨 Skill 的智能协作。

Content was rephrased for compliance with licensing restrictions.

#### 1.2.2 习惯养成的行为科学基础

BJ Fogg 的行为模型（B = MAP：Behavior = Motivation × Ability × Prompt）是习惯养成领域的经典理论。ZhiWei 的习惯 Skill 设计映射了这个模型：

| Fogg 模型要素 | ZhiWei 实现 |
|--------------|---------------|
| Motivation（动机） | 连续打卡天数（streak）提供成就感 |
| Ability（能力） | 一句话打卡（`habit checkin 晨跑`），极低操作门槛 |
| Prompt（提示） | ProactiveReasoner 在目标时间主动提醒 |

#### 1.2.3 竞品工具分析

| 竞品 | 待办 | 日程 | 习惯 | 记忆 | AI 集成 |
|------|------|------|------|------|---------|
| Todoist | ✅ 强 | ❌ | ❌ | ❌ | ⚠️ 基础 AI 建议 |
| TickTick（滴答清单） | ✅ 强 | ✅ 日历视图 | ✅ 习惯打卡 | ❌ | ❌ |
| Notion | ✅ 数据库 | ✅ 日历 | ⚠️ 需手动 | ❌ | ✅ Notion AI |
| Obsidian | ⚠️ 插件 | ⚠️ 插件 | ⚠️ 插件 | ✅ 知识图谱 | ⚠️ 插件 |
| Reclaim.ai | ❌ | ✅ 智能排程 | ✅ 习惯时间块 | ❌ | ✅ AI 排程 |
| ZhiWei | ✅ | ✅ | ✅ | ✅ 三层记忆 | ✅ Agent 原生 |

ZhiWei 的差异化：四个领域统一在一个 Agent 引擎下，Skill 之间可以交叉引用（如待办截止提醒关联日程冲突检测），记忆系统提供跨 Skill 的上下文感知。

### 1.3 三条核心设计原则

#### 原则 1：Provider 模式 — 蓝图与实例分离

`BuiltinSkillProvider` 提供 `SkillDefinition`（蓝图）和工具注册（`registerTools`）。蓝图描述 Skill 的能力，工具注册将具体的 `BuiltinTool` 注册到 `DynamicToolRegistry`。Skill 激活时由 `SkillActivator` 基于蓝图创建 SubAgent 实例。

#### 原则 2：最小权限 — 每个 Skill 只能访问自己的工具

Todo Skill 只能调用 `builtin.todo.*` 工具，不能调用 `builtin.schedule.*`。这通过 `SkillDefinition.allowedTools` 列表强制执行，`SkillActivator` 在创建 SubAgent 时只注入允许的工具。

#### 原则 3：Repository 直连 — 内置 Skill 不经过 LLM

内置 Skill 的工具直接调用 Repository 层操作数据库，不需要 LLM 参与。LLM 只在 Agent 层面决定"调用哪个工具、传什么参数"，工具执行本身是确定性的。

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                    内置技能插件架构                                    │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │              BuiltinSkillAutoConfiguration                    │  │
│  │              （Spring Boot 自动配置，扫描 @BuiltinSkill）       │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                            │                                        │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │              BuiltinSkillProvider 实现                         │  │
│  │                                                               │  │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐          │  │
│  │  │ TodoSkill    │ │ ScheduleSkill│ │ HabitSkill   │          │  │
│  │  │ Provider     │ │ Provider     │ │ Provider     │          │  │
│  │  │ order=10     │ │ order=20     │ │ order=30     │          │  │
│  │  │ 6 tools      │ │ 6 tools      │ │ 7 tools      │          │  │
│  │  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘          │  │
│  │         │                │                │                   │  │
│  │  ┌──────────────┐                                             │  │
│  │  │ MemorySkill  │                                             │  │
│  │  │ Provider     │                                             │  │
│  │  │ order=40     │                                             │  │
│  │  │ 5 tools      │                                             │  │
│  │  └──────┬───────┘                                             │  │
│  └─────────┼─────────────────────────────────────────────────────┘  │
│            │ provide() + registerTools()                             │
│  ┌─────────┼─────────────────────────────────────────────────────┐  │
│  │         ▼                                                     │  │
│  │  ┌──────────────┐    ┌──────────────────────────────────────┐│  │
│  │  │ SkillRegistry│    │ DynamicToolRegistry                  ││  │
│  │  │ （蓝图注册）   │    │ （工具注册：24 个 builtin.* 工具）    ││  │
│  │  └──────────────┘    └──────────────────────────────────────┘│  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │              数据持久层（SQLite Repository）                    │  │
│  │                                                               │  │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐          │  │
│  │  │ TodoRepo     │ │ ScheduleRepo │ │ HabitRepo    │          │  │
│  │  │ todo_items   │ │ schedule_items│ │ habit_items  │          │  │
│  │  │              │ │              │ │ habit_logs   │          │  │
│  │  └──────────────┘ └──────────────┘ └──────────────┘          │  │
│  │                                                               │  │
│  │  MemorySkillProvider 直接使用 HybridRetriever + SemanticMemory│  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. BuiltinSkillProvider — 内置技能提供者模式

### 3.1 接口定义

```java
public interface BuiltinSkillProvider {
    /** 提供 Skill 定义蓝图。 */
    SkillDefinition provide();

    /** 注册工具到 DynamicToolRegistry。 */
    void registerTools(DynamicToolRegistry toolRegistry);
}
```

### 3.2 @BuiltinSkill 注解

```java
@BuiltinSkill(id = "todo", order = 10)
public class TodoSkillProvider implements BuiltinSkillProvider { ... }
```

| 属性 | 说明 | 示例 |
|------|------|------|
| `id` | Skill 唯一标识 | `"todo"`, `"schedule"`, `"habit"`, `"memory"` |
| `order` | 注册顺序（数值越小越先注册） | 10, 20, 30, 40 |

### 3.3 注册流程

```
Spring Boot 启动
  → BuiltinSkillAutoConfiguration 扫描 @BuiltinSkill 注解
  → 按 order 排序
  → 对每个 Provider：
      1. provider.registerTools(toolRegistry)  // 注册工具
      2. provider.provide() → SkillDefinition  // 获取蓝图
      3. skillRegistry.register(definition)    // 注册蓝图
```

---

## 4. TodoSkillProvider — 待办管理

### 4.1 工具清单

| 工具 ID | 名称 | 风险等级 | 必填参数 | 说明 |
|---------|------|---------|---------|------|
| `builtin.todo.create` | 创建待办 | LOW | title | 支持 priority、dueDate、tags |
| `builtin.todo.list` | 查询列表 | LOW | — | 支持 status、priority 过滤 |
| `builtin.todo.get` | 查询详情 | LOW | id | — |
| `builtin.todo.update` | 更新待办 | LOW | id | 支持部分更新 |
| `builtin.todo.delete` | 删除待办 | MEDIUM | id | 不可逆操作 |
| `builtin.todo.complete` | 完成待办 | LOW | id | 状态变更为 COMPLETED |

### 4.2 数据模型

```java
record TodoItem(
    String id,
    String title,
    String description,
    Priority priority,    // HIGH / MEDIUM / LOW
    Status status,        // PENDING / IN_PROGRESS / COMPLETED
    String dueDate,       // ISO 8601
    List<String> tags,
    String createdAt,
    String updatedAt
)
```

### 4.3 设计决策

- `delete` 风险等级为 MEDIUM（不可逆），其他操作为 LOW
- 支持部分更新：`update` 工具只修改传入的字段，未传入的保持原值
- 标签以逗号分隔字符串传入，内部转为 `List<String>`

---

## 5. ScheduleSkillProvider — 日程管理

### 5.1 工具清单

| 工具 ID | 名称 | 风险等级 | 必填参数 | 说明 |
|---------|------|---------|---------|------|
| `builtin.schedule.create` | 创建日程 | LOW | title, startTime, endTime | 支持 location、notes |
| `builtin.schedule.list` | 查询列表 | LOW | — | 按开始时间升序 |
| `builtin.schedule.get` | 查询详情 | LOW | id | — |
| `builtin.schedule.update` | 更新日程 | LOW | id | 支持部分更新 |
| `builtin.schedule.delete` | 删除日程 | MEDIUM | id | 不可逆操作 |
| `builtin.schedule.conflicts` | 冲突检测 | LOW | startTime, endTime | 查找时间重叠的日程 |

### 5.2 冲突检测

`conflicts` 工具是日程 Skill 的差异化能力。给定一个时间段，查找所有与之重叠的已有日程：

```sql
SELECT * FROM schedule_items
WHERE start_time < :endTime AND end_time > :startTime
```

这使得 Agent 在创建新日程前可以先检测冲突，主动提醒用户。

---

## 6. HabitSkillProvider — 习惯养成

### 6.1 工具清单

| 工具 ID | 名称 | 风险等级 | 必填参数 | 说明 |
|---------|------|---------|---------|------|
| `builtin.habit.create` | 创建习惯 | LOW | name, frequency | 支持 targetTime |
| `builtin.habit.list` | 查询列表 | LOW | — | 按创建时间降序 |
| `builtin.habit.get` | 查询详情 | LOW | id | — |
| `builtin.habit.update` | 更新习惯 | LOW | id | 支持部分更新 |
| `builtin.habit.checkin` | 习惯打卡 | LOW | habitId | 自动更新 streak |
| `builtin.habit.streak` | 连续天数 | LOW | habitId | 查询当前 streak |
| `builtin.habit.completion-rate` | 完成率 | LOW | habitId, from, to | 指定时间范围 |

### 6.2 Streak 计算

连续打卡天数（streak）是习惯养成的核心激励机制。`HabitRepository.calculateStreak()` 从最近一次打卡向前回溯，计算连续不间断的打卡天数。

### 6.3 完成率计算

`completionRate = 实际打卡天数 / 应打卡天数`

- DAILY 习惯：应打卡天数 = 时间范围内的总天数
- WEEKLY 习惯：应打卡天数 = 时间范围内的总周数

---

## 7. MemorySkillProvider — 记忆管理

### 7.1 工具清单

| 工具 ID | 名称 | 风险等级 | 必填参数 | 说明 |
|---------|------|---------|---------|------|
| `builtin.memory.search` | 搜索记忆 | LOW | query | 混合检索（向量+FTS5+图） |
| `builtin.memory.create` | 创建记忆 | LOW | name, entityType | 11 种实体类型 |
| `builtin.memory.tag` | 添加标签 | LOW | sourceEntityId, targetEntityId, relationType | 建立实体关联 |
| `builtin.memory.timeline` | 时间线查询 | LOW | — | 指定时间点的记忆快照 |
| `builtin.memory.relate` | 关联查询 | LOW | entityId | 多跳图遍历 |

### 7.2 与其他 Skill 的差异

Memory Skill 是唯一具有记忆访问权限的内置 Skill：

| Skill | 记忆读权限 | 记忆写权限 | 预算 |
|-------|-----------|-----------|------|
| Todo | 无 | 无 | LIGHTWEIGHT |
| Schedule | 无 | 无 | LIGHTWEIGHT |
| Habit | 无 | 无 | LIGHTWEIGHT |
| Memory | L1+L2+L3 全层读 | L2 MEMO/TAG + L3 RELATION 写 | 自定义（6000 Token, 10 步） |

### 7.3 混合检索

`builtin.memory.search` 调用 `HybridRetriever.retrieve()`，融合三种检索策略：

1. **向量语义检索**：sqlite-vec KNN，捕获语义相似性
2. **FTS5 全文检索**：精确关键词匹配
3. **图遍历检索**：通过知识图谱关系发现关联实体

三种结果通过加权 RRF（Reciprocal Rank Fusion）融合，并应用时间衰减因子。

---

## 8. 前沿研究与竞品分析

### 8.1 个人生产力 AI Agent 趋势

2025-2026 年个人生产力领域的核心趋势是**从工具到助手的转变**。传统工具（Todoist、Google Calendar）需要用户主动操作，AI 助手（ZhiWei、Reclaim.ai）可以主动建议和自动化。

[newsbytesapp.com](https://www.newsbytesapp.com/news/science/ai-driven-techniques-for-goal-achievement/story) 指出，2026 年 AI 正在改变个人目标设定的方式，通过提供个性化计划、实时反馈和持续问责。ZhiWei 的习惯 Skill 正是这一趋势的实现。

Content was rephrased for compliance with licensing restrictions.

### 8.2 内置 Skill 与竞品功能对比

| 功能 | Todoist | TickTick | Reclaim.ai | ZhiWei |
|------|---------|----------|-----------|-----------|
| 自然语言创建 | ✅ | ✅ | ❌ | ✅ Agent 原生 |
| 冲突检测 | ❌ | ❌ | ✅ | ✅ schedule.conflicts |
| 习惯 streak | ❌ | ✅ | ✅ | ✅ habit.streak |
| 完成率统计 | ❌ | ✅ | ❌ | ✅ habit.completion-rate |
| 记忆关联 | ❌ | ❌ | ❌ | ✅ memory.relate |
| 跨功能协作 | ❌ | ⚠️ 有限 | ⚠️ 日历+任务 | ✅ Agent 统一调度 |
| 主动提醒 | ✅ 固定规则 | ✅ 固定规则 | ✅ AI 排程 | ✅ ProactiveReasoner |

---

## 9. 工具注册与风险分级

### 9.1 工具总量

| Skill | 工具数 | LOW | MEDIUM | HIGH |
|-------|--------|-----|--------|------|
| Todo | 6 | 5 | 1（delete） | 0 |
| Schedule | 6 | 5 | 1（delete） | 0 |
| Habit | 7 | 7 | 0 | 0 |
| Memory | 5 | 5 | 0 | 0 |
| **合计** | **24** | **22** | **2** | **0** |

### 9.2 风险分级原则

- `delete` 操作为 MEDIUM：数据删除不可逆，需要审计日志记录
- 其他操作为 LOW：创建、查询、更新都是安全操作
- 内置 Skill 无 HIGH/CRITICAL 操作：内置 Skill 只操作用户自己的数据，不涉及外部系统

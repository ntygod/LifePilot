# 记忆系统进阶 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.memory`（进阶子系统）
> **最后更新**：2026-04-16

## 1. 功能概述

记忆系统进阶模块赋予知微"从经验中学习"和"智能遗忘"的能力。通过 L4 程序记忆，Agent 能够记住用户的操作习惯并主动复用；通过记忆巩固管线，短期对话自动沉淀为长期知识；通过 MaRS 遗忘引擎，过时信息被智能清理，保持知识图谱的时效性和健康容量。

## 2. 核心特性

### 2.1 L4 程序记忆

使 Agent 能够学习和复用用户的行为模式：

- 操作模板（ProcedureTemplate）：记录用户常用的操作步骤序列，包含触发意图、步骤列表、成功率和执行次数。当用户发出类似意图时，Agent 可以主动建议复用已有模板。模板聚类默认启用（`templateEnabled=true`）
- 偏好规则（PreferenceRule）：按类别和键值对存储用户偏好（如"日程提醒提前 30 分钟"），支持强化机制——用户反复确认的偏好权重更高

### 2.2 意图匹配

将用户输入与已有操作模板进行智能匹配：

- 通过向量相似度匹配用户意图与模板触发意图
- 匹配结果包含模板详情、匹配分数和历史成功率
- 集成到 HybridRetriever 中，作为 ReasoningSlot 返回给 ContextAssembler
- 匹配失败静默处理，不影响主检索流程

### 2.3 记忆巩固管线

自动将短期记忆沉淀为长期知识，包含 6 个阶段：

1. **语义巩固**（EpisodicToSemanticConsolidator）：分析近期对话中已有实体的提及频率，提升高频实体的重要度评分（不再触发新增知识提取）
2. **程序巩固**（EpisodicToProceduralConsolidator）：识别对话中的重复行为模式，聚类生成操作模板，提取用户偏好规则
3. **偏好同步**（PreferenceConsolidator）：将 L3 的 PREFERENCE 实体同步为 L4 的 PreferenceRule
4. **经验合并**（ExperienceMerger）：将语义相似的 EXPERIENCE 实体合并为泛化的元经验
5. **用户画像巩固**（UserProfileConsolidator）：将 L3 碎片实体聚合为连贯的用户画像文本，存入 `__consolidated_profile` 特殊 CUSTOM 实体
6. **高频经验提升**（promoteHighFrequencyExperiences）：将 importanceScore ≥ 0.8 且 accessCount ≥ 3 的高频经验提升为 ProcedureTemplate

- 定时执行（Cron 可配置），各阶段故障隔离，互不影响
- 返回统计信息（分析对话数、提升实体数、创建模板数），支持可观测性

### 2.4 MaRS 认知遗忘

基于 MaRS 论文的六策略混合遗忘模型：

- FIFO 策略：按创建时间先进先出，清理最早的实体
- LRU 策略：按最后访问时间淘汰最久未用的实体
- 优先级衰减策略：根据时间衰减公式计算遗忘优先级，重要度随时间自然降低
- 反思摘要策略：对中等重要度实体使用 LLM 生成摘要，保留核心信息后遗忘原文
- 随机丢弃策略：随机选择遗忘候选（用于基线对比和测试）
- 混合策略（HybridPolicy）：编排四阶段遗忘流程，综合上述策略选择最佳候选

### 2.5 受保护实体机制

确保用户核心知识不被误遗忘（满足任一即受保护）：

- 类型保护：受保护类型可配置（`config.getProtectedTypes()`，默认 PREFERENCE / HABIT / GOAL）类型的实体永不遗忘
- 重要度保护：importanceScore ≥ 保护阈值（可配置，`config.getProtectionThreshold()`，默认 0.9）的实体永不遗忘
- 高频访问保护：accessCount ≥ 高频访问保护阈值（`config.getHighAccessCountProtection()`，默认 10）的实体永不遗忘
- 近期访问保护：最近 N 天内被访问过（`config.getRecentAccessProtectionDays()`，默认 7 天）的实体永不遗忘
- 保护机制在遗忘流程最前端执行，受保护实体不进入候选列表

### 2.6 遗忘审计日志

所有遗忘操作完整记录，支持事后追溯：

- 记录遗忘实体 ID、名称、使用的策略、执行的动作（COMPRESSED / ARCHIVED）、遗忘优先级
- 存储在 `forgetting_log` 表中，支持查询和分析

### 2.7 归档一致性保证

归档/压缩动作产生的实体一致性由两条机制保证：

- 压缩路径跳过语义缓存：LLM 压缩调用显式 `skipCache=true`，避免不同实体共享首条摘要（压缩 prompt 只在 name/description 上有差异，开启缓存会被语义相似度张冠李戴）
- 归档级联清理向量：所有归档动作（遗忘引擎、memory.delete、memory.cancel、巩固管线合并/提升）统一走 `SemanticMemory.archive()`，事务提交后通过 `afterCommit` 钩子删除对应的向量索引条目，避免归档实体继续被向量路径召回。事务回滚时向量保持原状，失败仅告警并由后续 archive 重试自然清理

## 3. 使用场景

用户每天使用知微管理待办事项，经过一段时间后，巩固管线自动识别出"每周一早上创建周计划"的重复模式，生成操作模板。下次周一早上用户说"帮我做周计划"时，IntentMatcher 匹配到该模板，Agent 直接按照用户习惯的步骤执行，无需重新询问细节。

同时，三个月前的一次性事件（如"参加某次会议"）因长期未被访问，LRU 策略将其标记为遗忘候选。由于该事件重要度为 0.3（低于保护阈值），遗忘引擎将其归档，释放知识图谱空间。而用户的偏好"喜欢早起"因类型为 PREFERENCE，永远不会被遗忘。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.memory.procedural.max-templates` | — | 操作模板最大数量 |
| `lifepilot.memory.procedural.min-reliability` | — | 模板最低可靠性阈值 |
| `lifepilot.memory.procedural.match-threshold` | — | 意图匹配相似度阈值 |
| `lifepilot.memory.consolidation.cron` | — | 巩固管线 Cron 表达式 |
| `lifepilot.memory.consolidation.trigger-mode` | — | 触发模式（cron / idle） |
| `lifepilot.memory.consolidation.lookback-days` | — | 巩固回溯天数 |
| `lifepilot.memory.consolidation.cluster-similarity-threshold` | — | 聚类相似度阈值 |
| `lifepilot.memory.forgetting.cron` | — | 遗忘引擎 Cron 表达式 |
| `lifepilot.memory.forgetting.max-retention-days` | — | 最大保留天数 |
| `lifepilot.memory.forgetting.priority-decay-rate` | — | 优先级衰减速率 |
| `lifepilot.memory.forgetting.max-forget-per-run` | — | 每次运行最大遗忘数 |
| `lifepilot.memory.forgetting.recentAccessProtectionDays` | 7 | 近期访问保护天数 |
| `lifepilot.memory.forgetting.highAccessCountProtection` | 10 | 高频访问保护阈值 |

## 5. 限制与未来方向

当前限制：
- 巩固管线仅支持 Cron 定时触发，尚未实现 Idle-Driven 空闲触发模式
- 操作模板的聚类算法基于简单的向量相似度，复杂多步骤模式可能识别不准确
- 遗忘策略的参数需要根据实际使用情况调优

未来方向：
- Idle-Driven 巩固触发：通过 IdleDetector 检测用户空闲状态，空闲时自动触发巩固
- 更智能的模式识别：引入序列模式挖掘算法，提升操作模板的识别准确率
- 用户可控遗忘：允许用户手动标记"永不遗忘"或"立即遗忘"特定记忆

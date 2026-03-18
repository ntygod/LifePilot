# 记忆系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.memory`
> **最后更新**：2026-03-18

## 1. 功能概述

记忆系统为知微提供类人的认知记忆能力，使 Agent 能够记住用户的偏好、习惯和历史交互，在对话中提供个性化、连贯的响应。系统实现四层认知记忆（工作记忆、情景记忆、语义记忆、程序记忆），支持混合检索（向量 + 全文 + 图遍历）、自动记忆巩固和智能遗忘，所有数据存储在本地 SQLite 中，保障用户隐私。此外，系统具备完整的经验学习能力，Agent 能从每次任务执行中自动提炼经验、追踪效果、对比学习、子任务反思和经验泛化合并，实现"越用越聪明"的自我进化。

## 2. 核心特性

### 2.1 四层认知记忆架构

模拟人类认知记忆的层次结构，每层承担不同职责：

- L1 工作记忆：管理当前会话的短期上下文，按 Token 预算动态分配槽位容量。支持三种槽位类型（对话历史、工具结果、推理上下文），超出预算时自动淘汰低优先级槽位
- L2 情景记忆：持久化完整对话记录，支持按时间、关键词、意图类型检索历史对话。提供对话压缩能力（摘要 / 要点提取），减少存储占用
- L3 语义记忆：维护时序知识图谱，存储版本化实体（人物、组织、地点、事件、偏好、习惯等 11 种类型）和关系。支持时间点查询和变更历史追踪
- L4 程序记忆：存储用户的操作模板、偏好规则和策略模式，使 Agent 能够学习用户的行为习惯并主动复用

### 2.2 九区域 Token 预算动态分配

智能分配 LLM 上下文窗口的 Token 预算，确保最相关的信息优先进入上下文：

- 固定区域：系统提示词（10%）和用户消息（15%）
- 六个记忆区域按优先级动态分配剩余 75%：当前会话历史 > 用户画像 > 跨会话摘要 > 知识实体 > 操作模板 > 知识库片段
- 场景自适应：长对话时增大当前会话权重，高检索相关度时增大知识实体权重
- 超出预算时按优先级从低到高截断，保证核心信息不丢失

### 2.3 混合检索引擎

三路并行检索 + 加权融合，兼顾语义理解和精确匹配：

- 向量语义检索（sqlite-vec）：捕捉语义相似性，sqlite-vec 不可用时自动降级为 JVM 暴力搜索
- FTS5 全文搜索：精确关键词匹配，覆盖向量检索可能遗漏的精确术语
- CTE 图遍历：沿知识图谱关系链发现关联实体，提供上下文丰富度
- 加权 RRF 融合：自适应权重调整（向量置信度低时自动降权），叠加时间衰减和重要度加成
- 空数据短路优化：三路检索全部为空时设置标记，后续检索直接返回，避免无效查询

### 2.4 AUDN 实时实体提取

每轮对话后自动提取关键实体信息，持续丰富用户知识图谱：

- 采用 Mem0 AUDN 模式（Add / Update / Delete / Noop），通过 LLM 结构化输出判断操作类型
- 在 Virtual Thread 中异步执行，带独立超时控制，不阻塞 Agent 响应
- 写入前经过三级冲突检测（精确匹配 → 语义匹配 → LLM 消歧义），避免重复实体
- 任何异常静默跳过，不影响主对话流程

### 2.5 记忆巩固管线

定时将短期记忆沉淀为长期知识，模拟人类睡眠期间的记忆巩固过程：

- 情景→语义巩固：从历史对话中提取实体和关系，提升高频实体的重要度评分
- 情景→程序巩固：从重复行为模式中聚类生成操作模板，提取用户偏好规则
- 故障隔离：两个巩固器独立执行，单个失败不影响另一个
- 支持 Cron 定时触发和手动调用（为 Idle-Driven 模式预留）

### 2.6 MaRS 智能遗忘

基于 MaRS 论文的认知遗忘策略，维持记忆系统健康容量：

- 受保护实体永不遗忘：偏好、习惯、目标类型实体，以及高重要度实体（≥ 0.9）
- 六种遗忘策略：FIFO / LRU / 优先级衰减 / 反思摘要 / 随机丢弃 / 混合策略
- HybridPolicy 编排四阶段遗忘流程，综合多种策略选择最佳遗忘候选
- 中等重要度实体尝试 LLM 压缩后归档（保留核心信息），其余直接归档
- 所有遗忘操作记录审计日志，支持事后追溯

### 2.7 记忆事件追踪

统一记录记忆系统中的关键操作，支持审计和可观测性：

- 事件类型覆盖：FORMATION / RETRIEVAL / CONSOLIDATION / FORGETTING / COMPRESSION / UPDATING / L1_FLUSH / L1_APPEND
- 每个事件记录层级、会话 ID、实体 ID、操作描述和元数据
- 为可观测性模块提供记忆操作的完整审计链

### 2.8 经验自动提炼

Agent 完成任务后自动分析 ReAct 轨迹，提炼结构化经验记录：

- 触发条件：任务包含工具调用（stepCount ≥ 2 且有 ToolCall 步骤），且未挂起
- 质量门控：通过 TrajectoryQualityAssessor 评估 goal 清晰度、轨迹完整性、工具调用有效率，低质量轨迹不提炼
- 结构化输出：通过 LLM 生成 ExperienceRecord（场景描述、策略总结、关键教训、适用条件、工具列表、成功标志）
- 去重机制：写入前检查语义相似度 ≥ 0.90 的已有经验，相似则合并增强而非新增
- 在 Virtual Thread 中异步执行，不阻塞 Agent 响应
- Eval 集成：支持从评估框架的高质量轨迹中提炼经验，eval 高分时放宽质量门控

### 2.9 经验效果反馈闭环

追踪注入经验的实际效果，动态调整经验权重：

- 注入追踪：ContextAssembler 注入经验时记录 traceId 和经验 ID 到注入记录表
- 效果评估：任务完成后综合判定经验有效性（任务成功度 + 工具调用有效率 + 步骤数合理性）
- 动态调整：有效经验 importanceScore 提升（+0.05），无效经验衰减（-0.03）
- 自动淘汰：importanceScore 低于 0.1 的经验自动归档，避免低效经验污染检索结果

### 2.10 失败轨迹对比学习

从成功/失败轨迹对中提取深层洞察：

- 经验提炼完成后，搜索语义相似但成功标志相反的已有经验
- 通过 LLM 从"成功做了什么不同"和"失败时哪一步出了问题"两个维度分析
- 生成对比洞察（失败根因、成功关键因素、对比教训、规避策略）
- 对比经验初始 importanceScore 为 0.7（信息密度高于单条经验）

### 2.11 执行上下文隔离

防止不同执行环境的经验互相污染：

- 五种执行上下文：主 Agent（MAIN_AGENT）、子 Agent（SUB_AGENT）、评估（EVAL）、工作流（WORKFLOW_LLM）、定时任务（SCHEDULED_TASK）
- 写入时自动标记：根据 sessionId 前缀和 Agent depth 推断上下文
- 检索时过滤：默认仅返回 MAIN_AGENT 上下文的经验，可配置跨上下文检索
- Eval 隔离强化：EVAL 上下文额外记录 evalRunId，便于按评估批次追溯

### 2.12 经验泛化与合并

自动将相似经验合并为更抽象的元经验：

- 在巩固管线中执行，通过向量相似度检测语义相似的经验对（阈值 0.85）
- 通过 LLM 提取共性模式、合并教训列表、生成更抽象的场景描述
- 合并后的元经验 importanceScore 取原始最大值，原始经验归档
- 每次巩固最多 10 次合并，避免单次运行消耗过多 LLM 调用

### 2.13 子任务级经验提取

从工具调用序列中提取细粒度的工具使用技巧：

- 触发条件：连续 3 个以上 ToolCall → Observation 步骤对，且至少一个成功
- 通过 LLM 从"工具选择是否正确"、"参数是否最优"、"调用顺序是否合理"三个维度总结
- 子任务经验初始 importanceScore 为 0.4（适用范围较窄）
- 复用去重逻辑避免重复的工具使用经验堆积

### 2.14 主动经验检索

Agent 在执行过程中可按需查询相关经验：

- 通过 `builtin.memory.search-experience` 工具主动检索，而非仅依赖上下文组装时的被动注入
- 支持按关键词搜索、限制返回数量、筛选仅成功经验
- 遵循执行上下文隔离规则，按 importanceScore 降序返回
- 适用场景：遇到类似任务、工具调用连续失败、需要了解工具最佳使用方式

## 3. 使用场景

用户与知微进行日常对话时，记忆系统在后台持续工作：当用户提到"我下周要去北京出差"时，RealtimeExtractor 自动提取"北京出差"事件实体写入知识图谱；下次用户问"帮我查一下北京的天气"时，HybridRetriever 通过语义检索关联到出差事件，Agent 能够主动提供出差相关的天气建议。

长期使用后，系统积累了用户的偏好（如"喜欢早起"）、习惯（如"每周一做周计划"）和操作模板（如"创建待办事项的固定流程"），Agent 能够越来越个性化地响应用户需求。同时，过时的信息（如已完成的项目、过期的事件）会被遗忘引擎自动清理，保持知识图谱的时效性。

用户与知微进行日常对话时，记忆系统在后台持续工作：当用户提到"我下周要去北京出差"时，RealtimeExtractor 自动提取"北京出差"事件实体写入知识图谱；下次用户问"帮我查一下北京的天气"时，HybridRetriever 通过语义检索关联到出差事件，Agent 能够主动提供出差相关的天气建议。

长期使用后，系统积累了用户的偏好（如"喜欢早起"）、习惯（如"每周一做周计划"）和操作模板（如"创建待办事项的固定流程"），Agent 能够越来越个性化地响应用户需求。同时，过时的信息（如已完成的项目、过期的事件）会被遗忘引擎自动清理，保持知识图谱的时效性。

经验学习方面：当 Agent 成功使用特定工具组合完成任务后，ExperienceSummarizer 自动提炼经验记录；下次遇到类似场景时，ContextAssembler 自动注入相关经验到 LLM 上下文，Agent 能复用之前的成功策略。如果某次注入的经验导致任务失败，EffectivenessTracker 会降低该经验的权重；如果同一场景有成功和失败的轨迹，ContrastiveLearner 会提取"为什么失败"的对比洞察。Agent 还可以通过 `search-experience` 工具主动查询相关经验。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.memory.enabled` | `true` | 记忆系统总开关 |
| `lifepilot.memory.working-memory-token-budget` | — | L1 工作记忆 Token 预算上限 |
| `lifepilot.memory.idle-session-timeout-minutes` | — | 空闲会话清理超时（分钟） |
| `lifepilot.memory.compression-threshold-tokens` | — | 触发对话压缩的 Token 阈值 |
| `lifepilot.memory.embedding-dimensions` | — | 向量维度 |
| `lifepilot.memory.semantic-match-threshold` | — | 语义匹配相似度阈值 |
| `lifepilot.memory.vector-db-url` | — | 向量数据库 SQLite URL |
| `lifepilot.memory.token-budget.*` | — | 九区域预算分配参数 |
| `lifepilot.memory.procedural.*` | — | L4 程序记忆参数 |
| `lifepilot.memory.consolidation.*` | — | 巩固管线参数（Cron、触发模式等） |
| `lifepilot.memory.forgetting.*` | — | 遗忘引擎参数（Cron、保留天数、衰减速率等） |
| `lifepilot.memory.extraction.timeout-seconds` | — | AUDN 实体提取超时（秒） |
| `lifepilot.memory.experience.enabled` | `true` | 经验学习总开关 |
| `lifepilot.memory.experience.max-input-tokens` | `4000` | 经验提炼 LLM 输入截断上限 |
| `lifepilot.memory.experience.dedup-similarity-threshold` | `0.90` | 经验去重相似度阈值 |
| `lifepilot.memory.experience.max-injection-count` | `3` | 上下文注入经验数量上限 |
| `lifepilot.memory.experience.injection-token-budget` | `500` | 经验注入 Token 预算 |
| `lifepilot.memory.experience.effectiveness.*` | — | 效果反馈配置 |
| `lifepilot.memory.experience.contrastive.*` | — | 对比学习配置 |
| `lifepilot.memory.experience.isolation.*` | — | 执行上下文隔离配置 |
| `lifepilot.memory.experience.merge.*` | — | 经验合并配置 |
| `lifepilot.memory.experience.subtask.*` | — | 子任务反思配置 |

## 5. 限制与未来方向

当前限制：
- 向量检索依赖 sqlite-vec 原生扩展，部分平台可能加载失败（自动降级为 JVM 暴力搜索，性能较低）
- 知识图谱遍历使用 SQLite CTE 递归查询，大规模图谱可能存在性能瓶颈
- 经验学习的对比学习、子任务反思和经验合并均依赖 LLM 调用，LLM 不可用时这些功能静默跳过
- WORKFLOW_LLM 和 SCHEDULED_TASK 执行上下文需要调用方显式传入，当前仅支持 MAIN_AGENT / SUB_AGENT / EVAL 的自动推断

未来方向：
- Agentic GraphRAG：评估使用图检索 Skill 替代 SQL CTE 穷举遍历
- Idle-Driven 记忆巩固：空闲事件驱动替代定时触发，提升资源利用效率
- 记忆可视化：在 Web UI 中展示知识图谱和记忆时间线
- 经验可视化：在 Web UI 中展示经验库、效果追踪趋势和对比洞察
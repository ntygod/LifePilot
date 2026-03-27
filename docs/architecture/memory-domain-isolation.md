# 记忆模块领域隔离与最终态设计

> **文档性质**：架构设计文档
> **模块归属**：`com.lifepilot.memory`
> **最后更新**：2026-03-27
> **状态**：最终态方案已落地核心链路，持续完善中

## 1. 设计目标

知微的记忆系统最终态需要同时满足六个目标：

- 用户真实长期记忆不能被知识库、Datastore、小说设定、角色扮演内容污染
- 领域资料仍然可以被稳定检索、引用、扩展，必要时还能沉淀为项目级领域记忆
- 单轮 `@datastore` / `@知识库` 这类临时作用域必须是硬边界，不能受会话后续恢复配置影响
- 记忆边界必须可解释、可追踪、可删除，不能只依赖提示词“尽量别学错”
- 知识源删除后，相关领域记忆必须能反查来源并安全清理，不能形成“知识泄露感”
- 向量、FTS、图检索三条链路必须共用同一套边界模型，不能各自理解一套“作用域”

因此，最终态不采用“当前会话挂了知识库就整体跳过记忆”的粗粒度方案，也不采用“知识库 ID / Datastore ID 直接充当记忆命名空间”的弱模型，而采用：

- `memory_space` 作为一等公民
- `memory_scope` 作为记忆类型
- `provenance` 作为来源追踪
- `turn snapshot` 作为真实作用域快照

## 1.1 当前实现收敛状态

截至 2026-03-27，以下核心能力已经在代码中落地：

- `memory_spaces`、`memory_entities`、`memory_entity_versions`、`memory_entity_provenances`
- `memory_relations`、`memory_relation_versions`、`memory_relation_provenances`
- `chat_turn_memory_snapshots`
- `ConflictDetector`、版本合并、关系建立均限制在同一 `space_id`
- Datastore / Knowledge Base 文档写入 `DOMAIN_MEMORY`
- `RealtimeExtractor`、`KnowledgeExtractionPipeline`、`HybridRetriever` 默认按 `memory_scope` / `space_id` 收口
- `MemoryController` 已暴露 `spaceId / memoryScope / realityType / provenance` 观察接口

当前仍在持续完善的部分主要有两类：

- 是否以及如何向用户开放“显式读取某个 DOMAIN space”的产品入口
- 更完整的删除回收策略，例如“按 provenance 级联归档实体 / 关系”

## 2. 五个核心原则

### 2.1 知识来源不等于记忆空间

`knowledge base` 和 `datastore` 是**知识来源容器**，不是最终的记忆归属边界。

例如：

- 一个小说项目可能同时绑定 1 个 Datastore、2 个知识库
- 一个公共写作指南知识库可能被多个项目共享
- 同一个 Datastore 可以 fan-out 到多个知识库做检索

如果直接把 `knowledgeBaseId` 或 `datastoreId` 当成记忆 namespace，模型会非常脆：

- 来源一变，记忆就要迁移
- 一个项目使用多个来源时，记忆被切碎
- 同一个来源被多个项目共享时，记忆归属会变得模糊

因此，**真正的边界应该是“记忆空间（memory space）”**。  
来源只负责“这条记忆是从哪里学来的”，空间才负责“这条记忆归谁管”。

### 2.2 记忆空间不等于记忆类型

`memory_space` 回答的是“归属在哪个空间”，`memory_scope` 回答的是“它是什么性质的记忆”。

例如：

- `space = personal:default`，`scope = USER_PROFILE`
- `space = personal:default`，`scope = USER_FACT`
- `space = agent:default`，`scope = AGENT_EXPERIENCE`
- `space = project:novel-workspace`，`scope = DOMAIN_MEMORY`

这两者不能混成一个字段，否则后面过滤、注入、学习策略都会变脆。

### 2.3 逻辑实体不等于版本行

当前 `temporal_entities` 直接把“逻辑实体”和“版本记录”揉在一起，短期能跑，长期会带来三个问题：

- 版本变更后，实体主键变了，关系和向量索引要跟着抖动
- 同一实体的多来源证据难以稳定挂接
- 删除某个来源时，很难判断是在删“一个实体版本”，还是删“这个实体的某条证据”

最终态必须把：

- `逻辑实体（stable identity）`
- `实体当前/历史版本（version rows）`
- `来源证据（provenance rows）`

三者分开建模。

### 2.4 来源证据不等于实体本体

一条领域记忆往往会有多份来源：

- 来自用户对话
- 来自 Datastore 卡片
- 来自知识库文档
- 来自后续巩固或人工确认

如果只在实体表上挂几个 `source_xxx_id` 字段，最终一定不够。  
最终态必须允许“一条实体，多条证据”，并支持按证据删除、审计、降权、回放。

### 2.5 会话默认配置不等于 turn 真实作用域

记忆写入和检索的最终依据必须是“这一轮实际生效了哪些知识源、哪些空间、哪些学习策略”，而不是“当前 session 现在长什么样”。

否则：

- 单轮 `@datastore` 临时切换会失真
- 失败重试 / 异步抽取 / 延迟巩固会用错作用域
- 用户改了会话配置，会污染过去 turn 的解释

所以必须有独立的 `chat_turn_memory_snapshots`。

## 3. 最终态数据模型

最终态建议引入六组核心表。

### 3.1 `memory_spaces`

记忆空间是**归属边界**，不是知识源。

建议字段：

- `id`
- `space_key`
  - 稳定唯一键，例如：
    - `personal:default`
    - `agent:default`
    - `project:novel-workspace`
- `space_type`
  - `PERSONAL | DOMAIN | EXPERIENCE`
- `display_name`
- `owner_type`
  - `USER | PROJECT | SYSTEM`
- `owner_id`
- `metadata_json`
- `created_at`
- `updated_at`

关键约束：

- 一个用户至少有一个 `PERSONAL` space
- `AGENT_EXPERIENCE` 应写入 `EXPERIENCE` space
- 领域创作、学习项目、小说项目应写入 `DOMAIN` space

### 3.2 `memory_space_knowledge_bases` / `memory_space_datastores`

这两张关系表用于表达“哪些知识源为这个记忆空间服务”，而不是表达记忆本体归属。

建议字段：

- `memory_space_id`
- `knowledge_base_id` / `datastore_id`
- `created_at`

这样可以支持：

- 一个项目空间绑定多个知识库、多个 Datastore
- 一个公共知识库被多个项目空间共享为检索源
- 来源重组时，不需要迁移既有记忆实体

这比把 `knowledgeBaseId` / `datastoreId` 直接塞进 namespace 更稳。

### 3.3 `memory_entities`

`memory_entities` 是**逻辑实体根表**，提供稳定身份，不直接承载版本化内容。

建议字段：

- `id`
- `space_id`
- `memory_scope`
  - `USER_PROFILE | USER_FACT | AGENT_EXPERIENCE | DOMAIN_MEMORY`
- `entity_type`
- `canonical_name`
- `normalized_name`
- `reality_type`
  - `REAL | FICTIONAL | SIMULATED | UNKNOWN`
- `status`
  - `ACTIVE | ARCHIVED | DELETED`
- `first_seen_at`
- `last_seen_at`
- `created_at`
- `updated_at`

关键约束：

- `USER_PROFILE / USER_FACT` 只能出现在 `PERSONAL` space
- `AGENT_EXPERIENCE` 只能出现在 `EXPERIENCE` space
- `DOMAIN_MEMORY` 只能出现在 `DOMAIN` space
- 冲突检测、版本合并、图关系建立都不能跨 `space_id`

### 3.4 `memory_entity_versions`

`memory_entity_versions` 承载实体当前快照和历史版本，是“这个实体在某时刻长什么样”。

建议字段：

- `id`
- `entity_id`
- `version_no`
- `description`
- `properties_json`
- `extraction_confidence`
- `importance_score`
- `is_current`
- `valid_from`
- `valid_to`
- `created_at`
- `updated_at`

索引与派生规则：

- `entity_embeddings` 向量索引应以 `entity_id` 为主键，只索引当前版本的文本表示
- FTS 索引也应该跟随当前版本，而不是绑定历史版本行
- 版本切换时，只更新当前版本映射，不改变逻辑实体身份

这样可以避免“版本一更新，关系、向量、引用全部跟着换主键”的问题。

### 3.5 `memory_relations` / `memory_relation_versions`

关系也应遵循“逻辑关系 + 版本快照”模型，至少需要：

- `memory_relations`
  - `id`
  - `space_id`
  - `source_entity_id`
  - `target_entity_id`
  - `relation_type`
  - `reality_type`
  - `status`
  - `created_at`
  - `updated_at`
- `memory_relation_versions`
  - `id`
  - `relation_id`
  - `version_no`
  - `strength`
  - `properties_json`
  - `is_current`
  - `valid_from`
  - `valid_to`
  - `created_at`

关键约束：

- `source_entity_id` 和 `target_entity_id` 必须属于同一个 `space_id`
- 默认不允许跨 space 建图关系
- `REAL` 和 `FICTIONAL` 的关系默认不自动合并

### 3.6 `memory_entity_provenances` / `memory_relation_provenances`

这是最终态最关键的表之一，用于解决“知识从哪里来的”“源删了如何清理”“实体为什么存在”。

建议字段：

- `id`
- `entity_id` / `relation_id`
- `version_id`
- `origin_type`
  - `CHAT | KNOWLEDGE_BASE_DOCUMENT | DATASTORE_DOCUMENT | MANUAL | TOOL | CONSOLIDATION`
- `source_session_id`
- `source_turn_id`
- `source_entry_id`
- `source_document_id`
- `source_knowledge_base_id`
- `source_datastore_id`
- `source_collection_id`
- `evidence_excerpt`
- `evidence_hash`
- `confidence`
- `created_at`

为什么必须单独建表：

- 同一角色设定可能同时来自 Datastore 卡片和多轮创作对话
- 同一用户事实可能有多轮对话共同支撑
- 删除知识库文档时，应该删除对应 provenance，而不是盲删整条实体
- 当某实体不再有任何 provenance，且没有人工 pin 或显式保留标记时，才应该归档

没有 provenance，后续的删除、审计、降权、回放都会很脆。

### 3.7 `chat_turn_memory_snapshots`

这是 turn 级真实作用域快照，不建议继续把这类语义塞进 `chat_turns.request_payload_json`。

建议字段：

- `turn_id`
- `session_id`
- `personal_space_id`
- `experience_space_id`
- `domain_write_space_id`
- `read_space_ids_json`
- `effective_knowledge_base_ids_json`
- `effective_datastore_ids_json`
- `personal_learning_enabled`
- `domain_learning_enabled`
- `experience_learning_enabled`
- `resolution_source_json`
  - 记录这轮 scope 来自哪里，例如：
    - `session_default`
    - `message_mention`
    - `ui_override`
- `created_at`

这里的重点不是“把所有上下文都结构化”，而是把**会影响记忆读写边界的最小事实**固化下来。

## 4. 最终态中的读写边界

### 4.1 普通对话

未绑定领域空间的普通对话：

- 读取：
  - `PERSONAL` space 中的 `USER_PROFILE / USER_FACT`
  - `EXPERIENCE` space 中的 `AGENT_EXPERIENCE`
- 写入：
  - `USER_PROFILE / USER_FACT -> personal_space`
  - `AGENT_EXPERIENCE -> experience_space`
- 不允许自动写 `DOMAIN_MEMORY`

### 4.2 领域创作对话

绑定了 `DOMAIN` space 的小说 / 学习 / 项目创作对话：

- 默认读取：
  - 该 turn 显式绑定的知识库 / Datastore
  - 可选读取该 `DOMAIN` space 内已有 `DOMAIN_MEMORY`
- 默认写入：
  - `AGENT_EXPERIENCE -> experience_space`
  - `USER_PROFILE / USER_FACT -> 默认关闭`
  - `DOMAIN_MEMORY -> 默认关闭，需显式开启`

也就是说：

- 领域知识默认是“检索增强”
- 领域记忆默认不是“自动学习”
- 一旦开启领域学习，也只写入当前 `DOMAIN` space，不进入个人长期记忆

### 4.3 知识库 / Datastore 导入

知识库文档和 Datastore 同步文档在最终态应遵循：

- 默认只作为外挂知识源参与检索
- 不自动写入 `USER_PROFILE / USER_FACT`
- 如开启“领域知识抽取”，则只允许写入对应 `DOMAIN` space 的 `DOMAIN_MEMORY`
- provenance 必须保留：
  - `source_document_id`
  - `source_knowledge_base_id`
  - `source_datastore_id`
  - `source_collection_id`

所以“知识库属于外挂记忆”这句话在最终态里更准确地表达为：

- **默认是外挂检索源**
- **可选成为领域记忆来源**
- **不是默认的个人记忆来源**

### 4.4 L2 -> L3 巩固

L2 到 L3 的抽取 / 巩固必须完全服从 turn snapshot：

- 只能使用 `chat_turn_memory_snapshots` 里的 read/write 规则
- 不能回头读取“当前 session 现在配置成什么样”
- 不能在 `PERSONAL` 和 `DOMAIN` 之间跨 space 合并实体

这条规则是避免“单轮 @ 命中小说素材，结果隔几分钟被巩固成用户事实”的关键。

## 5. 最终态检索模型

## 5.1 读路径的真正输入不是 query，而是 `query + memory plan`

最终态检索前必须先解析出 `MemoryReadPlan`，至少包含：

- `allowedSpaceIds`
- `allowedScopes`
- `allowedRealityTypes`
- `allowedOriginTypes`
- `knowledgeBaseIds`
- `datastoreIds`

只有这样，向量、FTS、图遍历才能共享同一套边界。

### 5.2 个人记忆默认注入

默认注入用户长期记忆时，只应检索：

- `space_type in (PERSONAL, EXPERIENCE)`
- `memory_scope in (USER_PROFILE, USER_FACT, AGENT_EXPERIENCE)`

因此默认不会捞到：

- 小说人物
- 创作剧情
- 项目术语
- Datastore 设定卡

### 5.3 领域会话注入

当当前 turn 绑定某个 `DOMAIN` space 时：

- 先走知识库 / Datastore 检索链路
- 如该空间开启了领域记忆，再补充检索：
  - `space_id in allowedDomainSpaces`
  - `memory_scope = DOMAIN_MEMORY`

领域记忆永远不应在普通闲聊里被默认召回。

### 5.4 Memory Tool 检索

`builtin.memory.search` 最终态至少要支持：

- `spaceIds`
- `memoryScopes`
- `originTypes`
- `realityTypes`

工具层必须能显式区分：

- “查用户记忆”
- “查 Agent 经验”
- “查某个项目空间里的设定”

## 6. 向量检索的最终要求

这里有一个非常关键的点：

**不能把“全局 topK 向量召回后再 SQL 过滤”当成最终方案。**

原因是：

- 如果全库先召回的 topK 大多来自无关 space
- 过滤之后可能一个结果都不剩
- 真正相关的目标 space 结果根本没进入候选集

这会导致“逻辑上隔离了，效果上却查不准”。

因此最终态必须做到“filter-aware vector retrieval”。  
可接受的实现方向有两类：

- 向量索引本身支持按 `space_id / memory_scope` 预过滤
- 或者在向量层做分区 / 分桶，让检索先限定到允许的空间范围，再做 KNN

但无论具体实现选哪种，数据模型上都必须保证以下字段在热路径可用：

- `space_id`
- `memory_scope`
- `is_current`
- `reality_type`

也就是说，Gemini 提到的 metadata / namespacing 方向是对的，但在知微这里，最终态不能只是“额外塞个 metadata JSON”；而必须让这些维度成为正式的一等字段，并进入检索计划。

## 7. 删除、撤回、审计如何工作

最终态删除能力依赖 provenance，而不是靠模糊匹配。

### 7.1 删除知识源

当知识库文档或 Datastore 文档被删除时：

1. 删除对应 provenance 行
2. 检查受影响的实体 / 关系是否还存在其他 provenance
3. 若仍有其他 provenance，则仅降权或保留
4. 若已无 provenance，且没有人工 pin / 显式保留，则归档对应实体 / 关系

### 7.2 撤回某轮学习

当需要撤回某个 turn 产生的学习结果时：

1. 按 `source_turn_id` 删除 provenance
2. 重算受影响实体的当前版本与重要度
3. 无支撑证据的实体归档

### 7.3 审计

每条领域记忆都应能回答：

- 它属于哪个 `memory_space`
- 它属于哪类 `memory_scope`
- 它来自哪些 chat turn / 文档 / Datastore 记录
- 它为什么会被当前检索命中

这才是真正可解释的隔离模型。

## 8. 为什么不先分成“用户记忆表 / 领域记忆表”

分表看起来简单，但它解决的是“物理隔离感”，不是完整的数据建模问题。

即使分成两张表，你仍然要回答：

- 一条记忆属于哪个项目空间
- 同一实体的多个来源如何追踪
- turn 级作用域如何固化
- 向量 / FTS / 图检索如何共享边界
- Agent 经验放哪

所以真正的关键不是“先分几张表”，而是先把下面四件事建对：

- `memory_space`
- `memory_scope`
- `provenance`
- `turn snapshot`

在这四件事已经稳定的前提下，如果后续出于性能、隔离、部署原因，要把 `PERSONAL` 和 `DOMAIN` 做物理分库/分表，是可以再做的；但那属于存储实现优化，不应该反过来主导领域模型。

## 9. 一个最小但完整的例子

以“小说项目 `novel-workspace`”为例：

- `memory_spaces`
  - `personal:default`
  - `agent:default`
  - `project:novel-workspace`
- `memory_space_datastores`
  - `project:novel-workspace -> datastore:novel-workspace`
- `memory_space_knowledge_bases`
  - `project:novel-workspace -> kb:知天命`
- 当前 turn 通过 `@novel-workspace` 进入创作模式
- `chat_turn_memory_snapshots`
  - `personal_learning_enabled = false`
  - `domain_learning_enabled = true`
  - `domain_write_space_id = project:novel-workspace`

如果这一轮抽取到：

- “林玄是主角”
- “青岚城是故事主舞台”

那么最终写入应是：

- `memory_entities.space_id = project:novel-workspace`
- `memory_entities.memory_scope = DOMAIN_MEMORY`
- provenance 指向：
  - 当前 `turn_id`
  - 相关 Datastore 文档或知识库文档

它们不会进入 `personal:default`。  
用户下一次普通闲聊时，也不会默认被注入。

## 10. 实施顺序

建议按六步落地，而不是再做一次全局硬拦截：

1. 新增 `memory_spaces` 与来源绑定表，完成“来源”和“空间”的解耦
2. 为 turn 建立 `chat_turn_memory_snapshots`
3. 引入 `memory_entities + memory_entity_versions + provenance`
4. 改造 `RealtimeExtractor / KnowledgeExtractionPipeline / EpisodicToSemanticConsolidator` 的写入目标
5. 改造 `ConflictDetector / VersionMerger / Graph`，严格限制在同 `space_id` 内工作
6. 改造向量、FTS、图检索为基于 `MemoryReadPlan` 的 filter-aware 检索

只有六步都完成，领域创作污染问题才算真正解决。

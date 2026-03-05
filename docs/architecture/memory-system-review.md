# 记忆系统复盘：问题分析与理想设计

> 日期：2026-03-05
> 状态：分析完成，待修复

---

## 1. 当前问题诊断

### 1.1 L1 写入时序倒置

**现象**：对话时提示词中"相关记忆"和"对话历史"区域为空。

**根因**：AgentLoop 的执行顺序导致当前轮用户消息在 LLM 调用之后才写入 L1。

```
当前时序（错误）：
  每轮迭代:
    1. assembleContext()     ← 读 L1（此时 L1 无当前轮消息）
    2. callLlm()
    3. reduceState()
  循环结束后:
    4. asyncPostProcess()    ← 异步写入 L1（用户消息 + AI 响应）
```

**影响**：
- 第一轮对话：L1 完全为空，ContextAssembler 的 `safeGetContext()` 返回空列表
- 后续轮次：依赖上一轮 asyncPostProcess 的 Virtual Thread 在新请求到来前完成（通常可以，但不保证）
- 当前轮的用户消息不会通过 L1 路径出现在上下文中（虽然 `state.goal()` 直接注入了用户请求，但对话历史区域仍为空）

### 1.2 L3 语义记忆冷启动困境

**现象**：HybridRetriever 三路检索全部返回空结果。

**根因**：`temporal_entities` 表中没有数据。新实体的创建完全依赖定时巩固任务中的 `KnowledgeExtractionPipeline`。

**数据流断裂链路**：
```
L1 (内存) → flush → L2 (conversations/messages 表)
                         ↓
              EpisodicToSemanticConsolidator（每日凌晨 3:00）
                         ↓
              查询已有 L3 实体 → 统计提及频率 → 提升 importanceScore
              ↓（仅当 KnowledgeExtractionPipeline 可用时）
              triggerKnowledgeExtraction() → 创建新实体写入 L3
```

**问题**：
- 如果 L3 一开始为空，巩固管线只会发现"0 个实体需要提升"
- 新实体创建完全依赖 `KnowledgeExtractionPipeline`（通过 ObjectProvider 按需获取）
- 如果该 Pipeline 不可用或提取失败，L3 永远为空
- HybridRetriever 永远检索不到任何东西

### 1.3 ContextAssembler 缺少 L2 直接检索路径

**现象**：即使 L2 中有丰富的历史对话，ContextAssembler 也无法利用。

**根因**：ContextAssembler 的记忆检索只走 HybridRetriever（L3 语义记忆），完全跳过了 L2（情景记忆）。

```
当前检索路径：
  ContextAssembler.assemble()
    → HybridRetriever.retrieve()  ← 只检索 L3 temporal_entities
    → WorkingMemory.getContext()  ← 只读 L1 内存槽位
    → 无 L2 直接检索路径          ← 缺失！
```

**影响**：跨会话的历史对话内容无法被检索到，除非先经过巩固管线提取为 L3 实体。

---

## 2. 理想的提示词记忆内容

一个合理的提示词应该包含以下记忆相关区域（按优先级排序）：

### 2.1 当前会话对话历史（L1 工作记忆）

**来源**：WorkingMemory 中当前 sessionId 的 ConversationSlot
**内容**：本次会话中的历史消息（user/assistant 交替）
**作用**：维持对话连贯性，让 LLM 知道"我们之前聊了什么"
**优先级**：最高 — 这是对话连贯性的基础

```
对话历史:
  [user] 帮我安排明天的日程
  [assistant] 好的，明天你有以下安排：...
  [user] 把下午的会议改到 3 点
```

### 2.2 用户画像与偏好（L3 语义记忆 + L4 偏好规则）

**来源**：temporal_entities 中 type=PERSON/CONCEPT 的实体 + preference_rules 表
**内容**：用户的身份信息、习惯偏好、常用表达方式
**作用**：个性化响应，让 LLM 了解"这个用户是谁，喜欢什么"
**优先级**：高 — 这是个人助手的核心差异化能力

```
用户画像:
  - 姓名：张三，软件工程师
  - 偏好：喜欢简洁回复，习惯用番茄工作法
  - 常用工具：Todo、日程管理
```

### 2.3 相关历史对话片段（L2 情景记忆）

**来源**：EpisodicMemory 中与当前查询语义相关的历史对话
**内容**：过去会话中与当前话题相关的对话片段
**作用**：跨会话的上下文延续，让 LLM 知道"我们以前聊过类似的事"
**优先级**：中高 — 避免用户重复说明背景

```
相关历史对话:
  - [3天前] 用户讨论过项目 X 的截止日期是下周五
  - [1周前] 用户提到习惯每天早上 9 点开始工作
```

### 2.4 相关知识实体（L3 语义记忆）

**来源**：HybridRetriever 从 temporal_entities 检索的实体
**内容**：与当前查询相关的事实性知识（人物、地点、事件、概念）
**作用**：提供事实性背景知识，让 LLM 的回答基于已知事实
**优先级**：中 — 增强回答的准确性和相关性

```
相关知识:
  - [PERSON] 李四：用户的同事，负责前端开发
  - [EVENT] 项目 X 评审会：定于下周五下午 2 点
  - [CONCEPT] 番茄工作法：用户常用的时间管理方法
```

### 2.5 操作模板建议（L4 程序记忆）

**来源**：IntentMatcher 从 procedure_templates 匹配的操作模板
**内容**：历史上成功执行过的操作序列
**作用**：指导 Agent 选择工具和执行步骤，提高执行效率
**优先级**：中 — 仅在 PLANNING/EXECUTING 阶段有价值

```
操作建议:
  - 模板"创建日程"（成功率 95%，使用 12 次）：
    步骤：解析时间 → 检查冲突 → 创建事件 → 确认
```

### 2.6 知识库片段（文档检索）

**来源**：DocumentRetriever 从关联知识库检索的文档分块
**内容**：用户上传的文档中与查询相关的片段
**作用**：RAG 增强，让 LLM 基于用户的私有知识回答
**优先级**：中 — 仅当会话关联了知识库时

```
知识库片段:
  - [KB] 公司手册.pdf / 请假流程：年假需提前 3 天申请...
```

---

## 3. 需要修复的点

### 3.1 L1 写入时序修正（紧急）

将用户消息的 L1 写入从 `asyncPostProcess()` 提前到 `assembleContext()` 之前：

```
修正后时序：
  用户请求到达
    → 立即写入 L1（ConversationSlot.userMessage）
    → assembleContext()（L1 已包含当前消息 + 历史消息）
    → callLlm()
    → AI 响应生成后立即写入 L1（ConversationSlot.assistantMessage）
    → asyncPostProcess()（仅负责会话快照持久化，不再写 L1）
```

### 3.2 实体提取的实时路径（重要）

在每轮对话结束时（或 flush 时），增加一个轻量级的实体提取步骤：

```
对话结束
  → flush L1 → L2
  → 同步/准同步提取关键实体 → 写入 L3
  → 定时巩固任务仅负责"提升重要度"和"合并冲突"
```

### 3.3 L2 直接检索路径（重要）

在 ContextAssembler 中增加 L2 情景记忆的直接检索：

```
ContextAssembler.assemble()
  → HybridRetriever.retrieve()     ← L3 实体检索
  → EpisodicMemory.searchRecent()  ← L2 历史对话检索（新增）
  → WorkingMemory.getContext()     ← L1 当前会话
  → DocumentRetriever.retrieve()   ← 知识库检索
```

### 3.4 用户画像注入（增强）

在 ContextAssembler 中增加用户画像的注入：

```
ContextAssembler.assemble()
  → 从 L3 中查询 type=PERSON 且 name 匹配当前用户的实体
  → 从 preference_rules 中查询用户偏好
  → 格式化为"用户画像"区域注入 System Prompt
```

---

## 4. 提示词结构设计（理想状态）

```
System Prompt:
  - 角色定义
  - 阶段指令
  - 用户画像（L3 + L4 偏好规则）
  - 约束条件

User Prompt:
  1. 用户请求（state.goal()）
  2. 相关知识实体（L3 HybridRetriever）
  3. 相关历史对话（L2 EpisodicMemory — 新增）
  4. 知识库片段（DocumentRetriever）
  5. 当前会话对话历史（L1 WorkingMemory）
  6. 工具结果（L1 ToolResultSlot）
  7. 推理上下文（L1 ReasoningSlot + L4 操作模板）
  8. 已执行步骤（state.steps()）
  9. 预算剩余
```


---

## 5. 业界记忆系统调研

> 调研日期：2026-03-05
> 核心问题：记忆不可能全量加载到提示词，业界如何做选择性注入？

### 5.1 ChatGPT 的记忆方案（逆向分析）

来源：[manthanguptaa.in — Reverse Engineering ChatGPT Memory](https://manthanguptaa.in)

ChatGPT 的记忆系统并不使用 RAG 检索对话历史，而是采用 4 层结构：

| 层级 | 内容 | 注入方式 | 生命周期 |
|------|------|---------|---------|
| Session Metadata | 当前时间、用户名、设备信息 | 每次请求注入 System Prompt | 临时 |
| User Memory | 用户显式/隐式的长期事实（约 33 条） | 始终注入 System Prompt | 持久 |
| Recent Conversations | 近期对话摘要（标题 + 首条用户消息，约 15 条） | 始终注入 System Prompt | 滚动窗口 |
| Current Session | 当前会话的滑动窗口 | 直接作为对话历史 | 会话级 |

关键设计决策：
- **不对对话历史做 RAG**：ChatGPT 不会向量检索过去的对话，而是用轻量摘要（标题 + 首条消息）代替
- **User Memory 是精炼的事实列表**：不是原始对话片段，而是提炼后的结构化事实（如"用户是软件工程师"、"偏好简洁回复"）
- **recency × frequency 门控**：决定哪些记忆自动注入，高频 + 近期的优先
- **总量控制**：User Memory 约 33 条、Recent Conversations 约 15 条，硬性上限防止 token 爆炸

**对 LifePilot 的启示**：
- 不需要对 L2 做全量 RAG，轻量摘要（会话标题 + 关键信息）更高效
- User Memory 模式非常适合 L3 语义记忆的注入策略：提炼为事实列表，始终注入
- 需要一个"记忆条目数上限"机制，而非无限检索

### 5.2 Letta/MemGPT 的记忆块模式

来源：[letta.com — Memory Management](https://letta.com)

Letta（原 MemGPT）的核心创新是让 Agent 自己管理记忆：

**两层架构**：
- **In-context 核心记忆**（始终在提示词中）：
  - `persona` 块：Agent 的身份和行为规则
  - `human` 块：用户的信息和偏好
  - 每个块有 token 上限（如 2000 tokens），Agent 通过工具调用编辑块内容
- **Out-of-context 记忆**（按需检索）：
  - `archival`：长期知识存储，向量检索
  - `recall`：对话历史，时间序列检索

**Agent 自编辑机制**：
```
Agent 发现用户说"我是前端工程师"
  → 调用 core_memory_replace(section="human", old="", new="职业：前端工程师")
  → human 块被更新，下次对话自动包含
```

**Sleep-time Compute**：
- 空闲时后台 Agent 自动整理记忆块
- 压缩冗余信息、提升重要信息的优先级
- 类似人类睡眠时的记忆巩固

**对 LifePilot 的启示**：
- "记忆块 + token 上限"模式值得借鉴：每个记忆区域有固定 token 预算
- Agent 自编辑记忆的思路可以简化为"对话后自动更新用户画像块"
- Sleep-time compute 与现有的定时巩固管线理念一致

### 5.3 Mem0 的 AUDN 记忆管理

来源：[mem0.ai](https://mem0.ai)、[arxiv 论文](https://arxiv.org)

Mem0 的核心是 AUDN 决策循环：每条新信息到来时，LLM 决定对已有记忆执行哪种操作：

| 操作 | 含义 | 示例 |
|------|------|------|
| Add | 新增记忆条目 | 用户首次提到"我养了一只猫" |
| Update | 更新已有条目 | 用户说"我的猫叫小花"→ 更新猫的名字 |
| Delete | 删除过时条目 | 用户说"我已经不养猫了" |
| Noop | 不操作 | 闲聊内容，无需记忆 |

**性能数据**（对比 OpenAI Memory）：
- 准确率提升 26%
- P95 延迟降低 91%
- Token 消耗降低 90%

**图增强变体 Mem0g**：
- 在事实记忆之上构建关系图
- 支持多跳推理（如"用户的同事李四负责的项目是什么？"）

**对 LifePilot 的启示**：
- AUDN 模式可以替代当前的"定时批量巩固"：每轮对话后实时决策是否更新记忆
- 比全量 RAG 高效得多：只存储和更新有价值的信息
- 图增强与现有的时序知识图谱方向一致

### 5.4 业界共识：记忆管线模式

来源：[sergeyenin — Memory for AI Agents](https://sergeyenin.substack.com)

业界对 Agent 记忆系统形成了以下共识：

**四阶段管线**：
```
Extract（提取）→ Consolidate（巩固）→ Store（存储）→ Retrieve（检索）
```

**三类记忆**：
| 类型 | 内容 | 检索方式 | LifePilot 对应 |
|------|------|---------|---------------|
| Semantic | 事实、概念、关系 | 向量 + 关键词 | L3 temporal_entities |
| Episodic | 事件、对话片段 | 时间 + 语义 | L2 conversations/messages |
| Procedural | 行为模式、操作序列 | 意图匹配 | L4 procedure_templates |

**检索评分公式**（综合排序）：
```
score = w1 × relevance + w2 × recency + w3 × importance + w4 × trust
```
- relevance：语义相似度（向量余弦）
- recency：时间衰减（指数衰减或对数衰减）
- importance：使用频率 / 显式标记
- trust：来源可信度（用户直接说的 > 推断的）

**关键洞察**：
> "If you just append every interaction to a database and search it later, you've built a log, not a memory."
> （如果只是把每次交互追加到数据库然后搜索，你建的是日志，不是记忆。）

记忆系统的核心价值在于**选择性遗忘和提炼**，而非全量存储。

### 5.5 综合分析：LifePilot 应采用的策略

基于以上调研，结合 LifePilot 的技术约束（Java 22 / SQLite / 单 JAR / 本地部署），推荐以下策略：

#### 5.5.1 提示词记忆注入策略（借鉴 ChatGPT + Letta）

采用"固定区域 + token 预算"模式，每个记忆区域有独立的 token 上限：

| 区域 | 来源 | 注入位置 | Token 预算 | 注入条件 |
|------|------|---------|-----------|---------|
| 用户画像 | L3 用户实体 + 偏好规则 | System Prompt | 500 | 始终注入 |
| 当前会话历史 | L1 ConversationSlot | Messages | 动态（总预算 - 其他区域） | 始终注入 |
| 近期会话摘要 | L2 会话标题 + 首条消息 | System Prompt | 300 | 始终注入 |
| 相关知识实体 | L3 HybridRetriever | User Prompt | 500 | 有检索结果时 |
| 操作模板 | L4 IntentMatcher | User Prompt | 300 | PLANNING/EXECUTING 阶段 |
| 知识库片段 | DocumentRetriever | User Prompt | 500 | 会话关联知识库时 |

#### 5.5.2 记忆更新策略（借鉴 Mem0 AUDN）

将当前的"定时批量巩固"改为"实时轻量提取 + 定时深度巩固"双轨模式：

```
实时路径（每轮对话后）：
  对话结束 → LLM 判断 AUDN 操作 → 更新 L3 用户画像/事实记忆
  延迟：< 2s，异步执行，不阻塞响应

定时路径（保留现有巩固管线）：
  每日凌晨 → 深度分析 L2 → 合并冲突实体 → 提升重要度 → 清理过时记忆
```

#### 5.5.3 检索策略（借鉴业界共识）

采用综合评分排序，替代当前的单一向量检索：

```
score = 0.4 × relevance + 0.3 × recency + 0.2 × importance + 0.1 × trust
```

每个记忆区域独立检索、独立排序、独立截断，最终按 token 预算组装。

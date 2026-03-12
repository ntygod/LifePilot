# ZhiWei 记忆系统完整评估报告

> 评估日期：2026-03-12
> 评估范围：记忆系统全链路（写入 → 存储 → 检索 → 上下文注入 → 反馈）
> 核心问题：上下文中出现的记忆数据大多不相关，浪费 token 且分散 LLM 注意力

---

## 一、评估维度总览

| 维度 | 当前状态 | 优先级 |
|------|---------|--------|
| 读取精度（检索 → 上下文注入） | 检索粗糙、注入无过滤 | 🔴 高 |
| 写入质量（提取 → 向量化 → 存储） | 提取过度、质量无门控 | 🔴 高 |
| 生命周期管理（遗忘 / 巩固） | 遗忘机制受限于 importanceScore 硬编码 | 🟡 中 |
| 层间协调与一致性 | 双重提取、时间维度未利用 | 🟡 中 |
| 反馈闭环 | 完全缺失（数据已有但未消费） | 🟡 中（长期最重要） |
| 可观测性与调试 | 部分有，不完整 | 🟢 低（但调试必需） |

---

## 二、读取侧分析（检索 → 上下文注入）

### 2.1 搜索查询过于粗糙

**源码位置**：`ContextAssembler.java` → `HybridRetriever.retrieve()`

当前检索使用 `state.goal()`（即用户原始输入）作为所有检索路径的查询文本：
- 向量检索：直接 embed 用户原始输入
- FTS5 全文检索：直接用原始输入做 BM25 匹配
- 跨会话检索：同样用原始输入

问题：用户输入往往是口语化的、模糊的，直接用作检索 query 会导致召回大量语义相关但实际无用的结果。

### 2.2 minFusedScore 阈值过滤失效

**源码位置**：`HybridRetriever.java` → `fusedScore` 计算

融合分数公式：`fusedScore = vectorSimilarity * vectorWeight + ftsScore * ftsWeight + importanceBoost`

其中 `importanceBoost = entity.importanceScore() * importanceWeight`。由于所有实体的 importanceScore 硬编码为 0.5（见 §3.1），importanceBoost 对所有实体的加成相同，无法区分高价值和低价值实体。低相关性但高 importance 的实体可以轻松越过 minFusedScore 阈值。

### 2.3 用户画像无条件注入

**源码位置**：`ContextAssembler.java`

用户画像（PREFERENCE/HABIT/GOAL 类型实体）在每次上下文组装时无条件注入，不考虑与当前对话的相关性。用户问"今天天气怎么样"时，"喜欢深色主题"这类偏好也会被注入。

### 2.4 跨会话检索缺少相关性过滤

**源码位置**：`HybridRetriever.java` → 跨会话摘要检索

FTS5 BM25 分数未被用于过滤，只要匹配到关键词就返回，导致大量弱相关的历史会话摘要被注入上下文。

### 2.5 检索结果双重注入

**源码位置**：`ContextAssembler.java`

同一批检索结果被注入两次：
1. 作为"相关记忆"section 直接拼接到上下文
2. 作为 ReasoningSlots 在 L1 层再次注入

这导致相同信息在上下文中出现两次，浪费 token。

### 2.6 预算分配不感知质量

**源码位置**：`TokenBudgetAllocator.java`

Token 预算按固定比例预分配给各记忆区域（用户画像、当前会话、跨会话、知识实体等），不考虑实际检索到的内容质量。即使某个区域检索到的全是低质量结果，预算也会被填满。

---

## 三、写入侧分析（提取 → 向量化 → 存储）

### 3.1 AUDN 提示词过于泛化

**源码位置**：`RealtimeExtractor.java` → `buildAudnPrompt()`

当前 AUDN 提示词是一段通用指令，没有提供任何上下文信息：
- 不包含已有实体列表（LLM 无法判断 ADD vs UPDATE）
- 不包含用户画像（LLM 不知道哪些信息对用户重要）
- 不包含提取标准（什么值得提取、什么应该忽略）

后果：LLM 倾向于过度提取，将对话中提到的每个名词都当作实体。例如用户说"帮我查一下北京的天气"，可能提取出"北京"作为 PLACE 实体，但这只是一次性查询，不值得持久化。

### 3.2 extractionConfidence 和 importanceScore 硬编码

**源码位置**：`RealtimeExtractor.java` → `executeAdd()`

```java
var entity = new TemporalEntity(
        ..., 0.8f, 0.5f, 0, null, now, now);
//         ^^^^  ^^^^
//  extractionConfidence  importanceScore
```

所有新提取的实体都获得相同的 extractionConfidence=0.8 和 importanceScore=0.5。这两个值本应由 LLM 根据上下文判断：
- extractionConfidence 应反映 LLM 对提取结果的确信程度
- importanceScore 应反映该信息对用户的重要程度

硬编码导致遗忘引擎（§4.1）无法区分高价值和低价值实体，所有实体在遗忘优先级上几乎等价。

### 3.3 textRepresentation() 生成低质量向量文本

**源码位置**：`TemporalEntity.java` → `textRepresentation()`

```java
public String textRepresentation() {
    var sb = new StringBuilder(name);
    if (description != null && !description.isBlank()) {
        sb.append(" ").append(description);
    }
    properties.forEach((k, v) ->
        sb.append(" ").append(k).append(":").append(v));
    return sb.toString();
}
```

问题：
- 输出格式不是自然语言句子，而是 `name key1:value1 key2:value2` 的拼接，embedding 模型对这种格式的语义理解很差
- 不包含 EntityType 信息，导致同名不同类型的实体（如"Python"作为 SKILL vs TOPIC）向量表示无法区分
- properties 的 key:value 拼接没有语义上下文，`location:北京` 不如 `位于北京` 对 embedding 友好

### 3.4 冲突检测去重质量受限

**源码位置**：`ConflictDetector.java` → `detectConflict()`

三级冲突检测（精确匹配 → 语义匹配 → LLM 消歧义）设计合理，但实际效果受限于：

1. **精确匹配依赖 name 字符串完全一致**：LLM 每次提取的 entityName 可能有微小差异（如"张三" vs "张三同学"），精确匹配无法命中
2. **语义匹配依赖 textRepresentation() 的向量质量**：如 §3.3 所述，向量文本质量差，语义匹配的召回率和精度都受影响
3. **LLM 消歧义的输入也是 textRepresentation()**：低质量的文本表示传给 LLM 做消歧义判断，LLM 也难以做出准确判断

后果：同一实体可能以不同 name 被多次 ADD，形成大量重复实体，进一步污染检索结果。

### 3.5 无提取后验证

提取流程中没有任何质量门控：
- 不验证 LLM 返回的 entityName 是否为空或过短
- 不验证 entityType 是否合理（如将"天气"标记为 PERSON）
- 不验证 description 是否有实际信息量（如 LLM 可能返回空描述）
- 不对单次提取的实体数量做上限控制

任何 LLM 返回的结果都直接写入 L3，没有"垃圾进、垃圾出"的防线。

### 3.6 写入侧问题链总结

写入侧的问题形成了一条因果链：

```
AUDN 提示词泛化 → 过度提取
    ↓
extractionConfidence/importanceScore 硬编码 → 无法区分质量
    ↓
textRepresentation() 质量差 → 向量化效果差
    ↓
冲突检测去重失效 → 大量重复实体
    ↓
无提取后验证 → 垃圾实体直接入库
    ↓
L3 数据质量差 → 检索结果不相关（回到 §2）
```

---

## 四、生命周期管理分析（遗忘 / 巩固）

### 4.1 遗忘引擎受限于 importanceScore 硬编码

**源码位置**：`ForgettingEngine.java`

遗忘引擎的核心决策依赖 importanceScore：
- `importanceScore >= 0.9` → 受保护，永不遗忘
- `importanceScore` 在 `[minImportance, maxImportance)` → 压缩后归档
- 其他 → 直接归档

由于所有实体的 importanceScore 初始值都是 0.5（§3.2），且只有巩固器会提升（§4.2），大部分实体的 importanceScore 长期停留在 0.5 附近。遗忘引擎实际上无法有效区分哪些实体该遗忘、哪些该保留。

### 4.2 巩固器频率统计使用简单字符串匹配

**源码位置**：`EpisodicToSemanticConsolidator.java` → `countEntityMentions()`

巩固器通过 `text.indexOf(entity.name())` 统计实体在对话文本中的提及频率。问题：
- 简单子串匹配会产生误匹配（如实体名"AI"会匹配到"WAIT"中的"AI"）
- 短名称实体（1-2 个字符）几乎必然产生大量误匹配
- 不考虑同义词或指代（如"他"指代某个 PERSON 实体）
- 中文分词问题：实体名"张三"可能出现在"张三丰"中被误匹配

### 4.3 无实体合并/去重/清理机制

当前系统没有任何机制来清理已有的垃圾数据：
- 没有重复实体合并功能（同一实体以不同 name 存在多份）
- 没有低质量实体批量清理功能
- 巩固器只提升 importanceScore，不降低也不清理

### 4.4 时序实体无自动过期

TemporalEntity 有 `validFrom` / `validTo` 字段，但没有任何机制自动将过期实体（如"明天下午3点开会"过了时间后）标记为非当前。EVENT 类型实体会永久保留在当前实体列表中。

---

## 五、层间协调与一致性

### 5.1 双重提取路径

实体写入 L3 有两条路径：
1. **实时提取**（RealtimeExtractor）：每轮对话后异步执行 AUDN
2. **巩固提取**（EpisodicToSemanticConsolidator）：定时批量触发 KnowledgeExtractionPipeline

两条路径独立运行，可能对同一段对话重复提取，产生重复实体。巩固器的 KnowledgeExtractionPipeline 使用的提取逻辑与 RealtimeExtractor 的 AUDN 不同，可能对同一信息产生不同的实体表示。

### 5.2 时间维度未被检索利用

TemporalEntity 记录了丰富的时间信息（validFrom、validTo、createdAt、updatedAt、lastAccessedAt），但检索时完全不使用这些信息：
- 不按时间衰减排序（最近的实体不优先）
- 不过滤已过期实体（validTo 已过的实体仍然被检索到）
- 不考虑实体的新鲜度（刚创建的实体和半年前的实体同等对待）

### 5.3 accessCount 更新时机不合理

accessCount 在检索命中时更新，但更新发生在 `HybridRetriever.retrieve()` 返回结果之后。如果检索到的实体最终被 TokenBudgetAllocator 截断（超出预算），accessCount 仍然被增加了，导致统计不准确。

---

## 六、反馈闭环

### 6.1 完全缺失的检索质量反馈

当前系统没有任何机制来评估记忆注入的效果：
- 不知道注入的记忆是否被 LLM 实际使用
- 不知道注入的记忆是否帮助了回答质量
- 不知道哪些记忆是噪音（被注入但完全无关）

这是记忆系统无法自我改进的根本原因。没有反馈信号，importanceScore 永远无法反映真实价值。

### 6.2 已有 message_feedback 数据未被消费

**源码位置**：`MessageFeedbackRepository.java`

系统已支持用户对 AI 回复的"有帮助/无帮助"反馈（message_feedback 表），但这些数据完全没有被记忆系统消费。可利用的方向：
- 用户标记"有帮助"的回复 → 提升该回复关联的记忆实体的 importanceScore
- 用户标记"无帮助"的回复 → 降低关联记忆实体的 importanceScore

### 6.3 缺失的关键数据：回复与记忆的关联

要实现 §6.2 的反馈闭环，需要知道每次 AI 回复时注入了哪些记忆实体。当前系统不记录这个关联关系。需要在 ContextAssembler 组装上下文时，将注入的实体 ID 列表保存到某个关联表中，才能在用户反馈时回溯到具体的记忆实体。

### 6.4 无用户主动纠正机制

用户无法主动纠正记忆系统中的错误信息。例如系统错误地记住了"用户喜欢Java"（实际上用户说的是"用户在学Java"），用户没有途径修正这条记忆。需要提供记忆管理 API 和前端页面（已在审计报告待办中）。

---

## 七、可观测性与调试

### 7.1 事件追踪不完整

记忆系统的关键操作有部分事件记录（forgetting_log、memory_consolidation_log），但缺少：
- 实时提取事件日志（每次 AUDN 提取了什么、决策了什么）
- 检索事件日志（每次检索的 query、返回结果、fusedScore 分布）
- 上下文注入日志（最终注入了哪些实体、占用了多少 token）
- 冲突检测日志（检测到的冲突、合并决策）

### 7.2 缺少健康度量指标

没有可观测的记忆系统健康指标：
- L3 实体总数 / 当前实体数 / 归档实体数
- 平均 importanceScore 分布
- 平均 extractionConfidence 分布
- 检索命中率（检索到的实体中有多少最终被注入上下文）
- 实体重复率估算
- 每次检索的平均 fusedScore

这些指标对于调优记忆系统参数（阈值、权重、预算比例）至关重要。

---

## 八、总结与优先级建议

### 8.1 核心问题因果链

记忆系统当前的核心问题可以归结为一条因果链：

```
写入质量差（§3）
  → L3 充满低质量/重复实体
    → 检索召回大量噪音（§2）
      → 上下文被无关记忆填满
        → LLM 注意力被分散，回答质量下降
          → 无反馈闭环（§6），系统无法自我修正
```

### 8.2 建议优先级

| 优先级 | 改进方向 | 预期收益 |
|--------|---------|---------|
| P0 | 写入质量门控：改进 AUDN prompt、LLM 输出 confidence/importance、提取后验证 | 从源头减少垃圾数据 |
| P0 | 检索精度提升：query 改写、相关性过滤、消除双重注入 | 立即减少上下文噪音 |
| P1 | textRepresentation() 改为自然语言句子 | 提升向量检索和冲突检测质量 |
| P1 | 用户画像条件注入（按相关性过滤） | 减少无关画像的 token 浪费 |
| P2 | 反馈闭环：记录注入实体、消费 message_feedback | 长期自我改进能力 |
| P2 | 时间维度利用：检索时间衰减、过期实体自动归档 | 提升检索结果新鲜度 |
| P3 | 可观测性：检索日志、健康指标、调试面板 | 支撑后续调优 |
| P3 | 记忆管理 API + 前端页面 | 用户主动纠正能力 |   memory-management-api

---
title: 工具暴露机制重构 — Defer 默认 + BM25 搜索
status: draft
owner: zsg
date: 2026-04-23
scope: 重构 Tool 暴露给 LLM 的分层机制，从"核心/非核心二分"改为"Tier 1 高频常驻 + Tier 2 BM25 可搜索"，解决核心界定模糊、Agent 自我感知不全、Skill 扩展视野缺失三个问题
---

# 工具暴露机制重构 — Defer 默认 + BM25 搜索

## 0. 一句话说明

把现有"13 个核心工具 schema 常驻 / 其余 30+ 工具靠 Skill 激活暴露"的二分架构，换成业界主流的**"少量高频工具 Tier 1 常驻 + 其余 deferred 通过 `tools.search` BM25 搜索按需暴露"**。对齐 Claude Code v2.1.69+ 的 defer_loading 模式，接住未来 MCP 生态的无限扩展，同时保留简单任务的 2 轮低延迟响应。

## 1. 背景与动机

### 1.1 现状的三个问题

1. **核心 vs 非核心界定模糊**：`application.yml::core-tool-ids` 白名单 13 个工具始终暴露完整 schema，其余工具只能通过 Skill 激活才能被 LLM 看见。新增工具时"进不进核心"没有客观规则，凭感觉决定。
2. **Agent 自我感知不完整**：LLM 每轮只看见 13 个核心工具，不知道系统还有 30+ 其他工具存在。用户问"能不能做 X"时，LLM 可能以"没有这个能力"的错误前提回答，即使系统实际能做。
3. **生成 Skill 时看不到全量工具**：`SkillGenerator` 现有的 LLM 调用路径受限于 13 个核心，生成的 Skill 只能引用已经在 prompt 里的工具，覆盖不了长尾能力。

### 1.2 目标

- **架构可扩展**：接入 MCP 生态后工具数量从 40+ 增长到 300+ 甚至 1000+ 时不崩
- **Agent 知道全景**：即便 LLM 当下没调用某个工具，它也知道"这类能力存在，可以搜索发现"
- **解耦 Skill 与工具暴露**：Skill 回归"场景指南"定位（本次最小触碰，留给未来 Skill refactor 深入）
- **不退化简单任务延迟**：Tier 1 的高频工具保持完整 schema 常驻，单工具任务 2 轮响应不变
- **可观测 + 可演进**：有指标、有回滚预案、Tier 1 名单可通过使用数据动态调整

### 1.3 不做的事（明确边界）

- ❌ **不做 Skill 系统的完整重构**（frontmatter 三分法、progressive loading、26 SKILL.md 迁移）— 留待独立的 Skill Refactor 项目
- ❌ **不做双轨兼容**（新旧暴露机制并存）— 新项目直接替换
- ❌ **不引入向量搜索作为默认路径** — BM25 裸跑优先，向量 fallback 仅作为开关预留
- ❌ **不自动改写 application.yml** — Tier 1 晋升走 advisory 表 + 人工审批
- ❌ **不引入 jieba 等中文分词依赖** — 改为工具 metadata 英文化，FTS5 unicode61 足够

## 2. 架构总览

### 2.1 暴露层次

```
┌─────────────────────────────────────────────────────────────┐
│ Prompt 常驻（每轮必送）                                      │
├─────────────────────────────────────────────────────────────┤
│ Tier 0 · Meta 工具（3 个，完整 schema 常驻）                 │
│   ├─ tools.search(query, category?, limit?) → top-k 工具摘要│
│   ├─ tools.describe(tool_ids[])             → 完整 schema   │
│   └─ tools.list(category?)                  → 分类 ID 列表  │
│                                                              │
│ Tier 1 · 高频核心工具（pinned + 晋升，完整 schema 常驻）     │
│   候选：file.read / file.write / web.search / memory.* ...  │
│   数量：~7-10 个（具体名单由 application.yml 配置）          │
│                                                              │
│ Category Hint · 系统提示片段（~300 token）                   │
│   告诉 LLM "另有 7 类工具可通过 tools.search 发现"           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Deferred 池（不进 prompt，进 BM25 索引）                    │
├─────────────────────────────────────────────────────────────┤
│ Tier 2 · 其余所有 Java 原生工具 + MCP + 动态 Skill 生成的工具│
│                                                              │
│ 索引字段：id / short_description / tags / actions / category│
│ 索引引擎：SQLite FTS5（复用现有基础设施）                   │
│ 查询策略：BM25 rank + category filter                       │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ 后端内部通道（不走 LLM 工具曝光）                            │
├─────────────────────────────────────────────────────────────┤
│ Skill 生成：SkillGenerator 直接从 DynamicToolRegistry       │
│              .getToolSnapshot() 拿全量，以 system prompt     │
│              形式注入一次                                    │
│                                                              │
│ 前端自检：/meta/capabilities API 读全量                     │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 关键约束

- Tier 0 的 3 个 meta 工具**绝不可 defer**（defer 自己会死循环，且 Anthropic Tool Search 明确报错）
- 所有工具（不管 Tier）都注册进 `DynamicToolRegistry`——Tier 只影响"是否进 prompt schema"，不影响调用能力
- `allowedToolIds`（多 Agent 受限代理场景）优先级高于 Tier 过滤
- Skill 激活的工具合并进 Tier 1 可见集合（复用现有 `activatedToolIds` 机制）

### 2.3 与现状的映射

| 现状 | 重构后 |
|------|--------|
| `application.yml::core-tool-ids`（13 个）| `agent.tools.tier1.pinned` + 动态晋升（≤10 个） |
| `load_skill` / `generate_skill` BuiltinTool | 保留（Skill 激活入口不变） |
| Skill 激活 → `activatedToolIds` → ToolBridge 过滤 | **保留原状**（Skill 机制最小触碰）|
| 非核心工具通过 Skill 暴露 | 通过 `tools.search` BM25 发现 |
| ToolBridgeAgentToolProvider 三分支过滤 | 统一视角：Tier 1 ∪ activated ∪ meta tools |

## 3. BM25 索引 + Meta 工具契约

### 3.1 FTS5 表结构

```sql
CREATE VIRTUAL TABLE tool_search_index USING fts5(
    tool_id UNINDEXED,
    short_description,          -- 英文，主要匹配字段
    tags,                       -- 英文同义词空格分隔
    actions,                    -- action 名 + 描述空格分隔
    category,                   -- 7 个 category 英文名
    tokenize = 'unicode61 remove_diacritics 2'
);
```

**不入索引的字段**：`inputSchema` / `outputSchema` / `examples` 细节——搜索靠摘要命中，细节靠 `describe` 取。

### 3.2 `tools.search` 契约

**输入**：
```json
{
  "query": "delete files",
  "category": "ACTION",
  "limit": 5
}
```

**输出**：
```json
{
  "results": [
    {
      "id": "file.delete",
      "description": "Delete a file or directory at the specified path",
      "category": "ACTION",
      "score": 8.342,
      "actions": ["soft_delete", "hard_delete"]
    }
  ],
  "total_matched": 3,
  "confidence": "high",
  "hint": null
}
```

**`confidence` 取值**：`high`（top-1 ≥ 阈值）/ `low`（top-1 < 阈值）/ `none`（零结果）。

### 3.3 `tools.describe` 契约

**输入**：
```json
{ "tool_ids": ["file.delete", "shell.exec"] }
```

**输出**：
```json
{
  "schemas": {
    "file.delete": {
      "description": "...",
      "input_schema": { ... },
      "output_schema": { ... },
      "risk_level": "MEDIUM",
      "idempotent": false,
      "examples": [ ... ]
    }
  },
  "not_found": []
}
```

**批量设计**：一次 describe 多个，降 LLM 往返次数。批量上限 10（`agent.tools.describe.max-batch-size`）。

### 3.4 `tools.list` 契约

**输入**：
```json
{ "category": "STORAGE" }
```

**输出**：
```json
{
  "categories": {
    "STORAGE": ["datastore.query_documents", "datastore.insert_document"]
  },
  "total": 12
}
```

**只返回 ID**（不返回 description，避免 token 膨胀）。Agent 看到感兴趣的再 describe。

### 3.5 索引生命周期

```
启动时：
  BuiltinToolRegistrar.registerAll() 完成后
  → ToolSearchIndexBuilder 扫描 DynamicToolRegistry.getToolSnapshot()
  → 批量 INSERT INTO tool_search_index
  
运行时（工具增删）：
  DynamicToolRegistry 发 ToolRegistryEvent
  → ToolSearchIndexMaintainer 监听，增量 INSERT / DELETE
  → MCP 连接/断开、动态 Skill 注册都走同一通道

查询时：
  tools.search → FTS5 MATCH 查询 + BM25 rank
  → 应用 Tier 1 / activatedToolIds / meta tools 排除
  → 应用 allowedToolIds 权限过滤（受限代理场景）
  → 返回 top-k
```

### 3.6 三层缓存

```
Layer A · schema 缓存（长效，Caffeine）
  Key:  tool_id
  Value: 预序列化 schema JSON
  TTL:  永久，失效触发：ToolRegistryEvent.UPDATE
  上限：500 条

Layer B · search 结果缓存（短效，Caffeine）
  Key:  sha256(query + category + limit) 归一化
  Value: 序列化结果 JSON
  TTL:  5 分钟，失效触发：ToolRegistryEvent 任何变更（粗粒度全表清）
  上限：1000 条

Layer C · 会话级 memoize（单轮，Map）
  Key:  ReactAgentState.traceId + query
  Value: 同 Layer B
  TTL:  会话结束自动清
```

### 3.7 索引效果保障

| 机制 | 作用 |
|------|------|
| **英文化 metadata** | `short_description` / `tags` / `actions` 描述一律英文。`unicode61` 分词器对英文 word-level 准确。避免中文单字分词的噪音 |
| **`tags` 同义词扩展** | 每个 deferred 工具必填 ≥ 3 个英文同义词（含动词 + 宾语多种表达）|
| **`actions` 独立索引** | action 名加描述一起进 actions 字段，BM25 可命中 |
| **向量 fallback**（开关，默认关闭）| `bm25-confidence-threshold` 以下触发向量补充，合并分数重排 |
| **质量回归测试集** | fixture YAML + 测试，召回率 < 85% 则测试失败 |

### 3.8 FTS5 查询防注入

FTS5 `MATCH` 语法有保留字符（`AND`、`OR`、`NOT`、`NEAR`、`*`、`"`、`(`、`)`）。`sanitizeQuery()` 处理：

```java
String sanitizeQuery(String query) {
    if (query == null || query.isBlank()) return "";
    String cleaned = query.replaceAll("[\"()*]", " ");
    return Arrays.stream(cleaned.split("\\s+"))
        .filter(t -> !t.isBlank())
        .map(t -> "\"" + t + "\"")
        .collect(Collectors.joining(" "));
}
```

例："delete file" → `"delete" "file"`（两个 phrase，隐式 AND）。

## 4. Tool 过滤逻辑的重写（Skill 最小触碰）

### 4.1 ToolBridgeAgentToolProvider 过滤规则

```java
// 伪代码
public List<ToolCallback> getToolCallbacks(ReactAgentState state, @Nullable String streamId) {
    List<ToolContract> all = toolRegistry.getToolSnapshot();
    Set<String> visible;
    
    if (state.allowedToolIds() != null && !state.allowedToolIds().isEmpty()) {
        // 多 Agent 受限场景：allowedToolIds + infrastructure 标签的工具
        visible = all.stream()
            .filter(t -> state.allowedToolIds().contains(t.id())
                      || t.tags().contains("infrastructure"))
            .map(ToolContract::id)
            .collect(toSet());
    } else {
        // 普通场景：Tier 1 + Skill 激活的工具 + Meta 工具
        Set<String> activated = nullToEmpty(state.activatedToolIds());
        visible = new HashSet<>();
        visible.addAll(tier1Service.getCurrentTier1Ids());
        visible.addAll(activated);
        visible.addAll(META_TOOL_IDS);    // tools.search / describe / list
    }
    
    return all.stream()
        .filter(t -> visible.contains(t.id()))
        .map(t -> toToolCallback(t, streamId, state))
        .toList();
}
```

### 4.2 Meta 工具的搜索作用域

```java
// 伪代码
public SearchResult search(ReactAgentState state, String query, String category, int limit) {
    Set<String> excluded = new HashSet<>();
    excluded.addAll(tier1Service.getCurrentTier1Ids());
    excluded.addAll(nullToEmpty(state.activatedToolIds()));
    excluded.addAll(META_TOOL_IDS);    // 搜索自己会死循环
    
    Set<String> allowedScope = state.allowedToolIds();    // 权限过滤
    
    // FTS5 查询 + BM25 排序 + 排除集 + 权限过滤 + limit
    ...
}
```

### 4.3 Skill 激活路径保持不变

- `SkillActivator.activate()` 逻辑不改
- `Skill.suggested_tools` 继续塞入 `activatedToolIds`
- 26 个预置 SKILL.md 一字不动
- `load_skill` / `generate_skill` BuiltinTool 保留

Skill 重构（priority/required/forbidden 三分法、SKILL.md progressive loading）在**独立的 Skill Refactor 项目**中进行，本次不涉及。

## 5. 数据流与错误处理

### 5.1 Happy Path

```
用户输入 "帮我删除 D:/tmp 下的所有日志"
    ↓
ReactAgentLoop.iterate()
    ├─ 构造 prompt: Tier 1 schema (9 个) + 3 meta schema + category hint + activated 工具
    ↓
LLM 调用 1: tools.search { query: "delete files by pattern" }
    ├─ Layer C miss → Layer B miss
    ├─ FTS5 查询 + BM25 排序
    ├─ 排除 Tier 1 / activated / meta / 权限外工具
    ├─ 返回 top-5，写 Layer B / C
    ↓
LLM 调用 2: tools.describe { tool_ids: ["shell.exec"] }
    ├─ Layer A hit
    └─ 返回 schema
    ↓
LLM 调用 3: shell.exec { command: "rm D:/tmp/*.log" }
    └─ 现有 ToolExecutionPipeline 执行（guardrail / 幂等 / 重试）
```

**典型 token 成本**：prompt 常驻 ~6500 + 3 次 LLM 往返。相比现状增加 2 次往返，但仅对长尾工具。

### 5.2 异常分支

| 场景 | 处理 |
|------|------|
| `search` 零结果 | 返回 `confidence: "none"` + hint，LLM 自主换思路 |
| `search` 低置信 | 返回 `confidence: "low"` + hint，若向量 fallback 启用则合并 |
| `describe` 不存在的 id | 返回 `not_found` + suggestion |
| LLM 调用未曝光工具 | ToolBridge 在 `call()` 入口返回结构化错误：`{error: "Tool 'xxx' not exposed. Use tools.search first."}` |
| 工具搜索后卸载 | `registry.resolve()` 空，返回 `TOOL_NOT_FOUND`（现有机制）|
| `describe` 批量部分失败 | 同时返回 `schemas` 和 `not_found`，不整批失败 |
| MCP 工具 schema 变更 | `ToolRegistryEvent.UPDATE` 失效 Layer A 对应 key |
| LLM 同轮重复搜索 | Layer C 命中，近零成本 |

### 5.3 缓存失效时序

```
ToolRegistryEvent（ADD/REMOVE/UPDATE）
    ↓
三监听器并行：
    ├─ ToolSearchIndexMaintainer: INSERT / DELETE / UPDATE 索引
    ├─ SearchCacheInvalidator:    清空 Layer B（粗粒度）
    └─ DescribeCacheInvalidator:  精确失效 Layer A 对应 tool_id
    
Layer C: 不主动失效（TTL + 会话结束清理，容忍短暂 stale）
```

### 5.4 观测指标（Micrometer）

| 指标 | 类型 | 作用 |
|------|------|------|
| `tool_search.invocations` | Counter | search 次数 |
| `tool_search.duration` | Timer | BM25 查询耗时 P50/P95/P99 |
| `tool_search.cache_hit{layer=a/b/c}` | Counter | 三层缓存命中率 |
| `tool_search.empty_results` | Counter | 零结果次数 |
| `tool_search.low_confidence` | Counter | top-1 低于阈值次数 |
| `tool_search.fallback_triggered` | Counter | 向量 fallback 触发次数 |
| `tool_describe.invocations` | Counter | describe 次数 |
| `tool_describe.batch_size` | Histogram | 批量 id 数分布 |
| `tool_invocation.not_exposed` | Counter | 调用未曝光工具次数（期望 ≈ 0）|

**告警阈值**：
- `empty_results / invocations > 10%` → 搜索质量问题，检查 metadata 完整度
- `tool_invocation.not_exposed > 0 持续出现` → LLM 没按链路走，调整 system prompt

## 6. Tier 1 机制 + 命名规范 + 配置

### 6.1 Tier 1 的动态管理

```yaml
agent:
  tools:
    tier1:
      pinned:
        - tools.search
        - tools.describe
        - tools.list
        - file.read
        - file.write
        # ... 最终名单根据使用数据拍板
      
      promotion:
        enabled: true
        window-days: 30
        session-threshold: 0.3        # ≥ 30% 会话使用 → 晋升
        max-promoted: 3                # 每次最多建议 3 个
      
      demotion:
        enabled: true
        idle-days: 60
        respect-pinned: true
```

### 6.2 Tier1AdvisoryJob

```java
@Scheduled(cron = "0 0 3 * * *")
public void reviewTier1() {
    var stats = toolUsageAnalytics.computeSessionCoverage(config.windowDays());
    
    var candidates = stats.entrySet().stream()
        .filter(e -> e.getValue() >= config.sessionThreshold())
        .filter(e -> !tier1Service.isPinned(e.getKey()))
        .sorted(Comparator.comparing(Map.Entry<String,Double>::getValue).reversed())
        .limit(config.maxPromoted())
        .toList();
    
    tier1AdvisoryRepository.saveAll(candidates, window);
    // 不直接改 application.yml，等管理员在 UI 上审批
}
```

### 6.3 工具命名硬规范（ToolValidator 启动时强校验）

| 规则 | 校验 | 违反结果 |
|------|------|----------|
| ID 格式 | `^[a-z][a-z0-9]*(\.[a-z][a-z0-9_]*)+$` | 启动失败 |
| ID 自描述度 | ID 包含动词词根或白名单 namespace | 启动失败 |
| `name` 是中文 | 正则检查包含中文字符 | 启动失败 |
| `description` 英文 + 长度 ≥ 40 字符 | 正则 + 长度 | 启动失败 |
| `tags` 英文 + 数量 ≥ 3（仅 Tier 2） | 正则 + 长度 | 启动失败 |
| `tags` 无重复 | `Set.size == List.size` | 启动失败 |
| `actions.description` 英文 | 正则 | 启动失败 |
| `description` 含主动词（轻量 stemmer） | NLP 检查 | **warn** |

### 6.4 工具定义模板

`BuiltinTool` 现有 `description` 字段**保持原名**，但语义从"中文长描述"转为"英文短描述"（≥ 40 字符，供 LLM 和 BM25 索引使用）。FTS5 表中对应列名 `short_description` 仅做 SQL 侧区分，Java 侧无字段重命名。

```java
BuiltinTool.builder()
    .id("datastore.query_documents")
    .name("查询文档集合")                          // 中文，UI 显示
    .description(                                   // 英文，LLM 读 + BM25 索引
        "Query documents from datastore collection by filter, " +
        "projection, and sort criteria. Supports pagination."
    )
    .tags(List.of(                                  // 英文同义词 ≥ 3
        "query", "find", "search", "retrieve", "list",
        "documents", "datastore", "database", "records"
    ))
    .category(ToolCategory.STORAGE)
    .riskLevel(RiskLevel.LOW)
    .idempotent(true)
    .build();
```

### 6.5 application.yml 完整新段

```yaml
agent:
  tools:
    # 删除旧字段 core-tool-ids
    
    tier1:
      pinned: [...]
      promotion: { enabled: true, window-days: 30, session-threshold: 0.3, max-promoted: 3 }
      demotion:  { enabled: true, idle-days: 60, respect-pinned: true }
    
    search:
      default-limit: 5
      max-limit: 20
      bm25-confidence-threshold: 1.0
      cache:
        layer-a-max-size: 500
        layer-b-max-size: 1000
        layer-b-ttl-minutes: 5
      fallback:
        vector-enabled: false
    
    describe:
      max-batch-size: 10
    
    category-hint: |
      You have access to a tool registry indexed in English.
      Besides the tools visible above, you can discover more via:
        - tools.search(query, category?) to find tools by keyword
        - tools.describe(ids[]) to inspect schemas
        - tools.list(category?) to browse by capability category
      Available categories: PERCEPTION, ACTION, COGNITION, STORAGE,
        INTERACTION, INTROSPECTION, EXTENSION.
      Always use English keywords in search queries.
```

### 6.6 数据库迁移（Flyway V15）

```sql
-- V15__tool_exposure_refactor.sql

CREATE VIRTUAL TABLE tool_search_index USING fts5(
    tool_id UNINDEXED,
    short_description,
    tags,
    actions,
    category,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TABLE tool_usage_stats (
    tool_id TEXT NOT NULL,
    stat_date TEXT NOT NULL,
    session_count INTEGER DEFAULT 0,
    invocation_count INTEGER DEFAULT 0,
    PRIMARY KEY (tool_id, stat_date)
);

CREATE INDEX idx_tool_usage_date ON tool_usage_stats(stat_date);

CREATE TABLE tier1_advisory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tool_id TEXT NOT NULL,
    advised_at TEXT NOT NULL,
    window_days INTEGER NOT NULL,
    coverage_ratio REAL NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    reviewed_by TEXT,
    reviewed_at TEXT
);

CREATE INDEX idx_tier1_advisory_status ON tier1_advisory(status);
```

## 7. 迁移路径 + 测试 + 验收

### 7.1 迁移分 Phase

```
Phase 0 — 准备（1 个 PR）
├─ ToolValidator 新增（启动时校验命名规范）
├─ 现有 ~13 个核心工具的 description 改英文化 + 补 tags
├─ application.yml 新结构加入（tier1/search/describe/category-hint 段）
├─ 旧 core-tool-ids 字段保留（@Deprecated，代码不读）
└─ 验收：mvn clean package 成功 + 应用启动成功

Phase A — 核心实施（1 个 PR）
├─ V15 迁移：tool_search_index / tool_usage_stats / tier1_advisory
├─ ToolSearchIndexBuilder + ToolSearchIndexMaintainer
├─ ToolSearchService（BM25 查询 + sanitize + 3 层缓存）
├─ tools.search / tools.describe / tools.list 三个 BuiltinTool
├─ ToolBridgeAgentToolProvider 过滤逻辑重写
├─ ReactAgentLoop 系统 prompt 加 category-hint
├─ ToolUsageStatsRecorder（拦截 pipeline 写 daily stats）
├─ Tier1AdvisoryJob（每日凌晨计算建议）
└─ Micrometer 指标接入

Phase B — 清理（1 个 PR）
├─ 删除 application.yml 旧 core-tool-ids 字段
├─ 删除 ToolBridgeAgentToolProvider 旧三分支代码
└─ 架构文档 / Skill 文档更新
```

### 7.2 测试策略

**单元测试**（JUnit 5 + jqwik，中文方法名）：
- `ToolSearchService_查询过滤测试.java`
- `ToolSearchService_缓存层级测试.java`
- `ToolSearchService_注入防护测试.java`（jqwik 属性测试）
- `ToolDescribeService_批量处理测试.java`
- `ToolValidator_启动校验测试.java`
- `ToolSearchIndexMaintainer_增量更新测试.java`
- `Tier1AdvisoryJob_晋升计算测试.java`

**集成测试**（`@SpringBootTest`）：
- 搜索未曝光工具 → describe → call 完整链路
- LLM 跳过搜索直调未曝光工具，返回结构化引导
- 受限代理场景 allowedToolIds 同步过滤搜索
- Skill 激活场景工具合并进 Tier 1 可见集合

**质量回归**（`src/test/resources/tool-search-fixtures.yaml`）：
```yaml
fixtures:
  - query: "delete files"
    expected_top_3: ["file.delete", "shell.exec", "file.list"]
  - query: "send message to feishu"
    expected_top_3: ["channel.send_feishu", "notify.send_message"]
  - query: "query database records"
    expected_top_3: ["datastore.query_documents", "knowledge.search"]
  # ... 30-50 条
```
召回率 < 85% 则测试失败。

### 7.3 验收标准（GO/NO-GO）

**功能**：
- 所有现有单元测试通过
- 所有现有集成测试通过（26 个预置 Skill 激活场景）
- 新增 search → describe → call 链路 E2E 通过
- ToolValidator 对所有现有工具校验通过

**性能**：
- Tier 1 任务保持 2 轮延迟（不退化成 4 轮）
- 常驻 prompt token 对比现状净下降 ≥ 20%
- BM25 查询 P95 < 20ms
- `tools.search → describe → call` 额外往返延迟 P95 < 1.5s

**质量**：
- 质量回归测试集召回率 ≥ 85%
- 启动日志零 ToolValidator WARN

**观测**：
- Micrometer 指标全部接入并在 dashboard 可见
- `tool_invocation.not_exposed` 真实流量下 < 1%

### 7.4 回滚预案

Phase A 上线后若严重问题：
1. Git revert Phase A 的 PR
2. V15 迁移保留表结构（下次重建不冲突）
3. 恢复 application.yml 的 core-tool-ids（Phase 0 保留未删）
4. 重启应用，恢复旧行为

不做"同时跑新旧"的兼容层——新项目，成本不值。

### 7.5 潜在风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 召回率不达标（query 质量差）| 中 | 高 | 向量 fallback 开关预留，先补 tags |
| LLM 不遵守 search→describe→call 链路 | 低 | 中 | 结构化错误 hint + 观测报警 |
| FTS5 注入导致查询异常 | 低 | 中 | sanitize + jqwik 属性测试 |
| 26 个 Skill 激活场景有遗漏 | 中 | 中 | 集成测试覆盖所有预置 Skill |
| Tier 1 晋升误判 | 低 | 低 | 只生成 advisory，不改配置 |
| MCP 动态注册与索引竞态 | 低 | 中 | `ToolRegistryEvent` 同步触发 + 测试覆盖 |

## 8. 代码影响清单

### 8.1 新增文件

```
src/main/java/com/lifepilot/tool/
├─ search/
│  ├─ ToolSearchService.java
│  ├─ ToolSearchIndexBuilder.java
│  ├─ ToolSearchIndexMaintainer.java
│  ├─ ToolSearchQuerySanitizer.java
│  ├─ ToolSearchResult.java
│  ├─ ToolDescribeService.java
│  ├─ cache/
│  │  ├─ SchemaCache.java (Layer A)
│  │  ├─ SearchResultCache.java (Layer B)
│  │  └─ SessionSearchMemo.java (Layer C)
│  └─ BuiltinToolSearchProvider.java   (注册 tools.search / describe / list)
│
├─ tier1/
│  ├─ Tier1Service.java
│  ├─ Tier1AdvisoryJob.java
│  ├─ Tier1AdvisoryRepository.java
│  ├─ ToolUsageAnalytics.java
│  └─ ToolUsageStatsRecorder.java
│
└─ validation/
   └─ ToolValidator.java

src/main/resources/db/migration/V15__tool_exposure_refactor.sql
src/test/resources/tool-search-fixtures.yaml
```

### 8.2 修改文件

```
src/main/java/com/lifepilot/tool/
├─ bridge/ToolBridgeAgentToolProvider.java    (过滤逻辑重写)
├─ config/ToolAutoConfiguration.java          (新 Bean 装配)
├─ config/ToolConfigProperties.java           (新配置字段)
├─ registry/BuiltinToolRegistrar.java         (触发 ToolValidator)
└─ BuiltinTool.java                           (补 actionMetadata 已有)

src/main/java/com/lifepilot/agent/
└─ ReactAgentLoop.java                        (prompt 加 category-hint)

src/main/resources/application.yml            (重构 agent.tools 段)
```

### 8.3 预期删除（Phase B）

```
src/main/resources/application.yml            (删 core-tool-ids 字段)
src/main/java/com/lifepilot/tool/bridge/ToolBridgeAgentToolProvider.java
  (删旧三分支分层注入逻辑)
```

## 9. 与现有原则的对齐

- **硬控制 vs 软引导**（memory）：ToolValidator 启动失败（硬）/ category-hint 提示（软）/ priority_tools 搜索 boost（软）——分工清晰
- **Schema 与 Skill 分工**（memory）：工具通用约束写 schema + short_description / 场景流程留给未来 Skill refactor
- **SKILL.md 不引用 docs**（memory）：本次不改 SKILL.md，保持现状
- **新项目无需兼容**（memory）：直接替换，Phase B 删旧代码
- **禁止 prompt 补丁**（memory）：LLM 若出现"调用未曝光工具"，代码层返回结构化错误让 LLM 自学，不在 prompt 里加"只能调 xxx"的矫正

## 10. 开放问题

- **Tier 1 pinned 最终名单**：需要根据实际使用数据（若有）或凭产品直觉拍板。设计中留了 `application.yml` 的配置位，具体名单在 Phase 0 时定。
- **向量 fallback 是否启用**：默认 `false`，观察 Phase A 上线后的召回率数据再决定。如召回率稳定 ≥ 85% 可永久关闭；若 < 80% 则启用。
- **notify 工具定位**：讨论中提及 notify 待定（不是"回复终点"），是否进 Tier 1 pinned 留待最终名单拍板。

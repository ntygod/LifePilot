# 通用数据存储 — 架构设计

> **文档性质**：架构设计文档
> **模块归属**：`com.lifepilot.datastore`
> **最后更新**：2026-04-13
> **实现状态**：✅ 已完成

## 1. 模块概述

通用数据存储（GenericDataStore）为知微的所有扩展模块（Skill、Agent、Workflow）提供统一的、Schema-Free 的数据持久化能力。本模块采用**文档优先**模型：每条记录以 Agent 整理的自然语言富文本（content）为主表示，直接存入知识库 `documents` 表；可选的结构化字段（metadata_json）作为副索引支持排序、过滤、聚合。通过「集合（Collection）+ 知识库文档（Document）+ 可选字段提示（FieldHint）」三层模型，任何扩展模块都能在运行时动态创建数据集合并执行 CRUD 操作，无需编写 Java 代码或 Flyway 迁移脚本。

### 1.1 问题域

| 扩展途径 | 当前存储能力 | 缺口 |
|---------|------------|------|
| YAML Skill | Skill 通过 `file.read(skill=...)` 按需激活，无数据存储 | 完全缺失 |
| 自定义 Agent | `AgentOrchestrator.run()` 执行，无持久化状态 | 完全缺失 |
| Workflow | `WorkflowContext` 为内存 HashMap，仅持久化执行状态 | 领域数据缺失 |
| MCP | 外部服务器自行管理存储 | 不在本模块范围 |

### 1.2 用户需求分类

通过分析个人助手的典型使用场景，用户数据需求归纳为两类存储模式：

**普通集合（结构化列表 + 笔记）**：书单、影单、购物清单、旅行计划、食谱、联系人、记账、日记、会议记录、灵感。CRUD + 过滤 + 排序 + 语义检索。在文档优先模型下，结构化列表和非结构化笔记的行为一致：content 存富文本，metadata 存可查询字段。

**时序集合（指标追踪）**：体重、运动量、睡眠、饮水、学习时长。追加 + 时间范围查询 + 聚合（均值、趋势、极值）。通过集合的 `timeSeries` 布尔标记区分。

### 1.3 设计目标

- 文档优先：每条记录的主表示是自然语言富文本，直接参与 embedding，语义检索效果好
- 消除双存储：文档直接存入知识库 `documents` 表，无异步同步链路，无一致性负担
- Schema-Free + 可选提示：默认无需定义结构，可选声明字段提示（FieldHint）以获得索引加速
- Agent 可发现：Agent 能通过工具调用自动创建集合、写入文档，无需用户手动配置
- 与记忆系统正交：GenericDataStore 存储用户显式管理的领域数据，记忆系统存储 Agent 隐式积累的认知数据


## 2. 架构图

```mermaid
graph TB
    subgraph "通用数据存储"
        DSM["DataStoreManager<br/>(文档优先门面)"]
        COL["Collection<br/>(数据集合)"]
        FH["FieldHint<br/>(可选字段提示)"]
        DSM --> COL
        DSM --> FH
    end

    subgraph "知识库存储层"
        KBM["KnowledgeBaseManager"]
        KDR["DocumentRepository<br/>(knowledge 模块)"]
        DI["DocumentIngester<br/>(分块+embedding)"]
    end

    subgraph "查询引擎"
        CR["CollectionRepository<br/>(ds_collections)"]
        QE["QueryEngine<br/>(动态 SQL 构建)"]
        AGG["AggregationEngine<br/>(时序聚合)"]
    end

    subgraph "工具层"
        STP["StorageToolProvider"]
        DST["DatastoreActionDispatchExecutor<br/>(9 个操作)"]
        STP --> DST
    end

    subgraph "集成层"
        DKBP["DatastoreKnowledgeBaseProvisioner<br/>(内部知识库生命周期)"]
        DSCA["DataStoreCrudAdapter&lt;T&gt;<br/>(泛型领域适配器)"]
    end

    DST -->|"CRUD + 查询"| DSM
    DSM -->|"文档 CRUD"| KDR
    DSM -->|"异步 ingest"| DI
    DSM -->|"删除文档"| KBM
    DSM -->|"集合 CRUD"| CR
    DSM -->|"结构化查询"| QE
    DSM -->|"时序聚合"| AGG
    DSM -->|"知识库绑定"| DKBP
    DSCA -->|"领域 CRUD"| DSM
```

## 3. 核心数据模型

### 3.1 Collection（数据集合）

集合是文档的逻辑容器。每个集合绑定一个内部知识库，文档直接存入知识库 `documents` 表。通过 `timeSeries` 布尔标记区分普通集合和时序集合。

```java
@Builder(toBuilder = true)
public record Collection(
    String id,                     // UUID
    String name,                   // 集合名称（唯一），如 "书单"、"体重记录"
    @Nullable String description,  // 集合描述（可选）
    boolean timeSeries,            // 是否为时序集合
    @Nullable String fieldHintsJson, // 字段提示 JSON（可选，List<FieldHint> 序列化）
    @Nullable String defaultKnowledgeBaseId, // 绑定的内部知识库 ID
    @Nullable String createdBy,    // 创建来源（skill:todo / agent:researcher）
    String createdAt,
    String updatedAt
) {}
```

### 3.2 文档存储（知识库 documents 表）

文档直接存入知识库的 `documents` 表，不再有独立的 `ds_documents` 表。每条 datastore 文档复用知识库现有字段，并使用两个新增列：

| 字段 | 用法 | 说明 |
|------|------|------|
| `source_type` | `'DATASTORE_DOCUMENT'` | 标识文档来源 |
| `source_datastore_id` | 指向 `ds_collections.id` | 集合归属 |
| `source_key` | `"DATASTORE:{collectionId}:{uuid}"` | 幂等标识 |
| `content` | Agent 整理的自然语言富文本 | **新增列**，作为 ingest 的输入源 |
| `metadata_json` | 结构化字段 JSON，如 `{"title":"三体","rating":5}` | 支持 `json_extract` 查询 |
| `recorded_at` | 时序集合的 ISO 8601 时间戳 | **新增列**，仅时序集合使用 |
| `content_hash` | content 的 SHA-256 | 更新时判断 content 是否变化 |
| `file_name` | `"{collection.name} - {title 或 docId}"` | 显示名 |
| `file_path` | `"datastore://{collectionId}/{documentId}"` | 虚拟路径 |
| `mime_type` | `"text/plain"` | 固定值 |

### 3.3 FieldHint（字段提示）

可选的字段提示，存储在 Collection 的 `fieldHintsJson` 中。声明后可获得：Generated Column 索引加速、Agent 查询时的字段描述。**不做写入校验**。

```java
public record FieldHint(
    String name,        // 字段名，如 "rating"
    String type,        // SQLite 亲和类型：TEXT / NUMBER / BOOLEAN
    @Nullable String description  // 语义描述，如 "用户对这本书的喜好评分，1-5"
) {}
```

与旧版 `PropertyDefinition` 的区别：
- 类型从 9 种精简为 3 种（TEXT / NUMBER / BOOLEAN），对应 SQLite 亲和类型
- 去除 `required` 约束，metadata 是自由 JSON
- 仅服务于索引和 Agent 提示，不做写入校验

### 3.4 数据库表设计

```sql
-- 集合表（ds_collections）
CREATE TABLE ds_collections (
    id                        TEXT PRIMARY KEY,
    name                      TEXT NOT NULL UNIQUE,
    description               TEXT,
    time_series               INTEGER NOT NULL DEFAULT 0,
    field_hints_json          TEXT,
    default_knowledge_base_id TEXT,
    created_by                TEXT,
    created_at                TEXT NOT NULL,
    updated_at                TEXT NOT NULL
);

-- 文档存储在知识库 documents 表，新增两列（V4 迁移）：
-- ALTER TABLE documents ADD COLUMN content TEXT;
-- ALTER TABLE documents ADD COLUMN recorded_at TEXT;

-- 时序查询索引
CREATE INDEX idx_documents_ds_recorded_at
    ON documents(source_datastore_id, recorded_at)
    WHERE source_datastore_id IS NOT NULL AND recorded_at IS NOT NULL;
```

**SQLite JSON + Generated Column 索引加速**：当集合声明了 FieldHint 后，通过 `ALTER TABLE` 动态在 `documents` 表添加 Generated Column + partial index，实现对 `metadata_json` 字段的高效查询：

```sql
-- 示例：为 "书单" 集合的 "rating" 字段创建索引
ALTER TABLE documents ADD COLUMN _idx_{prefix}_rating REAL
    GENERATED ALWAYS AS (json_extract(metadata_json, '$.rating')) VIRTUAL;
CREATE INDEX idx_ds_doc_{prefix}_rating ON documents(_idx_{prefix}_rating)
    WHERE source_datastore_id = '{collectionId}';
```

列名格式为 `_idx_{collectionId前8位}_{fieldName}`，partial index 按 `source_datastore_id` 过滤，查询只扫描该集合的行。这是 SQLite 3.31+ 支持的特性，利用 `json_extract` 虚拟生成列实现 B-tree 索引查找，避免全表 JSON 解析扫描。


## 4. 核心组件

### 4.1 DataStoreManager（数据存储管理门面）

- 职责：提供集合和文档的完整 CRUD 操作，是所有外部调用的统一入口
- 集合操作：创建、查询（按名称/类型）、更新、删除（级联删除文档）
- 文档操作：创建、查询（按集合 + 过滤条件）、更新、删除
- 创建集合时如果声明了属性定义，自动创建 Generated Column 索引
- 创建集合时会自动确保一个系统托管的内部 Knowledge Base，并回填 `defaultKnowledgeBaseId`
- 创建/更新集合时会将缺省的 `projectionConfigJson` 归一化为 `{}`，避免运行时写入 `NULL`
- 集合级投影配置会被后续 datastore → knowledge base 同步链路复用
- 写操作标注 `@Transactional`

### 4.2 QueryEngine（动态查询引擎）

- 职责：将用户的过滤、排序、分页条件转换为 SQLite SQL
- 支持的过滤操作：`eq`（等于）、`ne`（不等于）、`gt`/`gte`/`lt`/`lte`（比较）、`contains`（文本包含）、`in`（枚举匹配）
- 有 Generated Column 索引时使用索引列查询，否则降级为 `json_extract` 表达式
- 排序支持 JSON 路径字段排序
- 分页使用 `LIMIT` + `OFFSET`

查询条件模型：

```java
public record QueryFilter(
    String field,       // JSON 路径，如 "rating"、"author"
    FilterOp op,        // 过滤操作
    Object value        // 过滤值
) {}

public enum FilterOp {
    EQ, NE, GT, GTE, LT, LTE, CONTAINS, IN
}

public record QueryRequest(
    String collectionId,
    List<QueryFilter> filters,
    String sortField,
    SortDirection sortDirection,
    int offset,
    int limit
) {}
```

### 4.3 AggregationEngine（时序聚合引擎）

- 职责：为 METRIC 类型集合提供时间范围聚合查询
- 支持的聚合函数：`SUM`、`AVG`、`MIN`、`MAX`、`COUNT`
- 支持按时间粒度分组：`DAY`、`WEEK`、`MONTH`
- 基于 SQLite 的 `strftime` 函数实现时间分组
- 返回聚合结果列表，每项包含时间桶和聚合值

```java
public record AggregationRequest(
    String collectionId,
    String field,           // 聚合字段，如 "weight"
    AggregateFunction func, // SUM / AVG / MIN / MAX / COUNT
    TimeGranularity groupBy,// DAY / WEEK / MONTH
    String startTime,       // ISO 8601 起始时间
    String endTime          // ISO 8601 结束时间
) {}

public record AggregationResult(
    String timeBucket,  // 时间桶，如 "2026-03-01"
    double value        // 聚合值
) {}
```

### 4.4 CollectionRepository / DocumentRepository

- 基于 JdbcTemplate 的 SQLite 存储层
- CollectionRepository：集合表 CRUD + 按名称/类型查询
- DocumentRepository：文档表 CRUD + 动态 SQL 查询 + 时序聚合
- Generated Column 管理：创建/删除集合时动态添加/清理虚拟列和索引

### 4.5 Datastore → Knowledge 检索链路

- 每个 Datastore 默认对应一个内部 Knowledge Base
- 结构化数据先经过 `projection_config_json` 投影为检索文本
- 投影后的文本进入分块、向量索引和 FTS 索引
- 会话绑定 Datastore 后，资料型问题统一通过 `knowledge.search` 命中对应作用域
- `datastore.query_documents` 只负责结构化过滤、排序、分页，不承担语义检索职责

### 4.6 DataStoreTool（Agent 工具集）

注册为 BuiltinTool，提供 8 个操作供 Agent 调用：

| 工具 ID | 操作 | 说明 |
|---------|------|------|
| `datastore.create_collection` | 创建集合 | 指定名称、类型、可选属性定义、可选 `projectionConfig` |
| `datastore.list_collections` | 列出集合 | 返回所有集合及其属性定义 |
| `datastore.delete_collection` | 删除集合 | 删除整个集合及其文档 |
| `datastore.add_document` | 添加文档 | 向指定集合写入 JSON 文档 |
| `datastore.query_documents` | 查询文档 | 按过滤条件查询，支持排序分页，仅用于结构化查询 |
| `datastore.update_document` | 更新文档 | 按 ID 更新文档数据 |
| `datastore.delete_document` | 删除文档 | 按 ID 删除文档 |
| `datastore.aggregate` | 聚合查询 | METRIC 类型集合的时序聚合 |

Agent 使用示例：
- 用户说「帮我记一下这本书：三体，刘慈欣，评分 5 分」
- Agent 调用 `list_collections` 查找是否有「书单」集合
- 如果没有，调用 `create_collection` 创建，属性定义包含 title(TEXT)、author(TEXT)、rating(NUMBER)
- 调用 `add_document` 写入 `{"title":"三体","author":"刘慈欣","rating":5}`

`create_collection` 的 `projectionConfig` 参数为可选项；如果未提供，系统会自动保存 `{}`，表示启用默认通用投影策略。

### 4.7 DataStoreSkillProvider（内置 Skill 提供者）

- 实现 `BuiltinSkillProvider` 接口，注册 DataStore 相关工具
- 提供 Skill 定义蓝图，包含数据存储操作的指令模板
- 通过 `@BuiltinSkill(id = "datastore", order = 5)` 注册，优先级高于具体业务 Skill


## 5. 核心流程

### 5.1 Agent 自动创建集合并写入文档

```mermaid
sequenceDiagram
    participant U as 用户
    participant AL as ReactAgentLoop
    participant DST as DataStoreTool
    participant DSM as DataStoreManager
    participant DB as SQLite

    U->>AL: "帮我记一下这本书：三体，刘慈欣，评分5分"
    AL->>DST: list_collections()
    DST->>DSM: listCollections()
    DSM-->>DST: [] (空)
    DST-->>AL: 无"书单"集合

    AL->>DST: create_collection("书单", DOCUMENT, properties=[...])
    DST->>DSM: createCollection(...)
    DSM->>DB: INSERT INTO ds_collections
    DSM->>DB: ALTER TABLE 添加 Generated Column + 索引
    DSM-->>DST: collectionId

    AL->>DST: add_document(collectionId, {"title":"三体","author":"刘慈欣","rating":5})
    DST->>DSM: addDocument(...)
    DSM->>DB: INSERT INTO ds_documents
    DSM-->>DST: documentId
    DST-->>AL: 成功
    AL-->>U: "已添加到书单：三体"
```

### 5.2 时序指标追踪与聚合查询

```mermaid
sequenceDiagram
    participant U as 用户
    participant AL as ReactAgentLoop
    participant DST as DataStoreTool
    participant DSM as DataStoreManager
    participant AGG as AggregationEngine

    U->>AL: "今天体重 72.5kg"
    AL->>DST: add_document("体重记录", {"weight":72.5}, recordedAt=now)
    DST->>DSM: addDocument(...)
    DSM-->>DST: documentId

    U->>AL: "这个月体重趋势怎么样？"
    AL->>DST: aggregate("体重记录", field="weight", func=AVG, groupBy=WEEK)
    DST->>DSM: aggregate(...)
    DSM->>AGG: execute(request)
    AGG-->>DSM: [{"2026-W09": 73.1}, {"2026-W10": 72.8}, ...]
    DSM-->>DST: 聚合结果
    DST-->>AL: 周均值趋势数据
    AL-->>U: "本月体重呈下降趋势，从73.1降到72.5"
```

## 6. 设计决策

| 决策 | 选择 | 备选方案 | 理由 |
|------|------|---------|------|
| 存储模型 | JSON 文档 + 可选属性定义 | EAV 模型 / 动态建表 | JSON 文档最灵活，SQLite JSON 函数成熟；EAV 查询复杂度高；动态建表需要运行时 DDL 管理 |
| 索引加速 | SQLite Generated Column + json_extract | 全表 JSON 扫描 / 独立索引表 | Generated Column 是 SQLite 原生特性，零额外存储（VIRTUAL），B-tree 索引性能等同普通列 |
| 集合类型区分 | CollectionType 枚举（DOCUMENT/NOTE/METRIC） | 统一类型 | 三种类型的查询模式差异大：DOCUMENT 需过滤排序，NOTE 需全文搜索，METRIC 需时序聚合 |
| 全文搜索 | FTS5 虚拟表 | json_extract LIKE | FTS5 支持分词、排名、高亮，性能远优于 LIKE 模糊匹配 |
| 时序聚合 | SQLite strftime 分组 | 应用层聚合 | SQLite 内置时间函数足够，数据量在个人助手场景下不会成为瓶颈 |
| 与记忆系统关系 | 正交独立 | 复用 L3 语义记忆 | 记忆系统存储 Agent 隐式认知数据，DataStore 存储用户显式管理的领域数据，职责不同 |
| 工具暴露方式 | BuiltinTool（8 个操作） | 每个集合动态生成工具 | 固定工具集更简单，Agent 通过参数区分集合；动态工具会导致工具注册表膨胀 |

### 6.1 调研参考

**Notion 数据模型**：Notion 采用「Everything is a Block」理念，Database 是特殊的 Block 容器，每个 Page 是 Database 中的一行。Property 类型丰富（22 种），包括 checkbox、date、select、relation 等。知微借鉴其 Database + Property 概念，简化为 Collection + PropertyDefinition，属性类型精简为 9 种覆盖个人助手场景。

**LangGraph BaseStore**：LangChain/LangGraph 的长期记忆采用 namespace + key-value JSON 文档模型，支持层级命名空间和可选向量搜索。知微借鉴其 JSON 文档存储理念，但增加了集合级属性定义和时序聚合能力，更适合结构化数据管理。

**SQLite JSON + Generated Column**：SQLite 3.31+ 支持 Generated Column，结合 json_extract 可创建虚拟列并建立 B-tree 索引，查询性能等同普通列。这是本模块索引加速的核心技术，避免了引入额外存储引擎。

**Mnemora（AI Agent Memory DB）**：采用直接数据库 CRUD 路径（不在读路径引入 LLM），状态读取延迟 < 10ms。知微 DataStore 同样采用纯数据库 CRUD 路径，LLM 仅在 Agent 决策层参与。

## 7. 集成点

| 依赖模块 | 交互方式 | 说明 |
|---------|---------|------|
| Tool System (`com.lifepilot.tool`) | BuiltinTool 注册 | DataStoreTool 注册到 DynamicToolRegistry |
| Skill System (`com.lifepilot.skill`) | BuiltinSkillProvider | DataStoreSkillProvider 提供 Skill 定义 |
| Sync Engine (`com.lifepilot.sync`) | SyncConnector 扩展 | 未来扩展：DataStoreSyncAdapter 支持同步扩展数据 |
| Workflow (`com.lifepilot.workflow`) | WorkflowStep 扩展 | 未来扩展：DataStoreWorkflowAdapter 支持工作流读写 |
| Prompt Management (`com.lifepilot.prompt`) | PromptRegistry | 数据存储 Skill 指令模板 |

## 8. 配置参考

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.datastore.enabled` | `true` | 通用数据存储总开关 |
| `lifepilot.datastore.max-collections` | `100` | 最大集合数量 |
| `lifepilot.datastore.max-documents-per-collection` | `10000` | 单集合最大文档数 |
| `lifepilot.datastore.max-document-size-bytes` | `65536` | 单文档 JSON 最大字节数（64KB） |
| `lifepilot.datastore.default-page-size` | `20` | 默认分页大小 |
| `lifepilot.datastore.max-page-size` | `100` | 最大分页大小 |
| `lifepilot.datastore.index-threshold` | `100` | 文档数超过此阈值时建议创建属性索引 |

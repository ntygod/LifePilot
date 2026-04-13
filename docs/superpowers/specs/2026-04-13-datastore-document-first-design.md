# Datastore 文档优先改造设计

> **文档性质**：设计规格
> **模块归属**：`com.lifepilot.datastore` + `com.lifepilot.knowledge`
> **日期**：2026-04-13
> **状态**：待实施

## 1. 背景与动机

Datastore 的设计初衷是让 Agent 主动管理用户的领域数据，包括结构化和语义化两面。当前实现以结构化 JSON 为主存储，通过投影链路（JSON → 文本 → 分块 → embedding）同步到知识库实现语义检索。

这个架构存在两个核心问题：

1. **DOCUMENT 类型的投影质量差** — 一条 `{"title":"三体","author":"刘慈欣","rating":5}` 投影出来信息密度极低，向量检索效果不佳。Agent 提取结构化字段时丢弃了用户原话中的描述性信息（"硬科幻"、"讲三体文明入侵地球"、"很好看"），投影又无法还原这些语义。先压缩再解压，两头都有损。

2. **双存储 + 异步同步的复杂度** — 数据存在 ds_documents 和知识库两处，5 秒轮询同步，存在延迟和一致性负担。Agent 面对两条检索路径（datastore.query vs knowledge.search）需要做路由判断。

## 2. 设计目标

- 文档优先：每条记录的主表示是 Agent 整理的自然语言富文本，直接参与 embedding
- 消除双存储：Datastore 文档直接存入知识库，砍掉同步链路
- 保留结构化能力：metadata 作为副索引，支持排序、过滤、聚合
- 简化 Agent 心智模型：写入时整理自然语言 + 附带可选结构化字段，检索时以语义为主路径

## 3. 关键决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 数据迁移 | 不做，清空重来 | 功能早期，数据量小，集中精力做对新模型 |
| 集合类型 | 普通 + 时序（布尔标记） | NOTE/DOCUMENT 在文档优先模型下行为一致，合并；METRIC 因聚合需求保留为时序标记 |
| PropertyDefinition | 替换为 FieldHint（可选、无校验） | 去掉写入校验，仅保留索引和 Agent 提示两个用途 |
| 存储方案 | Datastore 文档直接存入知识库（方案 B） | 一份数据、无同步延迟、检索路径统一 |

## 4. 数据模型

### 4.1 ds_collections（保留，精简）

```sql
CREATE TABLE ds_collections (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    time_series INTEGER NOT NULL DEFAULT 0,
    field_hints_json TEXT,
    default_knowledge_base_id TEXT,
    created_by TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

字段变化：
- `type TEXT` → `time_series INTEGER`：枚举改为布尔标记
- `properties_json` → `field_hints_json`：语义变更，配套改名
- 删除 `projection_config_json`：不需要投影配置，content 由 Agent 直接提供
- 删除 `metadata_json`：集合级扩展元数据无实际用途

### 4.2 ds_documents（删除）

文档直接存入 documents 表（知识库现有表），复用和新增字段如下：

**现有字段复用**：

| 字段 | 用法 | 说明 |
|------|------|------|
| `source_type` | 值为 `'DATASTORE_DOCUMENT'` | 沿用现有枚举值，不改名 |
| `source_datastore_id` | 指向 ds_collections.id | 已有列，已有索引 |
| `source_key` | `"DATASTORE:{collectionId}:{uuid}"` | 沿用现有格式，uuid 在写入时生成，用于幂等 |
| `metadata_json` | 结构化字段，如 `{"title":"三体","rating":5}` | 已有列（`TEXT NOT NULL DEFAULT '{}'`），当前存 `Map<String, String>`，需改为 `Map<String, Object>` 以支持数值类型 |
| `file_name` | `"{collection.name} - {title 或 docId}"` | NOT NULL 列，沿用投影器的现有约定 |
| `file_path` | `"datastore://{collectionId}/{documentId}"` | NOT NULL 列，沿用投影器的现有约定 |
| `file_size` | content 的 UTF-8 字节数 | NOT NULL 列，填实际大小 |
| `mime_type` | `"text/plain"` | NOT NULL 列，datastore 文档统一为纯文本 |
| `content_hash` | content 的 SHA-256 | 用于更新时判断 content 是否变化，决定是否重新 ingest |

**新增列**（Flyway 迁移添加）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | `TEXT` | 文档正文，Agent 整理的自然语言富文本。仅 datastore 文档使用，文件类文档此列为 NULL |
| `recorded_at` | `TEXT` | 时序集合的 ISO 8601 时间戳。仅时序集合文档使用 |

**废弃列**：

| 字段 | 处理 |
|------|------|
| `source_collection_id` | 不再使用。当前与 `source_datastore_id` 语义重复（都指向 ds_collections.id）。保留列但不再写入，后续版本可清理 |

content 直接存在 documents 表上，作为 ingest 的输入源。更新时通过 content_hash 比对判断是否需要重新分块和 embedding。

### 4.3 FieldHint（替代 PropertyDefinition）

```java
record FieldHint(
    String name,        // 字段名，如 "rating"
    String type,        // TEXT / NUMBER / BOOLEAN（SQLite 亲和类型）
    String description  // 语义描述，如 "用户对这本书的喜好评分，1-5"
)
```

- 无 `required` 约束，metadata 是自由 JSON
- type 从 9 种简化为 3 种，对应 SQLite 亲和类型
- description 服务于两个用途：Agent 查询时的字段提示 + 集合上下文中的语义说明
- Generated Column 根据 FieldHint 创建在 documents 表上，partial index 按 `source_datastore_id` 过滤
- Generated Column 管理通过 KnowledgeBaseManager 暴露的接口操作（见 §6.2），不由 datastore 模块直接执行 DDL

## 5. 写入路径

Agent 调用 `datastore.add(collectionId, content, metadata, recordedAt)`：

```
1. 校验集合存在、文档数量限额
2. 时序集合 → 校验 recordedAt 非空且 ISO 8601 格式合法
3. 生成 sourceKey = "DATASTORE:{collectionId}:{uuid}"
4. 计算 contentHash = SHA-256(content)
5. 调用 KnowledgeBaseManager 创建知识库文档：
     - knowledgeBaseId = collection.defaultKnowledgeBaseId
     - content = Agent 传入的原始文本（不拼接集合前缀）
     - contextPrefix = "[{collection.name}] {collection.description}"
     - sourceType = DATASTORE_DOCUMENT
     - sourceKey = 步骤 3 生成的 key
     - sourceDatastoreId = collectionId
     - metadataJson = Agent 传入的 metadata
     - recordedAt = Agent 传入的 recordedAt
     - fileName = "{collection.name} - {title 或 docId}"
     - filePath = "datastore://{collectionId}/{documentId}"
     - fileSize = content 的 UTF-8 字节数
     - mimeType = "text/plain"
     - contentHash = 步骤 4 的哈希值
6. 文档记录同步创建（状态 CHUNKING，跳过 UPLOADING/PARSING）
7. 异步 ingest：分块 → embedding → FTS 索引
   分块时 contextPrefix 自动注入每个 chunk，参与 embedding 但不存入 content
8. 返回文档 ID
```

**集合上下文注入方式**：不拼入 content，而是通过 document_chunks 表已有的 `context_prefix` 列注入。好处：
- content 保持干净，更新时 diff 简单
- 集合描述变更后，重新 ingest 时自动使用最新描述，无需修改已存储的 content
- 与知识库现有的 chunk embedding 机制一致（`embeddingText() = contextPrefix + content`）

**更新操作**：

| 变更内容 | 判断方式 | 操作 | 文档状态转换 |
|---------|---------|------|------------|
| content 变了 | contentHash 不同 | 更新 content + 重新 ingest | UPDATING → CHUNKING → INDEXING → READY |
| 只改 metadata | contentHash 相同 | UPDATE metadata_json | 状态不变（保持 READY） |
| 两者都变 | contentHash 不同 | 更新 content + metadata + 重新 ingest | UPDATING → CHUNKING → INDEXING → READY |

**初始状态**：Datastore 文档跳过 UPLOADING 和 PARSING（无需上传和文件解析），直接从 CHUNKING 开始。

Skill 指令中的写入核心规则：

> 写入时，content 必须包含用户原话中所有有助于日后检索的信息。结构化字段只提取需要排序、过滤、聚合的数值和枚举值，放入 metadata。不要为了提取字段而丢弃用户的描述性语言。
> 写入前，先用 query 或 list 检查是否已有同名记录，避免重复添加。

## 6. 读取路径

### 6.1 语义检索（主路径）

Agent 调用 `knowledge.search(datastoreId=X, query="...")`。

文档已经在知识库中，无需任何改造。向量检索 + FTS + 图谱 → RRF 融合 → 返回命中文档。

### 6.2 结构化查询（精确路径）

Agent 调用 `datastore.query(collectionId, filters, sort, limit)`。

QueryEngine 生成 SQL 查询 documents 表：
- `WHERE source_datastore_id = ?` 替代原来的 `WHERE collection_id = ?`
- Generated Column 索引通过 partial index 按 `source_datastore_id` 过滤
- metadata_json 的 json_extract 和 Generated Column 逻辑与现有 ds_documents 一致
- 其余过滤、排序、分页逻辑不变

**Generated Column 跨模块管理**：

datastore 需要在 documents 表（knowledge 模块）上创建 Generated Column。为保持模块边界清晰，由 KnowledgeBaseManager 暴露 DDL 接口：

```java
// knowledge 模块暴露
void addDocumentMetadataIndex(String columnName, String sqliteAffinity, String filterDatastoreId);
void dropDocumentMetadataIndexes(String filterDatastoreId, List<String> columnNames);
```

DataStoreManager 通过此接口管理索引，不直接操作 documents 表的 DDL。

### 6.3 时序聚合（时序集合专用）

Agent 调用 `datastore.aggregate(collectionId, field, func, groupBy)`。

AggregationEngine 生成 SQL 查询 documents 表：
- 聚合目标从 ds_documents 改为 documents
- 按 `source_datastore_id` + `recorded_at` 范围过滤
- 聚合函数和时间分组逻辑不变

### 6.4 Agent 检索决策规则

```
能翻译成字段条件 → datastore.query     （快、精确）
不能             → knowledge.search    （语义、模糊）
需要统计         → datastore.aggregate （数值聚合）
不确定           → knowledge.search 优先，必要时补充 datastore.query
```

## 7. Agent 工具接口

| 操作 | 参数 | 说明 |
|------|------|------|
| `create-collection` | name, description, timeSeries, fieldHints | 创建集合，自动绑定内部知识库 |
| `list-collections` | (无) | 列出所有集合 |
| `update-collection` | id, description, fieldHints | 更新集合描述或字段提示 |
| `delete-collection` | id | 删除集合及其所有文档 |
| `add` | collectionId, content, metadata, recordedAt | 添加文档 |
| `get` | id | 按 ID 获取单个文档（含 content + metadata） |
| `query` | collectionId, filters, sort, limit | 结构化查询 |
| `update` | id, content, metadata | 更新文档（按 contentHash 判断是否重新 embedding） |
| `delete` | id | 删除文档 |
| `aggregate` | collectionId, field, func, groupBy, startTime, endTime | 时序聚合 |

**delete-collection 的级联策略**：

1. 删除绑定的内部知识库（`deleteDefaultKnowledgeBase`），知识库的 `ON DELETE CASCADE` 外键自动级联删除所有关联的 documents 和 chunks
2. 清理 documents 表上该集合的 Generated Column 索引（通过 `KnowledgeBaseManager.dropDocumentMetadataIndexes`）
3. 删除 ds_collections 记录

**去重策略**：

系统层面不做自动去重。Agent 通过 Skill 指令引导在写入前检查是否存在同名记录。source_key（`DATASTORE:{collectionId}:{uuid}`）保证每条文档的唯一标识，但不阻止内容相似的文档被多次添加。

## 8. 删除和简化的组件

### 8.1 删除

| 组件 | 文件 | 原因 |
|------|------|------|
| DocumentRepository | `datastore/repository/DocumentRepository.java` | ds_documents 表不存在了 |
| DataStoreKnowledgeSyncPublisher | `datastore/sync/DataStoreKnowledgeSyncPublisher.java` | 不需要同步了 |
| DataStoreKnowledgeSyncJobPublisher | `knowledge/sync/DataStoreKnowledgeSyncJobPublisher.java` | 同上 |
| DatastoreDocumentProjector | `knowledge/sync/DatastoreDocumentProjector.java` | content 由 Agent 直接提供 |
| PropertyDefinition | `datastore/model/PropertyDefinition.java` | 被 FieldHint 替代 |
| PropertyValidator | `datastore/validation/PropertyValidator.java` | 不做写入校验 |
| PropertyType | `datastore/model/PropertyType.java` | FieldHint.type 用字符串，3 种亲和类型 |
| CollectionType | `datastore/model/CollectionType.java` | 改为 timeSeries 布尔标记 |
| 同步 Worker 的 datastore job 类型 | `UPSERT/DELETE/RESYNC/PURGE_DATASTORE_*` | 无需异步同步 |

### 8.2 简化

| 组件 | 变化 |
|------|------|
| DataStoreManager | 文档操作委托给 KnowledgeBaseManager，不再自己管文档存储 |
| QueryEngine | 查询目标从 ds_documents → knowledge_documents |
| AggregationEngine | 同上 |
| CollectionRepository | 去掉 Generated Column 直接 DDL，改为通过 KnowledgeBaseManager 接口操作 |
| DatastoreKnowledgeBaseProvisioner | 保留，职责不变（管理内部知识库生命周期） |
| KnowledgeSyncWorker | 去掉 4 种 datastore 相关的 job type |

### 8.3 保留不变

- ds_collections 表 + CollectionRepository（集合 CRUD）
- DataStoreProperties（配置限额）
- FieldNames（字段名安全校验）
- DataStoreCrudAdapter（上层适配器，接口调整）
- StorageToolProvider + DatastoreActionDispatchExecutor（工具注册和分发，接口调整）

## 9. Flyway 迁移

新建迁移脚本 `V{n}__datastore_document_first.sql`：

```sql
-- 1. 清理旧 datastore 数据
DROP TABLE IF EXISTS ds_documents_fts;
DROP TABLE IF EXISTS ds_documents;
DROP TABLE IF EXISTS ds_collections;

-- 2. 重建 ds_collections（新结构）
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

-- 3. documents 表新增列
ALTER TABLE documents ADD COLUMN content TEXT;
ALTER TABLE documents ADD COLUMN recorded_at TEXT;

-- 4. 为 datastore 结构化查询建索引
CREATE INDEX idx_documents_recorded_at
    ON documents(source_datastore_id, recorded_at)
    WHERE source_datastore_id IS NOT NULL;
```

注意事项：
- `content` 列为 nullable：文件类文档不使用此列，仅 datastore 文档写入
- `recorded_at` 列为 nullable：仅时序集合文档写入
- `metadata_json` 列已存在（`TEXT NOT NULL DEFAULT '{}'`），但 Java 模型需从 `Map<String, String>` 改为 `Map<String, Object>` 以支持数值类型
- 已有的 `source_datastore_id`、`source_key`、`source_type` 列和索引无需改动
- 清理旧表会同时删除旧 datastore 数据和旧 Generated Column（决策为不做迁移）

## 10. 风险与缓解

| 风险 | 缓解 |
|------|------|
| documents 表行数增加，Generated Column 在非 datastore 行上的开销 | VIRTUAL Generated Column 无存储开销；partial index 按 `source_datastore_id` 过滤，查询只扫描相关行 |
| metadata 更新不应触发 re-embedding | 通过 contentHash 比对判断 content 是否变化，hash 相同则只执行 metadata UPDATE，状态保持 READY |
| Agent 写入的 content 质量不一致 | Skill 指令明确要求保留用户描述性信息；contextPrefix 注入集合上下文兜底 |
| 知识库模块被 datastore 逻辑侵入 | KnowledgeBaseManager 暴露 `addDocumentMetadataIndex` / `dropDocumentMetadataIndexes` 接口，datastore 走正门操作，不直接执行 DDL |
| 集合描述更新后已有文档的 embedding 不含新描述 | contextPrefix 在分块时注入，集合描述变更后需对该集合文档重新 ingest 才能生效。当前接受此延迟，后续可加"集合描述变更 → 批量重 ingest"能力 |
| metadata_json 类型从 `Map<String, String>` 改为 `Map<String, Object>` | documents 表的 metadata_json 列本身是 TEXT，JSON 格式兼容。只需修改 Java 侧序列化/反序列化逻辑，不影响现有 FILE 类型文档（其 metadata 仍为 string-value） |

# Datastore 文档优先改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Datastore 从结构化 JSON 优先改为文档优先，文档直接存入知识库，砍掉同步链路，保留结构化查询作为副索引。

**Architecture:** Datastore 文档以富文本 content 为主表示，直接存入 knowledge documents 表。metadata_json 作为副索引支持排序/过滤/聚合。集合类型简化为普通+时序（布尔标记），PropertyDefinition 替换为 FieldHint。同步链路（投影器、发布器、Worker job）全部移除。

**Tech Stack:** Java 22, Spring Boot, SQLite (WAL), Flyway, JUnit 5 + jqwik

**设计规格:** `docs/superpowers/specs/2026-04-13-datastore-document-first-design.md`

---

## 文件结构概览

### 新建文件

| 文件 | 职责 |
|------|------|
| `src/main/resources/db/migration/V4__datastore_document_first.sql` | 数据库迁移脚本 |
| `src/main/java/com/lifepilot/datastore/model/FieldHint.java` | 字段提示 record（替代 PropertyDefinition） |

### 修改文件

| 文件 | 主要变更 |
|------|---------|
| `datastore/model/Collection.java` | type → timeSeries，propertiesJson → fieldHintsJson，去掉 projectionConfigJson/metadataJson |
| `datastore/repository/CollectionRepository.java` | 适配新 Collection 字段，Generated Column 改为委托 KnowledgeBaseManager |
| `datastore/DataStoreManager.java` | 文档操作委托知识库，去掉同步/校验依赖 |
| `datastore/config/DataStoreAutoConfiguration.java` | 去掉 DocumentRepository/PropertyValidator bean，调整 DataStoreManager 构造 |
| `datastore/engine/QueryEngine.java` | 表名 ds_documents → documents，列名 collection_id → source_datastore_id |
| `datastore/engine/AggregationEngine.java` | 同上 |
| `datastore/adapter/DataStoreCrudAdapter.java` | 适配新 content+metadata 模型 |
| `knowledge/KnowledgeBaseManager.java` | 新增 createDatastoreDocument / updateDatastoreDocument / addDocumentMetadataIndex 等方法 |
| `knowledge/model/Document.java` | 新增 content、recordedAt 字段 |
| `knowledge/repository/DocumentRepository.java` | 读写新增的 content、recorded_at 列 |
| `knowledge/ingest/DocumentIngester.java` | 调整 ingestProjectedDocument → ingestDatastoreDocument（接收 contextPrefix） |
| `knowledge/sync/KnowledgeSyncWorker.java` | 去掉 4 种 DATASTORE job type |
| `meta/infra/storage/DatastoreActionDispatchExecutor.java` | 适配新工具参数（content+metadata），新增 get 操作 |
| `interaction/web/controller/DatastoreController.java` | 适配新 Collection 字段 |
| `interaction/web/model/CreateDatastoreRequest.java` | timeSeries + fieldHints 替代 type + properties |
| `src/main/resources/prompts/skill/datastore.st` | 新 Skill 指令 |

### 删除文件

| 文件 | 原因 |
|------|------|
| `datastore/repository/DocumentRepository.java` | ds_documents 表已删除 |
| `datastore/model/PropertyDefinition.java` | 被 FieldHint 替代 |
| `datastore/model/PropertyType.java` | FieldHint.type 用字符串 |
| `datastore/model/CollectionType.java` | 改为 timeSeries 布尔 |
| `datastore/model/Document.java` | datastore 文档即知识库文档 |
| `datastore/validation/PropertyValidator.java` | 不做写入校验 |
| `datastore/sync/DataStoreKnowledgeSyncPublisher.java` | 同步链路移除 |
| `knowledge/sync/DataStoreKnowledgeSyncJobPublisher.java` | 同步链路移除 |
| `knowledge/sync/DatastoreDocumentProjector.java` | 投影逻辑移除 |

### 测试文件（重写/删除）

| 文件 | 处理 |
|------|------|
| `datastore/repository/DocumentRepository_单元测试.java` | 删除 |
| `datastore/validation/PropertyValidator单元测试.java` | 删除 |
| `knowledge/sync/DatastoreDocumentProjectorTest.java` | 删除 |
| `datastore/DataStoreManager集成测试.java` | 重写 |
| `datastore/DataStoreManagerCreateCollectionRollbackTest.java` | 重写 |
| `datastore/DataStoreManagerProjectionConfigTest.java` | 删除 |
| `datastore/engine/QueryEngine单元测试.java` | 更新表名/列名 |
| `datastore/engine/AggregationEngine单元测试.java` | 更新表名/列名 |
| `datastore/repository/CollectionRepository_单元测试.java` | 更新字段 |
| `datastore/adapter/DataStoreCrudAdapter_单元测试.java` | 适配新模型 |
| `interaction/web/controller/DatastoreControllerTest.java` | 适配新字段 |
| `interaction/web/controller/DatastoreController_管理端点测试.java` | 适配新字段 |

---

## Task 1: Flyway 迁移脚本

**Files:**
- Create: `src/main/resources/db/migration/V4__datastore_document_first.sql`

- [ ] **Step 1: 编写迁移脚本**

```sql
-- V4__datastore_document_first.sql
-- Datastore 文档优先改造：删除旧表，重建集合表，扩展 documents 表

-- 1. 清理旧 datastore 表和数据
DROP TABLE IF EXISTS ds_documents_fts;
DROP TABLE IF EXISTS ds_documents;
DROP TABLE IF EXISTS ds_collections;

-- 2. 删除旧 datastore 同步到知识库的残留文档
DELETE FROM document_chunks WHERE source_type = 'DATASTORE_DOCUMENT';
DELETE FROM documents WHERE source_type = 'DATASTORE_DOCUMENT';

-- 3. 重建 ds_collections（新结构）
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

-- 4. documents 表新增列
ALTER TABLE documents ADD COLUMN content TEXT;
ALTER TABLE documents ADD COLUMN recorded_at TEXT;

-- 5. 为 datastore 时序查询建索引
CREATE INDEX idx_documents_ds_recorded_at
    ON documents(source_datastore_id, recorded_at)
    WHERE source_datastore_id IS NOT NULL AND recorded_at IS NOT NULL;
```

- [ ] **Step 2: 运行编译验证迁移脚本语法**

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS（Flyway 在启动时会校验迁移脚本）

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/db/migration/V4__datastore_document_first.sql
git commit -m "feat(datastore): V4 迁移脚本 — 文档优先改造表结构变更"
```

---

## Task 2: 数据模型层改造

**Files:**
- Create: `src/main/java/com/lifepilot/datastore/model/FieldHint.java`
- Modify: `src/main/java/com/lifepilot/datastore/model/Collection.java`
- Delete: `src/main/java/com/lifepilot/datastore/model/PropertyDefinition.java`
- Delete: `src/main/java/com/lifepilot/datastore/model/PropertyType.java`
- Delete: `src/main/java/com/lifepilot/datastore/model/CollectionType.java`
- Delete: `src/main/java/com/lifepilot/datastore/model/Document.java` (datastore 侧)
- Modify: `src/main/java/com/lifepilot/knowledge/model/Document.java` (knowledge 侧)

- [ ] **Step 1: 创建 FieldHint record**

```java
package com.lifepilot.datastore.model;

import org.springframework.lang.Nullable;

/**
 * 字段提示 — 集合的可选元数据字段声明。
 *
 * <p>用于两个用途：(1) 在 documents 表上创建 Generated Column 索引加速结构化查询
 * (2) 为 Agent 提供可查询字段的提示信息。不做写入校验。</p>
 *
 * @param name        字段名，如 "rating"
 * @param type        SQLite 亲和类型：TEXT / NUMBER / BOOLEAN
 * @param description 语义描述，如 "用户对这本书的喜好评分，1-5"
 * @author zsg
 * @since 2026-04-13
 */
public record FieldHint(
        String name,
        String type,
        @Nullable String description
) {

    /** 校验 type 是否为合法的 SQLite 亲和类型。 */
    public String toSqliteAffinity() {
        return switch (type.toUpperCase()) {
            case "NUMBER" -> "REAL";
            case "BOOLEAN" -> "INTEGER";
            case "TEXT" -> "TEXT";
            default -> throw new IllegalArgumentException("不支持的字段类型: " + type + "，仅支持 TEXT/NUMBER/BOOLEAN");
        };
    }
}
```

- [ ] **Step 2: 重写 Collection record**

将 `src/main/java/com/lifepilot/datastore/model/Collection.java` 替换为：

```java
package com.lifepilot.datastore.model;

import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 数据集合 — 文档的逻辑容器。
 *
 * <p>每个集合绑定一个内部知识库，文档直接存入知识库表。
 * {@code timeSeries} 标记决定是否为时序集合（支持 recordedAt 和聚合查询）。</p>
 *
 * @author zsg
 * @since 2026-04-13
 */
@Builder(toBuilder = true)
public record Collection(
        String id,
        String name,
        @Nullable String description,
        boolean timeSeries,
        @Nullable String fieldHintsJson,
        @Nullable String defaultKnowledgeBaseId,
        @Nullable String createdBy,
        String createdAt,
        String updatedAt
) {}
```

- [ ] **Step 3: 在 knowledge Document record 中新增 content 和 recordedAt 字段**

修改 `src/main/java/com/lifepilot/knowledge/model/Document.java`，在 record 参数列表末尾新增两个字段：

```java
// 在 sourceRef 之后新增
@Nullable String content,
@Nullable String recordedAt
```

同时更新两个构造函数以传递 `null` 作为默认值，保持向后兼容。

- [ ] **Step 4: 删除旧模型文件**

删除以下文件：
- `src/main/java/com/lifepilot/datastore/model/PropertyDefinition.java`
- `src/main/java/com/lifepilot/datastore/model/PropertyType.java`
- `src/main/java/com/lifepilot/datastore/model/CollectionType.java`
- `src/main/java/com/lifepilot/datastore/model/Document.java`

- [ ] **Step 5: 修复所有编译错误**

删除旧模型后，以下文件会出现编译错误，逐个修复引用：
- 所有 `import CollectionType` → 删除，改用 `collection.timeSeries()`
- 所有 `import PropertyDefinition` → 改为 `import FieldHint`
- 所有 `import PropertyType` → 删除
- 所有 `import com.lifepilot.datastore.model.Document` → 改为 `import com.lifepilot.knowledge.model.Document`

此步骤的目标是**编译通过**，不要求所有逻辑正确（后续 Task 逐步修正）。

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(datastore): 数据模型层改造 — FieldHint 替代 PropertyDefinition，Collection 简化"
```

---

## Task 3: Knowledge 模块扩展 — 文档存储与索引接口

**Files:**
- Modify: `src/main/java/com/lifepilot/knowledge/KnowledgeBaseManager.java`
- Modify: `src/main/java/com/lifepilot/knowledge/repository/DocumentRepository.java` (knowledge 侧)
- Modify: `src/main/java/com/lifepilot/knowledge/ingest/DocumentIngester.java`

- [ ] **Step 1: knowledge DocumentRepository 适配新列**

在 `src/main/java/com/lifepilot/knowledge/repository/DocumentRepository.java` 中：

1. `save()` 方法的 INSERT 语句新增 `content` 和 `recorded_at` 列
2. `rowMapper` 中新增 `rs.getString("content")` 和 `rs.getString("recorded_at")` 读取
3. 新增 `updateContent(String id, String content, String contentHash)` 方法：

```java
/**
 * 更新文档正文和内容哈希 — 用于 Datastore 文档 content 变更后触发重新 ingest。
 */
public void updateContent(String id, String content, String contentHash) {
    jdbcTemplate.update(
            "UPDATE documents SET content = ?, content_hash = ?, updated_at = ? WHERE id = ?",
            content, contentHash, Instant.now().toString(), id);
}
```

4. 新增 `updateMetadataJson(String id, String metadataJson)` 方法：

```java
/**
 * 仅更新 metadata_json — 不触发重新 ingest。
 */
public void updateMetadataJson(String id, String metadataJson) {
    jdbcTemplate.update(
            "UPDATE documents SET metadata_json = ?, updated_at = ? WHERE id = ?",
            metadataJson, Instant.now().toString(), id);
}
```

5. 新增 `findBySourceDatastoreId(String datastoreId, int offset, int limit)` 方法
6. 新增 `countBySourceDatastoreId(String datastoreId)` 方法

- [ ] **Step 2: KnowledgeBaseManager 新增 Datastore 文档管理方法**

在 `KnowledgeBaseManager.java` 中新增：

```java
/**
 * 创建 Datastore 文档 — 同步创建文档记录，异步启动 ingest。
 *
 * @return 创建的文档 ID
 */
@Transactional
public String createDatastoreDocument(String knowledgeBaseId, String content,
                                      String contextPrefix, String metadataJson,
                                      @Nullable String recordedAt,
                                      String sourceDatastoreId, String sourceKey,
                                      String fileName, String filePath) {
    kbRepository.findById(knowledgeBaseId)
            .orElseThrow(() -> new KnowledgeBaseNotFoundException("知识库不存在: id=" + knowledgeBaseId));

    String contentHash = DigestUtils.sha256Hex(content);
    var doc = new Document(
            UUID.randomUUID().toString(),
            knowledgeBaseId,
            fileName, filePath,
            content.getBytes(StandardCharsets.UTF_8).length,
            "text/plain",
            contentHash,
            DocumentStatus.CHUNKING,  // 跳过 UPLOADING/PARSING
            0, 0, null, null,
            Map.of("syncSource", "datastore"),
            Instant.now(), Instant.now(),
            DocumentSourceType.DATASTORE_DOCUMENT,
            sourceKey, sourceDatastoreId, null,
            Map.of(),
            content, recordedAt
    );
    docRepository.save(doc);

    // 异步 ingest（分块 → embedding → FTS）
    documentIngester.ingestDatastoreDocument(doc, content, contextPrefix);

    return doc.id();
}

/**
 * 在 documents 表上为 metadata_json 字段创建 Generated Column + partial index。
 */
public void addDocumentMetadataIndex(String columnName, String sqliteAffinity,
                                     String filterDatastoreId) {
    String idxCol = "_idx_" + FieldNames.safePrefix(filterDatastoreId) + "_" + columnName;
    jdbcTemplate.execute(
            "ALTER TABLE documents ADD COLUMN " + idxCol + " " + sqliteAffinity +
            " GENERATED ALWAYS AS (json_extract(metadata_json, '$." + columnName + "')) VIRTUAL");
    jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_doc_" + idxCol +
            " ON documents(" + idxCol + ") WHERE source_datastore_id = '" + filterDatastoreId + "'");
}

/**
 * 删除指定 datastore 的所有 Generated Column 索引。
 */
public void dropDocumentMetadataIndexes(String filterDatastoreId, List<String> columnNames) {
    String prefix = "_idx_" + FieldNames.safePrefix(filterDatastoreId) + "_";
    for (String col : columnNames) {
        String idxCol = prefix + col;
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_doc_" + idxCol);
        // SQLite 3.35+ 支持 DROP COLUMN
        try {
            jdbcTemplate.execute("ALTER TABLE documents DROP COLUMN " + idxCol);
        } catch (Exception e) {
            log.warn("无法删除 Generated Column: {} — 可能 SQLite 版本不支持 DROP COLUMN", idxCol, e);
        }
    }
}
```

注意：`addDocumentMetadataIndex` 和 `dropDocumentMetadataIndexes` 需要 JdbcTemplate 依赖，当前 KnowledgeBaseManager 没有。需要在构造函数中注入，或将这两个方法放到 knowledge DocumentRepository 中并由 KnowledgeBaseManager 委托调用。推荐后者以保持 Manager 不直接操作 SQL。

- [ ] **Step 3: DocumentIngester 新增 ingestDatastoreDocument 方法**

在 `DocumentIngester.java` 中，基于现有的 `ingestProjectedDocument` 修改为：

```java
/**
 * 导入 Datastore 文档 — 直接从 content 文本开始分块和索引。
 *
 * <p>跳过 UPLOADING/PARSING 阶段，从 CHUNKING 开始。
 * contextPrefix 注入每个 chunk，参与 embedding 但不存入 content。</p>
 */
public CompletableFuture<Document> ingestDatastoreDocument(Document doc, String content,
                                                           String contextPrefix) {
    if (content == null || content.isBlank()) {
        throw new IllegalArgumentException("文档内容不能为空");
    }
    return CompletableFuture.supplyAsync(() -> {
        try {
            log.info("Datastore 文档开始导入: documentId={}, datastoreId={}",
                    doc.id(), doc.sourceDatastoreId());

            updateStage(doc.id(), DocumentStatus.CHUNKING);
            var parseResult = new ParseResult(content, List.of(), DocumentMetadata.empty(), List.of());
            var chunks = doChunkAndEnrich(doc, parseResult);
            // 注入 contextPrefix
            if (contextPrefix != null && !contextPrefix.isBlank()) {
                chunks = chunks.stream()
                        .map(c -> c.withContextPrefix(contextPrefix))
                        .toList();
            }
            chunkRepository.saveAll(chunks);
            docRepository.updateChunkCount(doc.id(), chunks.size());

            updateStage(doc.id(), DocumentStatus.INDEXING);
            doIndex(chunks, doc.knowledgeBaseId());

            updateStage(doc.id(), DocumentStatus.EXTRACTING);
            doExtract(doc, chunks);

            docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);
            docRepository.updateLastProcessedStage(doc.id(), DocumentStatus.READY.name());
            refreshKnowledgeBaseCounts(doc.knowledgeBaseId());

            log.info("Datastore 文档导入完成: documentId={}, chunkCount={}",
                    doc.id(), chunks.size());
            return docRepository.findById(doc.id()).orElse(doc);
        } catch (Exception e) {
            docRepository.updateStatus(doc.id(), DocumentStatus.ERROR, e.getMessage());
            throw new RuntimeException("Datastore 文档导入失败: " + e.getMessage(), e);
        }
    }, Executors.newVirtualThreadPerTaskExecutor());
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(knowledge): 知识库扩展 — Datastore 文档存储与索引接口"
```

---

## Task 4: CollectionRepository 适配

**Files:**
- Modify: `src/main/java/com/lifepilot/datastore/repository/CollectionRepository.java`

- [ ] **Step 1: 更新 SQL 和 RowMapper**

所有 SQL 中的列名需要与新表结构对齐：
- `type` → `time_series`
- `properties_json` → `field_hints_json`
- 删除 `projection_config_json` 和 `metadata_json` 列引用
- RowMapper 中读取 `rs.getInt("time_series") == 1` 映射为 `boolean timeSeries`

- [ ] **Step 2: Generated Column 管理改为委托**

删除 `addGeneratedColumn()` 和 `dropGeneratedColumns()` 方法体中直接操作 `ds_documents` 的 DDL。这些方法改为委托 KnowledgeBaseManager：

```java
/**
 * 通过 KnowledgeBaseManager 在 documents 表上创建 Generated Column 索引。
 */
public void addGeneratedColumn(String fieldName, String sqliteAffinity, String collectionId) {
    knowledgeBaseManager.addDocumentMetadataIndex(fieldName, sqliteAffinity, collectionId);
}

public void dropGeneratedColumns(String collectionId, List<String> fieldNames) {
    knowledgeBaseManager.dropDocumentMetadataIndexes(collectionId, fieldNames);
}
```

CollectionRepository 需要新增 `KnowledgeBaseManager` 依赖注入。

- [ ] **Step 3: 编译验证**

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/lifepilot/datastore/repository/CollectionRepository.java
git commit -m "refactor(datastore): CollectionRepository 适配新表结构和委托索引管理"
```

---

## Task 5: QueryEngine 和 AggregationEngine 换表

**Files:**
- Modify: `src/main/java/com/lifepilot/datastore/engine/QueryEngine.java`
- Modify: `src/main/java/com/lifepilot/datastore/engine/AggregationEngine.java`

- [ ] **Step 1: QueryEngine 换表**

修改 `QueryEngine.java` 的 `buildQuery()` 方法：

1. 第 43-44 行 SELECT 语句：

```java
// 旧
"SELECT id, collection_id, data_json, recorded_at, source_type, knowledge_document_id, created_at, updated_at" +
" FROM ds_documents WHERE collection_id = ?"

// 新
"SELECT id, knowledge_base_id, content, metadata_json, recorded_at, source_datastore_id, source_key, created_at, updated_at" +
" FROM documents WHERE source_datastore_id = ? AND source_type = 'DATASTORE_DOCUMENT'"
```

2. 第 109 行 json_extract 路径：`data_json` → `metadata_json`

```java
return "json_extract(metadata_json, '$." + field + "')";
```

- [ ] **Step 2: AggregationEngine 换表**

修改 `AggregationEngine.java` 的 `buildAggregation()` 方法：

1. FROM 子句：`ds_documents` → `documents`
2. WHERE 子句：`collection_id = ?` → `source_datastore_id = ? AND source_type = 'DATASTORE_DOCUMENT'`
3. json_extract 路径：`data_json` → `metadata_json`

- [ ] **Step 3: 编译验证**

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/lifepilot/datastore/engine/QueryEngine.java src/main/java/com/lifepilot/datastore/engine/AggregationEngine.java
git commit -m "refactor(datastore): QueryEngine/AggregationEngine 查询目标从 ds_documents 切换到 documents"
```

---

## Task 6: DataStoreManager 重写

**Files:**
- Modify: `src/main/java/com/lifepilot/datastore/DataStoreManager.java`

这是改动量最大的文件，核心变化：文档操作委托 KnowledgeBaseManager，去掉 PropertyValidator 和 SyncPublisher。

- [ ] **Step 1: 更新构造函数**

去掉 `DocumentRepository`、`PropertyValidator`、`DataStoreKnowledgeSyncPublisher` 依赖，新增 `KnowledgeBaseManager` 和 `DocumentIngester`：

```java
public DataStoreManager(CollectionRepository collectionRepository,
                        QueryEngine queryEngine,
                        AggregationEngine aggregationEngine,
                        DataStoreProperties properties,
                        ObjectMapper objectMapper,
                        KnowledgeBaseManager knowledgeBaseManager,
                        DocumentIngester documentIngester,
                        @Nullable DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner) {
```

- [ ] **Step 2: 重写 createCollection**

核心变化：
- `CollectionType type` → `boolean timeSeries`
- `List<PropertyDefinition> propDefs` → `@Nullable List<FieldHint> fieldHints`
- 去掉 `projectionConfigJson` 参数
- FieldHint 索引创建委托 KnowledgeBaseManager

```java
@Transactional
public Collection createCollection(String name, boolean timeSeries,
                                   @Nullable List<FieldHint> fieldHints,
                                   @Nullable String description,
                                   @Nullable String createdBy) {
    int currentCount = collectionRepository.count();
    if (currentCount >= properties.getMaxCollections()) {
        throw new IllegalStateException("集合数量已达上限: " + properties.getMaxCollections());
    }
    if (collectionRepository.findByName(name).isPresent()) {
        throw new IllegalArgumentException("集合名称已存在: " + name);
    }

    String fieldHintsJson = serializeFieldHints(fieldHints);
    var collection = Collection.builder()
            .name(name).timeSeries(timeSeries).description(description)
            .fieldHintsJson(fieldHintsJson)
            .createdBy(createdBy).build();

    String collectionId = collectionRepository.insert(collection);
    try {
        collection = collectionRepository.findById(collectionId).orElseThrow(
                () -> new IllegalStateException("集合创建后查询失败: id=" + collectionId));

        if (fieldHints != null && !fieldHints.isEmpty()) {
            for (var hint : fieldHints) {
                knowledgeBaseManager.addDocumentMetadataIndex(
                        hint.name(), hint.toSqliteAffinity(), collectionId);
            }
        }

        if (datastoreKnowledgeBaseProvisioner != null) {
            String defaultKbId = datastoreKnowledgeBaseProvisioner.ensureDefaultKnowledgeBase(collection);
            if (defaultKbId == null || defaultKbId.isBlank()) {
                throw new IllegalStateException("Datastore 默认知识库创建失败");
            }
            if (!collectionRepository.updateDefaultKnowledgeBaseId(collectionId, defaultKbId)) {
                throw new IllegalStateException("Datastore 默认知识库回填失败");
            }
        }

        return collectionRepository.findById(collectionId).orElseThrow(
                () -> new IllegalStateException("集合创建后查询失败: id=" + collectionId));
    } catch (RuntimeException e) {
        cleanupFailedCollectionCreation(collection, e);
        throw e;
    }
}
```

- [ ] **Step 3: 重写 addDocument**

核心变化：接收 content + metadata，委托 KnowledgeBaseManager 创建文档。

```java
@Transactional
public String addDocument(String collectionId, String content,
                          @Nullable String metadataJson,
                          @Nullable String recordedAt) {
    var collection = collectionRepository.findById(collectionId)
            .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + collectionId));

    if (content == null || content.isBlank()) {
        throw new IllegalArgumentException("文档内容不能为空");
    }
    if (content.getBytes(StandardCharsets.UTF_8).length > properties.getMaxDocumentSizeBytes()) {
        throw new IllegalArgumentException("文档大小超过限制: " + properties.getMaxDocumentSizeBytes() + " bytes");
    }
    if (collection.timeSeries()) {
        if (recordedAt == null || recordedAt.isBlank()) {
            throw new IllegalArgumentException("时序集合文档必须包含 recordedAt");
        }
        try { DateTimeFormatter.ISO_DATE_TIME.parse(recordedAt); }
        catch (DateTimeParseException e) {
            throw new IllegalArgumentException("recordedAt 格式非法，期望 ISO 8601: " + recordedAt, e);
        }
    }

    // 文档数限额校验 — 查询 documents 表中该 datastore 的文档数
    // （通过 knowledge DocumentRepository 的 countBySourceDatastoreId）

    String contextPrefix = "[%s] %s".formatted(collection.name(),
            collection.description() != null ? collection.description() : "");
    String sourceKey = "DATASTORE:" + collectionId + ":" + UUID.randomUUID();
    String title = extractTitle(metadataJson);
    String fileName = "%s - %s".formatted(collection.name(), title != null ? title : sourceKey.substring(sourceKey.lastIndexOf(':') + 1));
    String filePath = "datastore://" + collectionId + "/" + sourceKey.substring(sourceKey.lastIndexOf(':') + 1);

    return knowledgeBaseManager.createDatastoreDocument(
            collection.defaultKnowledgeBaseId(),
            content, contextPrefix,
            metadataJson != null ? metadataJson : "{}",
            recordedAt,
            collectionId, sourceKey,
            fileName, filePath);
}
```

- [ ] **Step 4: 重写 updateDocument**

根据 contentHash 判断是否需要重新 ingest：

```java
@Transactional
public boolean updateDocument(String id, @Nullable String content, @Nullable String metadataJson) {
    var existingDoc = knowledgeBaseManager.getDocument(id)
            .orElseThrow(() -> new IllegalArgumentException("文档不存在: id=" + id));

    boolean contentChanged = false;
    if (content != null && !content.isBlank()) {
        String newHash = DigestUtils.sha256Hex(content);
        if (!newHash.equals(existingDoc.contentHash())) {
            contentChanged = true;
            // 更新 content + 重新 ingest
            knowledgeBaseManager.updateDatastoreDocumentContent(id, content, newHash);
        }
    }

    if (metadataJson != null) {
        knowledgeBaseManager.updateDatastoreDocumentMetadata(id, metadataJson);
    }

    return true;
}
```

- [ ] **Step 5: 重写 deleteDocument 和 deleteCollection**

deleteDocument 委托 `knowledgeBaseManager.removeDocument(id)`。
deleteCollection 的级联策略：先删知识库（CASCADE 文档和分块），清理索引列，最后删集合记录。

- [ ] **Step 6: 重写 queryDocuments 和 aggregate**

queryDocuments 和 aggregate 的逻辑基本不变，只需：
- 改从 knowledge DocumentRepository 执行查询（QueryEngine 已在 Task 5 中换表）
- 返回类型改为 knowledge Document

- [ ] **Step 7: 删除旧的同步和校验相关代码**

删除所有 `PropertyValidator`、`DataStoreKnowledgeSyncPublisher`、`DocumentRepository`（datastore 侧）的引用。删除不再使用的 `addFileReference`、`linkKnowledgeDocument` 等方法。

- [ ] **Step 8: 编译验证**

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 9: 提交**

```bash
git add -A
git commit -m "feat(datastore): DataStoreManager 重写 — 文档操作委托知识库"
```

---

## Task 7: 同步链路清理

**Files:**
- Delete: `src/main/java/com/lifepilot/datastore/sync/DataStoreKnowledgeSyncPublisher.java`
- Delete: `src/main/java/com/lifepilot/datastore/repository/DocumentRepository.java`
- Delete: `src/main/java/com/lifepilot/datastore/validation/PropertyValidator.java`
- Delete: `src/main/java/com/lifepilot/knowledge/sync/DataStoreKnowledgeSyncJobPublisher.java`
- Delete: `src/main/java/com/lifepilot/knowledge/sync/DatastoreDocumentProjector.java`
- Modify: `src/main/java/com/lifepilot/knowledge/sync/KnowledgeSyncWorker.java`
- Modify: `src/main/java/com/lifepilot/datastore/config/DataStoreAutoConfiguration.java`

- [ ] **Step 1: 删除同步链路文件**

删除以上 5 个文件。

- [ ] **Step 2: KnowledgeSyncWorker 去掉 DATASTORE job 类型**

在 `KnowledgeSyncWorker.java` 中，删除 `case UPSERT_DATASTORE_DOCUMENT`、`DELETE_DATASTORE_DOCUMENT`、`RESYNC_DATASTORE`、`PURGE_DATASTORE` 分支及对应的 handler 方法（`handleUpsertJob`、`handleDeleteJob`、`handleResyncJob`、`handlePurgeJob`）。

同时检查 `KnowledgeSyncJobType` 枚举，如果这些值只被 datastore 使用，一并删除。

- [ ] **Step 3: DataStoreAutoConfiguration 清理**

去掉 `DocumentRepository`、`PropertyValidator` bean 定义。DataStoreManager 的 bean 构造参数更新为匹配 Task 6 的新构造函数。

- [ ] **Step 4: 编译验证**

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "refactor(datastore): 移除同步链路 — 删除投影器、发布器、Worker job 类型"
```

---

## Task 8: 工具层适配

**Files:**
- Modify: `src/main/java/com/lifepilot/meta/infra/storage/DatastoreActionDispatchExecutor.java`
- Modify: `src/main/java/com/lifepilot/meta/infra/storage/StorageToolProvider.java`

- [ ] **Step 1: DatastoreActionDispatchExecutor 适配**

核心变更：
1. `create-collection` 参数：`type` → `timeSeries`（boolean），`properties` → `fieldHints`
2. `add` 操作：接收 `content` + `metadata` + `recordedAt`，不再只接收 `data`
3. 新增 `get` 操作：按 ID 获取单个文档
4. `update` 操作：接收 `content` + `metadata`，不再只接收 `data`
5. 所有返回文档的地方，返回 knowledge Document 而非 datastore Document

- [ ] **Step 2: StorageToolProvider 工具描述更新**

更新工具描述中的参数说明，反映新的 content + metadata 模型。

- [ ] **Step 3: 编译验证**

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "refactor(datastore): 工具层适配 — content+metadata 模型，新增 get 操作"
```

---

## Task 9: REST API 和 DTO 适配

**Files:**
- Modify: `src/main/java/com/lifepilot/interaction/web/controller/DatastoreController.java`
- Modify: `src/main/java/com/lifepilot/interaction/web/model/CreateDatastoreRequest.java`
- Modify: `src/main/java/com/lifepilot/interaction/web/model/UpdateDatastoreRequest.java`

- [ ] **Step 1: CreateDatastoreRequest DTO 更新**

```java
public record CreateDatastoreRequest(
        String name,
        @Nullable String description,
        boolean timeSeries,
        @Nullable List<FieldHint> fieldHints
) {}
```

- [ ] **Step 2: DatastoreController 适配**

1. `createDatastore` 方法：传 `timeSeries` 和 `fieldHints` 而非 `type` 和 `properties`
2. 列表/详情返回 Collection 新结构
3. 文件上传逻辑保留（上传文件仍走文件 ingest 路径，不受此次改造影响）

- [ ] **Step 3: 编译验证**

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "refactor(interaction): REST API 适配 Datastore 文档优先模型"
```

---

## Task 10: Skill 指令重写

**Files:**
- Modify: `src/main/resources/prompts/skill/datastore.st`

- [ ] **Step 1: 重写 datastore.st**

核心变更：
1. 写入指令：强调 content 必须包含用户描述性信息，metadata 只存需要排序/过滤/聚合的字段
2. 检索决策：能翻译成字段条件 → query，不能 → knowledge.search，需统计 → aggregate
3. 集合创建：timeSeries 标记替代 type 枚举，fieldHints 替代 properties
4. 写入前检查重复的指引
5. get 操作的说明

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/prompts/skill/datastore.st
git commit -m "feat(datastore): Skill 指令重写 — 文档优先写入策略和检索决策"
```

---

## Task 11: CrudAdapter 适配

**Files:**
- Modify: `src/main/java/com/lifepilot/datastore/adapter/DataStoreCrudAdapter.java`
- Modify: `src/main/java/com/lifepilot/datastore/adapter/CrudAdapterConfig.java`

- [ ] **Step 1: DataStoreCrudAdapter 适配新模型**

1. 所有引用 datastore Document 的地方改为 knowledge Document
2. 适配 content + metadata 模型
3. 适配 Collection 新字段

- [ ] **Step 2: 编译验证**

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "refactor(datastore): CrudAdapter 适配文档优先模型"
```

---

## Task 12: 删除旧测试 + 编写核心测试

**Files:**
- Delete: `src/test/java/com/lifepilot/datastore/repository/DocumentRepository_单元测试.java`
- Delete: `src/test/java/com/lifepilot/datastore/validation/PropertyValidator单元测试.java`
- Delete: `src/test/java/com/lifepilot/knowledge/sync/DatastoreDocumentProjectorTest.java`
- Delete: `src/test/java/com/lifepilot/datastore/DataStoreManagerProjectionConfigTest.java`
- Modify: `src/test/java/com/lifepilot/datastore/engine/QueryEngine单元测试.java`
- Modify: `src/test/java/com/lifepilot/datastore/engine/AggregationEngine单元测试.java`
- Modify: `src/test/java/com/lifepilot/datastore/repository/CollectionRepository_单元测试.java`

- [ ] **Step 1: 删除废弃测试文件**

删除以上 4 个测试文件。

- [ ] **Step 2: 更新 QueryEngine 单元测试**

更新表名和列名引用：
- `ds_documents` → `documents`
- `collection_id` → `source_datastore_id`
- `data_json` → `metadata_json`
- 新增 `source_type = 'DATASTORE_DOCUMENT'` 条件断言

- [ ] **Step 3: 更新 AggregationEngine 单元测试**

同 Step 2 的表名/列名变更。

- [ ] **Step 4: 更新 CollectionRepository 单元测试**

适配新的 Collection 字段（timeSeries, fieldHintsJson 等）。

- [ ] **Step 5: 运行测试**

Run: `mvn test -f D:/WorkSpace/Project/News/pom.xml -pl .`
Expected: 现有未修改的测试通过，新增/修改的测试通过

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "test(datastore): 更新测试 — 适配文档优先模型，删除废弃测试"
```

---

## Task 13: DataStoreManager 集成测试重写

**Files:**
- Modify: `src/test/java/com/lifepilot/datastore/DataStoreManager集成测试.java`
- Modify: `src/test/java/com/lifepilot/datastore/DataStoreManagerCreateCollectionRollbackTest.java`

- [ ] **Step 1: 重写集成测试**

核心测试场景：

1. **创建集合** — 验证 ds_collections 记录创建、内部知识库绑定、FieldHint 索引创建
2. **添加文档** — 验证 documents 表新增记录、content/metadata/recorded_at 正确存储
3. **结构化查询** — 验证 QueryEngine 在 documents 表上的过滤和排序
4. **语义检索** — 验证文档 ingest 后可通过 knowledge.search 命中
5. **时序聚合** — 验证 METRIC 类型的 AVG/SUM/COUNT
6. **更新文档** — 验证 content 变更触发重新 ingest，metadata-only 变更不触发
7. **删除文档** — 验证 documents 和 chunks 级联清理
8. **删除集合** — 验证知识库级联删除和 Generated Column 清理

注意：集成测试需要 ApplicationContextRunner 或 @SpringBootTest 环境，确保 Flyway 迁移执行完毕。

- [ ] **Step 2: 重写回滚测试**

适配新的 createCollection 参数签名（timeSeries, fieldHints）。

- [ ] **Step 3: 运行测试**

Run: `mvn test -f D:/WorkSpace/Project/News/pom.xml -Dtest="DataStoreManager*"`
Expected: ALL PASS

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "test(datastore): 集成测试重写 — 覆盖文档优先全流程"
```

---

## Task 14: 全量编译 + 测试 + 清理

- [ ] **Step 1: 全量编译**

Run: `mvn compile -f D:/WorkSpace/Project/News/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 2: 全量测试**

Run: `mvn test -f D:/WorkSpace/Project/News/pom.xml`
Expected: ALL PASS

- [ ] **Step 3: 排查残留引用**

搜索代码中是否还有对已删除类的残留引用：
- `PropertyDefinition`
- `PropertyType`
- `CollectionType`
- `DataStoreKnowledgeSyncPublisher`
- `DatastoreDocumentProjector`
- `ds_documents`（在非迁移文件中）

如有残留，修复后重新编译测试。

- [ ] **Step 4: 更新架构文档**

更新 `docs/architecture/generic-data-store.md` 中的数据模型、流程图、组件描述，反映文档优先改造后的新架构。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "chore(datastore): 全量清理 — 修复残留引用，更新架构文档"
```

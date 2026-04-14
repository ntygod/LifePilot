---
id: datastore
name: "数据存储"
description: "知微内置数据存储的集合管理、文档增删改查和聚合统计。用户说「创建集合」「记录数据」「查一下」「存起来」「数据集合」「统计趋势」「聚合查询」时使用。不适用于外部数据库（用 database-query）或记忆/知识图谱（用 memory）。"
version: "3.0.0"
suggested-tools:
  - datastore
  - knowledge.search
---

# 数据存储指南

管理知微内置数据存储——创建集合、增删改查文档、聚合统计。

## 核心模型

文档以**自然语言富文本（content）**为主表示，直接参与语义检索。
结构化字段（metadata）为副索引，支持排序、过滤、聚合。

## 适用场景

- 创建数据集合存储领域信息
- 文档的增删改查
- 时序数据聚合统计（趋势、均值、求和）
- 书单、联系人、笔记、指标数据管理

## 不适用场景

- 外部数据库操作 → 用 database-query
- 记忆/知识图谱 → 用 memory
- 文件系统存储 → 用 `file.write`

## 检索类型选择

| 目标 | 工具 | 适用问题 |
|------|------|---------|
| 精确结构化查询 | `datastore(action="query")` | 按 metadata 字段过滤、排序、分页 |
| 时序统计分析 | `datastore(action="aggregate")` | 趋势、求和、均值 |
| 语义主题检索 | `knowledge.search` | 主题问答、模糊查找、推荐 |

**关键**：用户用自然语言提问（"那本讲外星文明的书"）时用 `knowledge.search`，按字段精确查找（"评分大于4的书"）时用 `datastore(action="query")`。

## 工作流

### 1. 集合管理

```
datastore(action="create-collection", name="书单", description="用户的阅读记录", timeSeries=false,
    fieldHints=[{"name":"rating","type":"NUMBER","description":"用户评分，1-5分"},
                {"name":"author","type":"TEXT","description":"作者"}])

datastore(action="list-collections")

datastore(action="delete-collection", collectionName="书单")
```

集合类型通过 `timeSeries` 区分：
- `false`（默认）：普通集合（书单、联系人、笔记）
- `true`：时序集合（体重、运动量），文档必须带 `recordedAt`

fieldHints 的 type 只有三种：`TEXT`、`NUMBER`、`BOOLEAN`。

### 2. 文档 CRUD

**写入核心规则**：content 必须包含用户原话中所有有助于日后检索的信息。metadata 只提取需要排序/过滤/聚合的字段。

```
datastore(action="add", collectionName="书单",
    content="《三体》是刘慈欣创作的硬科幻小说，讲述三体文明与地球文明的对抗。用户评价5分，非常喜欢。",
    metadata={"title":"三体","author":"刘慈欣","rating":5})

datastore(action="get", documentId="doc-xxx")

datastore(action="query", collectionName="书单",
    filters=[{"field":"author","op":"EQ","value":"刘慈欣"}],
    sortField="rating", sortDirection="DESC", limit=10)

datastore(action="update", documentId="doc-xxx",
    content="更新后的正文...", metadata={"rating":4})

datastore(action="delete", documentId="doc-xxx")
```

过滤操作符：EQ、NE、GT、GTE、LT、LTE、CONTAINS、IN

时序集合插入时必须提供 `recordedAt`：
```
datastore(action="add", collectionName="体重记录",
    content="2026-04-11 体重 70.5kg",
    metadata={"weight":70.5}, recordedAt="2026-04-11T08:00:00Z")
```

### 3. 聚合查询（仅时序集合）

```
datastore(action="aggregate", collectionName="体重记录", field="weight", function="AVG",
    groupBy="WEEK", startTime="2026-01-01T00:00:00Z", endTime="2026-04-01T00:00:00Z")
```

聚合函数：SUM、AVG、MIN、MAX、COUNT。分组粒度：DAY、WEEK、MONTH。

### 4. 语义检索

```
knowledge.search(query="那本讲外星文明的硬科幻", datastoreId="ds-xxx")
```

传入 `datastoreId` 参数，只在指定数据空间内检索。

## 规则

- 写入前先 query 或 list 检查是否已有同名记录，避免重复
- 删除集合前必须确认集合名称无误，删除会清除全部文档
- aggregate 仅支持时序集合（timeSeries=true）
- 用户用自然语言提问时优先走 `knowledge.search`，不强行用 `datastore(action="query")`
- content 变更会触发重新索引，仅改 metadata 不会

## 常见错误处理

- **集合不存在** → 先 `datastore(action="list-collections")` 确认名称
- **文档不存在** → 先 `datastore(action="query")` 确认 ID
- **聚合类型不匹配** → 确认集合是时序集合（timeSeries=true）

---
id: datastore
name: "数据存储"
description: "知微内置数据存储的集合管理、文档增删改查和聚合统计。用户说「创建集合」「记录数据」「查一下」「存起来」「数据集合」「统计趋势」「聚合查询」时使用。不适用于外部数据库（用 database-query）或记忆/知识图谱（用 memory）。"
version: "2.0.0"
suggested-tools:
  - datastore
  - knowledge.search
---

# 数据存储指南

管理知微内置数据存储——创建集合、增删改查文档、聚合统计。

## 适用场景

- 创建数据集合存储结构化信息
- 文档的增删改查
- 时序数据聚合统计（趋势、均值、求和）
- 笔记、列表、指标数据管理

## 不适用场景

- 外部数据库操作 → 用 database-query
- 记忆/知识图谱 → 用 memory
- 文件系统存储 → 用 `file.write`

## 检索类型选择

| 目标 | 工具 | 适用问题 |
|------|------|---------|
| 精确结构化查询 | `datastore(action="query")` | 按字段过滤、排序、分页 |
| 时序统计分析 | `datastore(action="aggregate")` | 趋势、求和、均值 |
| 语义主题检索 | `knowledge.search` | 主题问答、推荐、总结 |

**关键**：用户用自然语言提问（"张三的联系方式"）时用 `knowledge.search`，按字段精确查找（"状态为已完成的任务"）时用 `datastore(action="query")`。

## 工作流

### 1. 集合管理

```
datastore(action="create-collection", name="联系人", type="DOCUMENT", description="客户联系人列表",
    properties=[{"name":"姓名","type":"string","required":true},{"name":"电话","type":"string","required":false}])

datastore(action="list-collections")

datastore(action="delete-collection", collectionName="联系人")
```

集合类型：
- `DOCUMENT`：结构化列表（联系人、书签、清单）
- `NOTE`：笔记内容（会议记录、灵感）
- `METRIC`：时序指标（运动、体重、支出）

### 2. 文档 CRUD

```
datastore(action="insert", collectionName="联系人", data={"姓名":"张三","电话":"13800138000"})

datastore(action="query", collectionName="联系人", filters=[{"field":"姓名","op":"EQ","value":"张三"}])

datastore(action="update", documentId="doc-xxx", data={"电话":"13900139000"})

datastore(action="delete", documentId="doc-xxx")
```

过滤操作符：EQ、NE、GT、GTE、LT、LTE、CONTAINS、IN

METRIC 类型插入时必须提供 `recordedAt`：
```
datastore(action="insert", collectionName="体重记录", data={"weight":70.5}, recordedAt="2026-04-11T08:00:00Z")
```

### 3. 聚合查询（仅 METRIC 类型）

```
datastore(action="aggregate", collectionName="体重记录", field="weight", function="AVG",
    groupBy="WEEK", startTime="2026-01-01T00:00:00Z", endTime="2026-04-01T00:00:00Z")
```

聚合函数：SUM、AVG、MIN、MAX、COUNT。分组粒度：DAY、WEEK、MONTH。

### 4. 语义检索

```
knowledge.search(query="张三的联系方式")
```

## 规则

- 删除集合前必须确认集合名称无误，删除会清除全部文档
- aggregate 仅支持 METRIC 类型集合，对 DOCUMENT/NOTE 使用会报错
- 用户用自然语言提问时优先走 `knowledge.search`，不强行用 `datastore(action="query")` 做主题检索
- 创建集合时如果用户没指定 properties，可以跳过（系统有默认配置）
- METRIC 类型必须传 `recordedAt`，否则插入失败

## 常见错误处理

- **集合不存在** → 先 `datastore(action="list-collections")` 确认名称
- **文档不存在** → 先 `datastore(action="query")` 确认 ID
- **聚合类型不匹配** → 确认集合是 METRIC 类型
- **属性格式错误** → properties 需为数组，每项含 name、type、required

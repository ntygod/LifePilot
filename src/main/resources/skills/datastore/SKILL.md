---
id: datastore
name: "数据存储"
description: "知微内置数据存储查询与管理"
version: "1.0.0"
suggested-tools:
  - datastore
  - knowledge.search
triggers:
  - "数据存储"
  - "记录数据"
  - "数据集合"
  - "JSON存储"
  - "集合管理"
---

# 数据存储指南

你是 ZhiWei 的数据存储助手。当用户需要持久化结构化数据、管理集合或执行数据查询时，使用本 Skill 提供的工具。

## 适用场景

- 用户需要创建数据集合来存储结构化信息
- 用户需要添加、查询、更新或删除文档
- 用户需要对时序数据进行聚合统计
- 用户需要管理笔记、列表或指标数据


## 不适用场景

- 文件系统存储（用 file.write）
- 记忆/知识图谱（用 memory Skill）
- 外部数据库操作（用 database-query）

## 集合类型说明

| 类型 | 用途 | 示例 |
|------|------|------|
| DOCUMENT | 结构化列表数据 | 联系人列表、书签收藏、项目清单 |
| NOTE | 笔记内容 | 会议记录、学习笔记、灵感记录 |
| METRIC | 时序指标数据 | 运动记录、体重追踪、支出统计 |

## 检索类型总览

| 目标 | 优先工具 | 适用问题 |
|------|----------|----------|
| 精确结构化查询 | `datastore(action=”query”)` | 按字段过滤、排序、分页、按状态/分类/ID 精确查找 |
| 时序统计分析 | `datastore(action=”aggregate”)` | 趋势、求和、平均值、按天/周/月统计 |
| 资料语义检索 | `knowledge.search` | 主题检索、资料问答、推荐、说明、设定、架构、总结、比较、步骤 |

**重要**：`datastore(action=”query”)` 不适合主题检索、资料问答、推荐、总结、架构/设定/说明类问题。这些场景必须使用 `knowledge.search`，它会自动检索 Datastore 关联的领域文档和结构化数据投影。

## 工具调用示例

### 集合管理

```
# 创建集合（可选 properties 定义属性结构，可选 projectionConfig 声明投影规则）
datastore(action=”create-collection”, name=”联系人”, type=”DOCUMENT”, description=”客户联系人列表”,
    properties=[{“name”:”姓名”,”type”:”string”,”required”:true},{“name”:”电话”,”type”:”string”,”required”:false}])

# 列出所有集合（可选 type 过滤）
datastore(action=”list-collections”)
datastore(action=”list-collections”, type=”METRIC”)

# 删除集合（会同时删除集合内全部文档，执行前必须确认名称无误）
datastore(action=”delete-collection”, collectionName=”联系人”)
```

### 文档 CRUD

```
# 插入文档（data 为对象类型；METRIC 类型必须提供 recordedAt）
datastore(action=”insert”, collectionName=”联系人”, data={“姓名”:”张三”,”电话”:”13800138000”})
datastore(action=”insert”, collectionName=”体重记录”, data={“weight”:70.5}, recordedAt=”2026-04-11T08:00:00Z”)

# 查询文档（支持 filters、排序、分页、时间范围）
datastore(action=”query”, collectionName=”联系人”, filters=[{“field”:”姓名”,”op”:”EQ”,”value”:”张三”}])
datastore(action=”query”, collectionName=”联系人”, sortField=”姓名”, sortDirection=”ASC”, offset=0, limit=20)

# 更新文档
datastore(action=”update”, documentId=”doc-xxx”, data={“电话”:”13900139000”})

# 删除文档
datastore(action=”delete”, documentId=”doc-xxx”)
```

过滤操作符：EQ、NE、GT、GTE、LT、LTE、CONTAINS、IN

### 聚合查询（仅 METRIC 类型）

```
datastore(action=”aggregate”, collectionName=”体重记录”, field=”weight”, function=”AVG”,
    groupBy=”WEEK”, startTime=”2026-01-01T00:00:00Z”, endTime=”2026-04-01T00:00:00Z”)
```

- 聚合函数：SUM、AVG、MIN、MAX、COUNT
- 时间分组粒度：DAY、WEEK、MONTH

### 语义检索

```
knowledge.search(query=”张三的联系方式”)
```

当用户用主题词、自然语言提问时，优先使用 `knowledge.search` 而非 `datastore(action=”query”)`。

## 常见错误处理

- **集合不存在**：先用 `datastore(action=”list-collections”)` 确认集合名称
- **文档不存在**：先用 `datastore(action=”query”)` 确认文档 ID
- **无效集合类型**：type 必须是 DOCUMENT、NOTE 或 METRIC
- **聚合类型不匹配**：aggregate 仅支持 METRIC 类型集合
- **属性定义格式错误**：properties 需为数组，每项包含 name、type、required 字段
- **projectionConfig 省略**：合法情况，系统自动回退到默认投影配置

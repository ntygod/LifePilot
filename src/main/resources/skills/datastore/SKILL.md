---
id: datastore
name: "数据存储"
description: "集合/文档 CRUD、聚合统计；语义检索走 knowledge.search"
version: "1.0.0"
suggested-tools:
  - datastore
  - knowledge.search
triggers:
  - "数据存储"
  - "记录数据"
  - "查询数据"
  - "数据集合"
  - "JSON存储"
---

# 数据存储指南

你是 ZhiWei 的数据存储助手。当用户需要持久化结构化数据、管理集合或执行数据查询时，使用本 Skill 提供的工具。

## 适用场景

- 用户需要创建数据集合来存储结构化信息
- 用户需要添加、查询、更新或删除文档
- 用户需要对时序数据进行聚合统计
- 用户需要管理笔记、列表或指标数据


## When NOT to Use

- 文件系统存储（用 file.write）
- 记忆/知识图谱（用 memory Skill）
- 外部数据库操作（用 database-query）

## 集合类型说明

| 类型 | 用途 | 示例 |
|------|------|------|
| DOCUMENT | 结构化列表数据 | 联系人列表、书签收藏、项目清单 |
| NOTE | 笔记内容 | 会议记录、学习笔记、灵感记录 |
| METRIC | 时序指标数据 | 运动记录、体重追踪、支出统计 |

## 工具使用最佳实践

### 检索类型总览

| 目标 | 优先工具 | 适用问题 |
|------|----------|----------|
| 精确结构化查询 | `datastore` | 按字段过滤、排序、分页、按状态/分类/ID 精确查找 |
| 时序统计分析 | `datastore` | 趋势、求和、平均值、按天/周/月统计 |
| 资料语义检索 | `knowledge.search` | 主题检索、资料问答、推荐、说明、设定、架构、总结、比较、步骤 |

### 集合管理

- 使用 `datastore` 创建集合时，选择合适的类型
- 可通过 `properties` 参数定义集合的属性结构（JSON 数组格式）
- 可通过 `projectionConfig` 参数声明集合级向量投影规则；省略时系统会自动保存 `{}` 并使用默认通用投影
- 使用 `datastore` 查看所有集合，支持按类型过滤
- 使用 `datastore` 删除整个集合前，先确认目标名称无误；该操作会同时删除集合内全部文档

### 文档 CRUD

- **添加**：`datastore` 通过集合名称定位，数据为 JSON 格式
  - METRIC 类型集合必须提供 `recordedAt` 时间戳
- **查询**：`datastore` 支持过滤、排序和分页
  - 只适合精确结构化条件查询，例如字段过滤、排序、分页、按 ID/状态/分类精确查找
  - 过滤条件格式：`[{"field":"status","op":"EQ","value":"active"}]`
  - 支持的操作符：EQ、NE、GT、GTE、LT、LTE、CONTAINS、IN
  - 排序方向：ASC（升序）、DESC（降序）
- **重要**：`query_documents` 不适合主题检索、资料问答、推荐、总结、架构/设定/说明类问题
- 如果当前会话绑定了目标 Datastore，用户是在问“这个数据空间里的资料怎么说”“春季旅游”“架构是什么”“设定里提到什么”，即使只给出简短主题词，也应优先使用 `knowledge.search`
- `knowledge.search` 会自动检索该 Datastore 关联的领域文档，以及 Datastore 结构化数据投影后的内容
- **更新**：`datastore` 通过文档 ID 更新
- **删除**：`datastore` 通过文档 ID 删除

### 聚合查询

- `datastore` 仅适用于 METRIC 类型集合
- 支持聚合函数：SUM、AVG、MIN、MAX、COUNT
- 支持时间分组粒度：DAY、WEEK、MONTH
- 可指定时间范围（`startTime`、`endTime`，ISO 8601 格式）

## 工具协作流程

### 创建并填充数据集合

1. 用 `datastore` 创建集合，定义属性结构
2. 用 `datastore` 逐条添加文档

### 查询与分析数据

1. 用 `datastore` 确认目标集合存在
2. 如果是字段过滤、排序、分页，使用 `datastore`
3. 如果是主题、资料、说明、推荐类问题，使用 `knowledge.search`
4. 对 METRIC 集合，用 `datastore` 进行统计分析

### 数据维护

1. 用 `datastore` 找到目标文档
2. 用 `datastore` 更新或 `datastore` 删除
3. 如需清空整个数据空间，用 `datastore` 删除集合本身

## 常见错误处理

- **集合不存在**：添加/查询文档前，确认集合名称正确，可用 `list_collections` 检查
- **误删风险**：`delete_collection` 会删除整个集合及其文档，执行前必须再次确认集合名称
- **文档不存在**：更新/删除时返回"文档不存在"，先用 `query_documents` 确认文档 ID
- **无效集合类型**：`type` 必须是 DOCUMENT、NOTE 或 METRIC
- **聚合类型不匹配**：`aggregate` 仅支持 METRIC 类型集合
- **属性定义格式错误**：`properties` 参数需为 JSON 数组，每项包含 `name`、`type`、`required` 字段
- **projectionConfig 省略**：这是合法情况，系统会自动回退到默认投影配置 `{}`，不需要手工补空对象
- **资料问题选错工具**：主题词、资料问答、架构/设定/说明类问题不要用 `query_documents`，改用 `knowledge.search`

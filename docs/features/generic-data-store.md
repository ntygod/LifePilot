# 通用数据存储 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.datastore`
> **最后更新**：2026-03-27
> **实现状态**：✅ 已完成

## 1. 功能概述

通用数据存储为知微提供 Schema-Free 的数据持久化能力，让用户通过自然语言对话即可创建数据集合、记录和查询各类个人数据。无论是书单、体重记录、购物清单还是日记，Agent 都能自动创建合适的数据结构并管理数据生命周期。

当前实现已采用 `datastore-first` 工作区形态：每个 Datastore 在创建时都会自动拥有一个系统托管的内部 Knowledge Base，用于承接文档同步、结构化数据投影、分块与检索。

核心价值：将知微从「只能管理待办/日程/习惯」的固定功能助手，升级为「能管理任意个人数据」的通用助手。

## 2. 核心特性

### 2.1 三种集合类型

**Document（结构化列表）**
- 适用场景：书单、影单、购物清单、旅行计划、食谱、联系人、记账
- 能力：CRUD + 多条件过滤 + 排序 + 分页
- 资料检索：绑定到会话后，结构化数据可通过投影进入统一资料检索链路
- 示例对话：
  - 「帮我建一个书单」→ 创建 DOCUMENT 类型集合
  - 「添加三体，刘慈欣，科幻，评分5分」→ 写入文档
  - 「我的书单里评分4分以上的有哪些？」→ 过滤查询

**Note（非结构化笔记）**
- 适用场景：日记、会议记录、灵感记录、梦境日志
- 能力：写入 + 资料沉淀
- 资料检索：绑定到会话后，笔记内容统一通过 `knowledge.search` 参与检索，而不是通过单独的 Datastore 全文搜索工具暴露给 LLM
- 示例对话：
  - 「记一下今天的想法：关于项目架构的思考...」→ 写入笔记
  - 「我之前记过关于架构的笔记吗？」→ 全文搜索

**Metric（时序指标）**
- 适用场景：体重、运动量、睡眠时长、饮水量、学习时长
- 能力：追加记录 + 时间范围查询 + 聚合统计（均值/极值/趋势）
- 示例对话：
  - 「今天体重72.5」→ 追加时序记录
  - 「这个月体重趋势怎么样？」→ 按周聚合，返回趋势分析


### 2.2 Schema-Free + 可选 Schema

默认情况下，用户无需定义数据结构。Agent 根据用户输入自动推断字段并以 JSON 存储。

当集合声明了属性定义（PropertyDefinition）后，可获得：
- 类型校验：写入时验证字段类型是否匹配
- 索引加速：通过 SQLite Generated Column 创建 B-tree 索引，大数据量下查询性能显著提升
- 工具描述增强：Agent 的工具 schema 中包含属性描述，提升 LLM 参数填充准确度

支持的属性类型：TEXT、NUMBER、BOOLEAN、DATE、DATETIME、SELECT、MULTI_SELECT、URL、JSON。

### 2.3 Agent 自动管理

Agent 通过 8 个内置工具操作数据存储，无需用户手动配置：

- `create_collection`：创建新集合（Agent 根据用户意图自动选择类型和属性）
- `list_collections`：列出已有集合（Agent 先查找是否已有匹配集合）
- `delete_collection`：删除整个集合
- `add_document`：添加文档
- `query_documents`：查询文档（支持过滤、排序、分页）
- `update_document`：更新文档
- `delete_document`：删除文档
- `aggregate`：时序聚合查询（仅 METRIC 类型）

其中：

- `datastore.query_documents` 仅用于精确结构化条件查询
- Datastore 绑定到会话后的资料型、主题型、说明型问题，统一优先通过 `knowledge.search` 检索

`create_collection` 还支持可选的 `projectionConfig` 参数，用于声明结构化数据如何投影为后续检索文本；如果未提供，系统会自动保存 `{}` 并启用默认通用投影规则。

### 2.4 集合级向量投影配置

当 Datastore 参与领域检索时，结构化文档需要先投影为文本再进入内部 Knowledge Base 的分块、向量化和全文索引链路。该行为由集合级 `projectionConfig` / `projection_config_json` 控制：

- 配置粒度在 Collection 级，不在知识库挂载级
- 省略配置时默认持久化为 `{}`，使用系统内置的通用结构投影
- 显式配置时可覆盖默认规则，适配小说素材、学习卡片、资料清单等复杂 JSON
- 该配置不会影响原始 Datastore 文档内容，只影响同步到内部 Knowledge Base 时的检索文本生成方式

### 2.5 时序聚合分析

METRIC 类型集合支持丰富的时序分析能力：

- 聚合函数：SUM（求和）、AVG（均值）、MIN（最小值）、MAX（最大值）、COUNT（计数）
- 时间粒度：按天（DAY）、按周（WEEK）、按月（MONTH）分组
- 时间范围：支持指定起止时间的范围查询

## 3. 使用场景

### 3.1 个人知识管理

```
用户：帮我建一个读书笔记本
Agent：已创建「读书笔记」集合（Note 类型），你可以随时记录读书心得。

用户：记一下：今天读完了《思考快与慢》第三部分，关于锚定效应的实验很有启发...
Agent：已记录到「读书笔记」。

用户：我之前记过关于认知偏差的笔记吗？
Agent：我先检索了当前绑定资料，找到 2 条相关笔记：
1. 3月5日 - 《思考快与慢》锚定效应实验...
2. 2月28日 - 《影响力》承诺一致性原理...
```

### 3.2 健康数据追踪

```
用户：今天跑了5公里，用时28分钟
Agent：已记录到「运动记录」。本周你已跑步3次，累计12公里。

用户：这个月运动情况怎么样？
Agent：本月运动统计：
- 总距离：45.2公里
- 平均配速：5'32"/km
- 运动次数：12次
- 周均：3次/周，趋势稳定
```

### 3.3 生活清单管理

```
用户：帮我记一下要买的东西：牛奶、面包、鸡蛋
Agent：已添加到「购物清单」。当前共5项待购。

用户：购物清单里还有什么？
Agent：购物清单（5项）：
1. 牛奶 ← 刚添加
2. 面包 ← 刚添加
3. 鸡蛋 ← 刚添加
4. 洗衣液 ← 3月8日添加
5. 垃圾袋 ← 3月6日添加
```

### 3.4 YAML Skill 扩展数据存储

自定义 YAML Skill 可通过 DataStore 工具持久化数据：

```yaml
# skills/recipe-manager.yaml
id: recipe-manager
name: 食谱管理
instructions: |
  你是一个食谱管理助手。使用 datastore 工具管理用户的食谱集合。
  集合名称：食谱
  属性：name(TEXT), cuisine(SELECT), difficulty(SELECT), ingredients(JSON), steps(JSON)
suggestedTools:
  - datastore.create_collection
  - datastore.add_document
  - datastore.query_documents
  - datastore.update_document
  - datastore.delete_document
```

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.datastore.enabled` | `true` | 通用数据存储总开关 |
| `lifepilot.datastore.max-collections` | `100` | 最大集合数量 |
| `lifepilot.datastore.max-documents-per-collection` | `10000` | 单集合最大文档数 |
| `lifepilot.datastore.max-document-size-bytes` | `65536` | 单文档 JSON 最大字节数 |
| `lifepilot.datastore.default-page-size` | `20` | 默认分页大小 |
| `lifepilot.datastore.max-page-size` | `100` | 最大分页大小 |
| `lifepilot.datastore.index-threshold` | `100` | 文档数超过此阈值时建议创建属性索引 |

## 5. 限制与未来扩展

### 5.1 当前限制

- 不支持跨集合关联查询（如「书单中的书关联到读书笔记」）
- 不支持集合级权限控制（当前为单用户场景）
- METRIC 聚合不支持自定义时间粒度（仅 DAY/WEEK/MONTH）
- Generated Column 索引在集合创建后动态添加，不支持对已有文档回填索引

### 5.2 未来扩展方向

- 跨集合引用：支持文档间关联（类似 Notion 的 Relation 属性）
- SyncEngine 集成：DataStoreSyncAdapter 支持将扩展数据同步到外部服务
- Workflow 集成：DataStoreWorkflowAdapter 支持工作流步骤读写数据存储
- 自动检索路由：在绑定 Datastore 的前提下，进一步提升资料检索与 Web 搜索的协同策略
- 数据导入导出：支持 CSV/JSON 批量导入导出

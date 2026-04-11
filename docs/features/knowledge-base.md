# 知识库管理 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.knowledge`
> **最后更新**：2026-04-11

## 1. 功能概述

知识库管理模块为知微提供文档级知识管理能力。用户可创建多个独立知识库，导入 PDF / Word / Markdown / TXT 等格式文档，系统自动完成格式检测、文本解析、智能分块（含 Parent-Child 两级分块）、向量索引和全文索引。检索时通过三路检索（向量 + FTS5 + 知识图谱）+ RRF 融合返回最相关的文档片段，为 Agent 对话提供知识增强。

## 2. 核心特性

### 2.1 多知识库实例管理

支持创建多个独立知识库，每个知识库可独立配置 Embedding 模型和分块策略。提供知识库的创建、查询（按关键词 / 标签 / 时间范围）、更新、删除等完整 CRUD 操作。删除文档时自动级联清理分块数据并刷新统计信息。

知识库列表接口返回包含系统管理的内部知识库。知识库模型包含 `systemManaged` 和 `ownerDatastoreId` 字段：
- `systemManaged=true` 的知识库由 Datastore 自动创建和维护，不可通过知识库删除接口直接删除（返回 403），需从对应的 Datastore 侧删除
- `ownerDatastoreId` 标识该系统知识库归属的 Datastore ID

### 2.2 多格式文档导入

基于 Apache Tika 自动检测文件格式，支持 4 种文档格式：
- PDF 文件（通过 Tika 解析）
- Word 文档（通过 Tika 解析）
- Markdown 文件（提取标题结构）
- 纯文本文件

文档解析器通过 sealed interface 定义，保证类型安全和穷举匹配。

### 2.3 智能分块策略

提供 6 种分块策略，SmartChunker 通过三层管线自动处理所有文档类型：
- 固定大小分块：支持重叠和句子边界尊重
- 递归分块：按分隔符层次递归切分（仅保留句子级及以上分隔符，避免碎片化）
- 标题分块：按 Markdown 标题层级切分
- 语义分块：基于 Embedding 相似度检测语义断点
- SmartChunker 三层架构：文档结构分析（逐行分类为标题/段落/代码/列表/表格）→ 区域路由（按结构类型选择最佳分块器）→ 合并后处理（合并碎片、应用重叠、重编号）
- Parent-Child 两级分块：大块（parent）用于返回给 LLM，小块（child）用于向量检索，兼顾精度与上下文完整性

### 2.4 三路检索 + RRF 融合

检索时同时执行向量语义检索（sqlite-vec）、全文关键词检索（FTS5）和知识图谱遍历，三路并行，通过自适应 RRF（Reciprocal Rank Fusion）融合三路结果。向量置信度低时自动调整权重，确保检索质量。检索管线依次执行：精排（基于原始匹配内容评分）→ 上下文窗口扩展（展示层增强不影响排序）→ Corrective RAG。查询增强与主搜索并行执行（非阻塞），不增加检索延迟。

### 2.5 查询增强

通过 LLM 改写或扩展用户查询以提升检索精度，支持三种模式：
- rewrite：生成查询改写变体，适合模糊查询
- HyDE：生成假设文档 Embedding，适合专业领域查询
- none：不增强，适合精确查询

带独立超时控制，LLM 不可用时降级返回原始查询。

### 2.6 可选精排

对初步检索结果进行二次排序，提升结果精度：
- LLM 精排：使用 LLM 对候选结果评分排序（支持 pointwise / listwise 模式）
- API 精排：调用外部 Reranker API（如 Jina）进行精排

### 2.7 分块上下文增强

可选使用 LLM 为每个分块生成上下文前缀，使独立分块也能被正确理解，提升检索精度。支持批量处理。

### 2.8 断点续传

文档摄入管线记录每个阶段的处理状态（`DocumentStatus`），大文档摄入失败后可从上次阶段恢复，避免重复处理。

### 2.9 知识提取

可选在文档摄入完成后，从分块中提取结构化实体写入 L3 语义记忆，为记忆系统的知识图谱提供文档级知识来源。

### 2.10 Datastore 领域绑定

知识库现在支持与 datastore 协同工作：

- 一个知识库可挂载多个 datastore
- 上传文件时可指定文档归属到某个 datastore
- 同一知识库内可同时存在共享文件、归属不同 datastore 的文件，以及由 datastore 同步而来的结构化文档
- datastore 结构化数据会异步同步进关联知识库，进入与普通文档一致的分块、向量和全文检索链路

知识库级挂载决定“服务哪些领域”，文档级归属决定“这篇文档真正属于哪个领域”。

### 2.11 文档级领域归属

文件文档支持在上传时指定 datastore，也支持后续在文档列表里修改归属，并提供“无归属”选项。这里的“无归属”表示共享文档：知识库显式加载时可检索，但不会在 datastore 自动收口检索时被带入。

由 datastore 同步生成的 `DATASTORE_DOCUMENT` 为只读来源文档，知识库侧不会直接改写其领域归属。

### 2.12 Datastore 自动收口检索

当会话加载某个 datastore 时，系统会自动带上它关联的知识库，但默认检索只搜索：

- `sourceDatastoreId = 当前 datastore` 的文件文档
- 当前 datastore 同步生成的 `DATASTORE_DOCUMENT`

这样可以复用知识库容器，而不把同库中其他领域或共享内容混进当前上下文。

## 3. 使用场景

用户将个人笔记、技术文档、学习资料等导入知识库后，在与 Agent 对话时，系统自动从知识库中检索相关片段注入上下文，使 Agent 能够基于用户的私有知识回答问题。例如用户导入一份项目文档后，可以直接询问项目相关细节，Agent 会引用文档内容作答。

多知识库机制允许用户按主题组织知识（如"工作文档"、"学习笔记"、"技术参考"），检索时可指定知识库范围，避免不相关内容干扰。

当用户在小说创作、学习资料整理等场景中加载某个 datastore 时，系统会只检索该领域下的素材文档和同步文档；如果用户需要跨领域共享资料，则应显式加载知识库本身。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.knowledge.enabled` | `true` | 知识库模块总开关 |
| `lifepilot.knowledge.data-dir` | `~/.zhiwei/data` | 文档存储目录 |
| `lifepilot.knowledge.max-file-size` | `104857600` | 最大文件大小（字节，默认 100MB） |
| `lifepilot.knowledge.chunking.default-strategy` | `smart` | 默认分块策略 |
| `lifepilot.knowledge.retrieval.top-k` | — | 检索返回数量 |
| `lifepilot.knowledge.reranker.enabled` | — | 是否启用精排 |
| `lifepilot.knowledge.query-enhancer.mode` | — | 查询增强模式（rewrite / hyde / none） |
| `lifepilot.knowledge.context-enricher.enabled` | — | 是否启用分块上下文增强 |
| `lifepilot.knowledge.extraction.enabled` | — | 是否启用知识提取 |

## 5. 限制与未来方向

当前限制：
- 仅支持 4 种文档格式（PDF / Word / Markdown / TXT），不支持 Excel、HTML 等
- 语义分块依赖 Embedding 模型，处理速度较慢
- 知识提取依赖 LLM，大量文档摄入时成本较高

未来方向：
- 支持更多文档格式（HTML、Excel、CSV 等）
- 增量更新：文档修改后仅重新处理变更部分
- 知识库版本管理和快照
- 跨知识库联合检索优化

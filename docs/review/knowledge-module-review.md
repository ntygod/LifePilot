# Knowledge 模块 — 完整问题清单与改进计划

> 包含代码级 review 问题（57 项）+ 架构/能力差距分析（14 项），合计 71 项。
> 按修复阶段分层组织，作为制定实施计划的输入。

---

## 第一部分：架构与能力差距（A 类）

> 这些问题不是"代码写错了"，而是"关键能力缺失或设计层面不足"，决定了系统是玩具还是生产级。

### A1 — 分块无层级关系（Parent-Child 架构缺失）

当前 DocumentChunk 是扁平的 — 检索单位 = 返回单位。生产级 RAG 的标准做法是 Parent-Child 分块：小块（200~400 token）用于向量检索精准命中，命中后返回其所属的大块（1000~2000 token）保证上下文完整。当前的 contextWindowSize 是检索后临时拼接相邻块，拼接点在硬切边界，语义完整性远不如预先存储的 parent chunk。

**影响**：检索精度和 LLM 回答质量的根本瓶颈。

### A2 — Token 计量全靠估算，无真实 Tokenizer

`estimateTokens` 用 `中文字符数 + 其他字符/4` 估算，实际误差 20%~40%。后果：分块大小与 embedding 模型 max_tokens 不对齐可能截断；上下文增强 prompt 可能超限；检索时返回的 token 预算不可控。应集成真实 tokenizer（如 `jtokkit` for Java，支持 tiktoken 编码）。

**影响**：分块精度、prompt 溢出风险。

### A3 — 知识图谱提取了但未接入检索

KnowledgeExtractionPipeline 从文档提取实体和关系写入 SemanticMemory，但 DocumentRetriever 完全不查询知识图谱。花了 LLM 调用成本提取，检索时却不用。多跳推理（"A 的合作方 B 的 CEO 是谁？"）完全无法完成。这正是 GraphRAG（Microsoft, 2024）解决的问题。

**影响**：多跳推理能力为零、LLM 调用浪费。

### A4 — FTS5 被短语搜索锁死

`escapeFtsQuery` 把查询包在双引号里，变成 FTS5 精确短语匹配。搜索"知识库检索优化"只匹配这个精确序列，不会匹配"知识库的检索需要优化"。hybrid search 退化为纯向量检索，FTS 这条路几乎废掉。

**影响**：全文检索召回率极低，hybrid search 名存实亡。

### A5 — 无检索质量评估 / Corrective RAG

当前流程：查询 → 检索 → 直接返回。没有评估"检索结果到底好不好"。生产系统（CRAG, 2024）在检索后评估质量：高质量直接用，质量模糊则改写重试，质量低则拒答或联网补充。这一环节对减少幻觉至关重要。

**影响**：无法防御低质量检索导致的 LLM 幻觉。

### A6 — 无 RAG 评估框架

没有 golden test set、没有自动化评估指标（MRR@k / NDCG@k / Recall@k / Faithfulness）。所有调优都是盲人摸象 — 改了分块策略/检索参数，无法量化"变好了还是变差了"。

**影响**：无法科学迭代，是生产系统和玩具的最根本区别。

### A7 — 检索结果无去重

RRF 融合后可能返回高度重叠的分块（来自同一段文字的不同切分窗口、或 overlap 重叠区域）。浪费 LLM 上下文窗口，降低信息密度。生产系统做 near-duplicate detection（MinHash / SimHash）保证结果多样性。

**影响**：LLM 上下文利用率低。

### A8 — sqlite-vec 能力天花板

sqlite-vec 只支持单向量暴力 KNN，无 ANN 索引（HNSW / IVF）、无 metadata pre-filtering（当前先搜 3×topK 候选再过滤）、无多向量检索（ColBERT 风格）。数据量超过 10 万条后性能断崖。

**影响**：中长期可扩展性瓶颈。当前数据量下可暂缓。

### A9 — PDF 解析仅文本提取，无版面分析

PdfParser 只用 PDFTextStripper 提取文本，丢失了表格结构、图片、多栏布局信息。文本合并后的分块无法区分表格单元格和正文段落。

**影响**：PDF 文档的检索质量。

### A10 — 无端到端检索可观测性

只有零散 log，无结构化 trace。无法回答"这次检索为什么慢 / 为什么召回了错误文档"。应产生结构化 trace：各阶段耗时、各路结果数量、最终分数分布。

**影响**：线上排查和持续优化困难。

### A11 — Embedding 调用全部逐条，无批量接口

SemanticChunker 逐句 embedding、VectorIndexer 逐 chunk embedding。EmbeddingRouter 缺少 `embedBatch(List<String>)` 接口，导致无法使用批量 API。100 个 chunk = 100 次 HTTP 调用。

**影响**：导入和语义分块性能。

### A12 — 批量 Embedding 接口缺失导致 SemanticChunker 不可用于生产

SemanticChunker 逐句调 embedding（100 句 = 100 次 API），耗时数分钟级。生产环境的语义分块应在秒级完成。此问题本质是 A11 的下游，批量接口加好后才能真正修复。

### A13 — 中文分词能力缺失

RecursiveChunker 在空格分隔符层将中文逐字拆分（功能性 bug），且整个模块没有中文分词能力。FTS5 中文检索也需要分词支持（jieba / HanLP）才能产生有意义的 token。

**影响**：中文文档的分块质量和全文检索质量。

### A14 — ChunkContextEnricher 逐块调 LLM，缺少并发控制

对每个 chunk 串行调用 LLM 生成上下文前缀。50 个 chunk 的文档 = 50 次 LLM 调用。虽有 batch 模式但 batch 内仍串行。无并发限流/令牌桶，大量文档同时导入时可能打满 API 配额。

**影响**：导入吞吐量。

---

## 第二部分：代码级问题（B 类）

> 按子模块组织，标注严重程度：🔴 功能性 bug / 🟡 性能问题 / 🔵 设计债务 / ⚪ 规范/风格

### B-chunking：分块子模块

| # | 问题 | 级别 | 位置 |
|---|------|------|------|
| B1 | estimateTokens() 和 sha256() 在 4 个实现类中完全重复 | 🔵 | FixedSizeChunker/RecursiveChunker/HeadingChunker/SemanticChunker |
| B2 | ChunkingConfig 配置体系混乱：RecursiveChunker/HeadingChunker 接收 config 但不用，maxChunkTokens/respectParagraphs/enableContextPrefix 从未使用 | 🔵 | ChunkingConfig, RecursiveChunker:37, HeadingChunker:39 |
| B3 | RecursiveChunker 中文逐字拆分 | 🔴 | RecursiveChunker:147-148 |
| B4 | RecursiveChunker 偏移量计算丢失分隔符，随文档长度漂移 | 🔴 | RecursiveChunker:249-256 |
| B5 | SemanticChunker 大块硬切破坏语义（应委托 fallbackChunker） | 🔴 | SemanticChunker:213-225 |
| B6 | SmartChunker estimateChunkCount 始终委托 recursiveChunker | 🔵 | SmartChunker:140-143 |
| B7 | SmartChunker selectStrategy 不利用 metadata（有 TODO） | 🔵 | SmartChunker:87 |
| B8 | SemanticChunker 不支持 overlap | 🔵 | SemanticChunker（整个类） |
| B9 | ChunkingStrategy / DocumentParser sealed interface Javadoc 过时 | ⚪ | ChunkingStrategy:9, DocumentParser:9 |
| B10 | DocumentChunk 16 参数构造器、null 默认 FILE、空安全不一致 | 🔵 | DocumentChunk |

### B-parser：解析子模块

| # | 问题 | 级别 | 位置 |
|---|------|------|------|
| B11 | estimateWordCount() 在 4 个 Parser 中重复 | 🔵 | MarkdownParser:305, PlainTextParser:226, PdfParser:210, WordParser:233 |
| B12 | ParseResult.estimateTokenCount 算法 bug（中文字符数与空格分词数不可比） | 🔴 | ParseResult:28-35 |
| B13 | MarkdownParser 不剥离 Front Matter，元数据文本污染分块 | 🔴 | MarkdownParser:52-85 |
| B14 | MarkdownParser Front Matter 解析过于简陋（不支持多行值/列表/嵌套） | 🟡 | MarkdownParser:113-137 |
| B15 | MarkdownParser 日期解析只支持 Instant 格式，几乎永远失败 | 🔴 | MarkdownParser:293-300 |
| B16 | MarkdownParser 不支持波浪线围栏代码块 | 🟡 | MarkdownParser FENCED_CODE_BLOCK |
| B17 | extractMetadata 在 Markdown/PlainText 中做完整 parse 浪费 | 🟡 | MarkdownParser:88-91, PlainTextParser:81-84 |
| B18 | PlainTextParser GBK 检测误判率高 | 🟡 | PlainTextParser:131-135 |
| B19 | WordParser 不识别中文标题样式（"标题 1"~"标题 6"） | 🔴 | WordParser:132-145 |
| B20 | WordParser 使用过时 FileInputStream | ⚪ | WordParser:40 |
| B21 | DocumentElement.Image 缺少 src/path 字段 | 🔵 | DocumentElement.Image |

### B-ingest：导入管线

| # | 问题 | 级别 | 位置 |
|---|------|------|------|
| B22 | DocumentIngester 构造器 14 个参数 | 🔵 | DocumentIngester:70-83 |
| B23 | 每次 ingest/doIndex 都创建新 VirtualThreadPerTaskExecutor | 🟡 | DocumentIngester:124, 141, 487, 494 |
| B24 | resolveChunkingConfigMetadata 始终返回空 Map（死代码） | 🔵 | DocumentIngester:460-463 |
| B25 | refreshKnowledgeBaseCounts 查全表做计数 | 🟡 | DocumentIngester:530-539 |
| B26 | doChunk 重建 DocumentChunk（16 参数逐字段拷贝） | 🔵 | DocumentIngester:352-361 |
| B27 | resume 不验证文件是否仍然存在 | 🟡 | DocumentIngester:165 |
| B28 | createConfiguredChunker 仅 fixed-size 支持 per-KB 配置 | 🔵 | DocumentIngester:425-428 |

### B-index：索引子模块

| # | 问题 | 级别 | 位置 |
|---|------|------|------|
| B29 | parseJsonList/buildScopeSql/parseSourceType/ScopeSql 在两个 Indexer 中完全重复 | 🔵 | FtsIndexer + VectorIndexer |
| B30 | VectorIndexer.indexBatch 逐条 INSERT（应 batchUpdate） | 🟡 | VectorIndexer:334-340 |
| B31 | FtsIndexer.indexChunks 逐条 UPDATE 且吞异常 | 🟡 | FtsIndexer:64-72 |
| B32 | VectorIndexer 距离→相似度转换无文档 | ⚪ | VectorIndexer:284 |
| B33 | candidateK = topK * 3 硬编码 | 🔵 | VectorIndexer:237 |
| B34 | initVec0Table 探测行可能残留 + 静默 DROP TABLE | 🟡 | VectorIndexer:84-87 |
| B35 | IN 子句可能超出 SQLite 变量限制 | 🟡 | VectorIndexer:257-265 |
| B36 | IndexingResult 返回值从未被使用 | 🔵 | DocumentIngester.doIndex |

### B-retrieve：检索子模块

| # | 问题 | 级别 | 位置 |
|---|------|------|------|
| B37 | expandContextWindow 对每个命中块查全文档分块（N+1 查询） | 🟡 | DocumentRetriever:295 |
| B38 | Rewrite 模式每个改写查询全量检索（开销线性增长） | 🟡 | DocumentRetriever:367-378, 392-404 |
| B39 | resolveEmbeddingModel/resolveRerankerModel 逐 KB 查询 | 🟡 | DocumentRetriever:167-168, 184-185 |
| B40 | QueryEnhancer.parseJsonArray 与 ChunkContextEnricher.parseBatchResponse 重复 | 🔵 | QueryEnhancer, ChunkContextEnricher |
| B41 | QueryEnhancer 模式用字符串比较而非枚举 | ⚪ | QueryEnhancer:56-59 |

### B-enricher/extract/detect

| # | 问题 | 级别 | 位置 |
|---|------|------|------|
| B42 | ChunkContextEnricher.estimateTokens 第 6 份重复 | 🔵 | ChunkContextEnricher:297-303 |
| B43 | ChunkContextEnricher.withContextPrefix 手动 16 参数拷贝 | 🔵 | ChunkContextEnricher:341-359 |
| B44 | DuplicateDetector.check 查全表做 hash 比对 | 🟡 | DuplicateDetector:54-57 |
| B45 | DuplicateDetector.computeHash 大文件 readAllBytes OOM 风险 | 🟡 | DuplicateDetector:77 |
| B46 | KnowledgeExtractionPipeline.extract(List, String) 旧签名无条件抛异常 | 🔵 | KnowledgeExtractionPipeline:79-81 |
| B47 | resolveEntityId 遍历所有 EntityType 逐一查询 | 🟡 | KnowledgeExtractionPipeline:226-233 |

### B-repository

| # | 问题 | 级别 | 位置 |
|---|------|------|------|
| B48 | parseSourceType 在 4 个类中重复 | 🔵 | DocumentChunkRepo, DocumentRepo, FtsIndexer, VectorIndexer |
| B49 | serializeMap/deserializeMetadata 在两个 Repository 重复 | 🔵 | DocumentChunkRepository, DocumentRepository |
| B50 | 多处 SELECT * 违反编码规范 | ⚪ | DocumentChunkRepository:103, DocumentRepository:111/123/134/147 |

### B-model/config

| # | 问题 | 级别 | 位置 |
|---|------|------|------|
| B51 | Document record 20 个字段 | 🔵 | Document.java |
| B52 | DocumentSearchResult 3 个构造器做向后兼容（应删旧构造器） | 🔵 | DocumentSearchResult:52-71 |
| B53 | 多处 null sourceType 静默默认 FILE（Document/DocumentSearchResult/DocumentChunk） | 🔵 | 3 处 compact constructor |

### B-cross：跨模块横切面

| # | 问题 | 级别 | 位置 |
|---|------|------|------|
| B54 | estimateTokens/estimateWordCount 至少 9 处重复且算法不一致 | 🔵 | 跨 chunking/enricher/parser |
| B55 | 手写 JSON 数组解析器至少 3 处 | 🔵 | FtsIndexer/VectorIndexer/QueryEnhancer |
| B56 | DocumentChunk 16 参数拷贝至少 3 处 | 🔵 | Ingester/Enricher/HeadingChunker |
| B57 | metadata 通路全链路废弃 | 🔵 | Ingester → SmartChunker → 各 Chunker |
| B58 | 6+ 处创建新的 VirtualThreadPerTaskExecutor | 🟡 | Ingester + Retriever |
| B59 | 3 处"查全表再内存过滤"反模式 | 🟡 | DuplicateDetector/Ingester/Retriever |

---

## 问题统计

| 类别 | 🔴 功能 bug | 🟡 性能 | 🔵 设计债务 | ⚪ 规范 | A 类架构 | 合计 |
|------|-----------|---------|-----------|--------|---------|------|
| 数量 | 7 | 16 | 25 | 5 | 14 | **67** |

> 注：部分 B 类问题被 A 类覆盖（如 B3 被 A13 覆盖、B1/B11/B42/B54 被 A2 覆盖），实际去重后独立问题约 60 项。

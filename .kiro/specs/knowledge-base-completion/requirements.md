# Requirements Document

## Introduction

本 spec 补全知识库管理模块（模块 8）的缺失组件。现有 knowledge-base spec 已实现基础层（数据模型、Repository、MarkdownParser、PlainTextParser、FixedSizeChunker、KnowledgeBaseManager CRUD、Flyway V8 迁移）。本 spec 在此基础上补全文档导入管线（DocumentIngester）、高级解析器（PDF/Word）、高级分块策略（Recursive/Heading/Smart）、分块上下文增强（ChunkContextEnricher）、双索引服务（VectorIndexer/FtsIndexer）、文档检索（DocumentRetriever）、可选 Reranker 精排、以及知识提取管线（KnowledgeExtractionPipeline）。

参考文档：
- 架构设计：`docs/architecture/knowledge-base.md`
- 特性设计：`docs/features/knowledge-base.md`
- 编码规范：`.kiro/steering/coding-standards.md`

## Glossary

- **DocumentIngester**: 文档导入管线编排器，协调解析→分块→索引→提取的完整流程
- **DocumentParser**: 文档解析器 sealed interface，将不同格式文档转换为统一纯文本 + 结构化表示
- **PdfParser**: PDF 文档解析器，基于 Apache PDFBox 实现
- **WordParser**: Word/DOCX 文档解析器，基于 Apache POI 实现
- **ChunkingStrategy**: 分块策略 sealed interface，将文本切分为 DocumentChunk 列表
- **RecursiveChunker**: 递归语义分块器，在自然边界（段落→句子→词）处递归切分
- **HeadingChunker**: 标题层级分块器，按文档标题结构切分并保留标题层级
- **SmartChunker**: 智能策略选择器，根据文档特征自动选择最佳分块策略
- **ChunkContextEnricher**: 分块上下文增强器，为每个分块添加文档级上下文前缀以提升检索召回率
- **VectorIndexer**: 向量索引服务，通过 LlmRouter.embed() 批量向量化分块并存入 sqlite-vec
- **FtsIndexer**: FTS5 全文索引服务，构建和维护 SQLite FTS5 全文索引
- **DocumentRetriever**: 文档混合检索服务，组合向量检索 + FTS5 全文搜索
- **Reranker**: 精排接口，对初步检索结果进行二次排序以提升精度
- **KnowledgeExtractionPipeline**: 知识提取管线，从文档分块中提取实体和关系写入 L3 语义记忆
- **DuplicateDetector**: 重复文档检测器，基于内容哈希判断文档是否已存在
- **IngestionProgress**: 导入进度 record，追踪文档导入管线各阶段的进度
- **LlmRouter**: LLM 路由引擎，提供 embed() 和 call() 等方法
- **SemanticMemory**: L3 语义记忆，存储时序实体和关系的知识图谱
- **FormatDetector**: 文档格式检测器，根据文件扩展名路由到对应解析器
- **KnowledgeBaseProperties**: 知识库配置属性，映射 `lifepilot.knowledge` 前缀

## Requirements

### Requirement 1: PDF 文档解析

**User Story:** As a user, I want to import PDF documents into my knowledge base, so that I can search and retrieve information from PDF files.

#### Acceptance Criteria

1. WHEN a PDF file is provided, THE PdfParser SHALL parse the file into a ParseResult containing extracted plain text, DocumentElement list, and DocumentMetadata
2. WHEN a PDF file contains metadata (title, author, creation date, page count), THE PdfParser SHALL extract the metadata into a DocumentMetadata record
3. IF a PDF file is encrypted or corrupted, THEN THE PdfParser SHALL throw a DocumentParseException with Phase.FORMAT_DECODE and a descriptive error message
4. IF a PDF file contains pages that cannot be parsed, THEN THE PdfParser SHALL return the successfully parsed content and record warnings in ParseResult.warnings
5. THE PdfParser SHALL report supported extensions as ["pdf"]
6. THE DocumentParser sealed interface SHALL be expanded to permit PdfParser

### Requirement 2: Word 文档解析

**User Story:** As a user, I want to import Word/DOCX documents into my knowledge base, so that I can search and retrieve information from Word files.

#### Acceptance Criteria

1. WHEN a DOCX file is provided, THE WordParser SHALL parse the file into a ParseResult containing extracted plain text, DocumentElement list, and DocumentMetadata
2. WHEN a DOCX file contains metadata (title, author, creation date), THE WordParser SHALL extract the metadata into a DocumentMetadata record
3. WHEN a DOCX file contains tables, THE WordParser SHALL extract each table as a DocumentElement.Table with headers and rows
4. IF a DOCX file is corrupted or in unsupported format (e.g. legacy .doc), THEN THE WordParser SHALL throw a DocumentParseException with a descriptive error message
5. THE WordParser SHALL report supported extensions as ["docx"]
6. THE DocumentParser sealed interface SHALL be expanded to permit WordParser

### Requirement 3: 递归语义分块

**User Story:** As a user, I want long documents to be chunked at natural semantic boundaries, so that each chunk preserves coherent meaning for better retrieval quality.

#### Acceptance Criteria

1. WHEN text is provided, THE RecursiveChunker SHALL split the text at natural boundaries in priority order: paragraph breaks → sentence endings → word boundaries
2. THE RecursiveChunker SHALL produce chunks where each chunk size is between minChunkSize and maxChunkSize characters
3. WHEN a natural boundary cannot be found within the maxChunkSize limit, THE RecursiveChunker SHALL fall back to the next lower-priority separator
4. THE RecursiveChunker SHALL apply overlap between consecutive chunks according to ChunkingConfig.overlapSize
5. THE ChunkingStrategy sealed interface SHALL be expanded to permit RecursiveChunker
6. FOR ALL valid text inputs, THE RecursiveChunker SHALL produce chunks whose concatenation (minus overlaps) covers the entire original text

### Requirement 4: 标题层级分块

**User Story:** As a user, I want structured documents to be chunked by heading hierarchy, so that each chunk corresponds to a logical section and preserves document structure.

#### Acceptance Criteria

1. WHEN text with headings is provided, THE HeadingChunker SHALL split the text at heading boundaries (levels 1-6)
2. THE HeadingChunker SHALL populate each DocumentChunk.headingHierarchy with the ancestor heading path (e.g. ["Chapter 1", "Section 1.1", "Subsection 1.1.1"])
3. WHEN a heading section exceeds maxChunkSize, THE HeadingChunker SHALL further split the section using RecursiveChunker as fallback
4. WHEN text contains no headings, THE HeadingChunker SHALL delegate to RecursiveChunker for the entire text
5. THE ChunkingStrategy sealed interface SHALL be expanded to permit HeadingChunker

### Requirement 5: 智能分块策略选择

**User Story:** As a user, I want the system to automatically choose the best chunking strategy based on document characteristics, so that I get optimal retrieval quality without manual configuration.

#### Acceptance Criteria

1. THE SmartChunker SHALL analyze document characteristics (heading density, average paragraph length, total text length, document format) to select the optimal chunking strategy
2. WHEN a document has high heading density (heading count / total paragraphs > configurable threshold), THE SmartChunker SHALL select HeadingChunker
3. WHEN a document has low heading density and text length exceeds a configurable threshold, THE SmartChunker SHALL select RecursiveChunker
4. WHEN a document is short (text length below a configurable threshold), THE SmartChunker SHALL select FixedSizeChunker
5. THE SmartChunker SHALL delegate actual chunking to the selected strategy
6. THE ChunkingStrategy sealed interface SHALL be expanded to permit SmartChunker

### Requirement 6: 分块上下文增强

**User Story:** As a user, I want each document chunk to carry document-level context, so that retrieval accuracy is improved by reducing ambiguity in isolated chunks.

#### Acceptance Criteria

1. WHEN ChunkingConfig.enableContextPrefix is true, THE ChunkContextEnricher SHALL generate a context prefix (50-100 tokens) summarizing the document and the chunk's position within it
2. THE ChunkContextEnricher SHALL use LlmRouter to generate the context prefix via LLM call
3. THE ChunkContextEnricher SHALL set the generated prefix into DocumentChunk.contextPrefix
4. IF LlmRouter is unavailable, THEN THE ChunkContextEnricher SHALL skip context enrichment and leave contextPrefix as Optional.empty without failing the ingestion pipeline
5. WHEN DocumentChunk.embeddingText() is called, THE DocumentChunk SHALL concatenate contextPrefix and content for embedding

### Requirement 7: 向量索引服务

**User Story:** As a user, I want document chunks to be vectorized and stored for semantic search, so that I can find relevant content by meaning rather than exact keywords.

#### Acceptance Criteria

1. WHEN a list of DocumentChunks is provided, THE VectorIndexer SHALL batch-vectorize the chunks using LlmRouter.embed() and store the embeddings in sqlite-vec
2. THE VectorIndexer SHALL process chunks in configurable batch sizes (default 32) to respect LLM API rate limits
3. WHEN a document is deleted, THE VectorIndexer SHALL remove all associated chunk embeddings from sqlite-vec
4. WHEN a similarity search query is provided, THE VectorIndexer SHALL return the top-K most similar chunks with similarity scores
5. IF LlmRouter.embed() fails for a batch, THEN THE VectorIndexer SHALL retry with exponential backoff (max 2 retries) before propagating the error

### Requirement 8: FTS5 全文索引服务

**User Story:** As a user, I want document chunks to be full-text indexed, so that I can find content by exact keyword matching complementing semantic search.

#### Acceptance Criteria

1. WHEN a list of DocumentChunks is provided, THE FtsIndexer SHALL insert the chunk content into the document_chunks_fts FTS5 virtual table
2. WHEN a document is deleted, THE FtsIndexer SHALL remove all associated entries from the FTS5 index
3. WHEN a full-text search query is provided, THE FtsIndexer SHALL return matching chunks ranked by BM25 relevance score
4. THE FtsIndexer SHALL require a Flyway migration (V13) to create the document_chunks_fts FTS5 virtual table

### Requirement 9: 文档导入管线

**User Story:** As a user, I want to upload documents and have them automatically parsed, chunked, indexed, and optionally knowledge-extracted, so that documents become searchable and integrated into my knowledge system.

#### Acceptance Criteria

1. WHEN a document file is submitted for ingestion, THE DocumentIngester SHALL orchestrate the pipeline: parse → chunk → index (vector + FTS5) → extract (optional)
2. THE DocumentIngester SHALL execute the ingestion pipeline asynchronously using Virtual Thread
3. THE DocumentIngester SHALL update Document.status through the lifecycle: UPLOADING → PARSING → CHUNKING → INDEXING → EXTRACTING → READY
4. WHEN the pipeline fails at any stage, THE DocumentIngester SHALL set Document.status to ERROR with the failed stage recorded in Document.lastProcessedStage and error details in Document.errorMessage
5. WHEN a document with status ERROR is submitted for resume, THE DocumentIngester SHALL restart the pipeline from the last failed stage
6. THE DocumentIngester SHALL use FormatDetector to select the appropriate DocumentParser based on file extension
7. THE DocumentIngester SHALL use SmartChunker as the default chunking strategy (configurable per knowledge base)
8. THE DocumentIngester SHALL invoke ChunkContextEnricher after chunking when ChunkingConfig.enableContextPrefix is true
9. THE DocumentIngester SHALL invoke VectorIndexer and FtsIndexer in parallel during the indexing stage
10. THE DocumentIngester SHALL publish IngestionProgress events at each stage transition for progress tracking

### Requirement 10: 重复文档检测

**User Story:** As a user, I want the system to detect duplicate documents within a knowledge base, so that I avoid redundant storage and indexing.

#### Acceptance Criteria

1. WHEN a document is submitted for ingestion, THE DuplicateDetector SHALL compute a SHA-256 content hash of the file
2. WHEN a document with the same content hash already exists in the same knowledge base, THE DuplicateDetector SHALL report the duplicate and prevent re-ingestion
3. WHEN a document with the same content hash exists but in a different knowledge base, THE DuplicateDetector SHALL allow ingestion (cross-KB duplicates are permitted)

### Requirement 11: 文档混合检索

**User Story:** As a user, I want to search my knowledge base using a combination of semantic and keyword search, so that I get the most relevant results.

#### Acceptance Criteria

1. WHEN a search query and knowledge base IDs are provided, THE DocumentRetriever SHALL execute vector search (VectorIndexer) and full-text search (FtsIndexer) in parallel
2. THE DocumentRetriever SHALL fuse results from both search paths using Reciprocal Rank Fusion (RRF) with configurable weights
3. THE DocumentRetriever SHALL return results as a list of DocumentSearchResult records containing chunk content, similarity score, document metadata, and source path indicator
4. THE DocumentRetriever SHALL support cross-knowledge-base search when multiple knowledge base IDs are provided
5. WHEN an optional Reranker is configured, THE DocumentRetriever SHALL apply reranking to the fused results before returning

### Requirement 12: Reranker 精排

**User Story:** As a user, I want retrieved results to be re-ranked for higher precision, so that the most relevant chunks appear at the top.

#### Acceptance Criteria

1. THE Reranker SHALL be defined as a sealed interface with rerank(query, candidates, topK) method returning re-scored results
2. THE LlmReranker SHALL use LlmRouter to score query-document pairs and re-rank candidates by relevance
3. WHERE an API-based reranker is configured, THE ApiReranker SHALL call external reranker APIs (e.g. Jina, Cohere) for scoring
4. IF the configured Reranker is unavailable, THEN THE DocumentRetriever SHALL return results without reranking (graceful degradation)
5. THE Reranker SHALL be an optional component — the system SHALL function correctly without any Reranker configured

### Requirement 13: 知识提取管线

**User Story:** As a user, I want entities and relations to be automatically extracted from my documents and integrated into the knowledge graph, so that document knowledge becomes part of my cognitive memory system.

#### Acceptance Criteria

1. WHEN document chunks are provided, THE KnowledgeExtractionPipeline SHALL use LlmRouter to extract entities (names, types, descriptions) from chunk content
2. THE KnowledgeExtractionPipeline SHALL use LlmRouter to extract relations between identified entities
3. THE KnowledgeExtractionPipeline SHALL write extracted entities and relations to SemanticMemory (L3) via SemanticMemory.upsertWithConflictDetection() and SemanticMemory.addRelation()
4. IF LlmRouter is unavailable, THEN THE KnowledgeExtractionPipeline SHALL skip extraction gracefully — the document SHALL still reach READY status with entityCount = 0
5. THE KnowledgeExtractionPipeline SHALL execute asynchronously and independently from the indexing stage — indexing completion is sufficient for the document to be searchable
6. THE KnowledgeExtractionPipeline SHALL use DataRedactor to sanitize sensitive content before sending to cloud LLM providers

### Requirement 14: 配置外部化

**User Story:** As a developer, I want all business-tunable parameters to be externalized to configuration, so that the knowledge base system can be tuned without code changes.

#### Acceptance Criteria

1. THE KnowledgeBaseProperties SHALL include configuration for: recursive chunking (separators, min/max chunk size), heading chunking (max heading level, max chunk size), smart chunker thresholds (heading density threshold, short document threshold), vector indexer (batch size, retry count), FTS indexer settings, reranker (type, model, top-K), context enricher (enabled, max prefix tokens), and extraction pipeline (enabled, batch size)
2. THE application.yml SHALL declare all new configuration keys with sensible default values under the `lifepilot.knowledge` prefix
3. THE KnowledgeAutoConfiguration SHALL register all new beans (PdfParser, WordParser, RecursiveChunker, HeadingChunker, SmartChunker, VectorIndexer, FtsIndexer, DocumentIngester, DocumentRetriever, ChunkContextEnricher, DuplicateDetector, Reranker, KnowledgeExtractionPipeline) with appropriate conditional annotations

### Requirement 15: Flyway 数据库迁移

**User Story:** As a developer, I want the FTS5 virtual table and chunk embedding table to be created via Flyway migration, so that the database schema is version-controlled and reproducible.

#### Acceptance Criteria

1. THE Flyway migration V13 SHALL create the document_chunks_fts FTS5 virtual table for full-text search on document chunk content
2. THE Flyway migration V13 SHALL create the chunk_embeddings virtual table (sqlite-vec) for storing chunk embedding vectors

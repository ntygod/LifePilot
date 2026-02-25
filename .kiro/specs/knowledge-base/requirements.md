# Requirements Document — 知识库管理基础层

## Introduction

本 spec 聚焦于知识库管理系统的基础层实现，为后续的高级功能（PDF/Word 解析、智能分块、向量索引、知识提取管线等）奠定数据模型、持久化和基础解析能力。

范围包括：数据模型定义、数据库表创建、Repository 层、Markdown 和纯文本解析器、固定大小分块策略、KnowledgeBaseManager 基础 CRUD、自动配置。

不在本 spec 范围内：PDF/Word 解析器、RecursiveChunker、HeadingChunker、SmartChunker、VectorIndexer、FtsIndexer、KnowledgeExtractionPipeline、DocumentIngester、DocumentRetriever、REST API、ChunkContextEnricher。

参考文档：
- 架构设计：docs/architecture/knowledge-base.md
- 特性设计：docs/features/knowledge-base.md
- 编码规范：.kiro/steering/coding-standards.md

## Glossary

- **KnowledgeBase**: 知识库 record，文档的逻辑分组容器，每个知识库独立配置 Embedding 模型和分块策略
- **Document**: 文档 record，知识库中的单个文档记录，包含文件元数据和处理状态
- **DocumentChunk**: 文档分块 record，文档被切分后的最小检索单元，携带内容、偏移量、标题层级和内容哈希
- **DocumentStatus**: 文档状态枚举（UPLOADING / PARSING / CHUNKING / INDEXING / EXTRACTING / READY / UPDATING / DELETING / ERROR）
- **DocumentParser**: 文档解析器 sealed interface，将不同格式的文档转换为统一的 ParseResult
- **MarkdownParser**: Markdown 文档解析器，基于正则解析标题、代码块、表格、YAML Front Matter
- **PlainTextParser**: 纯文本解析器，支持 UTF-8 / GBK / ISO-8859-1 编码自动检测
- **ParseResult**: 解析结果 record，包含纯文本、结构化元素列表（DocumentElement）和文档元数据
- **DocumentMetadata**: 文档元数据 record，包含标题、作者、创建时间、页数、字数等
- **DocumentElement**: 文档结构元素 sealed interface（Heading / Paragraph / Table / CodeBlock / ListBlock / Image）
- **DocumentParseException**: 文档解析异常，包含失败阶段（Phase 枚举）和文件路径
- **FormatDetector**: 格式检测器，根据文件扩展名路由到对应的 DocumentParser 实现
- **ChunkingStrategy**: 分块策略 sealed interface，将文档文本切分为 DocumentChunk 列表
- **FixedSizeChunker**: 固定大小分块器，按固定字符数切分，支持重叠和句子边界对齐
- **ChunkingConfig**: 分块配置 record，控制最大/最小分块大小、重叠大小、Token 上限等参数
- **KnowledgeBaseManager**: 知识库管理服务，提供知识库和文档的基础 CRUD 操作
- **KnowledgeBaseRepository**: 知识库数据访问层，基于 JdbcTemplate 操作 knowledge_bases 表
- **DocumentRepository**: 文档数据访问层，基于 JdbcTemplate 操作 documents 表
- **DocumentChunkRepository**: 分块数据访问层，基于 JdbcTemplate 操作 document_chunks 表
- **KnowledgeBaseProperties**: Spring Boot 配置属性 record，映射 lifepilot.knowledge 前缀
- **KnowledgeAutoConfiguration**: 知识库模块的 Spring Boot 自动配置类

## Requirements

### Requirement 1: 知识库数据模型

**User Story:** As a developer, I want well-defined data model records for KnowledgeBase, Document, DocumentChunk, and related types, so that the knowledge base system has a solid type-safe foundation.

#### Acceptance Criteria

1. THE KnowledgeBase record SHALL contain fields: id (UUID), name, description, embeddingModel, rerankerModel (Optional), chunkingStrategy, chunkingConfig (Map), documentCount, totalChunks, createdAt, updatedAt
2. THE Document record SHALL contain fields: id (UUID), knowledgeBaseId, fileName, filePath, fileSize, mimeType, contentHash, status (DocumentStatus), chunkCount, entityCount, errorMessage (Optional), lastProcessedStage (Optional), metadata (Map), createdAt, updatedAt
3. THE DocumentChunk record SHALL contain fields: id (UUID), documentId, knowledgeBaseId, content, contextPrefix (Optional), chunkIndex, startOffset, endOffset, tokenCount, contentHash, headingHierarchy (List), pageNumber, metadata (Map)
4. THE DocumentStatus enum SHALL define values: UPLOADING, PARSING, CHUNKING, INDEXING, EXTRACTING, READY, UPDATING, DELETING, ERROR, each with a Chinese displayName
5. THE DocumentStatus enum SHALL provide isTerminal() returning true for READY and ERROR, and isRetryable() returning true for ERROR
6. THE KnowledgeBase record SHALL provide a static factory method create(name, description, embeddingModel) that generates a UUID and sets default values (chunkingStrategy="smart", documentCount=0, totalChunks=0)
7. THE DocumentChunk record SHALL provide an embeddingText() method that concatenates contextPrefix and content when contextPrefix is present
8. THE DocumentChunk record SHALL provide a breadcrumb() method that joins headingHierarchy with " > " separator
9. THE DocumentChunk record SHALL provide a contentLength() method returning the character count of content

### Requirement 2: 数据库表和 Flyway 迁移

**User Story:** As a developer, I want the knowledge base database tables created via Flyway migration scripts, so that the schema is version-controlled and reproducible.

#### Acceptance Criteria

1. WHEN the application starts, THE Flyway migration SHALL create a knowledge_bases table with columns: id (TEXT PK), name (TEXT NOT NULL), description (TEXT NOT NULL DEFAULT ''), embedding_model (TEXT NOT NULL), reranker_model (TEXT), chunking_strategy (TEXT NOT NULL DEFAULT 'smart'), chunking_config_json (TEXT NOT NULL DEFAULT '{}'), document_count (INTEGER NOT NULL DEFAULT 0), total_chunks (INTEGER NOT NULL DEFAULT 0), created_at (TEXT NOT NULL), updated_at (TEXT NOT NULL)
2. WHEN the application starts, THE Flyway migration SHALL create a documents table with columns: id (TEXT PK), knowledge_base_id (TEXT NOT NULL FK → knowledge_bases), file_name, file_path, file_size, mime_type, content_hash, status (TEXT DEFAULT 'UPLOADING'), chunk_count, entity_count, error_message, last_processed_stage, metadata_json, created_at, updated_at, with ON DELETE CASCADE foreign key
3. WHEN the application starts, THE Flyway migration SHALL create a document_chunks table with columns: id (TEXT PK), document_id (TEXT NOT NULL FK → documents), knowledge_base_id (TEXT NOT NULL FK → knowledge_bases), content, context_prefix, chunk_index, start_offset, end_offset, token_count, content_hash, heading_hierarchy_json, page_number, metadata_json, created_at, with ON DELETE CASCADE foreign keys
4. THE Flyway migration SHALL create indexes: idx_knowledge_bases_name on knowledge_bases(name), idx_documents_kb_id on documents(knowledge_base_id), idx_documents_status on documents(status), idx_documents_content_hash on documents(knowledge_base_id, content_hash), idx_document_chunks_doc_id on document_chunks(document_id), idx_document_chunks_kb_id on document_chunks(knowledge_base_id), idx_document_chunks_hash on document_chunks(content_hash)
5. THE Flyway migration script SHALL follow the naming convention V8__create_knowledge_base_tables.sql (next version after V7)
6. THE database schema SHALL follow LifePilot conventions: TEXT for UUID primary keys, TEXT for ISO 8601 timestamps, TEXT with _json suffix for JSON columns

### Requirement 3: Repository 层

**User Story:** As a developer, I want JdbcTemplate-based repository classes for knowledge_bases, documents, and document_chunks tables, so that data access is encapsulated and consistent.

#### Acceptance Criteria

1. THE KnowledgeBaseRepository SHALL provide save(KnowledgeBase) using INSERT ... ON CONFLICT(id) DO UPDATE (upsert semantics)
2. THE KnowledgeBaseRepository SHALL provide findById(id) returning Optional of KnowledgeBase
3. THE KnowledgeBaseRepository SHALL provide findAll() returning List of KnowledgeBase ordered by created_at DESC
4. THE KnowledgeBaseRepository SHALL provide deleteById(id) deleting the knowledge base record
5. THE KnowledgeBaseRepository SHALL provide updateDocumentCount(id, documentCount, totalChunks) updating counts and updated_at
6. THE DocumentRepository SHALL provide save(Document) using INSERT ... ON CONFLICT(id) DO UPDATE
7. THE DocumentRepository SHALL provide findById(id) returning Optional of Document
8. THE DocumentRepository SHALL provide findByKnowledgeBaseId(knowledgeBaseId) returning List of Document
9. THE DocumentRepository SHALL provide deleteById(id) deleting the document record
10. THE DocumentRepository SHALL provide updateStatus(id, DocumentStatus, Optional errorMessage) updating status, error_message, and updated_at
11. THE DocumentRepository SHALL provide updateContentHash(id, contentHash) updating content_hash and updated_at
12. THE DocumentRepository SHALL provide updateChunkCount(id, chunkCount) updating chunk_count and updated_at
13. THE DocumentRepository SHALL provide updateLastProcessedStage(id, stage) updating last_processed_stage and updated_at
14. THE DocumentChunkRepository SHALL provide saveAll(List of DocumentChunk) batch inserting chunks
15. THE DocumentChunkRepository SHALL provide findByDocumentId(documentId) returning List of DocumentChunk ordered by chunk_index
16. THE DocumentChunkRepository SHALL provide deleteByDocumentId(documentId) deleting all chunks for a document
17. THE DocumentChunkRepository SHALL provide countByDocumentId(documentId) returning the chunk count for a document

### Requirement 4: 文档解析器基础框架

**User Story:** As a developer, I want a DocumentParser sealed interface with supporting types (ParseResult, DocumentMetadata, DocumentElement, DocumentParseException), so that document parsing has a well-defined contract for all format implementations.

#### Acceptance Criteria

1. THE DocumentParser sealed interface SHALL declare methods: supportedExtensions() returning List of String, parse(Path) returning ParseResult, extractMetadata(Path) returning DocumentMetadata
2. THE DocumentParser sealed interface SHALL provide a default canParse(Path) method that checks if the file extension matches supportedExtensions()
3. THE DocumentParser sealed interface SHALL permit MarkdownParser and PlainTextParser as implementations in this spec (PdfParser and WordParser reserved for future spec)
4. THE ParseResult record SHALL contain fields: text (String), elements (List of DocumentElement), metadata (DocumentMetadata), warnings (List of String)
5. THE ParseResult record SHALL provide isEmpty() returning true when text is null or blank
6. THE ParseResult record SHALL provide estimateTokenCount() that counts Chinese characters individually and English words by whitespace splitting
7. THE DocumentMetadata record SHALL contain fields: title (Optional), author (Optional), createdAt (Optional of Instant), modifiedAt (Optional of Instant), pageCount (int), wordCount (long), language (Optional), extraProperties (Map)
8. THE DocumentMetadata record SHALL provide static factory methods empty() and fromFile(Path, wordCount)
9. THE DocumentElement sealed interface SHALL permit Heading, Paragraph, Table, CodeBlock, ListBlock, Image as record implementations, each with startOffset and endOffset fields
10. THE DocumentParseException SHALL extend RuntimeException and contain a Phase enum (FILE_READ, FORMAT_DECODE, TEXT_EXTRACTION, METADATA_EXTRACTION), phase field, and filePath field

### Requirement 5: Markdown 解析器

**User Story:** As a developer, I want a MarkdownParser that extracts text, headings, code blocks, tables, and YAML Front Matter from Markdown files, so that structured Markdown documents can be parsed for the knowledge base.

#### Acceptance Criteria

1. THE MarkdownParser SHALL support file extensions: md, markdown, mkd
2. WHEN a valid Markdown file is provided, THE MarkdownParser SHALL extract the full text content as a String
3. WHEN a Markdown file contains ATX headings (# to ######), THE MarkdownParser SHALL identify each heading as a DocumentElement.Heading with correct level (1-6) and text
4. WHEN a Markdown file contains fenced code blocks (```language ... ```), THE MarkdownParser SHALL identify each as a DocumentElement.CodeBlock with optional language identifier
5. WHEN a Markdown file contains GFM-style tables, THE MarkdownParser SHALL identify each as a DocumentElement.Table with headers and rows
6. WHEN a Markdown file contains YAML Front Matter (--- delimited), THE MarkdownParser SHALL extract key-value pairs and use them to populate DocumentMetadata (title, author, date, lang)
7. THE MarkdownParser SHALL sort extracted DocumentElement instances by startOffset in ascending order
8. WHEN a Markdown file has no YAML Front Matter title, THE MarkdownParser SHALL use the first heading as the document title in DocumentMetadata
9. IF a Markdown file cannot be read, THEN THE MarkdownParser SHALL throw DocumentParseException with Phase.FILE_READ
10. FOR ALL valid Markdown files, parsing the file and then reconstructing from ParseResult.text SHALL preserve the original text content (round-trip text preservation)

### Requirement 6: 纯文本解析器

**User Story:** As a developer, I want a PlainTextParser that reads text files with automatic encoding detection, so that plain text documents in various encodings can be imported into the knowledge base.

#### Acceptance Criteria

1. THE PlainTextParser SHALL support file extensions: txt, text, log, csv, tsv
2. WHEN a text file is provided, THE PlainTextParser SHALL detect encoding in order: UTF-8 BOM → UTF-8 (no replacement characters) → GBK → ISO-8859-1 fallback
3. WHEN a text file is read, THE PlainTextParser SHALL normalize line endings to LF (\n)
4. WHEN a text file contains paragraphs separated by blank lines, THE PlainTextParser SHALL identify each paragraph as a DocumentElement.Paragraph with correct offsets
5. THE PlainTextParser SHALL populate DocumentMetadata with the file name as title and estimated word count
6. IF a text file cannot be read, THEN THE PlainTextParser SHALL throw DocumentParseException with Phase.FILE_READ
7. FOR ALL readable text files, THE PlainTextParser SHALL return a non-null ParseResult with empty warnings list

### Requirement 7: 格式检测器

**User Story:** As a developer, I want a FormatDetector that routes file paths to the correct DocumentParser implementation based on file extension, so that the system can automatically select the right parser.

#### Acceptance Criteria

1. WHEN a file with extension .md, .markdown, or .mkd is provided, THE FormatDetector SHALL return MarkdownParser
2. WHEN a file with extension .txt, .text, .log, .csv, or .tsv is provided, THE FormatDetector SHALL return PlainTextParser
3. WHEN a file with an unsupported extension is provided, THE FormatDetector SHALL return Optional.empty()
4. THE FormatDetector SHALL provide supportedExtensions() returning the union of all registered parsers' extensions

### Requirement 8: 固定大小分块策略

**User Story:** As a developer, I want a FixedSizeChunker that splits text into fixed-size chunks with overlap and sentence boundary alignment, so that documents can be chunked for retrieval.

#### Acceptance Criteria

1. THE ChunkingStrategy sealed interface SHALL declare methods: chunk(text, metadata) returning List of DocumentChunk, estimateChunkCount(textLength) returning int, strategyName() returning String
2. THE ChunkingConfig record SHALL contain fields: maxChunkSize, minChunkSize, overlapSize, maxChunkTokens, respectSentences, respectParagraphs, enableContextPrefix, with a compact constructor that validates maxChunkSize > 0, 0 <= minChunkSize < maxChunkSize, 0 <= overlapSize < maxChunkSize
3. THE ChunkingConfig record SHALL provide static constants: DEFAULT (1024/100/128/512/true/true/true), SMALL (512/50/64/256), LARGE (2048/200/256/1024)
4. WHEN text is provided, THE FixedSizeChunker SHALL split it into chunks where each chunk content length does not exceed maxChunkSize
5. WHEN respectSentences is true, THE FixedSizeChunker SHALL attempt to split at the nearest sentence boundary (Chinese: 。！？；, English: .!?) within the chunk range
6. WHEN overlap is configured, THE FixedSizeChunker SHALL start each subsequent chunk at (previous end position - overlapSize)
7. WHEN the last chunk is smaller than minChunkSize, THE FixedSizeChunker SHALL merge it with the previous chunk
8. THE FixedSizeChunker SHALL generate a UUID for each chunk id, compute SHA-256 contentHash, and estimate tokenCount
9. WHEN null or blank text is provided, THE FixedSizeChunker SHALL return an empty list
10. FOR ALL non-empty text inputs, THE FixedSizeChunker SHALL produce at least one chunk
11. FOR ALL chunk lists produced by FixedSizeChunker, each chunk content SHALL be a substring of the original text (no content fabrication)
12. THE FixedSizeChunker SHALL set strategyName() to "fixed-size"

### Requirement 9: KnowledgeBaseManager 基础 CRUD

**User Story:** As a developer, I want a KnowledgeBaseManager service that provides basic CRUD operations for knowledge bases and documents, so that the knowledge base lifecycle can be managed programmatically.

#### Acceptance Criteria

1. WHEN createKnowledgeBase(name, description, embeddingModel) is called, THE KnowledgeBaseManager SHALL create a new KnowledgeBase record with generated UUID and persist it via KnowledgeBaseRepository
2. WHEN getKnowledgeBase(id) is called, THE KnowledgeBaseManager SHALL return Optional of KnowledgeBase from KnowledgeBaseRepository
3. WHEN listKnowledgeBases() is called, THE KnowledgeBaseManager SHALL return all knowledge bases ordered by created_at DESC
4. WHEN updateKnowledgeBase(id, name, description) is called with a valid id, THE KnowledgeBaseManager SHALL update the specified fields (null means no change) and set updated_at to current time
5. IF updateKnowledgeBase is called with a non-existent id, THEN THE KnowledgeBaseManager SHALL throw KnowledgeBaseNotFoundException
6. WHEN deleteKnowledgeBase(id) is called, THE KnowledgeBaseManager SHALL delete the knowledge base and all associated documents and chunks (cascade)
7. WHEN listDocuments(knowledgeBaseId) is called, THE KnowledgeBaseManager SHALL return all documents for the specified knowledge base
8. WHEN removeDocument(documentId) is called with a valid id, THE KnowledgeBaseManager SHALL delete the document record and associated chunks
9. IF removeDocument is called with a non-existent documentId, THEN THE KnowledgeBaseManager SHALL throw DocumentNotFoundException
10. THE KnowledgeBaseManager SHALL annotate write operations with @Transactional to ensure atomicity

### Requirement 10: 配置属性和自动配置

**User Story:** As a developer, I want Spring Boot auto-configuration for the knowledge base module with externalized configuration properties, so that the module integrates seamlessly into the LifePilot application context.

#### Acceptance Criteria

1. THE KnowledgeBaseProperties record SHALL map configuration keys under the prefix lifepilot.knowledge, including: dataDir, maxFileSize, and nested chunking properties (defaultStrategy, fixedSize.chunkSize, fixedSize.minChunkSize, fixedSize.overlapSize, fixedSize.maxChunkTokens, fixedSize.respectSentences)
2. THE KnowledgeBaseProperties record SHALL provide sensible defaults: dataDir="${user.home}/.lifepilot/data", maxFileSize=104857600 (100MB), chunking.defaultStrategy="smart", fixedSize.chunkSize=1024, fixedSize.minChunkSize=100, fixedSize.overlapSize=128
3. THE KnowledgeAutoConfiguration SHALL register beans: FormatDetector, MarkdownParser, PlainTextParser, FixedSizeChunker, KnowledgeBaseRepository, DocumentRepository, DocumentChunkRepository, KnowledgeBaseManager
4. THE KnowledgeAutoConfiguration SHALL be annotated with @Configuration and @EnableConfigurationProperties(KnowledgeBaseProperties.class)
5. THE KnowledgeAutoConfiguration SHALL use @ConditionalOnProperty(prefix = "lifepilot.knowledge", name = "enabled", havingValue = "true", matchIfMissing = true) to allow disabling the module
6. WHEN the application context loads, THE KnowledgeAutoConfiguration SHALL create a FixedSizeChunker bean configured from KnowledgeBaseProperties.chunking.fixedSize values

### Requirement 11: 异常类型

**User Story:** As a developer, I want domain-specific exception classes for the knowledge base module, so that error handling is precise and informative.

#### Acceptance Criteria

1. THE KnowledgeBaseNotFoundException SHALL extend RuntimeException and accept a message parameter with Chinese error description
2. THE DocumentNotFoundException SHALL extend RuntimeException and accept a message parameter with Chinese error description
3. THE DocumentParseException SHALL extend RuntimeException, contain a Phase enum (FILE_READ, FORMAT_DECODE, TEXT_EXTRACTION, METADATA_EXTRACTION), and provide getPhase() and getFilePath() accessors
4. IF a DocumentParseException is constructed with a cause, THEN THE DocumentParseException SHALL chain the original exception via super(message, cause)

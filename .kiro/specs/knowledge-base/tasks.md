# Implementation Plan: 知识库管理基础层

## Overview

按依赖顺序实现知识库管理基础层：数据模型 → 数据库迁移 → Repository → 解析器框架 → 解析器实现 → 分块策略 → 管理服务 → 自动配置 → 集成测试。所有代码遵循 LifePilot 编码规范（中文注释/Javadoc/日志/测试方法名，英文类名/方法名/变量名，@author zsg，@since yyyy-MM-dd，@Bean 注册）。

## Tasks

- [x] 1. 实现数据模型和异常类型
  - [x] 1.1 创建 DocumentStatus 枚举
    - 创建 `com.lifepilot.knowledge.model.DocumentStatus` 枚举
    - 定义 9 个状态值（UPLOADING ~ ERROR），每个带中文 displayName
    - 实现 `isTerminal()`（READY/ERROR 返回 true）和 `isRetryable()`（ERROR 返回 true）
    - _Requirements: 1.4, 1.5_

  - [x] 1.2 创建 KnowledgeBase record
    - 创建 `com.lifepilot.knowledge.model.KnowledgeBase` record
    - 包含 id, name, description, embeddingModel, rerankerModel(Optional), chunkingStrategy, chunkingConfig(Map), documentCount, totalChunks, createdAt, updatedAt
    - 实现 `create(name, description, embeddingModel)` 静态工厂方法，生成 UUID，默认 chunkingStrategy="smart", documentCount=0, totalChunks=0
    - _Requirements: 1.1, 1.6_

  - [x] 1.3 创建 Document record
    - 创建 `com.lifepilot.knowledge.model.Document` record
    - 包含 id, knowledgeBaseId, fileName, filePath, fileSize, mimeType, contentHash, status(DocumentStatus), chunkCount, entityCount, errorMessage(Optional), lastProcessedStage(Optional), metadata(Map), createdAt, updatedAt
    - _Requirements: 1.2_

  - [x] 1.4 创建异常类型
    - 创建 `com.lifepilot.knowledge.exception.KnowledgeBaseNotFoundException` 继承 RuntimeException
    - 创建 `com.lifepilot.knowledge.exception.DocumentNotFoundException` 继承 RuntimeException
    - 创建 `com.lifepilot.knowledge.parser.DocumentParseException` 继承 RuntimeException，包含 Phase 枚举（FILE_READ, FORMAT_DECODE, TEXT_EXTRACTION, METADATA_EXTRACTION）、phase 字段、filePath 字段，支持 cause 链
    - _Requirements: 11.1, 11.2, 11.3, 11.4_

  - [x] 1.5 编写数据模型和异常类型的单元测试
    - 创建 `KnowledgeBaseTest`：测试 create() 工厂方法默认值
    - 创建 `DocumentStatusTest`：测试 isTerminal()、isRetryable()、displayName()
    - 创建 `DocumentParseExceptionTest`：测试构造器、Phase 枚举、cause 链
    - _Requirements: 1.4, 1.5, 1.6, 11.3, 11.4_

  - [ ]* 1.6 编写数据模型属性测试（jqwik）
    - **Property 1: KnowledgeBase.create() 工厂方法默认值**
    - **Property 3: DocumentStatus 终态和可重试判定**
    - **Validates: Requirements 1.5, 1.6**

- [x] 2. 实现解析器框架和文档分块数据模型
  - [x] 2.1 创建 DocumentElement sealed interface
    - 创建 `com.lifepilot.knowledge.parser.DocumentElement` sealed interface
    - 定义 permits: Heading, Paragraph, Table, CodeBlock, ListBlock, Image（均为内部 record）
    - 每个 record 包含 startOffset 和 endOffset 字段
    - _Requirements: 4.9_

  - [x] 2.2 创建 DocumentMetadata record
    - 创建 `com.lifepilot.knowledge.parser.DocumentMetadata` record
    - 包含 title(Optional), author(Optional), createdAt(Optional<Instant>), modifiedAt(Optional<Instant>), pageCount, wordCount, language(Optional), extraProperties(Map)
    - 实现 `empty()` 和 `fromFile(Path, wordCount)` 静态工厂方法
    - _Requirements: 4.7, 4.8_

  - [x] 2.3 创建 ParseResult record
    - 创建 `com.lifepilot.knowledge.parser.ParseResult` record
    - 包含 text, elements(List<DocumentElement>), metadata(DocumentMetadata), warnings(List<String>)
    - 实现 `isEmpty()` 和 `estimateTokenCount()` 方法
    - _Requirements: 4.4, 4.5, 4.6_

  - [x] 2.4 创建 DocumentParser sealed interface
    - 创建 `com.lifepilot.knowledge.parser.DocumentParser` sealed interface
    - 声明 `supportedExtensions()`, `parse(Path)`, `extractMetadata(Path)` 方法
    - 实现 default `canParse(Path)` 方法（扩展名匹配）
    - permits MarkdownParser, PlainTextParser
    - _Requirements: 4.1, 4.2, 4.3_

  - [x] 2.5 创建 DocumentChunk record
    - 创建 `com.lifepilot.knowledge.chunking.DocumentChunk` record
    - 包含 id, documentId, knowledgeBaseId, content, contextPrefix(Optional), chunkIndex, startOffset, endOffset, tokenCount, contentHash, headingHierarchy(List), pageNumber, metadata(Map)
    - 实现 `embeddingText()`, `breadcrumb()`, `contentLength()` 方法
    - _Requirements: 1.3, 1.7, 1.8, 1.9_

  - [x] 2.6 创建 ChunkingConfig record
    - 创建 `com.lifepilot.knowledge.chunking.ChunkingConfig` record
    - 包含 maxChunkSize, minChunkSize, overlapSize, maxChunkTokens, respectSentences, respectParagraphs, enableContextPrefix
    - compact constructor 验证参数合法性
    - 定义 DEFAULT, SMALL, LARGE 静态常量
    - _Requirements: 8.2, 8.3_

  - [x] 2.7 创建 ChunkingStrategy sealed interface
    - 创建 `com.lifepilot.knowledge.chunking.ChunkingStrategy` sealed interface
    - 声明 `chunk(text, metadata)`, `estimateChunkCount(textLength)`, `strategyName()` 方法
    - permits FixedSizeChunker
    - _Requirements: 8.1_

  - [x] 2.8 编写解析器框架和分块数据模型的单元测试
    - 创建 `ParseResultTest`：测试 isEmpty()、estimateTokenCount()
    - 创建 `DocumentChunkTest`：测试 embeddingText()、breadcrumb()、contentLength()
    - 创建 `ChunkingConfigTest`：测试参数验证、DEFAULT/SMALL/LARGE 常量
    - _Requirements: 1.7, 1.8, 1.9, 4.5, 4.6, 8.2, 8.3_

  - [ ]* 2.9 编写解析器框架属性测试（jqwik）
    - **Property 2: DocumentChunk 访问器方法正确性**
    - **Property 10: ParseResult.isEmpty 判定**
    - **Property 18: ChunkingConfig 验证拒绝非法参数**
    - **Validates: Requirements 1.7, 1.8, 1.9, 4.5, 8.2**

- [x] 3. Checkpoint — 编译检查和单元测试
  - 确保所有代码编译通过，运行已有单元测试确认全部通过，ask the user if questions arise.

- [x] 4. 实现 Flyway 数据库迁移脚本
  - [x] 4.1 创建 V8__create_knowledge_base_tables.sql
    - 创建 `src/main/resources/db/migration/V8__create_knowledge_base_tables.sql`
    - 创建 knowledge_bases 表（id TEXT PK, name, description, embedding_model, reranker_model, chunking_strategy, chunking_config_json, document_count, total_chunks, created_at, updated_at）
    - 创建 documents 表（id TEXT PK, knowledge_base_id FK → knowledge_bases ON DELETE CASCADE, file_name, file_path, file_size, mime_type, content_hash, status, chunk_count, entity_count, error_message, last_processed_stage, metadata_json, created_at, updated_at）
    - 创建 document_chunks 表（id TEXT PK, document_id FK → documents ON DELETE CASCADE, knowledge_base_id FK → knowledge_bases ON DELETE CASCADE, content, context_prefix, chunk_index, start_offset, end_offset, token_count, content_hash, heading_hierarchy_json, page_number, metadata_json, created_at）
    - 创建索引：idx_knowledge_bases_name, idx_documents_kb_id, idx_documents_status, idx_documents_content_hash, idx_document_chunks_doc_id, idx_document_chunks_kb_id, idx_document_chunks_hash
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

- [x] 5. 实现 Repository 层
  - [x] 5.1 实现 KnowledgeBaseRepository
    - 创建 `com.lifepilot.knowledge.repository.KnowledgeBaseRepository`
    - 构造器注入 JdbcTemplate 和 ObjectMapper
    - 实现 save()（INSERT ... ON CONFLICT DO UPDATE）、findById()、findAll()（ORDER BY created_at DESC）、deleteById()、updateDocumentCount()
    - 实现 RowMapper：TEXT → Instant.parse()，JSON TEXT → Map（Jackson ObjectMapper）
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [x] 5.2 实现 DocumentRepository
    - 创建 `com.lifepilot.knowledge.repository.DocumentRepository`
    - 构造器注入 JdbcTemplate 和 ObjectMapper
    - 实现 save()、findById()、findByKnowledgeBaseId()、deleteById()、updateStatus()、updateContentHash()、updateChunkCount()、updateLastProcessedStage()
    - RowMapper 将 status TEXT 通过 DocumentStatus.valueOf() 转换
    - _Requirements: 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13_

  - [x] 5.3 实现 DocumentChunkRepository
    - 创建 `com.lifepilot.knowledge.repository.DocumentChunkRepository`
    - 构造器注入 JdbcTemplate 和 ObjectMapper
    - 实现 saveAll()（JdbcTemplate.batchUpdate 批量插入）、findByDocumentId()（ORDER BY chunk_index）、deleteByDocumentId()、countByDocumentId()
    - RowMapper 将 heading_hierarchy_json 通过 Jackson 反序列化为 List<String>
    - _Requirements: 3.14, 3.15, 3.16, 3.17_

  - [x] 5.4 编写 Repository 层集成测试
    - 创建 `KnowledgeBaseRepositoryTest`（@SpringBootTest + 内存 SQLite）：测试 save/findById/findAll/deleteById round-trip、findAll 按 created_at 降序
    - 创建 `DocumentRepositoryTest`：测试 save/findById/findByKnowledgeBaseId/deleteById/updateStatus/updateContentHash/updateChunkCount/updateLastProcessedStage round-trip
    - 创建 `DocumentChunkRepositoryTest`：测试 saveAll/findByDocumentId/countByDocumentId/deleteByDocumentId round-trip 及排序
    - _Requirements: 3.1 ~ 3.17_

  - [ ]* 5.5 编写 Repository 层属性测试（jqwik）
    - **Property 4: KnowledgeBase Repository 保存/查找 round-trip**
    - **Property 5: KnowledgeBase findAll 按 created_at 降序排列**
    - **Property 6: Document Repository 保存/查找 round-trip**
    - **Property 7: Document 字段更新 round-trip**
    - **Property 8: DocumentChunk 批量保存/查找 round-trip 及排序**
    - **Validates: Requirements 3.1 ~ 3.17**

- [x] 6. Checkpoint — Repository 层集成测试
  - 确保 Flyway 迁移成功执行，Repository 层集成测试全部通过，ask the user if questions arise.

- [x] 7. 实现解析器
  - [x] 7.1 实现 MarkdownParser
    - 创建 `com.lifepilot.knowledge.parser.MarkdownParser` implements DocumentParser
    - 支持扩展名：md, markdown, mkd
    - 使用 Files.readString(filePath, UTF_8) 读取文件
    - 正则解析：ATX 标题（# ~ ######）、围栏代码块（```language ... ```）、GFM 表格、YAML Front Matter
    - YAML Front Matter 简单 key: value 逐行解析，提取 title/author/date/lang 到 DocumentMetadata
    - 元数据优先级：Front Matter title > 第一个标题
    - 所有 DocumentElement 按 startOffset 升序排列
    - 文件读取失败抛出 DocumentParseException(Phase.FILE_READ)
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10_

  - [x] 7.2 实现 PlainTextParser
    - 创建 `com.lifepilot.knowledge.parser.PlainTextParser` implements DocumentParser
    - 支持扩展名：txt, text, log, csv, tsv
    - 编码检测顺序：UTF-8 BOM → UTF-8（无替换字符 \uFFFD）→ GBK → ISO-8859-1 兜底
    - 行尾规范化：\r\n 和 \r 统一为 \n
    - 段落识别：按空行分隔，每个段落生成 DocumentElement.Paragraph
    - 元数据：文件名作标题，估算字数
    - 文件读取失败抛出 DocumentParseException(Phase.FILE_READ)
    - 返回空 warnings 列表
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_

  - [x] 7.3 实现 FormatDetector
    - 创建 `com.lifepilot.knowledge.parser.FormatDetector`
    - 构造器注入 List<DocumentParser>，使用 List.copyOf() 保存
    - 实现 detect(Path) 根据扩展名路由到对应解析器
    - 实现 supportedExtensions() 返回所有解析器扩展名的并集
    - 不支持的扩展名返回 Optional.empty()
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [x] 7.4 编写解析器单元测试
    - 创建测试资源文件 `src/test/resources/knowledge/` 目录，放置测试用 Markdown 和 TXT 文件
    - 创建 `MarkdownParserTest`：测试标题/代码块/表格/Front Matter 解析、元素排序、元数据提取、文件不存在异常
    - 创建 `PlainTextParserTest`：测试编码检测、行尾规范化、段落识别、元数据、文件不存在异常
    - 创建 `FormatDetectorTest`：测试扩展名路由、不支持的扩展名返回 empty
    - _Requirements: 5.1 ~ 5.10, 6.1 ~ 6.7, 7.1 ~ 7.4_

  - [ ]* 7.5 编写解析器属性测试（jqwik）
    - **Property 9: DocumentParser.canParse 扩展名匹配**
    - **Property 11: MarkdownParser 文本 round-trip**
    - **Property 13: MarkdownParser 元素按偏移量排序**
    - **Property 15: PlainTextParser 行尾规范化**
    - **Property 16: PlainTextParser 段落识别和元数据**
    - **Property 17: FormatDetector 扩展名路由**
    - **Validates: Requirements 4.2, 5.2, 5.7, 5.10, 6.3, 6.4, 6.5, 6.7, 7.1, 7.2, 7.3**

- [x] 8. 实现固定大小分块策略
  - [x] 8.1 实现 FixedSizeChunker
    - 创建 `com.lifepilot.knowledge.chunking.FixedSizeChunker` implements ChunkingStrategy
    - 构造器注入 ChunkingConfig
    - 实现 chunk(text, metadata)：按 maxChunkSize 切分，respectSentences 时在句子边界对齐（中文：。！？；，英文：.!?），overlapSize 重叠，最后一个分块小于 minChunkSize 时与前一个合并
    - 每个分块生成 UUID id、SHA-256 contentHash、估算 tokenCount
    - null/blank 输入返回空列表
    - strategyName() 返回 "fixed-size"
    - 实现 estimateChunkCount(textLength)
    - _Requirements: 8.1, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9, 8.10, 8.11, 8.12_

  - [x] 8.2 编写 FixedSizeChunker 单元测试
    - 创建 `FixedSizeChunkerTest`：测试分块大小边界、重叠、句子边界对齐、空输入、最小分块合并、分块元数据完整性
    - _Requirements: 8.4 ~ 8.12_

  - [ ]* 8.3 编写 FixedSizeChunker 属性测试（jqwik）
    - **Property 19: FixedSizeChunker 分块大小边界**
    - **Property 20: FixedSizeChunker 分块内容是原文子串**
    - **Property 21: FixedSizeChunker 非空文本至少产生一个分块**
    - **Property 22: FixedSizeChunker 重叠**
    - **Property 23: FixedSizeChunker 分块元数据完整性**
    - **Validates: Requirements 8.4, 8.6, 8.7, 8.8, 8.10, 8.11**

- [x] 9. Checkpoint — 解析器和分块策略测试
  - 确保所有解析器和分块策略的单元测试通过，ask the user if questions arise.

- [x] 10. 实现 KnowledgeBaseManager 管理服务
  - [x] 10.1 实现 KnowledgeBaseManager
    - 创建 `com.lifepilot.knowledge.KnowledgeBaseManager`
    - 构造器注入 KnowledgeBaseRepository, DocumentRepository, DocumentChunkRepository
    - 实现 createKnowledgeBase(name, description, embeddingModel)：调用 KnowledgeBase.create() + save()
    - 实现 getKnowledgeBase(id)：返回 Optional<KnowledgeBase>
    - 实现 listKnowledgeBases()：返回 List<KnowledgeBase>（按 created_at DESC）
    - 实现 updateKnowledgeBase(id, name, description)：null 参数不更新，不存在抛 KnowledgeBaseNotFoundException
    - 实现 deleteKnowledgeBase(id)：级联删除（依赖数据库 ON DELETE CASCADE）
    - 实现 listDocuments(knowledgeBaseId)：返回 List<Document>
    - 实现 removeDocument(documentId)：删除文档 + 关联分块，不存在抛 DocumentNotFoundException
    - 写操作标注 @Transactional
    - _Requirements: 9.1 ~ 9.10_

  - [x] 10.2 编写 KnowledgeBaseManager 集成测试
    - 创建 `KnowledgeBaseManagerTest`（@SpringBootTest + 内存 SQLite）
    - 测试 createKnowledgeBase/getKnowledgeBase/listKnowledgeBases round-trip
    - 测试 updateKnowledgeBase 正常更新和不存在 id 抛异常
    - 测试 deleteKnowledgeBase 级联删除
    - 测试 removeDocument 删除文档和分块、不存在 id 抛异常
    - _Requirements: 9.1 ~ 9.10_

  - [ ]* 10.3 编写 KnowledgeBaseManager 属性测试（jqwik）
    - **Property 24: KnowledgeBaseManager 创建/获取 round-trip**
    - **Property 25: KnowledgeBaseManager 级联删除**
    - **Property 26: KnowledgeBaseManager removeDocument 删除文档和分块**
    - **Validates: Requirements 9.1, 9.2, 9.3, 9.6, 9.8**

- [x] 11. 实现配置属性和自动配置
  - [x] 11.1 创建 KnowledgeBaseProperties
    - 创建 `com.lifepilot.knowledge.config.KnowledgeBaseProperties` record
    - 映射 lifepilot.knowledge 前缀：dataDir, maxFileSize, enabled, chunking（嵌套 Chunking record 含 defaultStrategy 和 FixedSize record）
    - 提供合理默认值：dataDir="${user.home}/.lifepilot/data", maxFileSize=104857600, chunking.defaultStrategy="smart", fixedSize.chunkSize=1024 等
    - _Requirements: 10.1, 10.2_

  - [x] 11.2 创建 KnowledgeAutoConfiguration
    - 创建 `com.lifepilot.knowledge.config.KnowledgeAutoConfiguration`
    - 标注 @Configuration + @EnableConfigurationProperties(KnowledgeBaseProperties.class)
    - 标注 @ConditionalOnProperty(prefix = "lifepilot.knowledge", name = "enabled", havingValue = "true", matchIfMissing = true)
    - 注册 @Bean：MarkdownParser, PlainTextParser, FormatDetector, ChunkingConfig（从配置构建）, FixedSizeChunker, KnowledgeBaseRepository, DocumentRepository, DocumentChunkRepository, KnowledgeBaseManager
    - _Requirements: 10.3, 10.4, 10.5, 10.6_

  - [x] 11.3 编写自动配置集成测试
    - 创建 `KnowledgeAutoConfigurationTest`（@SpringBootTest）
    - 验证所有 Bean 正确注册
    - 验证 @ConditionalOnProperty 禁用时不注册 Bean
    - 验证 ChunkingConfig 从配置属性正确构建
    - _Requirements: 10.3, 10.4, 10.5, 10.6_

- [x] 12. Flyway 迁移集成测试
  - [x] 12.1 编写 Flyway 迁移集成测试
    - 创建 `FlywayMigrationTest`（@SpringBootTest + 内存 SQLite）
    - 验证 V8 迁移脚本执行成功
    - 验证 knowledge_bases、documents、document_chunks 表存在
    - 验证所有索引存在
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 13. Final checkpoint — 全量测试
  - 确保所有单元测试、集成测试通过，getDiagnostics 无编译错误，ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests (jqwik) validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- 集成测试使用 @SpringBootTest + 内存 SQLite，不依赖外部服务
- 所有服务通过 @Bean 注册，不使用 @Service/@Component

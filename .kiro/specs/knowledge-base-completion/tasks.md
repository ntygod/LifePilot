# Implementation Plan: knowledge-base-completion

## Overview

补全知识库管理模块（模块 8）的缺失组件。按依赖顺序实现：基础设施（Maven 依赖 + sealed interface 扩展）→ 解析器 → 分块策略 → 上下文增强 → 数据库迁移 → 索引服务 → 重复检测 → 导入管线 → 检索服务 → 知识提取 → 配置外部化 → 集成测试。

## Tasks

- [ ] 1. 基础设施准备：Maven 依赖 + sealed interface 扩展
  - [ ] 1.1 添加 Maven 依赖（Apache PDFBox 3.0.4、Apache POI 5.3.0、jqwik 1.9.2 test scope）到 pom.xml
    - _Requirements: 1.1, 2.1_
  - [ ] 1.2 扩展 DocumentParser sealed interface 的 permits 列表，添加 PdfParser、WordParser
    - 修改 `com.lifepilot.knowledge.parser.DocumentParser`
    - _Requirements: 1.6, 2.6_
  - [ ] 1.3 扩展 ChunkingStrategy sealed interface 的 permits 列表，添加 RecursiveChunker、HeadingChunker、SmartChunker
    - 修改 `com.lifepilot.knowledge.chunking.ChunkingStrategy`
    - _Requirements: 3.5, 4.5, 5.6_

- [ ] 2. 实现 PDF 和 Word 文档解析器
  - [ ] 2.1 实现 PdfParser
    - 基于 Apache PDFBox 3.x（`Loader.loadPDF()` API）逐页提取文本
    - 提取元数据（title, author, createdAt, pageCount）
    - 加密 PDF 抛出 `DocumentParseException(Phase.FORMAT_DECODE)`
    - 单页失败记录 warning 继续处理
    - 启发式标题检测（编号模式 + 短行全大写）
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_
  - [ ]* 2.2 编写 PdfParser 单元测试
    - 测试正常 PDF 解析、加密 PDF 异常、损坏 PDF 异常、空白 PDF 警告、元数据提取、supportedExtensions
    - 测试 PDF 文件放在 `src/test/resources/knowledge/`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_
  - [ ] 2.3 实现 WordParser
    - 基于 Apache POI，仅支持 DOCX（Office 2007+）
    - 通过 `XWPFParagraph.getStyle()` 识别 Heading1-6
    - 表格提取为 `DocumentElement.Table`（第一行为表头）
    - 旧版 .doc 抛出 `DocumentParseException(Phase.FORMAT_DECODE)`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
  - [ ]* 2.4 编写 WordParser 单元测试
    - 测试正常 DOCX 解析、表格提取、标题识别、旧版 .doc 异常、元数据提取、supportedExtensions
    - 测试 DOCX 文件放在 `src/test/resources/knowledge/`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [ ] 3. Checkpoint - 确认解析器实现
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. 实现高级分块策略
  - [ ] 4.1 实现 RecursiveChunker
    - 分隔符优先级：段落（\n\n）→ 句子（。！？.!?）→ 词（空格/中文字符边界）
    - 分隔符列表从 `KnowledgeBaseProperties.Recursive` 读取
    - 递归逻辑：尝试当前分隔符切分 → 子块超过 maxChunkSize 则用下一级分隔符继续切分
    - 重叠通过回溯前一个分块的尾部 overlapSize 字符实现
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.6_

  - [ ]* 4.2 编写 RecursiveChunker 属性测试
    - **Property 1: 分块大小不变量** — 每个分块 content 长度在 minChunkSize 和 maxChunkSize 之间（最后一个分块例外）
    - **Validates: Requirements 3.2**
  - [ ]* 4.3 编写 RecursiveChunker 属性测试 — 覆盖性
    - **Property 2: 分块覆盖性** — 分块拼接（扣除重叠）覆盖原始文本全部内容
    - **Validates: Requirements 3.6**
  - [ ]* 4.4 编写 RecursiveChunker 属性测试 — 重叠
    - **Property 3: 连续分块重叠** — 连续分块共享 overlapSize 字符
    - **Validates: Requirements 3.4**
  - [ ]* 4.5 编写 RecursiveChunker 单元测试
    - 测试空文本、短文本（< minChunkSize）、纯中文文本、纯英文文本、混合语言
    - _Requirements: 3.1, 3.2, 3.3_
  - [ ] 4.6 实现 HeadingChunker
    - 通过正则 `^#{1,N}\s+(.+)` 识别 ATX 标题（N = maxHeadingLevel，默认 3）
    - 每个分块的 headingHierarchy 填充祖先标题路径
    - 无标题文档整体委托 RecursiveChunker
    - 超长节（> maxChunkSize）委托 RecursiveChunker 二次切分
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
  - [ ]* 4.7 编写 HeadingChunker 属性测试
    - **Property 4: 标题边界分块** — 分块边界对齐标题位置
    - **Validates: Requirements 4.1**
  - [ ]* 4.8 编写 HeadingChunker 属性测试 — 面包屑
    - **Property 5: 标题层级面包屑** — 分块携带正确的祖先标题路径
    - **Validates: Requirements 4.2**
  - [ ]* 4.9 编写 HeadingChunker 单元测试
    - 测试无标题文本委托、超长节二次切分、嵌套标题层级
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
  - [ ] 4.10 实现 SmartChunker
    - 分析文档特征（标题密度、文本长度）自动选择策略
    - 标题密度 > headingDensityThreshold → HeadingChunker
    - 文本长度 > shortDocumentThreshold → RecursiveChunker
    - 否则 → FixedSizeChunker
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_
  - [ ]* 4.11 编写 SmartChunker 属性测试
    - **Property 6: 智能策略选择** — 策略选择匹配文档特征阈值
    - **Validates: Requirements 5.2, 5.3, 5.4**
  - [ ]* 4.12 编写 SmartChunker 单元测试
    - 测试各阈值边界值
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 5. Checkpoint - 确认分块策略实现
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 6. 实现分块上下文增强 + 新增 record 类型 + 异常类型
  - [ ] 6.1 创建新增 record 类型和异常类型
    - 创建 `IndexingResult` record（vectorCount, ftsCount, durationMs）
    - 创建 `DocumentSearchResult` record（chunkId, documentId, knowledgeBaseId, content, contextPrefix, headingHierarchy, score, sourcePath, metadata）
    - 创建 `IngestionProgress` record（documentId, knowledgeBaseId, stage, progressPercent, message）
    - 创建 `DuplicateCheckResult` record（isDuplicate, contentHash, existingDocumentId）
    - 创建 `ExtractionResult` record（entityCount, relationCount, warnings）
    - 创建 `DuplicateDocumentException`、`IndexingException`、`ExtractionException` 异常类
    - _Requirements: 9.10, 10.1, 13.1_
  - [ ] 6.2 实现 ChunkContextEnricher
    - 通过 `LlmRouter.call("knowledge_extraction", prompt, null)` 生成上下文前缀
    - 前缀长度限制在 maxPrefixTokens（默认 100）Token
    - LLM 不可用时跳过增强，返回原始分块（graceful degradation）
    - _Requirements: 6.1, 6.2, 6.3, 6.4_
  - [ ]* 6.3 编写 ChunkContextEnricher 属性测试
    - **Property 7: 上下文前缀设置** — 启用时所有分块获得非空 contextPrefix
    - **Validates: Requirements 6.1**
  - [ ]* 6.4 编写 ChunkContextEnricher 单元测试
    - 测试 LLM 不可用降级、正常增强
    - _Requirements: 6.1, 6.4_
  - [ ]* 6.5 编写 DocumentChunk embeddingText 属性测试
    - **Property 8: embeddingText 拼接** — contextPrefix 存在时拼接，不存在时仅 content
    - **Validates: Requirements 6.5**

- [ ] 7. Flyway V13 数据库迁移
  - [ ] 7.1 创建 Flyway V13 迁移脚本 `V13__create_knowledge_search_tables.sql`
    - 创建 `document_chunks_fts` FTS5 虚拟表（外部内容表模式，关联 document_chunks）
    - 创建 FTS5 同步触发器（INSERT / DELETE）
    - 创建 `chunk_embeddings` sqlite-vec 虚拟表（vec0，float[1536]）
    - _Requirements: 15.1, 15.2, 15.3_
  - [ ]* 7.2 编写 Flyway V13 集成测试
    - 验证迁移脚本执行、FTS5 表创建、sqlite-vec 表创建
    - _Requirements: 15.1, 15.2, 15.3_

- [ ] 8. 实现索引服务
  - [ ] 8.1 实现 VectorIndexer
    - 批量向量化：每批 batchSize（默认 32）个分块调用 `LlmRouter.embed()`
    - 使用 `DocumentChunk.embeddingText()` 作为 Embedding 输入
    - 失败重试：指数退避（500ms → 1s → 2s），最多 maxRetries（默认 2）次
    - 实现 indexChunks、removeChunkEmbeddings、removeByDocumentId、searchSimilar
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_
  - [ ]* 8.2 编写 VectorIndexer 属性测试
    - **Property 9: 向量索引完整性** — 索引后 chunk_embeddings 表条目数匹配分块数
    - **Validates: Requirements 7.1, 7.2**
  - [ ] 8.3 实现 FtsIndexer
    - 实现 indexChunks（插入 FTS5 虚拟表）、removeByDocumentId、search（BM25 排序）
    - _Requirements: 8.1, 8.2, 8.3_
  - [ ]* 8.4 编写 FtsIndexer 属性测试
    - **Property 11: FTS 索引往返** — 索引后可通过全文搜索找回
    - **Validates: Requirements 8.1**
  - [ ]* 8.5 编写索引删除属性测试
    - **Property 10: 索引删除完整性** — 删除后无残留索引条目
    - **Validates: Requirements 7.3, 8.2**
  - [ ]* 8.6 编写 VectorIndexer 和 FtsIndexer 单元测试
    - 测试批量索引、删除、重试逻辑、BM25 搜索
    - _Requirements: 7.1, 7.2, 7.3, 7.5, 8.1, 8.2, 8.3_

- [ ] 9. Checkpoint - 确认索引服务实现
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 10. 实现重复检测 + 文档导入管线
  - [ ] 10.1 实现 DuplicateDetector
    - 基于 SHA-256 内容哈希检测重复
    - 同 KB 内重复阻止导入，跨 KB 允许
    - _Requirements: 10.1, 10.2, 10.3_
  - [ ]* 10.2 编写 DuplicateDetector 属性测试
    - **Property 14: SHA-256 哈希确定性** — 相同内容产生相同哈希
    - **Validates: Requirements 10.1**
  - [ ]* 10.3 编写 DuplicateDetector 属性测试 — 作用域
    - **Property 15: 重复检测作用域** — 同 KB 重复，跨 KB 允许
    - **Validates: Requirements 10.2, 10.3**
  - [ ]* 10.4 编写 DuplicateDetector 单元测试
    - 测试同 KB 重复、跨 KB 允许、空文件
    - _Requirements: 10.1, 10.2, 10.3_
  - [ ] 10.5 实现 DocumentIngester
    - 编排管线：parse → duplicate check → chunk → context enrich（可选）→ index（vector ‖ FTS5 并行）→ extract（异步）
    - 使用 `CompletableFuture.supplyAsync(..., Executors.newVirtualThreadPerTaskExecutor())` 异步执行
    - 每个阶段更新 Document.status 和 lastProcessedStage
    - 失败时设置 ERROR 状态 + errorMessage
    - resume() 从 lastProcessedStage 恢复
    - 通过 ApplicationEventPublisher 发布 IngestionProgress 事件
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10_
  - [ ]* 10.6 编写 DocumentIngester 属性测试 — 状态生命周期
    - **Property 12: 文档状态生命周期** — 成功时 READY，失败时 ERROR + lastProcessedStage + errorMessage
    - **Validates: Requirements 9.3, 9.4**
  - [ ]* 10.7 编写 DocumentIngester 属性测试 — 进度事件
    - **Property 13: 导入进度事件** — 每个阶段发布进度事件，成功导入至少 5 个事件
    - **Validates: Requirements 9.10**
  - [ ]* 10.8 编写 DocumentIngester 单元测试
    - 测试完整管线成功、各阶段失败、resume 恢复
    - _Requirements: 9.1, 9.3, 9.4, 9.5_

- [ ] 11. Checkpoint - 确认导入管线实现
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 12. 实现文档检索 + Reranker + 知识提取
  - [ ] 12.1 实现 Reranker sealed interface + LlmReranker + ApiReranker
    - Reranker sealed interface：rerank(query, candidates, topK)
    - LlmReranker：通过 LlmRouter 评分 query-document 对
    - ApiReranker：调用外部 Reranker API（Jina, Cohere）
    - _Requirements: 12.1, 12.2, 12.3, 12.5_
  - [ ] 12.2 实现 DocumentRetriever
    - 并行执行 VectorIndexer.searchSimilar() 和 FtsIndexer.search()
    - RRF 融合：score(d) = Σ 1/(k + rank_i(d))，k = rrfK（默认 60）
    - 可选 Reranker 精排，不可用时 graceful degradation
    - 支持跨知识库搜索（多个 kbId）
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 12.4_
  - [ ]* 12.3 编写 DocumentRetriever 属性测试
    - **Property 16: RRF 融合正确性** — RRF 分数遵循公式，结果按降序排列
    - **Validates: Requirements 11.2**
  - [ ]* 12.4 编写 DocumentRetriever 单元测试
    - 测试 RRF 融合计算、Reranker 降级、跨 KB 搜索
    - _Requirements: 11.1, 11.2, 11.4, 12.4_
  - [ ] 12.5 实现 KnowledgeExtractionPipeline
    - 使用 `LlmRouter.callEntity("knowledge_extraction", prompt, ExtractionResponse.class)` 结构化输出
    - 提取实体通过 `SemanticMemory.upsertWithConflictDetection()` 写入
    - 提取关系通过 `SemanticMemory.addRelation()` 写入
    - LLM 不可用时返回 ExtractionResult(0, 0, ["LLM 不可用，跳过知识提取"])
    - 使用 DataRedactor 脱敏后再发送到 LLM
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6_
  - [ ]* 12.6 编写 KnowledgeExtractionPipeline 单元测试
    - 测试实体提取、关系提取、LLM 不可用降级
    - _Requirements: 13.1, 13.2, 13.3, 13.4_

- [ ] 13. 配置外部化 + AutoConfiguration 更新
  - [ ] 13.1 扩展 KnowledgeBaseProperties
    - 新增 recursive、heading、smartChunker、vectorIndexer、retrieval、contextEnricher、extraction、reranker 配置段
    - 所有新增字段设置合理默认值
    - _Requirements: 14.1_
  - [ ] 13.2 更新 application.yml
    - 在 `lifepilot.knowledge` 前缀下声明所有新配置键及默认值
    - _Requirements: 14.2_
  - [ ] 13.3 更新 KnowledgeAutoConfiguration
    - 注册所有新 Bean：PdfParser, WordParser, RecursiveChunker, HeadingChunker, SmartChunker, VectorIndexer, FtsIndexer, DocumentIngester, DocumentRetriever, ChunkContextEnricher, DuplicateDetector, Reranker（条件注册）, KnowledgeExtractionPipeline
    - 更新 FormatDetector Bean 注册传入新解析器
    - 使用 `@ConditionalOnProperty` 控制可选组件（Reranker、ContextEnricher、ExtractionPipeline）
    - _Requirements: 14.3_

- [ ] 14. 集成测试 + 最终验证
  - [ ]* 14.1 编写 Knowledge_AutoConfiguration 集成测试
    - 验证 Spring Context 加载、所有 Bean 注入成功
    - _Requirements: 14.3_
  - [ ]* 14.2 编写 DocumentIngester_Pipeline 集成测试
    - 端到端测试：Markdown 文件导入（解析→分块→FTS 索引→检索）
    - _Requirements: 9.1, 8.1, 11.1_

- [ ] 15. Final checkpoint - 确认所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties (jqwik, tries=100)
- Unit tests validate specific examples and edge cases
- 所有代码遵循编码规范：中文注释/Javadoc/日志/异常消息/测试方法名，英文类名/方法名/变量名
- @author zsg, @since 填写文件创建日期
- Bean 注册在 KnowledgeAutoConfiguration 中，不使用 @Service/@Component
- 业务可调参数外部化到 KnowledgeBaseProperties + application.yml

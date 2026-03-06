package com.lifepilot.knowledge.ingest;

import com.lifepilot.knowledge.chunking.ChunkingStrategy;
import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.detect.DuplicateDetector;
import com.lifepilot.knowledge.enricher.ChunkContextEnricher;
import com.lifepilot.knowledge.exception.DuplicateDocumentException;
import com.lifepilot.knowledge.extract.KnowledgeExtractionPipeline;
import com.lifepilot.knowledge.index.FtsIndexer;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.model.IngestionProgress;
import com.lifepilot.knowledge.parser.FormatDetector;
import com.lifepilot.knowledge.parser.ParseResult;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * 文档导入管线编排器 — 协调解析→分块→索引→提取的完整流程。
 *
 * <p>管线阶段：PARSING → 重复检测 → CHUNKING → INDEXING → EXTRACTING → READY。
 * 每个阶段更新文档状态，失败时设置 ERROR 状态并记录错误信息。
 * 支持从 lastProcessedStage 恢复执行。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DocumentIngester {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngester.class);

    private final FormatDetector formatDetector;
    private final ChunkingStrategy smartChunker;
    @Nullable
    private final ChunkContextEnricher contextEnricher;
    @Nullable
    private final VectorIndexer vectorIndexer;
    private final FtsIndexer ftsIndexer;
    private final DuplicateDetector duplicateDetector;
    @Nullable
    private final KnowledgeExtractionPipeline extractionPipeline;
    private final DocumentRepository docRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final KnowledgeBaseProperties props;

    /**
     * 构造文档导入管线。
     */
    public DocumentIngester(FormatDetector formatDetector,
                            ChunkingStrategy smartChunker,
                            @Nullable ChunkContextEnricher contextEnricher,
                            @Nullable VectorIndexer vectorIndexer,
                            FtsIndexer ftsIndexer,
                            DuplicateDetector duplicateDetector,
                            @Nullable KnowledgeExtractionPipeline extractionPipeline,
                            DocumentRepository docRepository,
                            DocumentChunkRepository chunkRepository,
                            ApplicationEventPublisher eventPublisher,
                            KnowledgeBaseProperties props) {
        this.formatDetector = formatDetector;
        this.smartChunker = smartChunker;
        this.contextEnricher = contextEnricher;
        this.vectorIndexer = vectorIndexer;
        this.ftsIndexer = ftsIndexer;
        this.duplicateDetector = duplicateDetector;
        this.extractionPipeline = extractionPipeline;
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
        this.eventPublisher = eventPublisher;
        this.props = props;
        log.info("DocumentIngester 初始化完成: vectorIndexer={}, contextEnricher={}, extractionPipeline={}",
                vectorIndexer != null ? "启用" : "未启用",
                contextEnricher != null ? "可用" : "不可用",
                extractionPipeline != null ? "可用" : "不可用");
    }

    /**
     * 异步导入文档到指定知识库。
     *
     * @param kbId     知识库 ID
     * @param filePath 文件路径
     * @return 异步文档结果
     */
    public CompletableFuture<Document> ingest(String kbId, Path filePath) {
        return CompletableFuture.supplyAsync(() -> {
            var docId = UUID.randomUUID().toString();
            var now = Instant.now();
            var doc = new Document(
                    docId, kbId, filePath.getFileName().toString(), filePath.toString(),
                    filePath.toFile().length(), "", "", DocumentStatus.UPLOADING,
                    0, 0, null, null, Map.of(), now, now);
            docRepository.save(doc);
            return executeFullPipeline(doc, filePath);
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 从上次失败的阶段恢复导入。
     *
     * @param documentId 文档 ID
     * @return 异步文档结果
     */
    public CompletableFuture<Document> resume(String documentId) {
        return CompletableFuture.supplyAsync(() -> {
            var doc = docRepository.findById(documentId)
                    .orElseThrow(() -> new RuntimeException("文档不存在: " + documentId));
            var filePath = Path.of(doc.filePath());
            var lastStage = doc.lastProcessedStage() != null ? doc.lastProcessedStage() : "";
            return resumeFromStage(doc, filePath, lastStage);
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    // ---- 管线执行 ----

    /**
     * 执行完整管线。
     */
    private Document executeFullPipeline(Document doc, Path filePath) {
        try {
            // 1. PARSING
            publishProgress(doc, DocumentStatus.PARSING, 10, "开始解析文档");
            updateStage(doc.id(), DocumentStatus.PARSING);
            var parseResult = doParse(filePath);

            // 2. 重复检测
            publishProgress(doc, DocumentStatus.PARSING, 20, "检测重复文档");
            var dupResult = duplicateDetector.check(doc.knowledgeBaseId(), filePath);
            if (dupResult.isDuplicate()) {
                throw new DuplicateDocumentException(
                        "重复文档: 已存在文档 " + dupResult.existingDocumentId().orElse("unknown"));
            }
            docRepository.updateContentHash(doc.id(), dupResult.contentHash());

            // 3. CHUNKING
            publishProgress(doc, DocumentStatus.CHUNKING, 40, "分块处理中");
            updateStage(doc.id(), DocumentStatus.CHUNKING);
            var chunks = doChunk(doc, parseResult);

            // 4. 上下文增强（可选）
            if (props.contextEnricher().enabled()) {
                publishProgress(doc, DocumentStatus.CHUNKING, 50, "上下文增强中");
                var summary = parseResult.text().substring(0, Math.min(500, parseResult.text().length()));
                if (contextEnricher != null) {
                    chunks = contextEnricher.enrich(chunks, summary);
                } else {
                    log.warn("上下文增强已启用但 ChunkContextEnricher 不可用，已降级跳过: docId={}", doc.id());
                }
            }

            // 保存分块
            chunkRepository.saveAll(chunks);
            docRepository.updateChunkCount(doc.id(), chunks.size());

            // 5. INDEXING（向量 + FTS5 并行）
            publishProgress(doc, DocumentStatus.INDEXING, 60, "构建索引中");
            updateStage(doc.id(), DocumentStatus.INDEXING);
            doIndex(chunks);

            // 6. EXTRACTING（异步，可降级）
            publishProgress(doc, DocumentStatus.EXTRACTING, 80, "知识提取中");
            updateStage(doc.id(), DocumentStatus.EXTRACTING);
            doExtract(doc.id(), chunks);

            // 7. READY
            publishProgress(doc, DocumentStatus.READY, 100, "导入完成");
            docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);
            log.info("文档导入成功: docId={}, chunks={}", doc.id(), chunks.size());

            return docRepository.findById(doc.id()).orElse(doc);

        } catch (DuplicateDocumentException e) {
            log.warn("文档导入失败（重复）: docId={}, error={}", doc.id(), e.getMessage());
            docRepository.updateStatus(doc.id(), DocumentStatus.ERROR, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("文档导入失败: docId={}, error={}", doc.id(), e.getMessage(), e);
            docRepository.updateStatus(doc.id(), DocumentStatus.ERROR, e.getMessage());
            throw new RuntimeException("文档导入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从指定阶段恢复执行。
     */
    private Document resumeFromStage(Document doc, Path filePath, String lastStage) {
        try {
            return switch (lastStage) {
                case "PARSING" -> {
                    // 从分块阶段重新开始
                    var parseResult = doParse(filePath);
                    var chunks = doChunkAndEnrich(doc, parseResult);
                    chunkRepository.saveAll(chunks);
                    docRepository.updateChunkCount(doc.id(), chunks.size());
                    doIndex(chunks);
                    doExtract(doc.id(), chunks);
                    docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);
                    yield docRepository.findById(doc.id()).orElse(doc);
                }
                case "CHUNKING" -> {
                    // 分块已保存，从索引开始
                    var chunks = chunkRepository.findByDocumentId(doc.id());
                    doIndex(chunks);
                    doExtract(doc.id(), chunks);
                    docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);
                    yield docRepository.findById(doc.id()).orElse(doc);
                }
                case "INDEXING" -> {
                    // 索引可能部分完成，从提取开始
                    var chunks = chunkRepository.findByDocumentId(doc.id());
                    doExtract(doc.id(), chunks);
                    docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);
                    yield docRepository.findById(doc.id()).orElse(doc);
                }
                case "EXTRACTING" -> {
                    // 提取失败，重试
                    var chunks = chunkRepository.findByDocumentId(doc.id());
                    doExtract(doc.id(), chunks);
                    docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);
                    yield docRepository.findById(doc.id()).orElse(doc);
                }
                default -> executeFullPipeline(doc, filePath);
            };
        } catch (Exception e) {
            log.error("文档恢复失败: docId={}, lastStage={}, error={}", doc.id(), lastStage, e.getMessage(), e);
            docRepository.updateStatus(doc.id(), DocumentStatus.ERROR, e.getMessage());
            throw new RuntimeException("文档恢复失败: " + e.getMessage(), e);
        }
    }

    // ---- 管线阶段 ----

    private ParseResult doParse(Path filePath) {
        var parser = formatDetector.detect(filePath)
                .orElseThrow(() -> new RuntimeException("不支持的文件格式: " + filePath));
        return parser.parse(filePath);
    }

    private List<DocumentChunk> doChunk(Document doc, ParseResult parseResult) {
        var rawChunks = smartChunker.chunk(parseResult.text(), Map.of());
        // 填充 documentId 和 knowledgeBaseId
        return rawChunks.stream()
                .map(c -> new DocumentChunk(
                        c.id(), doc.id(), doc.knowledgeBaseId(), c.content(),
                        c.contextPrefix(), c.chunkIndex(), c.startOffset(), c.endOffset(),
                        c.tokenCount(), c.contentHash(), c.headingHierarchy(),
                        c.pageNumber(), c.metadata()))
                .toList();
    }

    private List<DocumentChunk> doChunkAndEnrich(Document doc, ParseResult parseResult) {
        var chunks = doChunk(doc, parseResult);
        if (props.contextEnricher().enabled()) {
            var summary = parseResult.text().substring(0, Math.min(500, parseResult.text().length()));
            if (contextEnricher != null) {
                chunks = contextEnricher.enrich(chunks, summary);
            } else {
                log.warn("上下文增强已启用但 ChunkContextEnricher 不可用，已降级跳过: docId={}", doc.id());
            }
        }
        return chunks;
    }

    private void doIndex(List<DocumentChunk> chunks) {
        // 向量索引（可选）和 FTS5 索引并行执行
        var vectorFuture = CompletableFuture.runAsync(() -> {
                    if (vectorIndexer != null) {
                        vectorIndexer.indexChunks(chunks);
                    } else {
                        log.warn("VectorIndexer 不可用，已降级跳过向量索引（仅构建 FTS5）");
                    }
                },
                Executors.newVirtualThreadPerTaskExecutor());
        var ftsFuture = CompletableFuture.runAsync(
                () -> ftsIndexer.indexChunks(chunks),
                Executors.newVirtualThreadPerTaskExecutor());
        CompletableFuture.allOf(vectorFuture, ftsFuture).join();
    }

    private void doExtract(String docId, List<DocumentChunk> chunks) {
        if (!props.extraction().enabled()) {
            log.debug("知识提取已禁用，跳过");
            return;
        }
        if (extractionPipeline == null) {
            log.warn("知识提取已启用但 KnowledgeExtractionPipeline 不可用，已降级跳过: docId={}", docId);
            return;
        }
        try {
            extractionPipeline.extract(chunks, docId);
        } catch (Exception e) {
            // 提取失败不影响文档状态，降级跳过
            log.warn("知识提取失败，降级跳过: docId={}, error={}", docId, e.getMessage());
        }
    }

    // ---- 辅助方法 ----

    private void updateStage(String docId, DocumentStatus stage) {
        docRepository.updateStatus(docId, stage, null);
        docRepository.updateLastProcessedStage(docId, stage.name());
    }

    private void publishProgress(Document doc, DocumentStatus stage, int percent, String message) {
        var progress = new IngestionProgress(
                doc.id(), doc.knowledgeBaseId(), stage, percent, message);
        eventPublisher.publishEvent(progress);
    }
}

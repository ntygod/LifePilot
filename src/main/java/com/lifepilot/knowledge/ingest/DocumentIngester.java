package com.lifepilot.knowledge.ingest;

import com.lifepilot.knowledge.chunking.ChunkingConfig;
import com.lifepilot.knowledge.chunking.ChunkingStrategy;
import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.chunking.FixedSizeChunker;
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
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
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
    private final Map<String, ChunkingStrategy> chunkerRegistry;
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
    private final KnowledgeBaseRepository kbRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final KnowledgeBaseProperties props;
    private final ChunkingConfig defaultChunkingConfig;

    /**
     * 构造文档导入管线。
     */
    public DocumentIngester(FormatDetector formatDetector,
                            ChunkingStrategy smartChunker,
                            Map<String, ChunkingStrategy> chunkerRegistry,
                            @Nullable ChunkContextEnricher contextEnricher,
                            @Nullable VectorIndexer vectorIndexer,
                            FtsIndexer ftsIndexer,
                            DuplicateDetector duplicateDetector,
                            @Nullable KnowledgeExtractionPipeline extractionPipeline,
                            DocumentRepository docRepository,
                            DocumentChunkRepository chunkRepository,
                            KnowledgeBaseRepository kbRepository,
                            ApplicationEventPublisher eventPublisher,
                            KnowledgeBaseProperties props,
                            ChunkingConfig defaultChunkingConfig) {
        this.formatDetector = formatDetector;
        this.smartChunker = smartChunker;
        this.chunkerRegistry = chunkerRegistry;
        this.contextEnricher = contextEnricher;
        this.vectorIndexer = vectorIndexer;
        this.ftsIndexer = ftsIndexer;
        this.duplicateDetector = duplicateDetector;
        this.extractionPipeline = extractionPipeline;
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
        this.kbRepository = kbRepository;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.defaultChunkingConfig = defaultChunkingConfig;
        log.info("DocumentIngester 初始化完成: vectorIndexer={}, contextEnricher={}, extractionPipeline={}, chunkerRegistry={}",
                vectorIndexer != null ? "启用" : "未启用",
                contextEnricher != null ? "可用" : "不可用",
                extractionPipeline != null ? "可用" : "不可用",
                chunkerRegistry.keySet());
    }

    /**
     * 异步导入文档到指定知识库。
     *
     * @param kbId             知识库 ID
     * @param filePath         文件路径
     * @param originalFileName 原始文件名（用户上传时的文件名）
     * @return 异步文档结果
     */
    public CompletableFuture<Document> ingest(String kbId, Path filePath, String originalFileName) {
        return CompletableFuture.supplyAsync(() -> {
            var docId = UUID.randomUUID().toString();
            var now = Instant.now();
            // 使用原始文件名而非临时文件名
            var fileName = (originalFileName != null && !originalFileName.isBlank())
                    ? originalFileName : filePath.getFileName().toString();
            var doc = new Document(
                    docId, kbId, fileName, filePath.toString(),
                    filePath.toFile().length(), "", "", DocumentStatus.UPLOADING,
                    0, 0, null, null, Map.of(), now, now);
            docRepository.save(doc);
            return executeFullPipeline(doc, filePath);
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 异步导入文档到指定知识库（使用文件路径名作为文件名）。
     *
     * @param kbId     知识库 ID
     * @param filePath 文件路径
     * @return 异步文档结果
     */
    public CompletableFuture<Document> ingest(String kbId, Path filePath) {
        return ingest(kbId, filePath, filePath.getFileName().toString());
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
            doIndex(chunks, doc.knowledgeBaseId());

            // 6. EXTRACTING（异步，可降级）
            publishProgress(doc, DocumentStatus.EXTRACTING, 80, "知识提取中");
            updateStage(doc.id(), DocumentStatus.EXTRACTING);
            doExtract(doc.id(), chunks);

            // 7. READY
            publishProgress(doc, DocumentStatus.READY, 100, "导入完成");
            docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);

            // 更新知识库的文档数和分块数
            refreshKnowledgeBaseCounts(doc.knowledgeBaseId());

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
                    doIndex(chunks, doc.knowledgeBaseId());
                    doExtract(doc.id(), chunks);
                    docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);
                    refreshKnowledgeBaseCounts(doc.knowledgeBaseId());
                    yield docRepository.findById(doc.id()).orElse(doc);
                }
                case "CHUNKING" -> {
                    // 分块已保存，从索引开始
                    var chunks = chunkRepository.findByDocumentId(doc.id());
                    doIndex(chunks, doc.knowledgeBaseId());
                    doExtract(doc.id(), chunks);
                    docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);
                    refreshKnowledgeBaseCounts(doc.knowledgeBaseId());
                    yield docRepository.findById(doc.id()).orElse(doc);
                }
                case "INDEXING" -> {
                    // 索引可能部分完成，从提取开始
                    var chunks = chunkRepository.findByDocumentId(doc.id());
                    doExtract(doc.id(), chunks);
                    docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);
                    refreshKnowledgeBaseCounts(doc.knowledgeBaseId());
                    yield docRepository.findById(doc.id()).orElse(doc);
                }
                case "EXTRACTING" -> {
                    // 提取失败，重试
                    var chunks = chunkRepository.findByDocumentId(doc.id());
                    doExtract(doc.id(), chunks);
                    docRepository.updateStatus(doc.id(), DocumentStatus.READY, null);
                    refreshKnowledgeBaseCounts(doc.knowledgeBaseId());
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
        // 根据知识库的 chunkingStrategy 选择分块器
        ChunkingStrategy selectedChunker = resolveChunker(doc.knowledgeBaseId());
        Map<String, String> metadata = resolveChunkingConfigMetadata(doc.knowledgeBaseId());

        var rawChunks = selectedChunker.chunk(parseResult.text(), metadata);
        // 填充 documentId 和 knowledgeBaseId
        return rawChunks.stream()
                .map(c -> new DocumentChunk(
                        c.id(), doc.id(), doc.knowledgeBaseId(), c.content(),
                        c.contextPrefix(), c.chunkIndex(), c.startOffset(), c.endOffset(),
                        c.tokenCount(), c.contentHash(), c.headingHierarchy(),
                        c.pageNumber(), c.metadata()))
                .toList();
    }

    /**
     * 根据知识库的 chunkingStrategy 解析对应的分块器。
     *
     * <p>如果知识库配置了 chunkingConfig 且策略为 fixed-size，
     * 则构造临时 ChunkingConfig 覆盖全局默认值，创建临时分块器实例。
     * 策略为 "smart" 或未知时回退到全局 SmartChunker。</p>
     *
     * @param kbId 知识库 ID
     * @return 选中的分块器
     */
    private ChunkingStrategy resolveChunker(String kbId) {
        var kbOpt = kbRepository.findById(kbId);
        if (kbOpt.isEmpty()) {
            log.warn("知识库不存在，回退到 SmartChunker: kbId={}", kbId);
            return smartChunker;
        }
        var kb = kbOpt.get();
        String strategy = kb.chunkingStrategy();

        // "smart" 或空策略回退到全局 SmartChunker
        if (strategy == null || strategy.isBlank() || "smart".equals(strategy)) {
            return smartChunker;
        }

        // 从注册表查找对应分块器
        var chunker = chunkerRegistry.get(strategy);
        if (chunker == null) {
            log.warn("未知分块策略，回退到 SmartChunker: strategy={}, kbId={}", strategy, kbId);
            return smartChunker;
        }

        // 如果知识库有自定义 chunkingConfig，构造临时分块器
        var kbConfig = kb.chunkingConfig();
        if (kbConfig != null && !kbConfig.isEmpty()) {
            return createConfiguredChunker(strategy, kbConfig, chunker);
        }

        log.debug("使用 per-KB 分块策略: strategy={}, kbId={}", strategy, kbId);
        return chunker;
    }

    /**
     * 根据知识库的 chunkingConfig 构造临时分块器实例。
     *
     * <p>从 chunkingConfig 中提取 maxChunkSize、minChunkSize、overlapSize 等参数，
     * 覆盖全局默认 ChunkingConfig，创建新的分块器实例。</p>
     */
    private ChunkingStrategy createConfiguredChunker(String strategy, Map<String, Object> kbConfig,
                                                      ChunkingStrategy fallback) {
        try {
            int maxChunkSize = getIntOrDefault(kbConfig, "maxChunkSize", defaultChunkingConfig.maxChunkSize());
            int minChunkSize = getIntOrDefault(kbConfig, "minChunkSize", defaultChunkingConfig.minChunkSize());
            int overlapSize = getIntOrDefault(kbConfig, "overlapSize", defaultChunkingConfig.overlapSize());
            int maxChunkTokens = getIntOrDefault(kbConfig, "maxChunkTokens", defaultChunkingConfig.maxChunkTokens());

            var customConfig = new ChunkingConfig(
                    maxChunkSize, minChunkSize, overlapSize, maxChunkTokens,
                    defaultChunkingConfig.respectSentences(),
                    defaultChunkingConfig.respectParagraphs(),
                    defaultChunkingConfig.enableContextPrefix());

            // 仅 fixed-size 策略支持临时配置覆盖（其他策略需要额外构造参数）
            if ("fixed-size".equals(strategy)) {
                log.debug("使用自定义 ChunkingConfig 构造临时 FixedSizeChunker: config={}", kbConfig);
                return new FixedSizeChunker(customConfig);
            }

            // 其他策略暂不支持临时配置覆盖，使用全局实例
            log.debug("策略 {} 暂不支持 chunkingConfig 覆盖，使用全局实例", strategy);
            return fallback;
        } catch (Exception e) {
            log.warn("构造临时分块器失败，回退到全局实例: strategy={}, error={}", strategy, e.getMessage());
            return fallback;
        }
    }

    /**
     * 从 chunkingConfig Map 中提取 int 值，不存在时返回默认值。
     */
    private static int getIntOrDefault(Map<String, Object> config, String key, int defaultValue) {
        var value = config.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // 忽略
            }
        }
        return defaultValue;
    }

    /**
     * 从知识库的 chunkingConfig 中提取元数据（传递给分块器的 metadata 参数）。
     */
    private Map<String, String> resolveChunkingConfigMetadata(String kbId) {
        // 当前不传递额外元数据，保持与原有行为一致
        return Map.of();
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

    private void doIndex(List<DocumentChunk> chunks, String kbId) {
        // 从知识库配置读取 embeddingModel
        String embeddingModel = kbRepository.findById(kbId)
                .map(kb -> kb.embeddingModel())
                .orElse(null);

        // 向量索引（可选）和 FTS5 索引并行执行
        var vectorFuture = CompletableFuture.runAsync(() -> {
                    if (vectorIndexer != null) {
                        vectorIndexer.indexChunks(chunks, embeddingModel);
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

    /**
     * 刷新知识库的文档数和分块数统计。
     *
     * <p>统计该知识库下所有文档数量和分块总数，更新到 knowledge_bases 表。</p>
     */
    private void refreshKnowledgeBaseCounts(String kbId) {
        try {
            var docs = docRepository.findByKnowledgeBaseId(kbId);
            int docCount = docs.size();
            int totalChunks = docs.stream().mapToInt(Document::chunkCount).sum();
            kbRepository.updateDocumentCount(kbId, docCount, totalChunks);
            log.debug("知识库统计已更新: kbId={}, docCount={}, totalChunks={}", kbId, docCount, totalChunks);
        } catch (Exception e) {
            log.warn("知识库统计更新失败（不影响文档导入）: kbId={}, error={}", kbId, e.getMessage());
        }
    }

    private void publishProgress(Document doc, DocumentStatus stage, int percent, String message) {
        var progress = new IngestionProgress(
                doc.id(), doc.knowledgeBaseId(), stage, percent, message);
        eventPublisher.publishEvent(progress);
    }
}

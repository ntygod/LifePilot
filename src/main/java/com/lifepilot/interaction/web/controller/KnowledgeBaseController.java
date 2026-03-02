package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ChunkResult;
import com.lifepilot.interaction.web.model.CreateKbRequest;
import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.interaction.web.model.LogEntry;
import com.lifepilot.interaction.web.model.ProcessingLogResponse;
import com.lifepilot.interaction.web.model.RetrievalTestRequest;
import com.lifepilot.interaction.web.model.RetrievalTestResponse;
import com.lifepilot.interaction.web.model.UpdateKbRequest;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.exception.DocumentNotFoundException;
import com.lifepilot.knowledge.exception.KnowledgeBaseNotFoundException;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库管理 REST Controller。
 *
 * <p>提供知识库 CRUD 和文档管理 API 端点，
 * 委托 {@link KnowledgeBaseManager} 和 {@link DocumentIngester} 完成业务逻辑。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    /** 支持的文件扩展名。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".docx", ".md", ".txt");

    private final KnowledgeBaseManager kbManager;
    @Nullable
    private final DocumentIngester documentIngester;
    @Nullable
    private final DocumentRetriever documentRetriever;
    @Nullable
    private final DocumentRepository documentRepository;

    public KnowledgeBaseController(KnowledgeBaseManager kbManager,
                                   @Nullable DocumentIngester documentIngester,
                                   @Nullable DocumentRetriever documentRetriever,
                                   @Nullable DocumentRepository documentRepository) {
        this.kbManager = kbManager;
        this.documentIngester = documentIngester;
        this.documentRetriever = documentRetriever;
        this.documentRepository = documentRepository;
    }

    /** 列出所有知识库。 */
    @GetMapping
    public ResponseEntity<List<KnowledgeBase>> listKnowledgeBases(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String timeRange
    ) {
        log.debug("查询知识库列表: q={}, tags={}, timeRange={}", q, tags, timeRange);
        
        // 如果没有任何查询参数，使用原有的 findAll 方法
        if (q == null && tags == null && timeRange == null) {
            return ResponseEntity.ok(kbManager.listKnowledgeBases());
        }
        
        // 否则使用条件查询
        return ResponseEntity.ok(kbManager.listKnowledgeBases(q, tags, timeRange));
    }

    /** 创建知识库。 */
    @PostMapping
    public ResponseEntity<?> createKnowledgeBase(@RequestBody CreateKbRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            log.warn("创建知识库失败: 名称为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "知识库名称不能为空", Instant.now()));
        }
        var kb = kbManager.createKnowledgeBase(
                request.name(),
                request.description() != null ? request.description() : "",
                request.embeddingModel() != null ? request.embeddingModel() : "default");
        log.info("知识库创建成功: id={}, name={}", kb.id(), kb.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(kb);
    }

    /** 获取知识库详情。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> getKnowledgeBase(@PathVariable String id) {
        return kbManager.getKnowledgeBase(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "知识库不存在: id=" + id, Instant.now())));
    }

    /** 更新知识库。 */
    @PatchMapping("/{id}")
    public ResponseEntity<?> updateKnowledgeBase(@PathVariable String id,
                                                 @RequestBody UpdateKbRequest request) {
        try {
            KnowledgeBase updated = kbManager.updateKnowledgeBase(
                    id,
                    null, // name 不支持更新
                    request.description(),
                    request.tags()
            );
            log.info("知识库更新成功: id={}", id);
            return ResponseEntity.ok(updated);
        } catch (KnowledgeBaseNotFoundException e) {
            log.warn("知识库更新失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, e.getMessage(), Instant.now()));
        }
    }

    /** 删除知识库。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteKnowledgeBase(@PathVariable String id) {
        kbManager.deleteKnowledgeBase(id);
        log.info("知识库删除: id={}", id);
        return ResponseEntity.noContent().build();
    }

    /** 列出知识库下的文档。 */
    @GetMapping("/{id}/documents")
    public ResponseEntity<List<Document>> listDocuments(@PathVariable String id) {
        log.debug("查询文档列表: kbId={}", id);
        return ResponseEntity.ok(kbManager.listDocuments(id));
    }

    /** 上传文档到知识库。 */
    @PostMapping("/{id}/documents")
    public ResponseEntity<?> uploadDocument(@PathVariable String id,
                                            @RequestParam("file") MultipartFile file) {
        var ingester = this.documentIngester;
        if (ingester == null) {
            log.error("文档上传失败: DocumentIngester 未初始化，请检查知识库和向量索引配置");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    new ErrorResponse(503, "文档导入功能未启用，请检查知识库配置", Instant.now()));
        }
        // 校验文件扩展名
        String originalName = file.getOriginalFilename();
        if (originalName == null || !hasAllowedExtension(originalName)) {
            log.warn("文件格式不支持: fileName={}", originalName);
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "不支持的文件格式，仅支持 PDF/Word/Markdown/TXT", Instant.now()));
        }

        try {
            // 保存到临时文件
            String suffix = originalName.substring(originalName.lastIndexOf('.'));
            Path tempFile = Files.createTempFile("lifepilot-upload-", suffix);
            file.transferTo(java.util.Objects.requireNonNull(tempFile.toFile()));

            // 异步处理文档
            ingester.ingest(id, tempFile);
            log.info("文档上传已提交异步处理: kbId={}, fileName={}", id, originalName);

            return ResponseEntity.accepted().body(
                    java.util.Map.of("message", "文档已提交处理", "fileName", originalName));
        } catch (IOException e) {
            log.error("文档上传失败: kbId={}, fileName={}, error={}", id, originalName, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "文档上传失败: " + e.getMessage(), Instant.now()));
        }
    }

    /** 删除文档。 */
    @DeleteMapping("/{id}/documents/{docId}")
    public ResponseEntity<Void> removeDocument(@PathVariable String id,
                                               @PathVariable String docId) {
        kbManager.removeDocument(docId);
        log.info("文档删除: docId={}", docId);
        return ResponseEntity.noContent().build();
    }

    /** 测试知识库检索。 */
    @PostMapping("/{id}/test-retrieval")
    public ResponseEntity<?> testRetrieval(@PathVariable String id,
                                          @RequestBody RetrievalTestRequest request) {
        // 验证知识库存在
        if (kbManager.getKnowledgeBase(id).isEmpty()) {
            log.warn("测试检索失败: 知识库不存在, id={}", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "知识库不存在: id=" + id, Instant.now()));
        }

        // 验证检索服务可用
        var retriever = this.documentRetriever;
        var docRepo = this.documentRepository;
        if (retriever == null || docRepo == null) {
            log.error("测试检索失败: DocumentRetriever 或 DocumentRepository 未初始化");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    new ErrorResponse(503, "检索功能未启用，请检查知识库配置", Instant.now()));
        }

        // 验证查询参数
        if (request.query() == null || request.query().isBlank()) {
            log.warn("测试检索失败: 查询问题为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "查询问题不能为空", Instant.now()));
        }

        try {
            int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 5;
            
            // 执行检索
            List<DocumentSearchResult> searchResults = retriever.retrieve(
                    request.query(), List.of(id), topK);

            log.debug("检索完成: kbId={}, query={}, topK={}, results={}", 
                    id, request.query(), topK, searchResults.size());

            // 批量查询文档名称（避免 N+1 查询）
            Set<String> documentIds = searchResults.stream()
                    .map(DocumentSearchResult::documentId)
                    .collect(Collectors.toSet());
            
            Map<String, String> documentNameMap = new HashMap<>();
            for (String docId : documentIds) {
                docRepo.findById(docId)
                        .ifPresent(doc -> documentNameMap.put(docId, doc.fileName()));
            }

            // 转换为响应格式
            List<ChunkResult> chunks = searchResults.stream()
                    .map(result -> {
                        String documentName = documentNameMap.getOrDefault(
                                result.documentId(), "未知文档");
                        return new ChunkResult(
                                result.chunkId(),
                                result.documentId(),
                                documentName,
                                result.content(),
                                result.score(),
                                result.metadata()
                        );
                    })
                    .collect(Collectors.toList());

            RetrievalTestResponse response = new RetrievalTestResponse(chunks, null);
            log.info("测试检索成功: kbId={}, query={}, chunks={}", id, request.query(), chunks.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("测试检索异常: kbId={}, query={}, error={}", id, request.query(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "检索失败: " + e.getMessage(), Instant.now()));
        }
    }

    /** 获取文档处理日志。 */
    @GetMapping("/{id}/documents/{docId}/logs")
    public ResponseEntity<?> getDocumentLogs(@PathVariable String id,
                                             @PathVariable String docId) {
        // 验证知识库存在
        if (kbManager.getKnowledgeBase(id).isEmpty()) {
            log.warn("获取文档日志失败: 知识库不存在, id={}", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "知识库不存在: id=" + id, Instant.now()));
        }

        // 验证文档存在
        var docRepo = this.documentRepository;
        if (docRepo == null) {
            log.error("获取文档日志失败: DocumentRepository 未初始化");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    new ErrorResponse(503, "文档功能未启用，请检查知识库配置", Instant.now()));
        }

        try {
            Document doc = docRepo.findById(docId)
                    .orElseThrow(() -> new DocumentNotFoundException("文档不存在: id=" + docId));

            // 验证文档属于指定知识库
            if (!doc.knowledgeBaseId().equals(id)) {
                log.warn("获取文档日志失败: 文档不属于指定知识库, docId={}, kbId={}", docId, id);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                        new ErrorResponse(400, "文档不属于指定知识库", Instant.now()));
            }

            // 基于文档状态生成日志
            List<LogEntry> logs = generateLogsFromDocument(doc);
            
            log.debug("获取文档日志成功: docId={}, logs={}", docId, logs.size());
            return ResponseEntity.ok(new ProcessingLogResponse(logs));
        } catch (DocumentNotFoundException e) {
            log.warn("获取文档日志失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("获取文档日志异常: docId={}, error={}", docId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "获取日志失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 基于文档状态生成日志条目。
     *
     * @param doc 文档对象
     * @return 日志条目列表
     */
    private List<LogEntry> generateLogsFromDocument(Document doc) {
        List<LogEntry> logs = new ArrayList<>();
        
        // 创建时间 - 文档上传
        logs.add(new LogEntry(
                doc.createdAt(),
                "INFO",
                "文档上传成功",
                Map.of("fileName", doc.fileName(), "fileSize", doc.fileSize())
        ));

        // 根据状态生成对应的日志
        DocumentStatus status = doc.status();
        String lastStage = doc.lastProcessedStage().orElse("");
        
        // 根据状态和阶段生成日志
        if (status == DocumentStatus.UPLOADING) {
            logs.add(new LogEntry(
                    doc.updatedAt(),
                    "INFO",
                    "等待处理",
                    Map.of()
            ));
        } else if (status == DocumentStatus.PARSING || lastStage.equals("PARSING")) {
            logs.add(new LogEntry(
                    doc.updatedAt(),
                    "INFO",
                    "开始解析文档",
                    Map.of()
            ));
        }
        
        if (status == DocumentStatus.CHUNKING || lastStage.equals("CHUNKING")) {
            logs.add(new LogEntry(
                    doc.updatedAt(),
                    "INFO",
                    "文档解析完成",
                    Map.of("chunkCount", doc.chunkCount())
            ));
        }
        
        if (status == DocumentStatus.INDEXING || lastStage.equals("INDEXING")) {
            logs.add(new LogEntry(
                    doc.updatedAt(),
                    "INFO",
                    "构建索引中",
                    Map.of("chunkCount", doc.chunkCount())
            ));
        }
        
        if (status == DocumentStatus.EXTRACTING || lastStage.equals("EXTRACTING")) {
            logs.add(new LogEntry(
                    doc.updatedAt(),
                    "INFO",
                    "知识提取中",
                    Map.of("entityCount", doc.entityCount())
            ));
        }
        
        if (status == DocumentStatus.READY) {
            logs.add(new LogEntry(
                    doc.updatedAt(),
                    "INFO",
                    "文档处理完成",
                    Map.of(
                            "chunkCount", doc.chunkCount(),
                            "entityCount", doc.entityCount()
                    )
            ));
        }
        
        if (status == DocumentStatus.ERROR) {
            String errorMsg = doc.errorMessage().orElse("未知错误");
            logs.add(new LogEntry(
                    doc.updatedAt(),
                    "ERROR",
                    "文档处理失败",
                    Map.of("error", errorMsg, "lastStage", lastStage)
            ));
        }

        return logs;
    }

    /** 检查文件扩展名是否在允许范围内。 */
    private boolean hasAllowedExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}

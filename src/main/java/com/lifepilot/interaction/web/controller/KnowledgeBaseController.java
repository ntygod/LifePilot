package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.CreateKbRequest;
import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
import java.util.List;
import java.util.Set;

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

    public KnowledgeBaseController(KnowledgeBaseManager kbManager,
                                   @Nullable DocumentIngester documentIngester) {
        this.kbManager = kbManager;
        this.documentIngester = documentIngester;
    }

    /** 列出所有知识库。 */
    @GetMapping
    public ResponseEntity<List<KnowledgeBase>> listKnowledgeBases() {
        log.debug("查询知识库列表");
        return ResponseEntity.ok(kbManager.listKnowledgeBases());
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

    /** 检查文件扩展名是否在允许范围内。 */
    private boolean hasAllowedExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}

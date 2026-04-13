package com.lifepilot.interaction.web.controller;

import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.FieldHint;
import com.lifepilot.datastore.sync.DatastoreKnowledgeBaseProvisioner;
import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.CreateDatastoreRequest;
import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.interaction.web.model.UpdateDatastoreRequest;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.repository.KnowledgeBaseDatastoreRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import java.util.Objects;
import java.util.Optional;

/**
 * Datastore Web API。
 *
 * <p>当前为前端提供 datastore 列表和详情查询能力，
 * 供聊天配置、知识库关联和文档归属入口使用。</p>
 *
 * @author zsg
 * @since 2026-03-27
 */
@RestController
@RequestMapping("/api/datastores")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class DatastoreController {

    private static final Logger log = LoggerFactory.getLogger(DatastoreController.class);
    private static final java.util.Set<String> ALLOWED_EXTENSIONS = java.util.Set.of(".pdf", ".docx", ".md", ".txt");

    private final DataStoreManager dataStoreManager;
    private final KnowledgeBaseManager knowledgeBaseManager;
    private final KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository;
    @Nullable
    private final DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner;
    @Nullable
    private final DocumentIngester documentIngester;
    private final KnowledgeBaseProperties knowledgeBaseProperties;

    public DatastoreController(DataStoreManager dataStoreManager,
                               KnowledgeBaseManager knowledgeBaseManager,
                               KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository,
                               @Nullable DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner,
                               @Nullable DocumentIngester documentIngester,
                               KnowledgeBaseProperties knowledgeBaseProperties) {
        this.dataStoreManager = dataStoreManager;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.knowledgeBaseDatastoreRepository = knowledgeBaseDatastoreRepository;
        this.datastoreKnowledgeBaseProvisioner = datastoreKnowledgeBaseProvisioner;
        this.documentIngester = documentIngester;
        this.knowledgeBaseProperties = knowledgeBaseProperties;
    }

    /**
     * 查询 datastore 列表。
     *
     * @param q 名称或描述关键字（可选）
     * @return datastore 列表
     */
    @GetMapping
    public ApiResponse<List<Collection>> listDatastores(@RequestParam(required = false) String q) {
        List<Collection> collections = dataStoreManager.listCollections();
        if (q == null || q.isBlank()) {
            return ApiResponse.ok(collections);
        }

        String keyword = q.strip().toLowerCase();
        List<Collection> filtered = collections.stream()
                .filter(collection -> matchesKeyword(collection, keyword))
                .toList();
        return ApiResponse.ok(filtered);
    }

    /**
     * 查询单个 datastore 详情。
     *
     * @param id datastore ID
     * @return datastore 详情
     */
    @GetMapping("/{id}")
    public ApiResponse<?> getDatastore(@PathVariable String id) {
        var collection = dataStoreManager.getCollection(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Datastore 不存在: id=" + id));
        return ApiResponse.ok(collection);
    }

    /**
     * 查询 Datastore 下的原始结构化文档。
     *
     * @param id datastore ID
     * @return 原始结构化文档列表
     */
    @GetMapping("/{id}/records")
    public ApiResponse<?> listDatastoreRecords(@PathVariable String id) {
        var collection = dataStoreManager.getCollection(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Datastore 不存在: id=" + id));
        return ApiResponse.ok(dataStoreManager.listDocuments(collection.id()));
    }

    /**
     * 查询 Datastore 关联的知识库（包含系统内部知识库）。
     *
     * @param id datastore ID
     * @return 知识库列表
     */
    @GetMapping("/{id}/knowledge-bases")
    public ApiResponse<?> listDatastoreKnowledgeBases(@PathVariable String id) {
        var collection = dataStoreManager.getCollection(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Datastore 不存在: id=" + id));
        List<KnowledgeBase> items = knowledgeBaseDatastoreRepository.findKnowledgeBaseIdsByDatastoreId(collection.id()).stream()
                .map(knowledgeBaseManager::getKnowledgeBase)
                .flatMap(Optional::stream)
                .toList();
        return ApiResponse.ok(items);
    }

    /**
     * 查询 Datastore 直管的领域文档。
     *
     * @param id datastore ID
     * @return 领域文档列表
     */
    @GetMapping("/{id}/documents")
    public ApiResponse<?> listDatastoreDomainDocuments(@PathVariable String id) {
        var collection = dataStoreManager.getCollection(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Datastore 不存在: id=" + id));
        String defaultKnowledgeBaseId = resolveDefaultKnowledgeBaseId(collection);
        var docs = knowledgeBaseManager.listDocuments(defaultKnowledgeBaseId).stream()
                .filter(doc -> doc.sourceType() == DocumentSourceType.FILE)
                .filter(doc -> Objects.equals(doc.sourceDatastoreId(), collection.id()))
                .toList();
        return ApiResponse.ok(docs);
    }

    /**
     * 直接向 Datastore 上传领域文档。
     *
     * @param id   datastore ID
     * @param file 上传文件
     * @return 提交结果
     */
    @PostMapping("/{id}/documents")
    public ApiResponse<?> uploadDatastoreDocument(@PathVariable String id,
                                                     @RequestParam("file") MultipartFile file) {
        var ingester = this.documentIngester;
        if (ingester == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文档导入功能未启用，请检查知识库配置");
        }

        var collection = dataStoreManager.getCollection(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Datastore 不存在: id=" + id));
        String originalName = file.getOriginalFilename();
        if (originalName == null || !hasAllowedExtension(originalName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的文件格式，仅支持 PDF/Word/Markdown/TXT");
        }
        long maxFileSize = knowledgeBaseProperties.maxFileSize();
        if (file.getSize() > maxFileSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, buildFileSizeExceededMessage(maxFileSize));
        }
        try {
            String defaultKnowledgeBaseId = resolveDefaultKnowledgeBaseId(collection);
            String suffix = originalName.substring(originalName.lastIndexOf('.'));
            Path tempFile = Files.createTempFile("lifepilot-datastore-upload-", suffix);
            file.transferTo(Objects.requireNonNull(tempFile.toFile()));

            // 异步 ingest 到知识库
            ingester.ingest(defaultKnowledgeBaseId, tempFile, originalName, collection.id())
                    .exceptionally(ex -> {
                        log.warn("文档 ingest 失败: datastoreId={}, error={}", collection.id(), ex.getMessage());
                        return null;
                    });

            log.info("Datastore 文档上传已提交: datastoreId={}, knowledgeBaseId={}, fileName={}",
                    collection.id(), defaultKnowledgeBaseId, originalName);
            return ApiResponse.ok(java.util.Map.of(
                    "message", "文档已提交处理",
                    "fileName", originalName,
                    "datastoreId", collection.id(),
                    "knowledgeBaseId", defaultKnowledgeBaseId
            ));
        } catch (IOException e) {
            log.error("Datastore 文档上传失败: datastoreId={}, error={}", collection.id(), e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文档上传失败: " + e.getMessage());
        }
    }

    /**
     * 创建 datastore。
     *
     * @param request 创建请求
     * @return 201 创建成功；400 参数错误
     */
    @PostMapping
    public ApiResponse<?> createDatastore(@RequestBody CreateDatastoreRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datastore 名称不能为空");
        }
        try {
            var collection = dataStoreManager.createCollection(
                    request.name().strip(),
                    request.timeSeries(),
                    request.fieldHints(),
                    request.description(),
                    null);
            log.info("Datastore 创建成功: id={}, name={}, timeSeries={}", collection.id(), collection.name(), collection.timeSeries());
            return ApiResponse.ok(collection);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Datastore 创建失败: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 更新 datastore。
     *
     * @param id      datastore ID
     * @param request 更新请求
     * @return 200 更新成功；404 不存在
     */
    @PutMapping("/{id}")
    public ApiResponse<?> updateDatastore(@PathVariable String id,
                                             @RequestBody UpdateDatastoreRequest request) {
        var existing = dataStoreManager.getCollection(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Datastore 不存在: id=" + id));
        dataStoreManager.updateCollection(id, request.description(), request.fieldHintsJson());
        log.info("Datastore 更新成功: id={}", id);
        var updated = dataStoreManager.getCollection(id).orElse(existing);
        return ApiResponse.ok(updated);
    }

    /**
     * 删除单个 datastore。
     *
     * @param id datastore ID
     * @return 204 删除成功；404 不存在
     */
    @DeleteMapping("/{id}")
    public ApiResponse<?> deleteDatastore(@PathVariable String id) {
        if (dataStoreManager.getCollection(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Datastore 不存在: id=" + id);
        }
        dataStoreManager.deleteCollection(id);
        log.info("Datastore 删除成功: id={}", id);
        return ApiResponse.ok();
    }

    private boolean matchesKeyword(Collection collection, String keyword) {
        if (collection.name() != null && collection.name().toLowerCase().contains(keyword)) {
            return true;
        }
        return collection.description() != null && collection.description().toLowerCase().contains(keyword);
    }

    private String resolveDefaultKnowledgeBaseId(Collection collection) {
        if (collection.defaultKnowledgeBaseId() == null || collection.defaultKnowledgeBaseId().isBlank()) {
            throw new IllegalStateException("Datastore 缺少默认知识库: datastoreId=" + collection.id());
        }
        return collection.defaultKnowledgeBaseId();
    }

    private boolean hasAllowedExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private String buildFileSizeExceededMessage(long maxFileSizeBytes) {
        long maxSizeMb = Math.max(1L, maxFileSizeBytes / (1024 * 1024));
        return "文件大小超过限制，当前最大支持 " + maxSizeMb + " MB";
    }
}

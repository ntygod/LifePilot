package com.lifepilot.datastore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.config.DataStoreProperties;
import com.lifepilot.datastore.engine.AggregationEngine;
import com.lifepilot.datastore.engine.QueryEngine;
import com.lifepilot.datastore.model.AggregationRequest;
import com.lifepilot.datastore.model.AggregationResult;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.FieldHint;
import com.lifepilot.datastore.model.FieldNames;
import com.lifepilot.datastore.model.QueryRequest;
import com.lifepilot.datastore.model.SqlWithParams;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.sync.DatastoreKnowledgeBaseProvisioner;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 数据存储管理门面 — 文档优先模型。
 *
 * <p>文档以富文本 content 为主表示，直接存入知识库 documents 表。
 * metadata_json 作为副索引支持排序、过滤、聚合。</p>
 *
 * @author zsg
 * @since 2026-04-13
 */
public class DataStoreManager {

    private static final Logger log = LoggerFactory.getLogger(DataStoreManager.class);
    private static final TypeReference<List<FieldHint>> FIELD_HINT_LIST_TYPE = new TypeReference<>() {};

    private final CollectionRepository collectionRepository;
    private final QueryEngine queryEngine;
    private final AggregationEngine aggregationEngine;
    private final DataStoreProperties properties;
    private final ObjectMapper objectMapper;
    @Nullable private final KnowledgeBaseManager knowledgeBaseManager;
    @Nullable private final DocumentIngester documentIngester;
    @Nullable private final DocumentRepository knowledgeDocRepository;
    @Nullable private final DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner;

    public DataStoreManager(CollectionRepository collectionRepository,
                            QueryEngine queryEngine,
                            AggregationEngine aggregationEngine,
                            DataStoreProperties properties,
                            ObjectMapper objectMapper,
                            @Nullable KnowledgeBaseManager knowledgeBaseManager,
                            @Nullable DocumentIngester documentIngester,
                            @Nullable DocumentRepository knowledgeDocRepository,
                            @Nullable DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner) {
        this.collectionRepository = collectionRepository;
        this.queryEngine = queryEngine;
        this.aggregationEngine = aggregationEngine;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.documentIngester = documentIngester;
        this.knowledgeDocRepository = knowledgeDocRepository;
        this.datastoreKnowledgeBaseProvisioner = datastoreKnowledgeBaseProvisioner;
    }

    // ---- 集合操作 ----

    @Transactional
    public Collection createCollection(String name, boolean timeSeries,
                                       @Nullable List<FieldHint> fieldHints,
                                       @Nullable String description,
                                       @Nullable String createdBy) {
        int currentCount = collectionRepository.count();
        if (currentCount >= properties.getMaxCollections()) {
            throw new IllegalStateException("集合数量已达上限: " + properties.getMaxCollections());
        }
        if (collectionRepository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("集合名称已存在: " + name);
        }

        String fieldHintsJson = serializeFieldHints(fieldHints);
        var collection = Collection.builder()
                .name(name).timeSeries(timeSeries).description(description)
                .fieldHintsJson(fieldHintsJson)
                .createdBy(createdBy).build();

        String collectionId = collectionRepository.insert(collection);
        try {
            collection = collectionRepository.findById(collectionId).orElseThrow(
                    () -> new IllegalStateException("集合创建后查询失败: id=" + collectionId));

            if (fieldHints != null && !fieldHints.isEmpty()) {
                for (var hint : fieldHints) {
                    collectionRepository.addGeneratedColumn(
                            hint.name(), hint.toSqliteAffinity(), collectionId);
                }
            }
            log.info("集合创建完成: id={}, name={}, timeSeries={}, 字段提示数={}", collectionId, name, timeSeries,
                    fieldHints != null ? fieldHints.size() : 0);

            if (datastoreKnowledgeBaseProvisioner != null) {
                String defaultKbId = datastoreKnowledgeBaseProvisioner.ensureDefaultKnowledgeBase(collection);
                if (defaultKbId == null || defaultKbId.isBlank()) {
                    throw new IllegalStateException("Datastore 默认知识库创建失败: 未返回知识库 ID");
                }
                if (!collectionRepository.updateDefaultKnowledgeBaseId(collectionId, defaultKbId)) {
                    throw new IllegalStateException("Datastore 默认知识库回填失败: datastoreId=" + collectionId);
                }
                log.info("Datastore 已绑定内部知识库: datastoreId={}, knowledgeBaseId={}", collectionId, defaultKbId);
            }

            return collectionRepository.findById(collectionId).orElseThrow(
                    () -> new IllegalStateException("集合创建后查询失败: id=" + collectionId));
        } catch (RuntimeException e) {
            cleanupFailedCollectionCreation(collection, e);
            throw e;
        }
    }

    public List<Collection> listCollections() {
        return List.copyOf(collectionRepository.findAll());
    }

    public List<Collection> listCollections(boolean timeSeries) {
        return List.copyOf(collectionRepository.findByTimeSeries(timeSeries));
    }

    public Optional<Collection> findCollection(String name) {
        return collectionRepository.findByName(name);
    }

    public Optional<Collection> getCollection(String id) {
        return collectionRepository.findById(id);
    }

    @Transactional
    public boolean updateCollection(String id, @Nullable String description,
                                    @Nullable String fieldHintsJson) {
        var existing = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + id));
        boolean updated = collectionRepository.update(id,
                description != null ? description : existing.description(),
                fieldHintsJson != null ? fieldHintsJson : existing.fieldHintsJson());
        if (updated) {
            log.info("集合更新完成: id={}", id);
        }
        return updated;
    }

    @Transactional
    public boolean deleteCollection(String id) {
        var collection = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + id));

        // 1. 清理 Generated Column 索引
        List<FieldHint> fieldHints = deserializeFieldHints(collection.fieldHintsJson());
        if (!fieldHints.isEmpty()) {
            List<String> indexable = fieldHints.stream().map(FieldHint::name).toList();
            collectionRepository.dropGeneratedColumns(id, indexable);
        }

        // 2. 删除内部知识库（CASCADE 删除所有关联文档和分块）
        if (datastoreKnowledgeBaseProvisioner != null) {
            datastoreKnowledgeBaseProvisioner.deleteDefaultKnowledgeBase(collection);
            log.info("Datastore 内部知识库删除已提交: datastoreId={}", collection.id());
        }

        // 3. 删除集合记录
        boolean deleted = collectionRepository.delete(id);
        if (deleted) {
            log.info("集合删除完成: id={}, name={}", id, collection.name());
        }
        return deleted;
    }

    // ---- 文档操作 ----

    /**
     * 添加文档 — 创建知识库文档，异步 ingest。
     *
     * @param collectionId 集合 ID
     * @param content      富文本内容
     * @param metadataJson 可选结构化元数据 JSON
     * @param recordedAt   时序集合的 ISO 8601 时间戳
     * @return 创建的知识库文档 ID
     */
    @Transactional
    public String addDocument(String collectionId, String content,
                              @Nullable String metadataJson,
                              @Nullable String recordedAt) {
        var collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + collectionId));

        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > properties.getMaxDocumentSizeBytes()) {
            throw new IllegalArgumentException("文档大小超过限制: " + properties.getMaxDocumentSizeBytes() + " bytes");
        }
        if (collection.timeSeries()) {
            if (recordedAt == null || recordedAt.isBlank()) {
                throw new IllegalArgumentException("时序集合文档必须包含 recordedAt");
            }
            try { DateTimeFormatter.ISO_DATE_TIME.parse(recordedAt); }
            catch (DateTimeParseException e) {
                throw new IllegalArgumentException("recordedAt 格式非法，期望 ISO 8601: " + recordedAt, e);
            }
        }

        if (knowledgeDocRepository != null) {
            int docCount = knowledgeDocRepository.countBySourceDatastoreId(collectionId);
            if (docCount >= properties.getMaxDocumentsPerCollection()) {
                throw new IllegalStateException("集合文档数量已达上限: " + properties.getMaxDocumentsPerCollection());
            }
        }

        String contextPrefix = buildContextPrefix(collection);
        String docId = UUID.randomUUID().toString();
        String sourceKey = "DATASTORE:" + collectionId + ":" + docId;
        String title = extractTitle(metadataJson);
        String fileName = "%s - %s".formatted(collection.name(), title != null ? title : docId.substring(0, 8));
        String filePath = "datastore://" + collectionId + "/" + docId;
        String contentHash = sha256(content);
        String normalizedMetadata = metadataJson != null ? metadataJson : "{}";

        var doc = new Document(
                docId,
                collection.defaultKnowledgeBaseId(),
                fileName, filePath,
                content.getBytes(StandardCharsets.UTF_8).length,
                "text/plain",
                contentHash,
                DocumentStatus.CHUNKING,
                0, 0, null, null,
                Map.of("syncSource", "datastore"),
                Instant.now(), Instant.now(),
                DocumentSourceType.DATASTORE_DOCUMENT,
                sourceKey, collectionId, null,
                Map.of(),
                content, recordedAt
        );

        if (knowledgeDocRepository != null) {
            knowledgeDocRepository.save(doc);
        }

        // 异步 ingest
        if (documentIngester != null) {
            documentIngester.ingestProjectedDocument(doc, content);
        }

        log.info("文档添加完成: id={}, collectionId={}", docId, collectionId);
        return docId;
    }

    public Optional<Document> getDocument(String id) {
        return knowledgeDocRepository != null ? knowledgeDocRepository.findById(id) : Optional.empty();
    }

    public List<Document> listDocuments(String collectionId, int offset, int limit) {
        collectionRepository.findById(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + collectionId));
        if (knowledgeDocRepository != null) {
            return List.copyOf(knowledgeDocRepository.findBySourceDatastoreId(collectionId, offset, limit));
        }
        return List.of();
    }

    public List<Document> listDocuments(String collectionId) {
        return listDocuments(collectionId, 0, properties.getMaxPageSize());
    }

    @Transactional
    public boolean updateDocument(String id, @Nullable String content, @Nullable String metadataJson) {
        if (knowledgeDocRepository == null) {
            return false;
        }
        var existingDoc = knowledgeDocRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: id=" + id));

        boolean contentChanged = false;
        if (content != null && !content.isBlank()) {
            String newHash = sha256(content);
            if (!newHash.equals(existingDoc.contentHash())) {
                contentChanged = true;
                knowledgeDocRepository.updateContent(id, content, newHash);
            }
        }

        if (metadataJson != null) {
            knowledgeDocRepository.updateMetadataJson(id, metadataJson);
        }

        if (contentChanged && documentIngester != null) {
            var updatedDoc = knowledgeDocRepository.findById(id).orElse(existingDoc);
            documentIngester.ingestProjectedDocument(updatedDoc,
                    content != null ? content : existingDoc.content());
        }

        log.info("文档更新完成: id={}, contentChanged={}", id, contentChanged);
        return true;
    }

    @Transactional
    public boolean deleteDocument(String id) {
        if (knowledgeBaseManager == null) {
            return false;
        }
        knowledgeBaseManager.removeDocument(id);
        log.info("文档删除完成: id={}", id);
        return true;
    }

    // ---- 查询操作 ----

    public List<Document> queryDocuments(QueryRequest request) {
        var collection = collectionRepository.findById(request.collectionId())
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + request.collectionId()));
        List<FieldHint> fieldHints = deserializeFieldHints(collection.fieldHintsJson());
        Set<String> indexedFields = fieldHints.stream().map(FieldHint::name).collect(Collectors.toSet());
        var sqlWithParams = queryEngine.buildQuery(request, indexedFields, FieldNames.safePrefix(request.collectionId()));
        if (knowledgeDocRepository != null) {
            var results = knowledgeDocRepository.queryRaw(sqlWithParams.sql(), sqlWithParams.params());
            log.debug("文档查询完成: collectionId={}, 结果数={}", request.collectionId(), results.size());
            return List.copyOf(results);
        }
        return List.of();
    }

    public List<AggregationResult> aggregate(AggregationRequest request) {
        var collection = collectionRepository.findById(request.collectionId())
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + request.collectionId()));
        if (!collection.timeSeries()) {
            throw new IllegalArgumentException("时序聚合仅支持时序集合");
        }
        List<FieldHint> fieldHints = deserializeFieldHints(collection.fieldHintsJson());
        Set<String> indexedFields = fieldHints.stream().map(FieldHint::name).collect(Collectors.toSet());
        var sqlWithParams = aggregationEngine.buildAggregation(request, indexedFields, FieldNames.safePrefix(request.collectionId()));
        if (knowledgeDocRepository != null) {
            var results = knowledgeDocRepository.aggregateRaw(sqlWithParams.sql(), sqlWithParams.params());
            log.debug("时序聚合完成: collectionId={}, func={}, 结果数={}", request.collectionId(), request.func(), results.size());
            return List.copyOf(results);
        }
        return List.of();
    }

    // ---- 内部方法 ----

    @Nullable
    private String serializeFieldHints(@Nullable List<FieldHint> fieldHints) {
        if (fieldHints == null || fieldHints.isEmpty()) { return null; }
        try { return objectMapper.writeValueAsString(fieldHints); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("字段提示序列化失败: " + e.getMessage(), e); }
    }

    List<FieldHint> deserializeFieldHints(@Nullable String fieldHintsJson) {
        if (fieldHintsJson == null || fieldHintsJson.isBlank()) { return List.of(); }
        try { return objectMapper.readValue(fieldHintsJson, FIELD_HINT_LIST_TYPE); }
        catch (JsonProcessingException e) {
            log.warn("字段提示反序列化失败: json={}, error={}", fieldHintsJson, e.getMessage());
            return List.of();
        }
    }

    private String buildContextPrefix(Collection collection) {
        var sb = new StringBuilder("[").append(collection.name()).append("]");
        if (collection.description() != null && !collection.description().isBlank()) {
            sb.append(" ").append(collection.description());
        }
        return sb.toString();
    }

    @Nullable
    private String extractTitle(@Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) { return null; }
        try {
            var root = objectMapper.readTree(metadataJson);
            for (String key : List.of("title", "name", "书名", "标题")) {
                if (root.has(key) && root.get(key).isTextual()) {
                    return root.get(key).asText();
                }
            }
        } catch (JsonProcessingException e) {
            log.debug("提取标题失败: {}", e.getMessage());
        }
        return null;
    }

    private void cleanupFailedCollectionCreation(Collection collection, RuntimeException cause) {
        log.warn("集合创建失败，开始清理: id={}, name={}", collection.id(), collection.name(), cause);
        if (datastoreKnowledgeBaseProvisioner != null) {
            try { datastoreKnowledgeBaseProvisioner.deleteDefaultKnowledgeBase(collection); }
            catch (Exception cleanupEx) {
                log.error("清理 Datastore 默认知识库失败: datastoreId={}", collection.id(), cleanupEx);
                cause.addSuppressed(cleanupEx);
            }
        }
        try { collectionRepository.delete(collection.id()); }
        catch (Exception cleanupEx) {
            log.error("清理半成品集合失败: datastoreId={}", collection.id(), cleanupEx);
            cause.addSuppressed(cleanupEx);
        }
    }

    private static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}

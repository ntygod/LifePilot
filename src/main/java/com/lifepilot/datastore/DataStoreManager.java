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
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.Document;
import com.lifepilot.datastore.model.FieldNames;
import com.lifepilot.datastore.model.PropertyDefinition;
import com.lifepilot.datastore.model.QueryRequest;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.repository.DocumentRepository;
import com.lifepilot.datastore.sync.DataStoreKnowledgeSyncPublisher;
import com.lifepilot.datastore.sync.DatastoreKnowledgeBaseProvisioner;
import com.lifepilot.datastore.validation.PropertyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据存储管理门面 — 提供集合和文档的完整 CRUD 操作的统一入口。
 *
 * <p>封装限额检查、名称唯一性校验、属性校验、Generated Column 管理、
 * FTS5 同步等横切逻辑，对外暴露简洁的业务方法。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class DataStoreManager {

    private static final Logger log = LoggerFactory.getLogger(DataStoreManager.class);
    private static final TypeReference<List<PropertyDefinition>> PROP_LIST_TYPE =
            new TypeReference<>() {};

    private final CollectionRepository collectionRepository;
    private final DocumentRepository documentRepository;
    private final QueryEngine queryEngine;
    private final AggregationEngine aggregationEngine;
    private final PropertyValidator propertyValidator;
    private final DataStoreProperties properties;
    private final ObjectMapper objectMapper;
    @Nullable
    private final DataStoreKnowledgeSyncPublisher knowledgeSyncPublisher;
    @Nullable
    private final DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner;

    public DataStoreManager(CollectionRepository collectionRepository,
                            DocumentRepository documentRepository,
                            QueryEngine queryEngine,
                            AggregationEngine aggregationEngine,
                            PropertyValidator propertyValidator,
                            DataStoreProperties properties,
                            ObjectMapper objectMapper,
                            @Nullable DataStoreKnowledgeSyncPublisher knowledgeSyncPublisher,
                            @Nullable DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner) {
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.queryEngine = queryEngine;
        this.aggregationEngine = aggregationEngine;
        this.propertyValidator = propertyValidator;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.knowledgeSyncPublisher = knowledgeSyncPublisher;
        this.datastoreKnowledgeBaseProvisioner = datastoreKnowledgeBaseProvisioner;
    }

    // ---- 集合操作 ----

    @Transactional
    public Collection createCollection(String name, CollectionType type,
                                       @Nullable List<PropertyDefinition> propDefs,
                                       @Nullable String description,
                                       @Nullable String createdBy) {
        return createCollection(name, type, propDefs, description, createdBy, null);
    }

    @Transactional
    public Collection createCollection(String name, CollectionType type,
                                       @Nullable List<PropertyDefinition> propDefs,
                                       @Nullable String description,
                                       @Nullable String createdBy,
                                       @Nullable String projectionConfigJson) {
        int currentCount = collectionRepository.count();
        if (currentCount >= properties.getMaxCollections()) {
            throw new IllegalStateException("集合数量已达上限: " + properties.getMaxCollections());
        }
        if (collectionRepository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("集合名称已存在: " + name);
        }

        String propertiesJson = serializeProperties(propDefs);
        var collection = Collection.builder()
                .name(name).type(type).description(description)
                .propertiesJson(propertiesJson)
                .projectionConfigJson(Collection.normalizeProjectionConfig(projectionConfigJson))
                .createdBy(createdBy).build();

        String collectionId = collectionRepository.insert(collection);
        try {
            collection = collectionRepository.findById(collectionId).orElseThrow(
                    () -> new IllegalStateException("集合创建后查询失败: id=" + collectionId));

            if (propDefs != null && !propDefs.isEmpty()) {
                for (var prop : propDefs) {
                    if (prop.type().isIndexable()) {
                        collectionRepository.addGeneratedColumn(prop.name(), prop.type().toSqliteAffinity(), collectionId);
                    }
                }
            }
            log.info("集合创建完成: id={}, name={}, type={}, 属性数={}", collectionId, name, type,
                    propDefs != null ? propDefs.size() : 0);

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

    public List<Collection> listCollections(CollectionType type) {
        return List.copyOf(collectionRepository.findByType(type));
    }

    public Optional<Collection> findCollection(String name) {
        return collectionRepository.findByName(name);
    }

    public Optional<Collection> getCollection(String id) {
        return collectionRepository.findById(id);
    }

    public boolean updateCollection(String id, @Nullable String description,
                                    @Nullable String metadataJson) {
        return updateCollection(id, description, metadataJson, null);
    }

    @Transactional
    public boolean updateCollection(String id, @Nullable String description,
                                    @Nullable String metadataJson,
                                    @Nullable String projectionConfigJson) {
        var existing = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + id));
        boolean updated = collectionRepository.update(id,
                description != null ? description : existing.description(),
                Collection.normalizeProjectionConfig(
                        projectionConfigJson != null ? projectionConfigJson : existing.projectionConfigJson()),
                metadataJson != null ? metadataJson : existing.metadataJson());
        if (updated) {
            log.info("集合更新完成: id={}", id);
            if (projectionConfigJson != null
                    && !Collection.normalizeProjectionConfig(projectionConfigJson)
                    .equals(Collection.normalizeProjectionConfig(existing.projectionConfigJson()))
                    && knowledgeSyncPublisher != null) {
                knowledgeSyncPublisher.publishDatastoreResync(id);
            }
        }
        return updated;
    }

    @Transactional
    public boolean deleteCollection(String id) {
        var collection = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + id));

        List<PropertyDefinition> propDefs = deserializeProperties(collection.propertiesJson());
        if (!propDefs.isEmpty()) {
            List<String> indexable = propDefs.stream()
                    .filter(p -> p.type().isIndexable()).map(PropertyDefinition::name).toList();
            if (!indexable.isEmpty()) {
                collectionRepository.dropGeneratedColumns(id, indexable);
            }
        }

        documentRepository.deleteFtsByCollectionId(id);

        if (knowledgeSyncPublisher != null) {
            knowledgeSyncPublisher.publishDatastorePurge(id);
        }
        if (datastoreKnowledgeBaseProvisioner != null) {
            datastoreKnowledgeBaseProvisioner.deleteDefaultKnowledgeBase(collection);
            log.info("Datastore 内部知识库删除已提交: datastoreId={}, knowledgeBaseId={}",
                    collection.id(), collection.defaultKnowledgeBaseId());
        }
        boolean deleted = collectionRepository.delete(id);
        if (deleted) {
            log.info("集合删除完成: id={}, name={}", id, collection.name());
        }
        return deleted;
    }

    // ---- 文档操作 ----

    @Transactional
    public Document addDocument(String collectionId, String dataJson,
                                @Nullable String recordedAt) {
        var collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + collectionId));

        int docCount = documentRepository.countByCollection(collectionId);
        if (docCount >= properties.getMaxDocumentsPerCollection()) {
            throw new IllegalStateException("集合文档数量已达上限: " + properties.getMaxDocumentsPerCollection());
        }
        if (dataJson.getBytes(StandardCharsets.UTF_8).length > properties.getMaxDocumentSizeBytes()) {
            throw new IllegalArgumentException("文档大小超过限制: " + properties.getMaxDocumentSizeBytes() + " bytes");
        }

        try { objectMapper.readTree(dataJson); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("无效的 JSON 数据: " + e.getMessage(), e); }

        List<PropertyDefinition> propDefs = deserializeProperties(collection.propertiesJson());
        if (!propDefs.isEmpty()) {
            var errors = propertyValidator.validate(propDefs, dataJson);
            if (!errors.isEmpty()) { throw new IllegalArgumentException("属性校验失败: " + errors); }
        }

        if (collection.type() == CollectionType.METRIC) {
            if (recordedAt == null || recordedAt.isBlank()) {
                throw new IllegalArgumentException("METRIC 类型文档必须包含 recordedAt 字段");
            }
            try { DateTimeFormatter.ISO_DATE_TIME.parse(recordedAt); }
            catch (DateTimeParseException e) { throw new IllegalArgumentException("recordedAt 格式非法，期望 ISO 8601: " + recordedAt, e); }
        }

        var document = Document.builder().collectionId(collectionId).dataJson(dataJson).recordedAt(recordedAt).build();
        String documentId = documentRepository.insert(document);

        if (collection.type() == CollectionType.NOTE) {
            documentRepository.insertFts(documentId, extractFtsContent(dataJson));
        }

        log.info("文档添加完成: id={}, collectionId={}", documentId, collectionId);
        var created = documentRepository.findById(documentId).orElseThrow(
                () -> new IllegalStateException("文档创建后查询失败: id=" + documentId));
        if (knowledgeSyncPublisher != null) {
            knowledgeSyncPublisher.publishDocumentUpsert(collection, created);
        }
        return created;
    }

    /**
     * 向集合中添加文件引用文档。
     *
     * <p>当文件上传至知识库后，在 Datastore 中创建一条文件元数据记录，
     * 使 Datastore 能完整呈现"N 条结构化数据 + M 份参考文档"。</p>
     */
    @Transactional
    public Document addFileReference(String collectionId, String fileName, long fileSize,
                                     String mimeType, @Nullable String knowledgeDocumentId) {
        collectionRepository.findById(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + collectionId));
        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(java.util.Map.of(
                    "fileName", fileName, "fileSize", fileSize, "mimeType", mimeType));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文件元数据序列化失败", e);
        }

        var document = Document.builder()
                .collectionId(collectionId).dataJson(dataJson)
                .sourceType(Document.SOURCE_TYPE_FILE_REF)
                .knowledgeDocumentId(knowledgeDocumentId).build();
        String documentId = documentRepository.insert(document);
        log.info("文件引用文档添加完成: id={}, collectionId={}, fileName={}", documentId, collectionId, fileName);
        return documentRepository.findById(documentId).orElseThrow(
                () -> new IllegalStateException("文件引用文档创建后查询失败: id=" + documentId));
    }

    public Optional<Document> getDocument(String id) {
        return documentRepository.findById(id);
    }

    public List<Document> listDocuments(String collectionId, int offset, int limit) {
        collectionRepository.findById(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + collectionId));
        return List.copyOf(documentRepository.findByCollectionId(collectionId, offset, limit));
    }

    public List<Document> listDocuments(String collectionId) {
        collectionRepository.findById(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + collectionId));
        return List.copyOf(documentRepository.findByCollectionId(collectionId));
    }

    @Transactional
    public boolean updateDocument(String id, String dataJson) {
        var existingDoc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: id=" + id));
        if (dataJson.getBytes(StandardCharsets.UTF_8).length > properties.getMaxDocumentSizeBytes()) {
            throw new IllegalArgumentException("文档大小超过限制: " + properties.getMaxDocumentSizeBytes() + " bytes");
        }
        try { objectMapper.readTree(dataJson); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("无效的 JSON 数据: " + e.getMessage(), e); }

        var collection = collectionRepository.findById(existingDoc.collectionId())
                .orElseThrow(() -> new IllegalStateException("文档所属集合不存在: collectionId=" + existingDoc.collectionId()));
        List<PropertyDefinition> propDefs = deserializeProperties(collection.propertiesJson());
        if (!propDefs.isEmpty()) {
            var errors = propertyValidator.validate(propDefs, dataJson);
            if (!errors.isEmpty()) { throw new IllegalArgumentException("属性校验失败: " + errors); }
        }

        documentRepository.update(id, dataJson);
        if (collection.type() == CollectionType.NOTE) {
            documentRepository.updateFts(id, extractFtsContent(dataJson));
        }
        log.info("文档更新完成: id={}", id);
        if (knowledgeSyncPublisher != null) {
            var updatedDoc = documentRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("文档更新后查询失败: id=" + id));
            knowledgeSyncPublisher.publishDocumentUpsert(collection, updatedDoc);
        }
        return true;
    }

    @Transactional
    public boolean deleteDocument(String id) {
        var existingDoc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: id=" + id));
        var collection = collectionRepository.findById(existingDoc.collectionId());
        if (collection.isPresent() && collection.get().type() == CollectionType.NOTE) {
            documentRepository.deleteFts(id);
        }
        documentRepository.delete(id);
        log.info("文档删除完成: id={}", id);
        if (knowledgeSyncPublisher != null) {
            knowledgeSyncPublisher.publishDocumentDelete(existingDoc.collectionId(), id, existingDoc.updatedAt());
        }
        return true;
    }

    // ---- 查询操作 ----

    public List<Document> queryDocuments(QueryRequest request) {
        var collection = collectionRepository.findById(request.collectionId())
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + request.collectionId()));
        List<PropertyDefinition> propDefs = deserializeProperties(collection.propertiesJson());
        Set<String> indexedFields = propDefs.stream()
                .filter(p -> p.type().isIndexable()).map(PropertyDefinition::name).collect(Collectors.toSet());
        var sqlWithParams = queryEngine.buildQuery(request, indexedFields, FieldNames.safePrefix(request.collectionId()));
        var results = documentRepository.query(sqlWithParams.sql(), sqlWithParams.params());
        log.debug("文档查询完成: collectionId={}, 结果数={}", request.collectionId(), results.size());
        return List.copyOf(results);
    }

    public List<Document> searchDocuments(String collectionId, String query, int limit) {
        var collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + collectionId));
        if (collection.type() != CollectionType.NOTE) {
            throw new IllegalArgumentException("全文搜索仅支持 NOTE 类型集合");
        }
        var results = documentRepository.searchFts(collectionId, query, limit);
        log.debug("全文搜索完成: collectionId={}, query={}, 结果数={}", collectionId, query, results.size());
        return List.copyOf(results);
    }

    public List<AggregationResult> aggregate(AggregationRequest request) {
        var collection = collectionRepository.findById(request.collectionId())
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + request.collectionId()));
        if (collection.type() != CollectionType.METRIC) {
            throw new IllegalArgumentException("时序聚合仅支持 METRIC 类型集合");
        }
        List<PropertyDefinition> propDefs = deserializeProperties(collection.propertiesJson());
        Set<String> indexedFields = propDefs.stream()
                .filter(p -> p.type().isIndexable()).map(PropertyDefinition::name).collect(Collectors.toSet());
        var sqlWithParams = aggregationEngine.buildAggregation(request, indexedFields, FieldNames.safePrefix(request.collectionId()));
        var results = documentRepository.aggregate(sqlWithParams.sql(), sqlWithParams.params());
        log.debug("时序聚合完成: collectionId={}, func={}, 结果数={}", request.collectionId(), request.func(), results.size());
        return List.copyOf(results);
    }

    // ---- 内部方法 ----

    @Nullable
    private String serializeProperties(@Nullable List<PropertyDefinition> propDefs) {
        if (propDefs == null || propDefs.isEmpty()) { return null; }
        try { return objectMapper.writeValueAsString(propDefs); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("属性定义序列化失败: " + e.getMessage(), e); }
    }

    List<PropertyDefinition> deserializeProperties(@Nullable String propertiesJson) {
        if (propertiesJson == null || propertiesJson.isBlank()) { return List.of(); }
        try { return objectMapper.readValue(propertiesJson, PROP_LIST_TYPE); }
        catch (JsonProcessingException e) {
            log.warn("属性定义反序列化失败: json={}, error={}", propertiesJson, e.getMessage());
            return List.of();
        }
    }

    /** 清理创建失败的集合 — 补偿外部系统副作用 + 兜底删除集合记录（防止非事务场景残留）。 */
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

    private String extractFtsContent(String dataJson) {
        try {
            var root = objectMapper.readTree(dataJson);
            if (root != null && root.has("content") && root.get("content").isTextual()) {
                return root.get("content").asText();
            }
        } catch (JsonProcessingException e) {
            log.debug("FTS 内容提取时 JSON 解析失败，使用原始 JSON: {}", e.getMessage());
        }
        return dataJson;
    }

}

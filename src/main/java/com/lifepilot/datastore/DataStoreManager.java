package com.lifepilot.datastore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.config.DataStoreProperties;
import com.lifepilot.datastore.engine.AggregationEngine;
import com.lifepilot.datastore.engine.QueryEngine;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.Document;
import com.lifepilot.datastore.model.PropertyDefinition;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.repository.DocumentRepository;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<PropertyDefinition>> PROP_LIST_TYPE =
            new TypeReference<>() {};

    private final CollectionRepository collectionRepository;
    private final DocumentRepository documentRepository;
    private final QueryEngine queryEngine;
    private final AggregationEngine aggregationEngine;
    private final PropertyValidator propertyValidator;
    private final DataStoreProperties properties;

    public DataStoreManager(CollectionRepository collectionRepository,
                            DocumentRepository documentRepository,
                            QueryEngine queryEngine,
                            AggregationEngine aggregationEngine,
                            PropertyValidator propertyValidator,
                            DataStoreProperties properties) {
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.queryEngine = queryEngine;
        this.aggregationEngine = aggregationEngine;
        this.propertyValidator = propertyValidator;
        this.properties = properties;
    }

    // ---- 集合操作 ----

    /**
     * 创建新集合。
     *
     * <p>执行限额检查、名称唯一性校验、INSERT 集合记录，
     * 并为可索引属性创建 Generated Column 和 partial index。</p>
     *
     * @param name        集合名称
     * @param type        集合类型
     * @param propDefs    属性定义列表（可选）
     * @param description 集合描述（可选）
     * @param createdBy   创建者（可选）
     * @return 创建的集合
     * @throws IllegalStateException    集合数量已达上限
     * @throws IllegalArgumentException 集合名称已存在
     */
    @Transactional
    public Collection createCollection(String name, CollectionType type,
                                       @Nullable List<PropertyDefinition> propDefs,
                                       @Nullable String description,
                                       @Nullable String createdBy) {
        // 1. 限额检查
        int currentCount = collectionRepository.count();
        if (currentCount >= properties.getMaxCollections()) {
            throw new IllegalStateException("集合数量已达上限: " + properties.getMaxCollections());
        }

        // 2. 名称唯一性检查
        if (collectionRepository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("集合名称已存在: " + name);
        }

        // 3. 序列化属性定义
        String propertiesJson = serializeProperties(propDefs);

        // 4. 构建 Collection 并插入
        var collection = Collection.builder()
                .name(name)
                .type(type)
                .description(description)
                .propertiesJson(propertiesJson)
                .createdBy(createdBy)
                .build();

        String collectionId = collectionRepository.insert(collection);

        // 5. 为可索引属性创建 Generated Column
        if (propDefs != null && !propDefs.isEmpty()) {
            for (var prop : propDefs) {
                if (prop.type().isIndexable()) {
                    String affinity = prop.type().toSqliteAffinity();
                    collectionRepository.addGeneratedColumn(prop.name(), affinity, collectionId);
                }
            }
        }

        log.info("集合创建完成: id={}, name={}, type={}, 属性数={}", collectionId, name, type,
                propDefs != null ? propDefs.size() : 0);

        // 6. 返回完整集合
        return collectionRepository.findById(collectionId).orElseThrow(
                () -> new IllegalStateException("集合创建后查询失败: id=" + collectionId));
    }

    /**
     * 查询所有集合。
     *
     * @return 所有集合列表
     */
    public List<Collection> listCollections() {
        return List.copyOf(collectionRepository.findAll());
    }

    /**
     * 按类型查询集合。
     *
     * @param type 集合类型
     * @return 匹配类型的集合列表
     */
    public List<Collection> listCollections(CollectionType type) {
        return List.copyOf(collectionRepository.findByType(type));
    }

    /**
     * 按名称查找集合。
     *
     * @param name 集合名称
     * @return 集合 Optional
     */
    public Optional<Collection> findCollection(String name) {
        return collectionRepository.findByName(name);
    }

    /**
     * 更新集合的描述和元数据。
     *
     * @param id           集合 ID
     * @param description  新描述
     * @param metadataJson 新元数据 JSON
     * @return 是否更新成功
     */
    public boolean updateCollection(String id, @Nullable String description,
                                    @Nullable String metadataJson) {
        boolean updated = collectionRepository.update(id, description, metadataJson);
        if (updated) {
            log.info("集合更新完成: id={}", id);
        }
        return updated;
    }

    /**
     * 删除集合及其关联资源。
     *
     * <p>删除顺序：Generated Column 索引 → FTS5 条目 → 集合记录（CASCADE 删除文档）。</p>
     *
     * @param id 集合 ID
     * @return 是否删除成功
     */
    @Transactional
    public boolean deleteCollection(String id) {
        // 1. 查找集合
        var collection = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + id));

        // 2. 删除 Generated Column 索引
        List<PropertyDefinition> propDefs = deserializeProperties(collection.propertiesJson());
        if (!propDefs.isEmpty()) {
            List<String> propertyNames = propDefs.stream()
                    .filter(p -> p.type().isIndexable())
                    .map(PropertyDefinition::name)
                    .toList();
            if (!propertyNames.isEmpty()) {
                collectionRepository.dropGeneratedColumns(id, propertyNames);
            }
        }

        // 3. 清理 FTS5 条目（查询该集合所有文档 ID，逐个删除 FTS 记录）
        cleanupFtsEntries(id);

        // 4. 删除集合（CASCADE 自动删除文档）
        boolean deleted = collectionRepository.delete(id);
        if (deleted) {
            log.info("集合删除完成: id={}, name={}", id, collection.name());
        }
        return deleted;
    }

    // ---- 文档操作 ----

    /**
     * 向集合中添加文档。
     *
     * <p>执行集合存在性检查、文档数量限额、文档大小限额、JSON 合法性校验、
     * 属性定义校验、METRIC recordedAt 校验，然后 INSERT 文档并同步 FTS5（NOTE 类型）。</p>
     *
     * @param collectionId 目标集合 ID
     * @param dataJson     文档数据 JSON
     * @param recordedAt   记录时间（METRIC 类型必填，ISO 8601）
     * @return 创建的文档
     * @throws IllegalArgumentException 集合不存在、JSON 无效、属性校验失败、recordedAt 格式非法
     * @throws IllegalStateException    文档数量已达上限
     */
    public Document addDocument(String collectionId, String dataJson,
                                @Nullable String recordedAt) {
        // 1. 集合存在性检查
        var collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("集合不存在: id=" + collectionId));

        // 2. 文档数量限额检查
        int docCount = documentRepository.countByCollection(collectionId);
        if (docCount >= properties.getMaxDocumentsPerCollection()) {
            throw new IllegalStateException(
                    "集合文档数量已达上限: " + properties.getMaxDocumentsPerCollection());
        }

        // 3. 文档大小限额检查
        if (dataJson.getBytes(StandardCharsets.UTF_8).length > properties.getMaxDocumentSizeBytes()) {
            throw new IllegalArgumentException(
                    "文档大小超过限制: " + properties.getMaxDocumentSizeBytes() + " bytes");
        }

        // 4. JSON 合法性校验
        try {
            MAPPER.readTree(dataJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无效的 JSON 数据: " + e.getMessage(), e);
        }

        // 5. 属性定义校验
        List<PropertyDefinition> propDefs = deserializeProperties(collection.propertiesJson());
        if (!propDefs.isEmpty()) {
            var errors = propertyValidator.validate(propDefs, dataJson);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("属性校验失败: " + errors);
            }
        }

        // 6. METRIC 类型 recordedAt 校验
        if (collection.type() == CollectionType.METRIC) {
            if (recordedAt == null || recordedAt.isBlank()) {
                throw new IllegalArgumentException("METRIC 类型文档必须包含 recordedAt 字段");
            }
            try {
                DateTimeFormatter.ISO_DATE_TIME.parse(recordedAt);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "recordedAt 格式非法，期望 ISO 8601: " + recordedAt, e);
            }
        }

        // 7. 构建 Document 并插入
        var document = Document.builder()
                .collectionId(collectionId)
                .dataJson(dataJson)
                .recordedAt(recordedAt)
                .build();
        String documentId = documentRepository.insert(document);

        // 8. NOTE 类型同步 FTS5
        if (collection.type() == CollectionType.NOTE) {
            String ftsContent = extractFtsContent(dataJson);
            documentRepository.insertFts(documentId, ftsContent);
        }

        log.info("文档添加完成: id={}, collectionId={}", documentId, collectionId);

        // 9. 返回创建的文档
        return documentRepository.findById(documentId).orElseThrow(
                () -> new IllegalStateException("文档创建后查询失败: id=" + documentId));
    }

    /**
     * 根据 ID 获取文档。
     *
     * @param id 文档 ID
     * @return 文档 Optional
     */
    public Optional<Document> getDocument(String id) {
        return documentRepository.findById(id);
    }

    /**
     * 更新文档数据。
     *
     * <p>执行文档存在性检查、大小限额、JSON 合法性校验、属性定义校验，
     * 然后更新文档并同步 FTS5（NOTE 类型）。</p>
     *
     * @param id       文档 ID
     * @param dataJson 新的文档数据 JSON
     * @return 是否更新成功
     * @throws IllegalArgumentException 文档不存在、JSON 无效、属性校验失败
     */
    public boolean updateDocument(String id, String dataJson) {
        // 1. 文档存在性检查
        var existingDoc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: id=" + id));

        // 2. 文档大小限额检查
        if (dataJson.getBytes(StandardCharsets.UTF_8).length > properties.getMaxDocumentSizeBytes()) {
            throw new IllegalArgumentException(
                    "文档大小超过限制: " + properties.getMaxDocumentSizeBytes() + " bytes");
        }

        // 3. JSON 合法性校验
        try {
            MAPPER.readTree(dataJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无效的 JSON 数据: " + e.getMessage(), e);
        }

        // 4. 属性定义校验
        var collection = collectionRepository.findById(existingDoc.collectionId())
                .orElseThrow(() -> new IllegalStateException(
                        "文档所属集合不存在: collectionId=" + existingDoc.collectionId()));
        List<PropertyDefinition> propDefs = deserializeProperties(collection.propertiesJson());
        if (!propDefs.isEmpty()) {
            var errors = propertyValidator.validate(propDefs, dataJson);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("属性校验失败: " + errors);
            }
        }

        // 5. 更新文档
        documentRepository.update(id, dataJson);

        // 6. NOTE 类型同步 FTS5
        if (collection.type() == CollectionType.NOTE) {
            String ftsContent = extractFtsContent(dataJson);
            documentRepository.updateFts(id, ftsContent);
        }

        log.info("文档更新完成: id={}", id);
        return true;
    }

    /**
     * 删除文档。
     *
     * <p>删除文档记录，NOTE 类型同步清理 FTS5 索引。</p>
     *
     * @param id 文档 ID
     * @return 是否删除成功
     * @throws IllegalArgumentException 文档不存在
     */
    public boolean deleteDocument(String id) {
        // 1. 文档存在性检查
        var existingDoc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: id=" + id));

        // 2. 查找集合以判断类型
        var collection = collectionRepository.findById(existingDoc.collectionId());

        // 3. NOTE 类型清理 FTS5
        if (collection.isPresent() && collection.get().type() == CollectionType.NOTE) {
            documentRepository.deleteFts(id);
        }

        // 4. 删除文档
        documentRepository.delete(id);

        log.info("文档删除完成: id={}", id);
        return true;
    }

    // ---- 查询操作（Task 7.3 实现） ----

    // ---- 内部方法 ----

    /**
     * 序列化属性定义列表为 JSON。
     */
    @Nullable
    private String serializeProperties(@Nullable List<PropertyDefinition> propDefs) {
        if (propDefs == null || propDefs.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(propDefs);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("属性定义序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 反序列化属性定义 JSON 为列表。
     */
    List<PropertyDefinition> deserializeProperties(@Nullable String propertiesJson) {
        if (propertiesJson == null || propertiesJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(propertiesJson, PROP_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("属性定义反序列化失败: json={}, error={}", propertiesJson, e.getMessage());
            return List.of();
        }
    }

    /**
     * 清理指定集合所有文档的 FTS5 条目。
     */
    private void cleanupFtsEntries(String collectionId) {
        // 查询该集合所有文档的 ID
        var documents = documentRepository.query(
                "SELECT * FROM ds_documents WHERE collection_id = ?",
                new Object[]{collectionId});

        for (var doc : documents) {
            documentRepository.deleteFts(doc.id());
        }

        if (!documents.isEmpty()) {
            log.info("FTS5 条目清理完成: collectionId={}, 文档数={}", collectionId, documents.size());
        }
    }

    /**
     * 从文档 JSON 中提取 FTS5 索引内容。
     *
     * <p>优先提取 "content" 字段的文本值，若不存在则使用完整 JSON 字符串。</p>
     *
     * @param dataJson 文档数据 JSON
     * @return 用于 FTS5 索引的文本内容
     */
    private String extractFtsContent(String dataJson) {
        try {
            var root = MAPPER.readTree(dataJson);
            if (root != null && root.has("content") && root.get("content").isTextual()) {
                return root.get("content").asText();
            }
        } catch (JsonProcessingException e) {
            log.debug("FTS 内容提取时 JSON 解析失败，使用原始 JSON: {}", e.getMessage());
        }
        return dataJson;
    }
}

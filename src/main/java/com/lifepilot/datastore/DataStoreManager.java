package com.lifepilot.datastore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.config.DataStoreProperties;
import com.lifepilot.datastore.engine.AggregationEngine;
import com.lifepilot.datastore.engine.QueryEngine;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.PropertyDefinition;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.repository.DocumentRepository;
import com.lifepilot.datastore.validation.PropertyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;

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

    // ---- 文档操作（Task 7.2 实现） ----

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
}

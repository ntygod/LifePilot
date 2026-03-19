package com.lifepilot.datastore.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.QueryFilter;
import com.lifepilot.datastore.model.QueryRequest;
import com.lifepilot.datastore.model.SortDirection;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DataStore CRUD 泛型适配器 — 将领域实体的 CRUD 操作映射到 DataStore Collection/Document。
 *
 * <p>每个适配器实例绑定一个 Collection（按 collectionName 查找或自动创建），
 * 提供类型安全的 CRUD 方法。实体序列化/反序列化通过 Jackson ObjectMapper 委托。</p>
 *
 * <p>首次执行任何 CRUD 操作时，通过 {@link #ensureCollection()} 幂等地查找或创建
 * 对应的 Collection，并缓存 collectionId 供后续操作复用。</p>
 *
 * @param <T> 领域实体类型
 * @author zsg
 * @since 2026-03-16
 */
public class DataStoreCrudAdapter<T> {

    private static final Logger log = LoggerFactory.getLogger(DataStoreCrudAdapter.class);

    private final DataStoreManager dataStoreManager;
    private final ObjectMapper objectMapper;
    private final CrudAdapterConfig<T> config;

    /** 缓存的 collectionId，首次操作时初始化。 */
    private volatile String cachedCollectionId;

    public DataStoreCrudAdapter(DataStoreManager dataStoreManager,
                                ObjectMapper objectMapper,
                                CrudAdapterConfig<T> config) {
        this.dataStoreManager = dataStoreManager;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    // ==================== CRUD 操作 ====================

    /**
     * 创建实体，返回包含文档 ID 的 ToolResult。
     *
     * <p>首次调用时自动查找或创建对应的 Collection。
     * 序列化失败时返回 {@link ToolResult#error(String)}。</p>
     *
     * @param entity 领域实体
     * @return 成功时 data 包含 {@code documentId}；序列化失败时返回 error
     */
    public ToolResult create(T entity) {
        String json = serialize(entity);
        if (json == null) {
            return ToolResult.error("序列化失败: 无法将实体转换为 JSON");
        }
        String collectionId = ensureCollection();
        var doc = dataStoreManager.addDocument(collectionId, json, null);
        return ToolResult.success(Map.of("documentId", doc.id()));
    }

    /**
     * 按文档 ID 查找实体。
     *
     * <p>反序列化失败时记录 WARN 日志并返回 {@link Optional#empty()}。</p>
     *
     * @param documentId 文档 ID
     * @return 实体 Optional，文档不存在或反序列化失败时返回 empty
     */
    public Optional<T> findById(String documentId) {
        return dataStoreManager.getDocument(documentId)
                .flatMap(doc -> deserialize(doc.dataJson()));
    }

    /**
     * 按条件查询实体列表。
     *
     * <p>反序列化失败的文档会被跳过并记录 WARN 日志。</p>
     *
     * @param filters       过滤条件列表（可选）
     * @param sortField     排序字段（可选）
     * @param sortDirection 排序方向（可选）
     * @param offset        分页偏移
     * @param limit         每页数量
     * @return 反序列化成功的实体列表
     */
    public List<T> list(@Nullable List<QueryFilter> filters,
                        @Nullable String sortField,
                        @Nullable SortDirection sortDirection,
                        int offset, int limit) {
        String collectionId = ensureCollection();
        var request = new QueryRequest(
                collectionId,
                filters != null ? filters : List.of(),
                sortField,
                sortDirection,
                offset,
                limit
        );
        return dataStoreManager.queryDocuments(request).stream()
                .map(doc -> deserialize(doc.dataJson()))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 按条件查询实体列表，同时返回文档 ID。
     *
     * <p>返回 {@code Map.Entry<documentId, entity>} 列表，
     * 反序列化失败的文档会被跳过并记录 WARN 日志。</p>
     *
     * @param filters       过滤条件列表（可选）
     * @param sortField     排序字段（可选）
     * @param sortDirection 排序方向（可选）
     * @param offset        分页偏移
     * @param limit         每页数量
     * @return documentId → entity 的 Entry 列表
     */
    public List<Map.Entry<String, T>> listWithId(@Nullable List<QueryFilter> filters,
                                                  @Nullable String sortField,
                                                  @Nullable SortDirection sortDirection,
                                                  int offset, int limit) {
        String collectionId = ensureCollection();
        var request = new QueryRequest(
                collectionId,
                filters != null ? filters : List.of(),
                sortField,
                sortDirection,
                offset,
                limit
        );
        return dataStoreManager.queryDocuments(request).stream()
                .map(doc -> deserialize(doc.dataJson())
                        .map(entity -> (Map.Entry<String, T>) new AbstractMap.SimpleImmutableEntry<>(doc.id(), entity)))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 更新实体。
     *
     * <p>序列化失败时返回 {@link ToolResult#error(String)}。</p>
     *
     * @param documentId 文档 ID
     * @param entity     更新后的实体
     * @return 成功时 data 包含 {@code documentId}；序列化失败时返回 error
     */
    public ToolResult update(String documentId, T entity) {
        String json = serialize(entity);
        if (json == null) {
            return ToolResult.error("序列化失败: 无法将实体转换为 JSON");
        }
        dataStoreManager.updateDocument(documentId, json);
        return ToolResult.success(Map.of("documentId", documentId));
    }

    /**
     * 删除实体。
     *
     * @param documentId 文档 ID
     * @return 成功时 data 包含 {@code documentId}
     */
    public ToolResult delete(String documentId) {
        dataStoreManager.deleteDocument(documentId);
        return ToolResult.success(Map.of("documentId", documentId));
    }

    // ==================== 内部方法 ====================

    /**
     * 确保 Collection 存在，不存在则自动创建（幂等）。
     *
     * <p>使用 double-checked locking 模式：volatile 读 + synchronized 块，
     * 保证并发场景下只创建一个 Collection 并缓存 collectionId。</p>
     *
     * @return 集合 ID
     */
    String ensureCollection() {
        if (cachedCollectionId != null) {
            return cachedCollectionId;
        }
        synchronized (this) {
            if (cachedCollectionId != null) {
                return cachedCollectionId;
            }
            var existing = dataStoreManager.findCollection(config.collectionName());
            if (existing.isPresent()) {
                cachedCollectionId = existing.get().id();
            } else {
                var created = dataStoreManager.createCollection(
                        config.collectionName(),
                        config.collectionType(),
                        config.propertyDefinitions(),
                        config.description(),
                        "skill:" + config.domain()
                );
                cachedCollectionId = created.id();
            }
            return cachedCollectionId;
        }
    }

    /**
     * 将实体序列化为 JSON 字符串。
     *
     * @param entity 领域实体
     * @return JSON 字符串；序列化失败时返回 null 并记录 WARN 日志
     */
    @Nullable
    private String serialize(T entity) {
        try {
            return objectMapper.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            log.warn("实体序列化失败: entityClass={}, error={}", config.entityClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 将 JSON 字符串反序列化为实体。
     *
     * @param json JSON 字符串
     * @return 实体 Optional；反序列化失败时返回 empty 并记录 WARN 日志
     */
    private Optional<T> deserialize(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, config.entityClass()));
        } catch (JsonProcessingException e) {
            log.warn("实体反序列化失败: entityClass={}, error={}", config.entityClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }
}

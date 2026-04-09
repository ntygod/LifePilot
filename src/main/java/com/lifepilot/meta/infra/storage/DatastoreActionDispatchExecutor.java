package com.lifepilot.meta.infra.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.AggregateFunction;
import com.lifepilot.datastore.model.AggregationRequest;
import com.lifepilot.datastore.model.AggregationResult;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.Document;
import com.lifepilot.datastore.model.FilterOp;
import com.lifepilot.datastore.model.PropertyDefinition;
import com.lifepilot.datastore.model.QueryFilter;
import com.lifepilot.datastore.model.QueryRequest;
import com.lifepilot.datastore.model.SortDirection;
import com.lifepilot.datastore.model.TimeGranularity;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据存储 action 路由执行器。
 *
 * <p>统一承接集合与文档的 CRUD、查询和聚合操作。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class DatastoreActionDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(DatastoreActionDispatchExecutor.class);

    private final DataStoreManager dataStoreManager;
    private final ObjectMapper objectMapper;

    public DatastoreActionDispatchExecutor(DataStoreManager dataStoreManager, ObjectMapper objectMapper) {
        this.dataStoreManager = dataStoreManager;
        this.objectMapper = objectMapper;
        registerCollectionActions();
        registerDocumentActions();
        registerQueryActions();
    }

    // ==================== 集合操作 ====================

    private void registerCollectionActions() {
        register("create-collection", RiskLevel.LOW,
                ToolExecutionSemantics.of(PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("collections", "name")),
                this::handleCreateCollection);

        register("list-collections", RiskLevel.LOW,
                ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE),
                this::handleListCollections);

        register("update-collection", RiskLevel.LOW,
                ToolExecutionSemantics.of(PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("collections", "collectionName")),
                this::handleUpdateCollection);

        register("delete-collection", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("collections", "collectionName")),
                this::handleDeleteCollection);
    }

    private ToolResult handleCreateCollection(ToolInput input) {
        try {
            String name = input.getParam("name", String.class);
            CollectionType type = CollectionType.valueOf(input.getParam("type", String.class).toUpperCase());
            Object rawProperties = input.parameters().get("properties");
            String description = input.getOptionalParam("description", String.class).orElse(null);
            String projectionConfig = input.getOptionalParam("projectionConfig", String.class).orElse(null);

            var propDefs = parsePropertyDefinitions(rawProperties);
            Collection created = dataStoreManager.createCollection(name, type, propDefs, description, null, projectionConfig);
            var result = new HashMap<String, Object>();
            result.put("id", created.id());
            result.put("name", created.name());
            result.put("type", created.type().name());
            if (created.defaultKnowledgeBaseId() != null && !created.defaultKnowledgeBaseId().isBlank()) {
                result.put("defaultKnowledgeBaseId", created.defaultKnowledgeBaseId());
            }
            return ToolResult.success(Map.copyOf(result));
        } catch (Exception e) {
            log.error("创建集合失败: {}", e.getMessage(), e);
            return ToolResult.error("创建集合失败: " + e.getMessage());
        }
    }

    private ToolResult handleListCollections(ToolInput input) {
        try {
            String typeStr = input.getOptionalParam("type", String.class).orElse(null);
            List<Collection> collections = typeStr != null
                    ? dataStoreManager.listCollections(CollectionType.valueOf(typeStr.toUpperCase()))
                    : dataStoreManager.listCollections();
            List<Map<String, Object>> items = collections.stream().map(this::collectionToMap).toList();
            return ToolResult.success(Map.of("collections", items));
        } catch (Exception e) {
            log.error("查询集合列表失败: {}", e.getMessage(), e);
            return ToolResult.error("查询集合列表失败: " + e.getMessage());
        }
    }

    private ToolResult handleUpdateCollection(ToolInput input) {
        try {
            String collectionName = input.getParam("collectionName", String.class);
            Collection collection = dataStoreManager.findCollection(collectionName)
                    .orElseThrow(() -> new IllegalArgumentException("集合不存在: " + collectionName));
            String description = input.getOptionalParam("description", String.class).orElse(null);
            String metadataJson = input.getOptionalParam("metadataJson", String.class).orElse(null);
            String projectionConfig = input.getOptionalParam("projectionConfig", String.class).orElse(null);
            boolean success = dataStoreManager.updateCollection(collection.id(), description, metadataJson, projectionConfig);
            return success
                    ? ToolResult.success(Map.of("updated", true, "id", collection.id(), "name", collection.name()))
                    : ToolResult.error("集合更新失败: " + collectionName);
        } catch (Exception e) {
            log.error("更新集合失败: {}", e.getMessage(), e);
            return ToolResult.error("更新集合失败: " + e.getMessage());
        }
    }

    private ToolResult handleDeleteCollection(ToolInput input) {
        try {
            String collectionName = input.getParam("collectionName", String.class);
            Collection collection = dataStoreManager.findCollection(collectionName)
                    .orElseThrow(() -> new IllegalArgumentException("集合不存在: " + collectionName));
            boolean success = dataStoreManager.deleteCollection(collection.id());
            return success
                    ? ToolResult.success(Map.of("deleted", true, "id", collection.id(), "name", collection.name()))
                    : ToolResult.error("集合不存在: " + collectionName);
        } catch (Exception e) {
            log.error("删除集合失败: {}", e.getMessage(), e);
            return ToolResult.error("删除集合失败: " + e.getMessage());
        }
    }

    // ==================== 文档操作 ====================

    private void registerDocumentActions() {
        register("insert", RiskLevel.LOW,
                ToolExecutionSemantics.of(PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("collections", "collectionName")),
                this::handleInsert);

        register("update", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("documentIds", "documentId")),
                this::handleUpdate);

        register("delete", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("documentIds", "documentId")),
                this::handleDelete);
    }

    private ToolResult handleInsert(ToolInput input) {
        try {
            String collectionName = input.getParam("collectionName", String.class);
            String data = resolveJsonParam(input, "data");
            String recordedAt = input.getOptionalParam("recordedAt", String.class).orElse(null);
            Collection collection = dataStoreManager.findCollection(collectionName)
                    .orElseThrow(() -> new IllegalArgumentException("集合不存在: " + collectionName));
            Document doc = dataStoreManager.addDocument(collection.id(), data, recordedAt);
            return ToolResult.success(Map.of("id", doc.id(), "collectionId", doc.collectionId()));
        } catch (Exception e) {
            log.error("添加文档失败: {}", e.getMessage(), e);
            return ToolResult.error("添加文档失败: " + e.getMessage());
        }
    }

    private ToolResult handleUpdate(ToolInput input) {
        try {
            String documentId = input.getParam("documentId", String.class);
            String data = resolveJsonParam(input, "data");
            boolean success = dataStoreManager.updateDocument(documentId, data);
            return success ? ToolResult.success(Map.of("updated", true))
                    : ToolResult.error("文档不存在: id=" + documentId);
        } catch (Exception e) {
            log.error("更新文档失败: {}", e.getMessage(), e);
            return ToolResult.error("更新文档失败: " + e.getMessage());
        }
    }

    private ToolResult handleDelete(ToolInput input) {
        try {
            String documentId = input.getParam("documentId", String.class);
            boolean success = dataStoreManager.deleteDocument(documentId);
            return success ? ToolResult.success(Map.of("deleted", true))
                    : ToolResult.error("文档不存在: id=" + documentId);
        } catch (Exception e) {
            log.error("删除文档失败: {}", e.getMessage(), e);
            return ToolResult.error("删除文档失败: " + e.getMessage());
        }
    }

    // ==================== 查询操作 ====================

    private void registerQueryActions() {
        register("query", RiskLevel.LOW,
                ToolExecutionSemantics.of(PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.exactValues("collections", "collectionName")),
                this::handleQuery);

        register("aggregate", RiskLevel.LOW,
                ToolExecutionSemantics.of(PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.exactValues("collections", "collectionName")),
                this::handleAggregate);
    }

    private ToolResult handleQuery(ToolInput input) {
        try {
            String collectionName = input.getParam("collectionName", String.class);
            Collection collection = dataStoreManager.findCollection(collectionName)
                    .orElseThrow(() -> new IllegalArgumentException("集合不存在: " + collectionName));
            List<QueryFilter> filters = parseFilters(input);
            String sortField = input.getOptionalParam("sortField", String.class).orElse(null);
            String sortDirStr = input.getOptionalParam("sortDirection", String.class).orElse(null);
            SortDirection sortDirection = sortDirStr != null ? SortDirection.valueOf(sortDirStr.toUpperCase()) : null;
            int offset = input.getOptionalParam("offset", Number.class).map(Number::intValue).orElse(0);
            int limit = input.getOptionalParam("limit", Number.class).map(Number::intValue).orElse(20);
            String startTime = input.getOptionalParam("startTime", String.class).orElse(null);
            String endTime = input.getOptionalParam("endTime", String.class).orElse(null);

            QueryRequest request = new QueryRequest(collection.id(), filters, sortField, sortDirection, offset, limit, startTime, endTime);
            List<Document> docs = dataStoreManager.queryDocuments(request);
            List<Map<String, Object>> items = docs.stream().map(this::documentToMap).toList();
            return ToolResult.success(Map.of("documents", items));
        } catch (Exception e) {
            log.error("查询文档失败: {}", e.getMessage(), e);
            return ToolResult.error("查询文档失败: " + e.getMessage());
        }
    }

    private ToolResult handleAggregate(ToolInput input) {
        try {
            String collectionName = input.getParam("collectionName", String.class);
            String field = input.getParam("field", String.class);
            String funcStr = input.getParam("function", String.class);
            Collection collection = dataStoreManager.findCollection(collectionName)
                    .orElseThrow(() -> new IllegalArgumentException("集合不存在: " + collectionName));
            AggregateFunction func = AggregateFunction.valueOf(funcStr.toUpperCase());
            String groupByStr = input.getOptionalParam("groupBy", String.class).orElse(null);
            TimeGranularity groupBy = groupByStr != null ? TimeGranularity.valueOf(groupByStr.toUpperCase()) : null;
            String startTime = input.getOptionalParam("startTime", String.class).orElse(null);
            String endTime = input.getOptionalParam("endTime", String.class).orElse(null);

            AggregationRequest request = new AggregationRequest(collection.id(), field, func, groupBy, startTime, endTime);
            List<AggregationResult> results = dataStoreManager.aggregate(request);
            List<Map<String, Object>> items = results.stream()
                    .map(r -> Map.<String, Object>of("timeBucket", r.timeBucket(), "value", r.value())).toList();
            return ToolResult.success(Map.of("results", items));
        } catch (Exception e) {
            log.error("聚合查询失败: {}", e.getMessage(), e);
            return ToolResult.error("聚合查询失败: " + e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> collectionToMap(Collection col) {
        var map = new HashMap<String, Object>();
        map.put("id", col.id());
        map.put("name", col.name());
        map.put("type", col.type().name());
        if (col.description() != null) map.put("description", col.description());
        if (col.propertiesJson() != null) map.put("propertiesJson", col.propertiesJson());
        if (col.projectionConfigJson() != null) map.put("projectionConfigJson", col.projectionConfigJson());
        if (col.defaultKnowledgeBaseId() != null) map.put("defaultKnowledgeBaseId", col.defaultKnowledgeBaseId());
        map.put("createdAt", col.createdAt());
        map.put("updatedAt", col.updatedAt());
        return Map.copyOf(map);
    }

    private Map<String, Object> documentToMap(Document doc) {
        var map = new HashMap<String, Object>();
        map.put("id", doc.id());
        map.put("collectionId", doc.collectionId());
        map.put("dataJson", doc.dataJson());
        if (doc.recordedAt() != null) map.put("recordedAt", doc.recordedAt());
        if (doc.sourceType() != null) map.put("sourceType", doc.sourceType());
        if (doc.knowledgeDocumentId() != null) map.put("knowledgeDocumentId", doc.knowledgeDocumentId());
        map.put("createdAt", doc.createdAt());
        map.put("updatedAt", doc.updatedAt());
        return Map.copyOf(map);
    }

    private List<PropertyDefinition> parsePropertyDefinitions(Object rawProperties) throws JsonProcessingException {
        return switch (rawProperties) {
            case null -> null;
            case String propsJson -> objectMapper.readValue(propsJson, new TypeReference<>() {});
            case List<?> rawList -> objectMapper.convertValue(rawList, new TypeReference<>() {});
            default -> throw new IllegalArgumentException("properties 参数类型不匹配: 期望数组或 JSON 字符串");
        };
    }

    private String resolveJsonParam(ToolInput input, String paramName) throws JsonProcessingException {
        Object raw = input.parameters().get(paramName);
        if (raw == null) { throw new IllegalArgumentException("缺少必需参数: " + paramName); }
        return switch (raw) {
            case String s -> s;
            case Map<?, ?> m -> objectMapper.writeValueAsString(m);
            case List<?> l -> objectMapper.writeValueAsString(l);
            default -> raw.toString();
        };
    }

    @SuppressWarnings("unchecked")
    private List<QueryFilter> parseFilters(ToolInput input) throws JsonProcessingException {
        Object raw = input.parameters().get("filters");
        if (raw == null) { return new ArrayList<>(); }
        List<Map<String, Object>> filterMaps = switch (raw) {
            case List<?> list -> (List<Map<String, Object>>) list;
            case String json -> objectMapper.readValue(json, new TypeReference<>() {});
            default -> throw new IllegalArgumentException("filters 参数类型不匹配: 期望数组或 JSON 字符串");
        };
        var filters = new ArrayList<QueryFilter>();
        for (Map<String, Object> fm : filterMaps) {
            filters.add(new QueryFilter(
                    (String) fm.get("field"),
                    FilterOp.valueOf(((String) fm.get("op")).toUpperCase()),
                    fm.get("value")));
        }
        return filters;
    }
}

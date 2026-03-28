package com.lifepilot.meta.infra.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.*;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储工具提供者 — 注册 8 个数据存储 CRUD 工具到 DynamicToolRegistry。
 *
 * <p>所有工具归类为 {@link ToolCategory#STORAGE}，提供数据存储 Skill 定义蓝图。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class StorageToolProvider {

    private static final Logger log = LoggerFactory.getLogger(StorageToolProvider.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DataStoreManager dataStoreManager;

    public StorageToolProvider(DataStoreManager dataStoreManager) {
        this.dataStoreManager = dataStoreManager;
    }

    /**
     * 注册数据存储工具到 DynamicToolRegistry。
     *
     * @param toolRegistry 动态工具注册中心
     */
    public void registerTools(DynamicToolRegistry toolRegistry) {
        var tools = List.of(
                buildCreateCollectionTool(),
                buildListCollectionsTool(),
                buildDeleteCollectionTool(),
                buildAddDocumentTool(),
                buildQueryDocumentsTool(),
                buildUpdateDocumentTool(),
                buildDeleteDocumentTool(),
                buildAggregateTool()
        );
        tools.forEach(toolRegistry::registerBuiltinTool);
        log.info("数据存储 Skill 工具注册完成: count={}", tools.size());
    }

    // ---- 工具构建方法 ----

    /** 构建创建集合工具。 */
    private BuiltinTool buildCreateCollectionTool() {
        return BuiltinTool.builder()
                .id("datastore.create_collection")
                .name("创建集合")
                .description("创建新的数据集合，支持 DOCUMENT（结构化列表）、NOTE（笔记）、METRIC（时序指标）三种类型")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("name", "type"),
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "集合名称（唯一）"),
                                "type", Map.of("type", "string", "description", "集合类型: DOCUMENT/NOTE/METRIC"),
                                "properties", Map.of(
                                        "type", "string",
                                        "description", "属性定义 JSON 数组，如 [{\"name\":\"title\",\"type\":\"TEXT\",\"required\":true}]；type 推荐使用 TEXT/NUMBER/BOOLEAN/DATE/DATETIME/SELECT/MULTI_SELECT/URL/JSON"
                                ),
                                "description", Map.of("type", "string", "description", "集合描述"),
                                "projectionConfig", Map.of("type", "string", "description", "向量投影配置 JSON，可选")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("collections", "name")
                ))
                .executor(input -> {
                    try {
                        String name = input.getParam("name", String.class);
                        String typeStr = input.getParam("type", String.class);
                        CollectionType type = CollectionType.valueOf(typeStr.toUpperCase());
                        Object rawProperties = input.parameters().get("properties");
                        String description = input.getOptionalParam("description", String.class).orElse(null);
                        String projectionConfig = input.getOptionalParam("projectionConfig", String.class).orElse(null);

                        var propDefs = parsePropertyDefinitions(rawProperties);

                        Collection created = dataStoreManager.createCollection(
                                name, type, propDefs, description, null, projectionConfig);
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
                })
                .build();
    }

    /** 构建查询集合列表工具。 */
    private BuiltinTool buildListCollectionsTool() {
        return BuiltinTool.builder()
                .id("datastore.list_collections")
                .name("查询集合列表")
                .description("查询所有数据集合，可按类型过滤")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "type", Map.of("type", "string", "description", "类型过滤: DOCUMENT/NOTE/METRIC")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .executor(input -> {
                    try {
                        String typeStr = input.getOptionalParam("type", String.class).orElse(null);
                        List<Collection> collections = typeStr != null
                                ? dataStoreManager.listCollections(CollectionType.valueOf(typeStr.toUpperCase()))
                                : dataStoreManager.listCollections();

                        List<Map<String, Object>> items = collections.stream()
                                .map(this::collectionToMap)
                                .toList();
                        return ToolResult.success(Map.of("collections", items));
                    } catch (Exception e) {
                        log.error("查询集合列表失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询集合列表失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建删除集合工具。 */
    private BuiltinTool buildDeleteCollectionTool() {
        return BuiltinTool.builder()
                .id("datastore.delete_collection")
                .name("删除集合")
                .description("按集合名称删除整个数据集合及其全部文档")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("collectionName"),
                        "properties", Map.of(
                                "collectionName", Map.of("type", "string", "description", "目标集合名称")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("collections", "collectionName")
                ))
                .executor(input -> {
                    try {
                        String collectionName = input.getParam("collectionName", String.class);
                        Collection collection = dataStoreManager.findCollection(collectionName)
                                .orElseThrow(() -> new IllegalArgumentException("集合不存在: " + collectionName));
                        boolean success = dataStoreManager.deleteCollection(collection.id());
                        return success
                                ? ToolResult.success(Map.of(
                                        "deleted", true,
                                        "id", collection.id(),
                                        "name", collection.name()
                                ))
                                : ToolResult.error("集合不存在: " + collectionName);
                    } catch (Exception e) {
                        log.error("删除集合失败: {}", e.getMessage(), e);
                        return ToolResult.error("删除集合失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建添加文档工具。 */
    private BuiltinTool buildAddDocumentTool() {
        return BuiltinTool.builder()
                .id("datastore.add_document")
                .name("添加集合文档")
                .description("向指定集合添加 JSON 文档，通过集合名称定位")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("collectionName", "data"),
                        "properties", Map.of(
                                "collectionName", Map.of("type", "string", "description", "目标集合名称"),
                                "data", Map.of("type", "string", "description", "文档 JSON 数据"),
                                "recordedAt", Map.of("type", "string", "description", "记录时间 ISO 8601（METRIC 类型必填）")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("collections", "collectionName")
                ))
                .executor(input -> {
                    try {
                        String collectionName = input.getParam("collectionName", String.class);
                        String data = input.getParam("data", String.class);
                        String recordedAt = input.getOptionalParam("recordedAt", String.class).orElse(null);

                        Collection collection = dataStoreManager.findCollection(collectionName)
                                .orElseThrow(() -> new IllegalArgumentException("集合不存在: " + collectionName));

                        Document doc = dataStoreManager.addDocument(collection.id(), data, recordedAt);
                        return ToolResult.success(Map.of(
                                "id", doc.id(),
                                "collectionId", doc.collectionId()
                        ));
                    } catch (Exception e) {
                        log.error("添加文档失败: {}", e.getMessage(), e);
                        return ToolResult.error("添加文档失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询文档工具。 */
    private BuiltinTool buildQueryDocumentsTool() {
        return BuiltinTool.builder()
                .id("datastore.query_documents")
                .name("查询集合文档")
                .description("按条件查询集合中的文档，支持过滤、排序和分页。" +
                        "用于精确结构化条件查询，例如字段过滤、排序、分页、按 ID/状态/分类精确查找。")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("collectionName"),
                        "properties", Map.of(
                                "collectionName", Map.of("type", "string", "description", "目标集合名称"),
                                "filters", Map.of("type", "string", "description", "过滤条件 JSON 数组，如 [{\"field\":\"status\",\"op\":\"EQ\",\"value\":\"active\"}]"),
                                "sortField", Map.of("type", "string", "description", "排序字段"),
                                "sortDirection", Map.of("type", "string", "description", "排序方向: ASC/DESC"),
                                "offset", Map.of("type", "integer", "description", "分页偏移"),
                                "limit", Map.of("type", "integer", "description", "每页数量")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.exactValues("collections", "collectionName")
                ))
                .executor(input -> {
                    try {
                        String collectionName = input.getParam("collectionName", String.class);
                        Collection collection = dataStoreManager.findCollection(collectionName)
                                .orElseThrow(() -> new IllegalArgumentException("集合不存在: " + collectionName));

                        // 解析过滤条件
                        String filtersJson = input.getOptionalParam("filters", String.class).orElse(null);
                        List<QueryFilter> filters = new ArrayList<>();
                        if (filtersJson != null) {
                            List<Map<String, Object>> filterMaps = OBJECT_MAPPER.readValue(
                                    filtersJson, new TypeReference<>() {});
                            for (Map<String, Object> fm : filterMaps) {
                                filters.add(new QueryFilter(
                                        (String) fm.get("field"),
                                        FilterOp.valueOf(((String) fm.get("op")).toUpperCase()),
                                        fm.get("value")
                                ));
                            }
                        }

                        String sortField = input.getOptionalParam("sortField", String.class).orElse(null);
                        String sortDirStr = input.getOptionalParam("sortDirection", String.class).orElse(null);
                        SortDirection sortDirection = sortDirStr != null
                                ? SortDirection.valueOf(sortDirStr.toUpperCase()) : null;

                        int offset = input.getOptionalParam("offset", Number.class).map(Number::intValue).orElse(0);
                        int limit = input.getOptionalParam("limit", Number.class).map(Number::intValue).orElse(20);
                        String startTime = input.getOptionalParam("startTime", String.class).orElse(null);
                        String endTime = input.getOptionalParam("endTime", String.class).orElse(null);

                        QueryRequest request = new QueryRequest(
                                collection.id(), filters, sortField, sortDirection, offset, limit, startTime, endTime);
                        List<Document> docs = dataStoreManager.queryDocuments(request);

                        List<Map<String, Object>> items = docs.stream()
                                .map(this::documentToMap)
                                .toList();
                        return ToolResult.success(Map.of("documents", items));
                    } catch (Exception e) {
                        log.error("查询文档失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询文档失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建更新文档工具。 */
    private BuiltinTool buildUpdateDocumentTool() {
        return BuiltinTool.builder()
                .id("datastore.update_document")
                .name("更新集合文档")
                .description("根据文档 ID 更新文档数据")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("documentId", "data"),
                        "properties", Map.of(
                                "documentId", Map.of("type", "string", "description", "文档 ID"),
                                "data", Map.of("type", "string", "description", "新的文档 JSON 数据")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("documentIds", "documentId")
                ))
                .executor(input -> {
                    try {
                        String documentId = input.getParam("documentId", String.class);
                        String data = input.getParam("data", String.class);
                        boolean success = dataStoreManager.updateDocument(documentId, data);
                        return success
                                ? ToolResult.success(Map.of("updated", true))
                                : ToolResult.error("文档不存在: id=" + documentId);
                    } catch (Exception e) {
                        log.error("更新文档失败: {}", e.getMessage(), e);
                        return ToolResult.error("更新文档失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建删除文档工具。 */
    private BuiltinTool buildDeleteDocumentTool() {
        return BuiltinTool.builder()
                .id("datastore.delete_document")
                .name("删除文档")
                .description("根据文档 ID 删除文档")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("documentId"),
                        "properties", Map.of(
                                "documentId", Map.of("type", "string", "description", "文档 ID")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("documentIds", "documentId")
                ))
                .executor(input -> {
                    try {
                        String documentId = input.getParam("documentId", String.class);
                        boolean success = dataStoreManager.deleteDocument(documentId);
                        return success
                                ? ToolResult.success(Map.of("deleted", true))
                                : ToolResult.error("文档不存在: id=" + documentId);
                    } catch (Exception e) {
                        log.error("删除文档失败: {}", e.getMessage(), e);
                        return ToolResult.error("删除文档失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建聚合查询工具。 */
    private BuiltinTool buildAggregateTool() {
        return BuiltinTool.builder()
                .id("datastore.aggregate")
                .name("聚合查询")
                .description("对 METRIC 类型集合执行时序聚合查询，支持 SUM/AVG/MIN/MAX/COUNT 函数和按天/周/月分组")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("collectionName", "field", "function"),
                        "properties", Map.of(
                                "collectionName", Map.of("type", "string", "description", "目标集合名称（METRIC 类型）"),
                                "field", Map.of("type", "string", "description", "聚合字段名称"),
                                "function", Map.of("type", "string", "description", "聚合函数: SUM/AVG/MIN/MAX/COUNT"),
                                "groupBy", Map.of("type", "string", "description", "时间分组粒度: DAY/WEEK/MONTH"),
                                "startTime", Map.of("type", "string", "description", "时间范围起始 ISO 8601"),
                                "endTime", Map.of("type", "string", "description", "时间范围结束 ISO 8601")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.exactValues("collections", "collectionName")
                ))
                .executor(input -> {
                    try {
                        String collectionName = input.getParam("collectionName", String.class);
                        String field = input.getParam("field", String.class);
                        String funcStr = input.getParam("function", String.class);

                        Collection collection = dataStoreManager.findCollection(collectionName)
                                .orElseThrow(() -> new IllegalArgumentException("集合不存在: " + collectionName));

                        AggregateFunction func = AggregateFunction.valueOf(funcStr.toUpperCase());
                        String groupByStr = input.getOptionalParam("groupBy", String.class).orElse(null);
                        TimeGranularity groupBy = groupByStr != null
                                ? TimeGranularity.valueOf(groupByStr.toUpperCase()) : null;
                        String startTime = input.getOptionalParam("startTime", String.class).orElse(null);
                        String endTime = input.getOptionalParam("endTime", String.class).orElse(null);

                        AggregationRequest request = new AggregationRequest(
                                collection.id(), field, func, groupBy, startTime, endTime);
                        List<AggregationResult> results = dataStoreManager.aggregate(request);

                        List<Map<String, Object>> items = results.stream()
                                .map(r -> Map.<String, Object>of(
                                        "timeBucket", r.timeBucket(),
                                        "value", r.value()
                                ))
                                .toList();
                        return ToolResult.success(Map.of("results", items));
                    } catch (Exception e) {
                        log.error("聚合查询失败: {}", e.getMessage(), e);
                        return ToolResult.error("聚合查询失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // ---- 辅助方法 ----

    /** 将 Collection 转换为 Map 用于 ToolResult。 */
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

    private List<PropertyDefinition> parsePropertyDefinitions(Object rawProperties)
            throws JsonProcessingException {
        return switch (rawProperties) {
            case null -> null;
            case String propsJson -> OBJECT_MAPPER.readValue(
                    propsJson,
                    new TypeReference<>() {
                    });
            case List<?> rawList -> OBJECT_MAPPER.convertValue(
                    rawList,
                    new TypeReference<>() {
                    });
            default -> throw new IllegalArgumentException("properties 参数类型不匹配: 期望数组或 JSON 字符串");
        };
    }

    /** 将 Document 转换为 Map 用于 ToolResult。 */
    private Map<String, Object> documentToMap(Document doc) {
        var map = new HashMap<String, Object>();
        map.put("id", doc.id());
        map.put("collectionId", doc.collectionId());
        map.put("dataJson", doc.dataJson());
        if (doc.recordedAt() != null) map.put("recordedAt", doc.recordedAt());
        map.put("createdAt", doc.createdAt());
        map.put("updatedAt", doc.updatedAt());
        return Map.copyOf(map);
    }
}

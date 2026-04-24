package com.lifepilot.meta.infra.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储工具提供者。
 *
 * <p>集中管理统一的 {@code datastore} 元能力工具，通过 action 参数路由到
 * 集合 CRUD、文档 CRUD、查询和聚合操作。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class StorageToolProvider {

    private final DataStoreManager dataStoreManager;
    private final ObjectMapper objectMapper;

    public StorageToolProvider(DataStoreManager dataStoreManager, ObjectMapper objectMapper) {
        this.dataStoreManager = dataStoreManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建数据存储工具列表。
     *
     * <p><b>Plan 3 §5.2（2026-04-23）</b>：datastore 工具从 LLM 工具集下线。
     * 后端 {@link DataStoreManager} 及完整能力保留（spec §5.3），仅 LLM 不再接触
     * 这个工具；未来若决定复活（例如百万级结构化数据高频 CRUD 场景），恢复下面
     * 被注释的返回语句即可。</p>
     *
     * @return 空列表（Plan 3 下架）
     */
    public List<BuiltinTool> buildStorageTools() {
        return List.of();
        // Plan 3 之前的实现：
        // var executor = new DatastoreActionDispatchExecutor(dataStoreManager, objectMapper);
        // return List.of(buildDatastoreTool(executor));
    }

    /** 构建统一数据存储工具。 */
    private BuiltinTool buildDatastoreTool(DatastoreActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("datastore")
                .name("数据存储")
                .description("Execute structured datastore operations on collections. Actions: create-collection, list-collections, update-collection, delete-collection, add, get, query, update, delete, aggregate.")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(buildSchema()))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("collections", "name", "collectionName", "documentId")
                ))
                .tags(List.of("datastore", "storage", "database", "collection", "query", "crud", "aggregate", "document",
                        "add", "get", "insert", "update", "delete", "list_collections",
                        "create_collection", "update_collection", "delete_collection"))
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    private Map<String, Object> buildSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("create-collection", "list-collections", "update-collection", "delete-collection",
                        "add", "get", "query", "update", "delete", "aggregate"),
                "description", "数据存储操作类型"
        ));
        properties.put("name", Map.of("type", "string", "description", "action=create-collection 时的集合名称（唯一）"));
        properties.put("timeSeries", Map.of("type", "boolean", "description", "action=create-collection 时是否为时序集合（默认 false）"));
        properties.put("fieldHints", Map.ofEntries(
                Map.entry("type", "array"),
                Map.entry("description", "action=create-collection 时的字段提示数组，用于索引加速"),
                Map.entry("items", Map.of("type", "object", "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "type", Map.of("type", "string", "enum", List.of("TEXT", "NUMBER", "BOOLEAN")),
                        "description", Map.of("type", "string")
                )))));
        properties.put("description", Map.of("type", "string", "description", "action=create-collection/update-collection 时的集合描述"));
        properties.put("collectionName", Map.of("type", "string", "description", "目标集合名称；用于 delete-collection/add/query/aggregate"));
        properties.put("content", Map.of("type", "string", "description", "action=add/update 时的文档正文（自然语言富文本）"));
        properties.put("metadata", Map.of("type", "object", "description", "action=add/update 时的结构化元数据 JSON，用于排序/过滤/聚合"));
        properties.put("recordedAt", Map.of("type", "string", "description", "记录时间 ISO 8601（时序集合 add 时必填）"));
        properties.put("filters", Map.ofEntries(
                Map.entry("type", "array"),
                Map.entry("description", "action=query 时的过滤条件数组，每个元素包含 field/op/value 字段"),
                Map.entry("items", Map.of("type", "object", "properties", Map.of(
                        "field", Map.of("type", "string"),
                        "op", Map.of("type", "string"),
                        "value", Map.of("type", "string")
                )))));
        properties.put("sortField", Map.of("type", "string", "description", "action=query 时排序字段"));
        properties.put("sortDirection", Map.of("type", "string", "enum", List.of("ASC", "DESC"), "description", "action=query 时排序方向"));
        properties.put("offset", Map.of("type", "integer", "description", "action=query 时分页偏移"));
        properties.put("limit", Map.of("type", "integer", "description", "action=query 时每页数量"));
        properties.put("startTime", Map.of("type", "string", "description", "时间范围起始 ISO 8601；用于 query/aggregate"));
        properties.put("endTime", Map.of("type", "string", "description", "时间范围结束 ISO 8601；用于 query/aggregate"));
        properties.put("documentId", Map.of("type", "string", "description", "文档 ID；用于 update/delete"));
        properties.put("field", Map.of("type", "string", "description", "action=aggregate 时的聚合字段名称"));
        properties.put("function", Map.of("type", "string", "enum", List.of("SUM", "AVG", "MIN", "MAX", "COUNT"), "description", "action=aggregate 时的聚合函数"));
        properties.put("groupBy", Map.of("type", "string", "enum", List.of("DAY", "WEEK", "MONTH"), "description", "action=aggregate 时的时间分组粒度"));

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("required", List.of("action"));
        schema.put("properties", properties);
        return schema;
    }
}

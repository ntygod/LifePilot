package com.lifepilot.meta.infra.storage;

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

    public StorageToolProvider(DataStoreManager dataStoreManager) {
        this.dataStoreManager = dataStoreManager;
    }

    /**
     * 构建数据存储工具列表（1 个）。
     *
     * @return 数据存储工具列表
     */
    public List<BuiltinTool> buildStorageTools() {
        var executor = new DatastoreActionDispatchExecutor(dataStoreManager);
        return List.of(buildDatastoreTool(executor));
    }

    /** 构建统一数据存储工具。 */
    private BuiltinTool buildDatastoreTool(DatastoreActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("datastore")
                .name("数据存储")
                .description("管理结构化数据存储（Datastore）。通过 action 参数支持：" +
                        "create-collection=创建集合（DOCUMENT/NOTE/METRIC 类型），" +
                        "list-collections=列出所有集合，delete-collection=删除集合，" +
                        "insert=添加文档，query=按条件查询文档（支持过滤、排序、分页），" +
                        "update=更新文档，delete=删除文档，" +
                        "aggregate=对 METRIC 集合执行时序聚合（SUM/AVG/MIN/MAX/COUNT）。" +
                        "语义搜索绑定资料请用 knowledge.search，搜索知识实体请用 memory。")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(buildSchema()))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.MODIFY_DATASTORE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.exactValues("collections", "name", "collectionName", "documentId")
                ))
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    private Map<String, Object> buildSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("create-collection", "list-collections", "delete-collection", "insert", "query", "update", "delete", "aggregate"),
                "description", "数据存储操作类型"
        ));
        properties.put("name", Map.of("type", "string", "description", "action=create-collection 时的集合名称（唯一）"));
        properties.put("type", Map.of("type", "string", "description", "action=create-collection/list-collections 时的集合类型: DOCUMENT/NOTE/METRIC"));
        properties.put("properties", Map.of("type", "string", "description", "action=create-collection 时的属性定义 JSON 数组"));
        properties.put("description", Map.of("type", "string", "description", "action=create-collection 时的集合描述"));
        properties.put("projectionConfig", Map.of("type", "string", "description", "action=create-collection 时的向量投影配置 JSON"));
        properties.put("collectionName", Map.of("type", "string", "description", "目标集合名称；用于 delete-collection/insert/query/aggregate"));
        properties.put("data", Map.of("type", "string", "description", "文档 JSON 数据；用于 insert/update"));
        properties.put("recordedAt", Map.of("type", "string", "description", "记录时间 ISO 8601（METRIC 类型 insert 时必填）"));
        properties.put("filters", Map.of("type", "string", "description", "action=query 时的过滤条件 JSON 数组"));
        properties.put("sortField", Map.of("type", "string", "description", "action=query 时排序字段"));
        properties.put("sortDirection", Map.of("type", "string", "description", "action=query 时排序方向: ASC/DESC"));
        properties.put("offset", Map.of("type", "integer", "description", "action=query 时分页偏移"));
        properties.put("limit", Map.of("type", "integer", "description", "action=query 时每页数量"));
        properties.put("startTime", Map.of("type", "string", "description", "时间范围起始 ISO 8601；用于 query/aggregate"));
        properties.put("endTime", Map.of("type", "string", "description", "时间范围结束 ISO 8601；用于 query/aggregate"));
        properties.put("documentId", Map.of("type", "string", "description", "文档 ID；用于 update/delete"));
        properties.put("field", Map.of("type", "string", "description", "action=aggregate 时的聚合字段名称"));
        properties.put("function", Map.of("type", "string", "description", "action=aggregate 时的聚合函数: SUM/AVG/MIN/MAX/COUNT"));
        properties.put("groupBy", Map.of("type", "string", "description", "action=aggregate 时的时间分组粒度: DAY/WEEK/MONTH"));

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("required", List.of("action"));
        schema.put("properties", properties);
        return schema;
    }
}

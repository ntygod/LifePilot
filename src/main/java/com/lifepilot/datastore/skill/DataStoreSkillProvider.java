package com.lifepilot.datastore.skill;

import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.AggregateFunction;
import com.lifepilot.datastore.model.AggregationRequest;
import com.lifepilot.datastore.model.AggregationResult;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.Document;
import com.lifepilot.datastore.model.FilterOp;
import com.lifepilot.datastore.model.QueryFilter;
import com.lifepilot.datastore.model.QueryRequest;
import com.lifepilot.datastore.model.SortDirection;
import com.lifepilot.datastore.model.TimeGranularity;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据存储内置 Skill 提供者。
 *
 * <p>注册 7 个数据存储 CRUD 工具到 DynamicToolRegistry，
 * 提供数据存储 Skill 定义蓝图。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
@BuiltinSkill(id = "datastore", order = 5)
public class DataStoreSkillProvider implements BuiltinSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(DataStoreSkillProvider.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DataStoreManager dataStoreManager;
    private final PromptRegistry promptRegistry;

    public DataStoreSkillProvider(DataStoreManager dataStoreManager, PromptRegistry promptRegistry) {
        this.dataStoreManager = dataStoreManager;
        this.promptRegistry = promptRegistry;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("datastore")
                .name("数据存储")
                .description("通用数据存储管理，支持集合创建、文档 CRUD、动态查询、全文搜索和时序聚合")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions(promptRegistry.render("skill/datastore"))
                .suggestedTools(List.of(
                        "builtin.datastore.create_collection",
                        "builtin.datastore.list_collections",
                        "builtin.datastore.add_document",
                        "builtin.datastore.query_documents",
                        "builtin.datastore.update_document",
                        "builtin.datastore.delete_document",
                        "builtin.datastore.aggregate"
                ))
                .metadata(Map.of())
                .build();
    }

    @Override
    public void registerTools(DynamicToolRegistry toolRegistry) {
        toolRegistry.registerBuiltinTool(buildCreateCollectionTool());
        toolRegistry.registerBuiltinTool(buildListCollectionsTool());
        toolRegistry.registerBuiltinTool(buildAddDocumentTool());
        toolRegistry.registerBuiltinTool(buildQueryDocumentsTool());
        toolRegistry.registerBuiltinTool(buildUpdateDocumentTool());
        toolRegistry.registerBuiltinTool(buildDeleteDocumentTool());
        toolRegistry.registerBuiltinTool(buildAggregateTool());
        log.info("数据存储 Skill 工具注册完成: count=7");
    }

    // ---- 工具构建方法 ----

    /** 构建创建集合工具。 */
    private BuiltinTool buildCreateCollectionTool() {
        return BuiltinTool.builder()
                .id("builtin.datastore.create_collection")
                .name("创建集合")
                .description("创建新的数据集合，支持 DOCUMENT（结构化列表）、NOTE（笔记）、METRIC（时序指标）三种类型")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("name", "type"),
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "集合名称（唯一）"),
                                "type", Map.of("type", "string", "description", "集合类型: DOCUMENT/NOTE/METRIC"),
                                "properties", Map.of("type", "string", "description", "属性定义 JSON 数组，如 [{\"name\":\"title\",\"type\":\"TEXT\",\"required\":true}]"),
                                "description", Map.of("type", "string", "description", "集合描述")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(false)
                .executor(input -> {
                    try {
                        String name = input.getParam("name", String.class);
                        String typeStr = input.getParam("type", String.class);
                        CollectionType type = CollectionType.valueOf(typeStr.toUpperCase());
                        String propsJson = input.getOptionalParam("properties", String.class).orElse(null);
                        String description = input.getOptionalParam("description", String.class).orElse(null);

                        var propDefs = propsJson != null
                                ? OBJECT_MAPPER.readValue(propsJson, new TypeReference<List<com.lifepilot.datastore.model.PropertyDefinition>>() {})
                                : null;

                        Collection created = dataStoreManager.createCollection(name, type, propDefs, description, null);
                        return ToolResult.success(Map.of(
                                "id", created.id(),
                                "name", created.name(),
                                "type", created.type().name()
                        ));
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
                .id("builtin.datastore.list_collections")
                .name("查询集合列表")
                .description("查询所有数据集合，可按类型过滤")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "type", Map.of("type", "string", "description", "类型过滤: DOCUMENT/NOTE/METRIC")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
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

    /** 构建添加文档工具。 */
    private BuiltinTool buildAddDocumentTool() {
        return BuiltinTool.builder()
                .id("builtin.datastore.add_document")
                .name("添加文档")
                .description("向指定集合添加 JSON 文档，通过集合名称定位")
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
                .id("builtin.datastore.query_documents")
                .name("查询文档")
                .description("按条件查询集合中的文档，支持过滤、排序和分页")
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

                        QueryRequest request = new QueryRequest(
                                collection.id(), filters, sortField, sortDirection, offset, limit);
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
                .id("builtin.datastore.update_document")
                .name("更新文档")
                .description("根据文档 ID 更新文档数据")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("documentId", "data"),
                        "properties", Map.of(
                                "documentId", Map.of("type", "string", "description", "文档 ID"),
                                "data", Map.of("type", "string", "description", "新的文档 JSON 数据")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
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
                .id("builtin.datastore.delete_document")
                .name("删除文档")
                .description("根据文档 ID 删除文档")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("documentId"),
                        "properties", Map.of(
                                "documentId", Map.of("type", "string", "description", "文档 ID")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
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
                .id("builtin.datastore.aggregate")
                .name("聚合查询")
                .description("对 METRIC 类型集合执行时序聚合查询，支持 SUM/AVG/MIN/MAX/COUNT 函数和按天/周/月分组")
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
        map.put("createdAt", col.createdAt());
        map.put("updatedAt", col.updatedAt());
        return Map.copyOf(map);
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

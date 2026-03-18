package com.lifepilot.skill.builtin.memory;

import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.prompt.PromptRegistry;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 记忆管理内置 Skill 提供者。
 *
 * <p>注册 6 个记忆管理工具到 DynamicToolRegistry，
 * 提供记忆搜索、创建、标签、时间线、关联查询和时间点记忆查询能力。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@BuiltinSkill(id = "memory", order = 40)
public class MemorySkillProvider implements BuiltinSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(MemorySkillProvider.class);

    private final HybridRetriever hybridRetriever;
    private final SemanticMemory semanticMemory;
    private final PromptRegistry promptRegistry;

    public MemorySkillProvider(HybridRetriever hybridRetriever,
                               SemanticMemory semanticMemory,
                               PromptRegistry promptRegistry) {
        this.hybridRetriever = hybridRetriever;
        this.semanticMemory = semanticMemory;
        this.promptRegistry = promptRegistry;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("memory")
                .name("记忆管理")
                .description("管理长期记忆，支持搜索、创建、标签、时间线和关联查询")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions(promptRegistry.render("skill/memory"))
                .suggestedTools(List.of(
                        "builtin.memory.search",
                        "builtin.memory.create",
                        "builtin.memory.tag",
                        "builtin.memory.timeline",
                        "builtin.memory.relate",
                        "builtin.memory.query-at-time"
                ))
                .metadata(Map.of())
                .build();
    }

    @Override
    public void registerTools(DynamicToolRegistry toolRegistry) {
        toolRegistry.registerBuiltinTool(buildSearchTool());
        toolRegistry.registerBuiltinTool(buildCreateTool());
        toolRegistry.registerBuiltinTool(buildTagTool());
        toolRegistry.registerBuiltinTool(buildTimelineTool());
        toolRegistry.registerBuiltinTool(buildRelateTool());
        toolRegistry.registerBuiltinTool(buildQueryAtTimeTool());
        log.info("记忆 Skill 工具注册完成: count=6");
    }

    // ---- 工具构建方法 ----

    /** 构建记忆搜索工具。 */
    private BuiltinTool buildSearchTool() {
        return BuiltinTool.builder()
                .id("builtin.memory.search")
                .name("搜索记忆")
                .description("通过混合检索搜索记忆，支持向量语义匹配、全文搜索和图遍历")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词或语义描述"),
                                "topK", Map.of("type", "integer", "description", "返回结果数量，默认 5")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String query = input.getParam("query", String.class);
                        int topK = input.getOptionalParam("topK", Integer.class).orElse(5);
                        List<RetrievalResult> results = hybridRetriever.retrieve(query, topK, RetrievalWeights.DEFAULT);
                        List<Map<String, Object>> items = results.stream()
                                .map(this::retrievalResultToMap)
                                .toList();
                        return ToolResult.success(Map.of("results", items, "count", items.size()));
                    } catch (Exception e) {
                        log.error("搜索记忆失败: {}", e.getMessage(), e);
                        return ToolResult.error("搜索记忆失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建创建记忆工具。 */
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("builtin.memory.create")
                .name("创建记忆")
                .description("创建新的记忆实体，支持指定类型、名称、描述和属性")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("name", "entityType"),
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "实体名称"),
                                "entityType", Map.of("type", "string", "description",
                                        "实体类型: PERSON/ORGANIZATION/PLACE/EVENT/PROJECT/TOPIC/PREFERENCE/HABIT/GOAL/SKILL/CUSTOM"),
                                "description", Map.of("type", "string", "description", "实体描述"),
                                "conversationId", Map.of("type", "string", "description", "来源会话 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String name = input.getParam("name", String.class);
                        String typeStr = input.getParam("entityType", String.class);
                        String description = input.getOptionalParam("description", String.class).orElse(null);
                        String conversationId = input.getOptionalParam("conversationId", String.class).orElse(null);

                        EntityType entityType = EntityType.valueOf(typeStr.toUpperCase());
                        var now = Instant.now();
                        var incoming = new TemporalEntity(
                                null, entityType, name, description,
                                Map.of(), 1, true,
                                now, null, conversationId,
                                1.0f, 0.5f, 0, null, now, now);
                        var created = semanticMemory.upsertWithConflictDetection(incoming, conversationId);
                        return ToolResult.success(Map.of(
                                "id", created.id(),
                                "name", created.name(),
                                "type", created.type().name(),
                                "version", created.version()));
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error("无效的实体类型: " + e.getMessage());
                    } catch (Exception e) {
                        log.error("创建记忆失败: {}", e.getMessage(), e);
                        return ToolResult.error("创建记忆失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建标签关系工具。 */
    private BuiltinTool buildTagTool() {
        return BuiltinTool.builder()
                .id("builtin.memory.tag")
                .name("添加记忆标签")
                .description("为记忆实体添加关系标签，建立实体间的关联")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("sourceEntityId", "targetEntityId", "relationType"),
                        "properties", Map.of(
                                "sourceEntityId", Map.of("type", "string", "description", "源实体 ID"),
                                "targetEntityId", Map.of("type", "string", "description", "目标实体 ID"),
                                "relationType", Map.of("type", "string", "description", "关系类型（如 RELATED_TO, BELONGS_TO, CAUSED_BY）"),
                                "strength", Map.of("type", "number", "description", "关系强度 0.0-1.0，默认 0.5"),
                                "conversationId", Map.of("type", "string", "description", "来源会话 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String sourceId = input.getParam("sourceEntityId", String.class);
                        String targetId = input.getParam("targetEntityId", String.class);
                        String relationType = input.getParam("relationType", String.class);
                        float strength = input.getOptionalParam("strength", Number.class)
                                .map(Number::floatValue).orElse(0.5f);
                        String conversationId = input.getOptionalParam("conversationId", String.class).orElse(null);

                        var now = Instant.now();
                        var relation = new TemporalRelation(
                                UUID.randomUUID().toString(),
                                sourceId, targetId, relationType, strength,
                                null, now, null, conversationId, now);
                        semanticMemory.addRelation(relation);
                        return ToolResult.success(Map.of(
                                "id", relation.id(),
                                "relationType", relationType,
                                "sourceEntityId", sourceId,
                                "targetEntityId", targetId));
                    } catch (Exception e) {
                        log.error("添加记忆标签失败: {}", e.getMessage(), e);
                        return ToolResult.error("添加记忆标签失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建时间线查询工具。 */
    private BuiltinTool buildTimelineTool() {
        return BuiltinTool.builder()
                .id("builtin.memory.timeline")
                .name("时间线查询")
                .description("查询指定时间点有效的所有记忆实体快照")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "timePoint", Map.of("type", "string", "description",
                                        "查询时间点 ISO 8601 格式，默认当前时间")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String timeStr = input.getOptionalParam("timePoint", String.class).orElse(null);
                        Instant point = timeStr != null ? Instant.parse(timeStr) : Instant.now();
                        List<TemporalEntity> entities = semanticMemory.queryAtTime(point);
                        List<Map<String, Object>> items = entities.stream()
                                .map(this::entityToMap)
                                .toList();
                        return ToolResult.success(Map.of("entities", items, "count", items.size(),
                                "timePoint", point.toString()));
                    } catch (Exception e) {
                        log.error("时间线查询失败: {}", e.getMessage(), e);
                        return ToolResult.error("时间线查询失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建关联查询工具。 */
    private BuiltinTool buildRelateTool() {
        return BuiltinTool.builder()
                .id("builtin.memory.relate")
                .name("关联查询")
                .description("查找与指定记忆实体相关联的其他实体，支持多跳遍历")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("entityId"),
                        "properties", Map.of(
                                "entityId", Map.of("type", "string", "description", "实体 ID"),
                                "maxDepth", Map.of("type", "integer", "description", "最大遍历深度，默认 2")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String entityId = input.getParam("entityId", String.class);
                        int maxDepth = input.getOptionalParam("maxDepth", Integer.class).orElse(2);
                        List<TemporalEntity> related = semanticMemory.findRelated(entityId, maxDepth);
                        List<Map<String, Object>> items = related.stream()
                                .map(this::entityToMap)
                                .toList();
                        return ToolResult.success(Map.of("related", items, "count", items.size(),
                                "entityId", entityId, "maxDepth", maxDepth));
                    } catch (Exception e) {
                        log.error("关联查询失败: {}", e.getMessage(), e);
                        return ToolResult.error("关联查询失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建时间点记忆查询工具。 */
    private BuiltinTool buildQueryAtTimeTool() {
        return BuiltinTool.builder()
                .id("builtin.memory.query-at-time")
                .name("时间点记忆查询")
                .description("查询指定时间点有效的记忆实体，支持按实体类型过滤")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("timestamp"),
                        "properties", Map.of(
                                "timestamp", Map.of("type", "string", "description", "ISO 8601 格式时间戳"),
                                "entityType", Map.of("type", "string", "description", "过滤实体类型")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String timestamp = input.getParam("timestamp", String.class);
                        Instant instant = Instant.parse(timestamp);
                        List<TemporalEntity> entities = semanticMemory.queryAtTime(instant);

                        // 可选按 entityType 过滤
                        String entityTypeStr = input.getOptionalParam("entityType", String.class).orElse(null);
                        if (entityTypeStr != null) {
                            EntityType filterType = EntityType.valueOf(entityTypeStr.toUpperCase());
                            entities = entities.stream()
                                    .filter(e -> e.type() == filterType)
                                    .toList();
                        }

                        List<Map<String, Object>> items = entities.stream()
                                .map(this::entityToMap)
                                .toList();
                        return ToolResult.success(Map.of(
                                "entities", items,
                                "count", items.size(),
                                "timestamp", timestamp));
                    } catch (DateTimeParseException e) {
                        return ToolResult.error("无效的时间戳格式");
                    } catch (Exception e) {
                        log.error("时间点记忆查询失败: {}", e.getMessage(), e);
                        return ToolResult.error("时间点记忆查询失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // ---- 辅助方法 ----

    /** 将 RetrievalResult 转换为 Map 用于 ToolResult。 */
    private Map<String, Object> retrievalResultToMap(RetrievalResult result) {
        var map = new HashMap<String, Object>();
        map.put("entityId", result.entityId());
        map.put("entityType", result.entityType());
        map.put("name", result.name());
        if (result.description() != null) map.put("description", result.description());
        map.put("score", result.fusedScore());
        map.put("sourcePath", result.sourcePath());
        return Map.copyOf(map);
    }

    /** 将 TemporalEntity 转换为 Map 用于 ToolResult。 */
    private Map<String, Object> entityToMap(TemporalEntity entity) {
        var map = new HashMap<String, Object>();
        map.put("id", entity.id());
        map.put("type", entity.type().name());
        map.put("name", entity.name());
        if (entity.description() != null) map.put("description", entity.description());
        map.put("version", entity.version());
        map.put("validFrom", entity.validFrom().toString());
        if (entity.validTo() != null) map.put("validTo", entity.validTo().toString());
        map.put("importanceScore", entity.importanceScore());
        return Map.copyOf(map);
    }
}

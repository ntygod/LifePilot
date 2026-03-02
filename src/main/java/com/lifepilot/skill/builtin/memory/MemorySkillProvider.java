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
import com.lifepilot.skill.model.*;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 记忆管理内置 Skill 提供者。
 *
 * <p>注册 5 个记忆管理工具到 DynamicToolRegistry，
 * 提供记忆搜索、创建、标签、时间线和关联查询能力。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@BuiltinSkill(id = "memory", order = 40)
public class MemorySkillProvider implements BuiltinSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(MemorySkillProvider.class);

    private static final String SYSTEM_PROMPT = """
            角色：记忆管理助手
            
            核心职责：
            帮助用户管理长期记忆，通过混合检索和关联查询构建知识图谱，支持语义搜索和时间线查询。
            
            能力范围：
            1. 记忆搜索
               - 混合检索：向量语义匹配 + 全文搜索 + 图遍历
               - 支持自然语言查询，理解用户意图
               - 返回相关性排序的结果列表
            2. 记忆创建
               - 实体类型：PERSON（人物）、ORGANIZATION（组织）、PLACE（地点）、EVENT（事件）、
                           PROJECT（项目）、TOPIC（主题）、PREFERENCE（偏好）、HABIT（习惯）、
                           GOAL（目标）、SKILL（技能）、CUSTOM（自定义）
               - 设置名称、描述、来源会话ID
            3. 关系管理
               - 添加标签关系：建立实体间的关联（如 RELATED_TO, BELONGS_TO, CAUSED_BY）
               - 设置关系强度（0.0-1.0）
            4. 时间线查询
               - 查询指定时间点有效的所有记忆实体快照
               - 支持历史回溯和版本查询
            5. 关联查询
               - 查找与指定实体相关联的其他实体
               - 支持多跳遍历（默认深度2）
            
            交互原则：
            - 搜索时提供相关性评分和来源路径
            - 创建记忆时明确实体类型和描述
            - 建立关系时说明关系类型和强度
            - 使用清晰、结构化的方式展示结果
            
            回复风格：专业、结构化、知识导向
            """;

    private final HybridRetriever hybridRetriever;
    private final SemanticMemory semanticMemory;

    public MemorySkillProvider(HybridRetriever hybridRetriever, SemanticMemory semanticMemory) {
        this.hybridRetriever = hybridRetriever;
        this.semanticMemory = semanticMemory;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("memory")
                .name("记忆管理")
                .description("管理长期记忆，支持搜索、创建、标签、时间线和关联查询")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .systemPrompt(SYSTEM_PROMPT)
                .allowedTools(List.of(
                        "builtin.memory.search",
                        "builtin.memory.create",
                        "builtin.memory.tag",
                        "builtin.memory.timeline",
                        "builtin.memory.relate"
                ))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(new MemoryAccessPolicy(
                        List.of(
                                new MemoryReadPermission("L1_WORKING", List.of("*"), null),
                                new MemoryReadPermission("L2_EPISODIC", List.of("*"), null),
                                new MemoryReadPermission("L3_SEMANTIC", List.of("*"), null)
                        ),
                        List.of(
                                new MemoryWritePermission("L2_EPISODIC", List.of("MEMO", "TAG"), false),
                                new MemoryWritePermission("L3_SEMANTIC", List.of("RELATION"), false)
                        )
                ))
                .budget(new SkillBudget(6000, 10, 90, 30))
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
        log.info("记忆 Skill 工具注册完成: count=5");
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

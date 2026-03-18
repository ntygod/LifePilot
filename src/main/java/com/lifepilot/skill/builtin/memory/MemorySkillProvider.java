package com.lifepilot.skill.builtin.memory;

import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
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
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 记忆管理内置 Skill 提供者。
 *
 * <p>注册 7 个记忆管理工具到 DynamicToolRegistry：
 * search / recall / search-docs / create / update / delete / tag。</p>
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
    @Nullable private final EpisodicMemory episodicMemory;
    @Nullable private final DocumentRetriever documentRetriever;
    @Nullable private final SessionKnowledgeBaseRepository sessionKbRepo;
    @Nullable private final MemoryProperties memoryProperties;

    public MemorySkillProvider(HybridRetriever hybridRetriever,
                               SemanticMemory semanticMemory,
                               PromptRegistry promptRegistry,
                               @Nullable EpisodicMemory episodicMemory,
                               @Nullable DocumentRetriever documentRetriever,
                               @Nullable SessionKnowledgeBaseRepository sessionKbRepo,
                               @Nullable MemoryProperties memoryProperties) {
        this.hybridRetriever = hybridRetriever;
        this.semanticMemory = semanticMemory;
        this.promptRegistry = promptRegistry;
        this.episodicMemory = episodicMemory;
        this.documentRetriever = documentRetriever;
        this.sessionKbRepo = sessionKbRepo;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public SkillDefinition provide() {
        var tools = new ArrayList<>(List.of(
                "builtin.memory.search",
                "builtin.memory.create",
                "builtin.memory.update",
                "builtin.memory.delete",
                "builtin.memory.tag"
        ));
        if (episodicMemory != null) {
            tools.add("builtin.memory.recall");
        }
        if (documentRetriever != null && sessionKbRepo != null) {
            tools.add("builtin.memory.search-docs");
        }
        return SkillDefinition.builder()
                .id("memory")
                .name("记忆管理")
                .description("管理长期记忆，支持搜索、回忆、知识库检索、创建、更新、删除和标签")
                .version("2.0.0")
                .source(new SkillSource.Builtin())
                .instructions(promptRegistry.render("skill/memory"))
                .suggestedTools(List.copyOf(tools))
                .metadata(Map.of())
                .build();
    }

    @Override
    public void registerTools(DynamicToolRegistry toolRegistry) {
        toolRegistry.registerBuiltinTool(buildSearchTool());
        toolRegistry.registerBuiltinTool(buildCreateTool());
        toolRegistry.registerBuiltinTool(buildUpdateTool());
        toolRegistry.registerBuiltinTool(buildDeleteTool());
        toolRegistry.registerBuiltinTool(buildTagTool());
        int count = 5;
        if (episodicMemory != null) {
            toolRegistry.registerBuiltinTool(buildRecallTool());
            count++;
        }
        if (documentRetriever != null && sessionKbRepo != null) {
            toolRegistry.registerBuiltinTool(buildSearchDocsTool());
            count++;
        }
        log.info("记忆 Skill 工具注册完成: count={}", count);
    }

    // ---- 工具构建方法 ----

    /** 构建记忆搜索工具 — 搜索知识实体。 */
    private BuiltinTool buildSearchTool() {
        int defaultTopK = memoryProperties != null
                ? memoryProperties.getAgenticTool().getDefaultTopK() : 10;
        return BuiltinTool.builder()
                .id("builtin.memory.search")
                .name("搜索记忆")
                .description("搜索知识实体（人物、地点、事件、偏好、习惯、目标等）。" +
                        "当用户提到具体的人名、地名、事件名，或询问你记住的偏好/习惯时使用。" +
                        "不要用于搜索历史对话内容（用 recall）或知识库文档（用 search-docs）。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词或语义描述"),
                                "topK", Map.of("type", "integer", "description", "返回结果数量，默认 " + defaultTopK)
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String query = input.getParam("query", String.class);
                        int topK = input.getOptionalParam("topK", Integer.class).orElse(defaultTopK);
                        List<RetrievalResult> results = hybridRetriever.retrieve(query, topK, RetrievalWeights.DEFAULT);
                        // 更新 accessCount
                        if (!results.isEmpty()) {
                            hybridRetriever.updateAccessCounts(results);
                        }
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

    /** 构建跨会话对话回忆工具。 */
    private BuiltinTool buildRecallTool() {
        int defaultTopK = memoryProperties != null
                ? memoryProperties.getAgenticTool().getDefaultTopK() : 10;
        return BuiltinTool.builder()
                .id("builtin.memory.recall")
                .name("回忆对话")
                .description("回忆历史对话片段（跨会话）。" +
                        "当用户说'我之前说过...'、'上次我们聊到...'、'你还记得我说的...'时使用。" +
                        "不要用于搜索知识实体（用 search）或知识库文档（用 search-docs）。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词"),
                                "top_k", Map.of("type", "integer", "description", "返回数量，默认 " + defaultTopK)
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String query = input.getParam("query", String.class);
                        int topK = input.getOptionalParam("top_k", Integer.class).orElse(defaultTopK);
                        String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
                        if (sessionId == null) {
                            return ToolResult.error("无法获取当前会话 ID");
                        }
                        List<MessageRecord> messages = episodicMemory.searchExcludingSession(query, sessionId, topK);
                        List<Map<String, Object>> items = messages.stream()
                                .map(this::messageRecordToMap)
                                .toList();
                        return ToolResult.success(Map.of("results", items, "count", items.size()));
                    } catch (Exception e) {
                        log.error("回忆对话失败: {}", e.getMessage(), e);
                        return ToolResult.error("回忆对话失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建知识库文档搜索工具。 */
    private BuiltinTool buildSearchDocsTool() {
        int defaultTopK = memoryProperties != null
                ? memoryProperties.getAgenticTool().getDocsDefaultTopK() : 5;
        return BuiltinTool.builder()
                .id("builtin.memory.search-docs")
                .name("搜索知识库")
                .description("搜索知识库文档。" +
                        "当用户的问题可能涉及已上传的文档、资料、手册内容时使用。" +
                        "不要用于搜索知识实体（用 search）或历史对话（用 recall）。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词"),
                                "top_k", Map.of("type", "integer", "description", "返回数量，默认 " + defaultTopK)
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String query = input.getParam("query", String.class);
                        int topK = input.getOptionalParam("top_k", Integer.class).orElse(defaultTopK);
                        String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
                        if (sessionId == null) {
                            return ToolResult.success(Map.of(
                                    "message", "无法获取当前会话 ID", "results", List.of(), "count", 0));
                        }
                        List<String> kbIds = sessionKbRepo.findKnowledgeBaseIdsBySessionId(sessionId);
                        if (kbIds.isEmpty()) {
                            return ToolResult.success(Map.of(
                                    "message", "当前会话未绑定知识库", "results", List.of(), "count", 0));
                        }
                        List<DocumentSearchResult> results = documentRetriever.retrieve(query, kbIds, topK);
                        List<Map<String, Object>> items = results.stream()
                                .map(this::docSearchResultToMap)
                                .toList();
                        return ToolResult.success(Map.of("results", items, "count", items.size()));
                    } catch (Exception e) {
                        log.error("搜索知识库失败: {}", e.getMessage(), e);
                        return ToolResult.error("搜索知识库失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建创建记忆工具。 */
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("builtin.memory.create")
                .name("创建记忆")
                .description("创建新的记忆实体（人物、地点、事件、偏好、习惯、目标等）。" +
                        "当对话中出现值得长期记住的新信息时使用。如果实体已存在，会自动版本化合并。")
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

    /** 构建更新记忆工具。 */
    private BuiltinTool buildUpdateTool() {
        return BuiltinTool.builder()
                .id("builtin.memory.update")
                .name("更新记忆")
                .description("更新已有记忆实体的描述或类型。当用户纠正或补充之前记住的信息时使用。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("entityId"),
                        "properties", Map.of(
                                "entityId", Map.of("type", "string", "description", "要更新的实体 ID"),
                                "description", Map.of("type", "string", "description", "新的描述"),
                                "entityType", Map.of("type", "string", "description", "新的实体类型")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String entityId = input.getParam("entityId", String.class);
                        var existing = semanticMemory.findById(entityId);
                        if (existing.isEmpty()) {
                            return ToolResult.error("实体不存在: " + entityId);
                        }
                        var entity = existing.get();
                        // 合并新字段
                        String newDesc = input.getOptionalParam("description", String.class)
                                .orElse(entity.description());
                        EntityType newType = input.getOptionalParam("entityType", String.class)
                                .map(s -> EntityType.valueOf(s.toUpperCase()))
                                .orElse(entity.type());
                        var now = Instant.now();
                        var updated = new TemporalEntity(
                                entity.id(), newType, entity.name(), newDesc,
                                entity.properties(), entity.version(), entity.isCurrent(),
                                entity.validFrom(), entity.validTo(), entity.sourceConversationId(),
                                entity.extractionConfidence(), entity.importanceScore(),
                                entity.accessCount(), entity.lastAccessedAt(), entity.createdAt(), now);
                        var result = semanticMemory.upsertWithConflictDetection(updated, null);
                        return ToolResult.success(Map.of(
                                "id", result.id(),
                                "name", result.name(),
                                "type", result.type().name(),
                                "version", result.version(),
                                "description", result.description() != null ? result.description() : ""));
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error("无效的实体类型: " + e.getMessage());
                    } catch (Exception e) {
                        log.error("更新记忆失败: {}", e.getMessage(), e);
                        return ToolResult.error("更新记忆失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建删除（归档）记忆工具。 */
    private BuiltinTool buildDeleteTool() {
        return BuiltinTool.builder()
                .id("builtin.memory.delete")
                .name("删除记忆")
                .description("删除（归档）记忆实体。当用户明确要求忘记某条记忆时使用。" +
                        "实体不会被物理删除，而是标记为归档。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("entityId"),
                        "properties", Map.of(
                                "entityId", Map.of("type", "string", "description", "要删除的实体 ID")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executor(input -> {
                    try {
                        String entityId = input.getParam("entityId", String.class);
                        var existing = semanticMemory.findById(entityId);
                        if (existing.isEmpty()) {
                            return ToolResult.error("实体不存在: " + entityId);
                        }
                        var entity = existing.get();
                        semanticMemory.archive(entity);
                        return ToolResult.success(Map.of(
                                "id", entity.id(),
                                "name", entity.name(),
                                "archived", true));
                    } catch (Exception e) {
                        log.error("删除记忆失败: {}", e.getMessage(), e);
                        return ToolResult.error("删除记忆失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建标签关系工具。 */
    private BuiltinTool buildTagTool() {
        return BuiltinTool.builder()
                .id("builtin.memory.tag")
                .name("添加记忆标签")
                .description("为记忆实体添加关系标签，建立实体间的关联（如 RELATED_TO, BELONGS_TO, CAUSED_BY）。" +
                        "当需要记录两个实体之间的关系时使用。")
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

    // ---- 辅助方法 ----

    /** 将 RetrievalResult 转换为 Map。 */
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

    /** 将 MessageRecord 转换为 Map。 */
    private Map<String, Object> messageRecordToMap(MessageRecord msg) {
        var map = new HashMap<String, Object>();
        map.put("role", msg.role());
        map.put("content", msg.effectiveContent());
        map.put("createdAt", msg.createdAt().toString());
        return Map.copyOf(map);
    }

    /** 将 DocumentSearchResult 转换为 Map。 */
    private Map<String, Object> docSearchResultToMap(DocumentSearchResult doc) {
        var map = new HashMap<String, Object>();
        map.put("chunkId", doc.chunkId());
        map.put("documentId", doc.documentId());
        map.put("content", doc.content());
        map.put("score", doc.score());
        if (!doc.headingHierarchy().isEmpty()) {
            map.put("headingHierarchy", doc.headingHierarchy());
        }
        return Map.copyOf(map);
    }
}

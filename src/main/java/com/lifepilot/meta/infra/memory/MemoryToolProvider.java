package com.lifepilot.meta.infra.memory;

import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.knowledge.retrieve.SessionKnowledgeScopeResolver;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.ConversationSnippetRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 记忆管理工具提供者。
 *
 * <p>注册 9 个记忆管理工具到 DynamicToolRegistry：
 * search / recall / knowledge.search / create / update / delete / tag / query-at-time / search-experience。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class MemoryToolProvider {

    private static final Logger log = LoggerFactory.getLogger(MemoryToolProvider.class);

    private final HybridRetriever hybridRetriever;
    private final SemanticMemory semanticMemory;
    @Nullable private final EpisodicMemory episodicMemory;
    @Nullable private final DocumentRetriever documentRetriever;
    @Nullable private final SessionKnowledgeBaseRepository sessionKbRepo;
    @Nullable private final SessionKnowledgeScopeResolver sessionKnowledgeScopeResolver;
    @Nullable private final MemoryProperties memoryProperties;

    public MemoryToolProvider(HybridRetriever hybridRetriever,
                              SemanticMemory semanticMemory,
                              @Nullable EpisodicMemory episodicMemory,
                              @Nullable DocumentRetriever documentRetriever,
                              @Nullable SessionKnowledgeBaseRepository sessionKbRepo,
                              @Nullable SessionKnowledgeScopeResolver sessionKnowledgeScopeResolver,
                              @Nullable MemoryProperties memoryProperties) {
        this.hybridRetriever = hybridRetriever;
        this.semanticMemory = semanticMemory;
        this.episodicMemory = episodicMemory;
        this.documentRetriever = documentRetriever;
        this.sessionKbRepo = sessionKbRepo;
        this.sessionKnowledgeScopeResolver = sessionKnowledgeScopeResolver;
        this.memoryProperties = memoryProperties;
    }

    /**
     * 注册记忆管理工具到 DynamicToolRegistry。
     *
     * @param toolRegistry 动态工具注册中心
     */
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
        if (documentRetriever != null && (sessionKnowledgeScopeResolver != null || sessionKbRepo != null)) {
            toolRegistry.registerBuiltinTool(buildKnowledgeSearchTool());
            count++;
        }
        toolRegistry.registerBuiltinTool(buildQueryAtTimeTool());
        count++;
        toolRegistry.registerBuiltinTool(buildSearchExperienceTool());
        count++;
        log.info("记忆/资料检索工具注册完成: count={}", count);
    }

    // ---- 工具构建方法 ----

    /** 构建记忆搜索工具 — 搜索知识实体。 */
    private BuiltinTool buildSearchTool() {
        int defaultTopK = memoryProperties != null
                ? memoryProperties.getAgenticTool().getDefaultTopK() : 10;
        return BuiltinTool.builder()
                .id("memory.search")
                .name("搜索记忆")
                .description("搜索知识实体（人物、地点、事件、偏好、习惯、目标等）。" +
                        "当用户提到具体的人名、地名、事件名，或询问你记住的偏好/习惯时使用。" +
                        "不要用于搜索历史对话内容（用 recall）或资料文档（用 knowledge.search）。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词或语义描述"),
                                "topK", Map.of("type", "integer", "description", "返回结果数量，默认 " + defaultTopK)
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .executor(input -> {
                    try {
                        String query = input.getParam("query", String.class);
                        int topK = input.getOptionalParam("topK", Integer.class).orElse(defaultTopK);
                        List<RetrievalResult> results = hybridRetriever.retrieve(
                                query,
                                topK,
                                RetrievalWeights.DEFAULT,
                                MemoryReadFilter.userMemory());
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
                .id("memory.recall")
                .name("回忆对话")
                .description("回忆历史对话片段（跨会话）。" +
                        "当用户说'我之前说过...'、'上次我们聊到...'、'你还记得我说的...'时使用。" +
                        "不要用于搜索知识实体（用 search）或资料文档（用 knowledge.search）。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词"),
                                "top_k", Map.of("type", "integer", "description", "返回数量，默认 " + defaultTopK)
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .executor(input -> {
                    try {
                        String query = input.getParam("query", String.class);
                        int topK = input.getOptionalParam("top_k", Integer.class).orElse(defaultTopK);
                        String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
                        if (sessionId == null) {
                            return ToolResult.error("无法获取当前会话 ID");
                        }
                        List<ConversationSnippetRecord> snippets =
                                episodicMemory.searchSnippetsExcludingSession(query, sessionId, topK);
                        List<Map<String, Object>> items = snippets.stream()
                                .map(this::conversationSnippetToMap)
                                .toList();
                        return ToolResult.success(Map.of("results", items, "count", items.size()));
                    } catch (Exception e) {
                        log.error("回忆对话失败: {}", e.getMessage(), e);
                        return ToolResult.error("回忆对话失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建统一资料搜索工具。 */
    private BuiltinTool buildKnowledgeSearchTool() {
        int defaultTopK = memoryProperties != null
                ? memoryProperties.getAgenticTool().getDocsDefaultTopK() : 5;
        return BuiltinTool.builder()
                .id("knowledge.search")
                .name("检索资料")
                .description("搜索当前会话绑定的资料内容。" +
                        "当当前会话绑定了 Datastore 或 Knowledge Base，且用户是在问某个主题、资料内容、推荐、说明、设定、架构、总结、比较、步骤、文档结论时，优先使用本工具先检索资料；即使用户只给出简短主题词也适用。" +
                        "当前会话如果绑定了 datastore，会自动搜索该 datastore 关联的领域文档和结构化投影内容。" +
                        "不要用于精确字段过滤（用 datastore.query_documents）、搜索知识实体（用 search）或历史对话（用 recall）。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词或自然语言问题"),
                                "top_k", Map.of("type", "integer", "description", "返回数量，默认 " + defaultTopK)
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .executor(input -> {
                    try {
                        String query = input.getParam("query", String.class);
                        int topK = input.getOptionalParam("top_k", Integer.class).orElse(defaultTopK);
                        String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
                        if (sessionId == null) {
                            return ToolResult.success(Map.of(
                                    "message", "无法获取当前会话 ID", "results", List.of(), "count", 0));
                        }
                        List<KnowledgeSearchScope> scopes = resolveKnowledgeScopes(sessionId);
                        if (scopes.isEmpty()) {
                            return ToolResult.success(Map.of(
                                    "message", "当前会话未绑定知识库或 datastore", "results", List.of(), "count", 0));
                        }
                        List<DocumentSearchResult> results = documentRetriever.retrieveByScopes(query, scopes, topK);
                        List<Map<String, Object>> items = results.stream()
                                .map(this::docSearchResultToMap)
                                .toList();
                        return ToolResult.success(Map.of("results", items, "count", items.size()));
                    } catch (Exception e) {
                        log.error("检索资料失败: {}", e.getMessage(), e);
                        return ToolResult.error("检索资料失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建创建记忆工具。 */
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("memory.create")
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
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_MEMORY,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("entityNames", false, "name")
                ))
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
                .id("memory.update")
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
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_MEMORY,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("entityIds", "entityId")
                ))
                .executor(input -> {
                    try {
                        String entityId = input.getParam("entityId", String.class);
                        var existing = semanticMemory.findById(entityId);
                        if (existing.isEmpty()) {
                            return ToolResult.error("实体不存在: " + entityId);
                        }
                        var entity = existing.get();
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
                .id("memory.delete")
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
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_MEMORY,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("entityIds", "entityId")
                ))
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
                .id("memory.tag")
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
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_MEMORY,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("entityIds", "sourceEntityId", "targetEntityId")
                ))
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

    /** 构建时间点查询工具 — 查询指定时间点有效的记忆实体。 */
    private BuiltinTool buildQueryAtTimeTool() {
        return BuiltinTool.builder()
                .id("memory.query-at-time")
                .name("时间点查询")
                .description("查询指定时间点有效的记忆实体。" +
                        "当用户问'那时候我的偏好是什么'、'某个时间点的状态'时使用。" +
                        "返回在该时间点处于有效状态的所有实体。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("timestamp"),
                        "properties", Map.of(
                                "timestamp", Map.of("type", "string", "description", "ISO 8601 格式时间戳，如 2026-01-15T10:30:00Z"),
                                "entityType", Map.of("type", "string", "description", "可选，过滤实体类型（如 PREFERENCE, HABIT, GOAL）")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .executor(input -> {
                    try {
                        String timestampStr = input.getParam("timestamp", String.class);
                        Instant instant;
                        try {
                            instant = Instant.parse(timestampStr);
                        } catch (Exception e) {
                            return ToolResult.error("无效的时间格式，请使用 ISO 8601 格式（如 2026-01-15T10:30:00Z）");
                        }
                        List<TemporalEntity> entities = semanticMemory.queryAtTime(instant);
                        var typeFilter = input.getOptionalParam("entityType", String.class);
                        if (typeFilter.isPresent()) {
                            try {
                                EntityType filterType = EntityType.valueOf(typeFilter.get().toUpperCase());
                                entities = entities.stream()
                                        .filter(e -> e.type() == filterType)
                                        .toList();
                            } catch (IllegalArgumentException e) {
                                return ToolResult.error("无效的实体类型: " + typeFilter.get());
                            }
                        }
                        List<Map<String, Object>> items = entities.stream()
                                .map(e -> {
                                    var map = new HashMap<String, Object>();
                                    map.put("id", e.id());
                                    map.put("name", e.name());
                                    map.put("type", e.type().name());
                                    if (e.description() != null) map.put("description", e.description());
                                    map.put("validFrom", e.validFrom().toString());
                                    if (e.validTo() != null) map.put("validTo", e.validTo().toString());
                                    return Map.copyOf(map);
                                })
                                .toList();
                        return ToolResult.success(Map.of("results", items, "count", items.size(),
                                "queryTime", timestampStr));
                    } catch (Exception e) {
                        log.error("时间点查询失败: {}", e.getMessage(), e);
                        return ToolResult.error("时间点查询失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建经验检索工具。 */
    private BuiltinTool buildSearchExperienceTool() {
        return BuiltinTool.builder()
                .id("memory.search-experience")
                .name("搜索经验")
                .description("搜索历史执行经验。当遇到以下场景时使用：" +
                        "1) 之前处理过的类似任务；" +
                        "2) 工具调用连续失败需要参考成功经验；" +
                        "3) 需要了解特定工具的最佳使用方式。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "搜索关键词或场景描述"),
                                "topK", Map.of("type", "integer",
                                        "description", "返回数量，默认 3"),
                                "successOnly", Map.of("type", "boolean",
                                        "description", "是否仅返回成功经验，默认 false")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .executor(input -> {
                    try {
                        String query = input.getParam("query", String.class);
                        int topK = input.getOptionalParam("topK", Integer.class).orElse(3);
                        boolean successOnly = input.getOptionalParam("successOnly", Boolean.class).orElse(false);

                        if (query.isBlank()) {
                            return ToolResult.error("query 参数不能为空");
                        }

                        var experiences = semanticMemory.findCurrentByType(
                                EntityType.EXPERIENCE,
                                MemoryReadFilter.agentExperience());

                        boolean crossContext = memoryProperties != null
                                && memoryProperties.getExperience().getIsolation().isCrossContextRetrieval();
                        if (!crossContext) {
                            experiences = experiences.stream()
                                    .filter(e -> {
                                        var ctx = e.properties().get("executionContext");
                                        return ctx == null || "MAIN_AGENT".equals(ctx.toString());
                                    })
                                    .toList();
                        }

                        if (successOnly) {
                            experiences = experiences.stream()
                                    .filter(e -> Boolean.TRUE.equals(e.properties().get("success")))
                                    .toList();
                        }

                        var results = experiences.stream()
                                .sorted(Comparator.comparingDouble(
                                        TemporalEntity::importanceScore).reversed())
                                .limit(topK)
                                .map(e -> {
                                    var m = new LinkedHashMap<String, Object>();
                                    m.put("entityId", e.id());
                                    m.put("scenario", e.name());
                                    m.put("strategy", e.description() != null ? e.description() : "");
                                    m.put("lessons", e.properties().getOrDefault("lessons", List.of()));
                                    m.put("toolsUsed", e.properties().getOrDefault("toolsUsed", List.of()));
                                    m.put("success", e.properties().getOrDefault("success", false));
                                    m.put("importanceScore", e.importanceScore());
                                    return Map.<String, Object>copyOf(m);
                                })
                                .toList();

                        return ToolResult.success(Map.of("results", results, "count", results.size()));
                    } catch (Exception e) {
                        log.error("经验检索工具执行失败: error={}", e.getMessage(), e);
                        return ToolResult.error("经验检索失败: " + e.getMessage());
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

    /** 将 ConversationSnippetRecord 转换为 Map。 */
    private Map<String, Object> conversationSnippetToMap(ConversationSnippetRecord snippet) {
        var map = new HashMap<String, Object>();
        map.put("sessionId", snippet.sessionId());
        if (snippet.sessionTitle() != null) {
            map.put("sessionTitle", snippet.sessionTitle());
        }
        if (snippet.sessionSummary() != null) {
            map.put("sessionSummary", snippet.sessionSummary());
        }
        map.put("matchedMessageId", snippet.matchedMessageId());
        map.put("rank", snippet.hitRank());
        map.put("startedAt", snippet.startedAt().toString());
        map.put("endedAt", snippet.endedAt().toString());
        map.put("messages", snippet.messages().stream().map(this::messageRecordToMap).toList());
        map.put("snippet", snippet.messages().stream()
                .map(message -> message.role() + ": " + message.effectiveContent())
                .toList());
        return Map.copyOf(map);
    }

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
        map.put("sourceType", doc.sourceType().name());
        doc.sourceDatastoreId().ifPresent(value -> map.put("sourceDatastoreId", value));
        doc.sourceCollectionId().ifPresent(value -> map.put("sourceCollectionId", value));
        return Map.copyOf(map);
    }

    private List<KnowledgeSearchScope> resolveKnowledgeScopes(String sessionId) {
        if (sessionKnowledgeScopeResolver != null) {
            return sessionKnowledgeScopeResolver.resolveScopes(sessionId);
        }
        if (sessionKbRepo == null) {
            return List.of();
        }
        return sessionKbRepo.findKnowledgeBaseIdsBySessionId(sessionId).stream()
                .map(kbId -> new KnowledgeSearchScope(kbId, null))
                .toList();
    }
}

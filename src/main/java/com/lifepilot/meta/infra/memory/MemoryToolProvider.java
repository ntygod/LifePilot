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
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
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
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆管理工具提供者。
 *
 * <p>集中管理两个元能力工具：memory / knowledge.search。</p>
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

    public void registerTools(DynamicToolRegistry toolRegistry) {
        buildMemoryTools().forEach(toolRegistry::registerBuiltinTool);
        log.info("记忆/资料检索工具注册完成: count={}", buildMemoryTools().size());
    }

    public List<BuiltinTool> buildMemoryTools() {
        var tools = new java.util.ArrayList<BuiltinTool>();
        var memoryExecutor = new MemoryActionDispatchExecutor(
                this,
                hybridRetriever,
                semanticMemory,
                episodicMemory,
                sessionKbRepo,
                memoryProperties
        );
        tools.add(buildMemoryTool(memoryExecutor));
        if (documentRetriever != null && (sessionKnowledgeScopeResolver != null || sessionKbRepo != null)) {
            tools.add(buildKnowledgeSearchTool());
        }
        return List.copyOf(tools);
    }

    private BuiltinTool buildMemoryTool(MemoryActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("memory")
                .category(ToolCategory.ACTION)
                .name("记忆管理")
                .description("管理长期记忆。通过 action 参数支持：" +
                        "search=搜索知识实体（人物、偏好、事件等），" +
                        "recall=回忆历史对话片段（跨会话），" +
                        "create=创建新记忆实体，update=更新已有实体，delete=归档实体，" +
                        "tag=建立实体间关系（如 RELATED_TO），" +
                        "query-at-time=查询指定时间点的记忆状态，" +
                        "search-experience=检索历史执行经验和成功模式。" +
                        "搜索当前会话绑定的资料文档请用 knowledge.search，精确字段过滤请用 datastore。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("search", "recall", "create", "update", "delete", "tag", "query-at-time", "search-experience"),
                                        "description", "记忆操作类型")),
                                Map.entry("query", Map.of("type", "string", "description", "搜索关键词、语义描述或经验检索场景")),
                                Map.entry("topK", Map.of("type", "integer", "description", "返回数量，search/search-experience 使用")),
                                Map.entry("top_k", Map.of("type", "integer", "description", "返回数量，recall 使用")),
                                Map.entry("name", Map.of("type", "string", "description", "action=create 时的实体名称")),
                                Map.entry("entityType", Map.of("type", "string", "description", "实体类型或 query-at-time 的类型过滤")),
                                Map.entry("description", Map.of("type", "string", "description", "实体描述")),
                                Map.entry("conversationId", Map.of("type", "string", "description", "来源会话 ID")),
                                Map.entry("entityId", Map.of("type", "string", "description", "action=update/delete 时的实体 ID")),
                                Map.entry("sourceEntityId", Map.of("type", "string", "description", "action=tag 时的源实体 ID")),
                                Map.entry("targetEntityId", Map.of("type", "string", "description", "action=tag 时的目标实体 ID")),
                                Map.entry("relationType", Map.of("type", "string", "description", "action=tag 时的关系类型")),
                                Map.entry("strength", Map.of("type", "number", "description", "action=tag 时的关系强度 0.0-1.0，默认 0.5")),
                                Map.entry("timestamp", Map.of("type", "string", "description", "action=query-at-time 时的 ISO 8601 时间戳")),
                                Map.entry("successOnly", Map.of("type", "boolean", "description", "action=search-experience 时是否仅返回成功经验，默认 false"))
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_MEMORY,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("entityNames", false, "name", "entityId", "sourceEntityId", "targetEntityId")
                ))
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    private BuiltinTool buildKnowledgeSearchTool() {
        int defaultTopK = memoryProperties != null
                ? memoryProperties.getAgenticTool().getDocsDefaultTopK() : 5;
        return BuiltinTool.builder()
                .id("knowledge.search")
                .name("检索资料")
                .description("搜索当前会话绑定的资料内容。不要用于精确字段过滤（用 datastore）、搜索知识实体（用 memory(action=search)）或历史对话（用 memory(action=recall)）。")
                .category(ToolCategory.PERCEPTION)
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
                        List<Map<String, Object>> items = results.stream().map(this::docSearchResultToMap).toList();
                        return ToolResult.success(Map.of("results", items, "count", items.size()));
                    } catch (Exception e) {
                        log.error("检索资料失败: {}", e.getMessage(), e);
                        return ToolResult.error("检索资料失败: " + e.getMessage());
                    }
                })
                .build();
    }

    ToolResult executeSearch(com.lifepilot.tool.model.ToolInput input) {
        int defaultTopK = memoryProperties != null ? memoryProperties.getAgenticTool().getDefaultTopK() : 10;
        try {
            String query = input.getParam("query", String.class);
            int topK = input.getOptionalParam("topK", Integer.class).orElse(defaultTopK);
            List<RetrievalResult> results = hybridRetriever.retrieve(query, topK, RetrievalWeights.DEFAULT, MemoryReadFilter.userMemory());
            if (!results.isEmpty()) {
                hybridRetriever.updateAccessCounts(results);
            }
            List<Map<String, Object>> items = results.stream().map(this::retrievalResultToMap).toList();
            return ToolResult.success(Map.of("results", items, "count", items.size()));
        } catch (Exception e) {
            log.error("搜索记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("搜索记忆失败: " + e.getMessage());
        }
    }

    ToolResult executeRecall(com.lifepilot.tool.model.ToolInput input) {
        if (episodicMemory == null) {
            return ToolResult.error("回忆对话功能不可用");
        }
        int defaultTopK = memoryProperties != null ? memoryProperties.getAgenticTool().getDefaultTopK() : 10;
        try {
            String query = input.getParam("query", String.class);
            int topK = input.getOptionalParam("top_k", Integer.class).orElse(defaultTopK);
            String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
            if (sessionId == null) {
                return ToolResult.error("无法获取当前会话 ID");
            }
            List<ConversationSnippetRecord> snippets = episodicMemory.searchSnippetsExcludingSession(query, sessionId, topK);
            List<Map<String, Object>> items = snippets.stream().map(this::conversationSnippetToMap).toList();
            return ToolResult.success(Map.of("results", items, "count", items.size()));
        } catch (Exception e) {
            log.error("回忆对话失败: {}", e.getMessage(), e);
            return ToolResult.error("回忆对话失败: " + e.getMessage());
        }
    }

    ToolResult executeCreate(com.lifepilot.tool.model.ToolInput input) {
        try {
            String name = input.getParam("name", String.class);
            String typeStr = input.getParam("entityType", String.class);
            String description = input.getOptionalParam("description", String.class).orElse(null);
            String conversationId = input.getOptionalParam("conversationId", String.class).orElse(null);
            EntityType entityType = EntityType.valueOf(typeStr.toUpperCase());
            var now = Instant.now();
            var incoming = new TemporalEntity(null, entityType, name, description, Map.of(), 1, true,
                    now, null, conversationId, 1.0f, 0.5f, 0, null, now, now);
            var created = semanticMemory.upsertWithConflictDetection(incoming, conversationId);
            return ToolResult.success(Map.of(
                    "id", created.id(), "name", created.name(), "type", created.type().name(), "version", created.version()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("无效的实体类型: " + e.getMessage());
        } catch (Exception e) {
            log.error("创建记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("创建记忆失败: " + e.getMessage());
        }
    }

    ToolResult executeUpdate(com.lifepilot.tool.model.ToolInput input) {
        try {
            String entityId = input.getParam("entityId", String.class);
            var existing = semanticMemory.findById(entityId);
            if (existing.isEmpty()) {
                return ToolResult.error("实体不存在: " + entityId);
            }
            var entity = existing.get();
            String newDesc = input.getOptionalParam("description", String.class).orElse(entity.description());
            EntityType newType = input.getOptionalParam("entityType", String.class).map(s -> EntityType.valueOf(s.toUpperCase())).orElse(entity.type());
            var now = Instant.now();
            var updated = new TemporalEntity(entity.id(), newType, entity.name(), newDesc,
                    entity.properties(), entity.version(), entity.isCurrent(),
                    entity.validFrom(), entity.validTo(), entity.sourceConversationId(),
                    entity.extractionConfidence(), entity.importanceScore(),
                    entity.accessCount(), entity.lastAccessedAt(), entity.createdAt(), now);
            var result = semanticMemory.upsertWithConflictDetection(updated, null);
            return ToolResult.success(Map.of(
                    "id", result.id(), "name", result.name(), "type", result.type().name(),
                    "version", result.version(), "description", result.description() != null ? result.description() : ""));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("无效的实体类型: " + e.getMessage());
        } catch (Exception e) {
            log.error("更新记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("更新记忆失败: " + e.getMessage());
        }
    }

    ToolResult executeDelete(com.lifepilot.tool.model.ToolInput input) {
        try {
            String entityId = input.getParam("entityId", String.class);
            var existing = semanticMemory.findById(entityId);
            if (existing.isEmpty()) {
                return ToolResult.error("实体不存在: " + entityId);
            }
            var entity = existing.get();
            semanticMemory.archive(entity);
            return ToolResult.success(Map.of("id", entity.id(), "name", entity.name(), "archived", true));
        } catch (Exception e) {
            log.error("删除记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("删除记忆失败: " + e.getMessage());
        }
    }

    ToolResult executeTag(com.lifepilot.tool.model.ToolInput input) {
        try {
            String sourceId = input.getParam("sourceEntityId", String.class);
            String targetId = input.getParam("targetEntityId", String.class);
            String relationType = input.getParam("relationType", String.class);
            float strength = input.getOptionalParam("strength", Number.class).map(Number::floatValue).orElse(0.5f);
            String conversationId = input.getOptionalParam("conversationId", String.class).orElse(null);
            var now = Instant.now();
            var relation = new TemporalRelation(UUID.randomUUID().toString(), sourceId, targetId, relationType, strength,
                    null, now, null, conversationId, now);
            semanticMemory.addRelation(relation);
            return ToolResult.success(Map.of(
                    "id", relation.id(), "relationType", relationType,
                    "sourceEntityId", sourceId, "targetEntityId", targetId));
        } catch (Exception e) {
            log.error("添加记忆标签失败: {}", e.getMessage(), e);
            return ToolResult.error("添加记忆标签失败: " + e.getMessage());
        }
    }

    ToolResult executeQueryAtTime(com.lifepilot.tool.model.ToolInput input) {
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
                    entities = entities.stream().filter(e -> e.type() == filterType).toList();
                } catch (IllegalArgumentException e) {
                    return ToolResult.error("无效的实体类型: " + typeFilter.get());
                }
            }
            List<Map<String, Object>> items = entities.stream().map(e -> {
                var map = new HashMap<String, Object>();
                map.put("id", e.id());
                map.put("name", e.name());
                map.put("type", e.type().name());
                if (e.description() != null) map.put("description", e.description());
                map.put("validFrom", e.validFrom().toString());
                if (e.validTo() != null) map.put("validTo", e.validTo().toString());
                return Map.copyOf(map);
            }).toList();
            return ToolResult.success(Map.of("results", items, "count", items.size(), "queryTime", timestampStr));
        } catch (Exception e) {
            log.error("时间点查询失败: {}", e.getMessage(), e);
            return ToolResult.error("时间点查询失败: " + e.getMessage());
        }
    }

    ToolResult executeSearchExperience(com.lifepilot.tool.model.ToolInput input) {
        try {
            String query = input.getParam("query", String.class);
            int topK = input.getOptionalParam("topK", Integer.class).orElse(3);
            boolean successOnly = input.getOptionalParam("successOnly", Boolean.class).orElse(false);
            if (query.isBlank()) {
                return ToolResult.error("query 参数不能为空");
            }
            var experiences = semanticMemory.findCurrentByType(EntityType.EXPERIENCE, MemoryReadFilter.agentExperience());
            boolean crossContext = memoryProperties != null && memoryProperties.getExperience().getIsolation().isCrossContextRetrieval();
            if (!crossContext) {
                experiences = experiences.stream().filter(e -> {
                    var ctx = e.properties().get("executionContext");
                    return ctx == null || "MAIN_AGENT".equals(ctx.toString());
                }).toList();
            }
            if (successOnly) {
                experiences = experiences.stream().filter(e -> Boolean.TRUE.equals(e.properties().get("success"))).toList();
            }
            var results = experiences.stream()
                    .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore).reversed())
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
    }

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

    private Map<String, Object> conversationSnippetToMap(ConversationSnippetRecord snippet) {
        var map = new HashMap<String, Object>();
        map.put("sessionId", snippet.sessionId());
        if (snippet.sessionTitle() != null) map.put("sessionTitle", snippet.sessionTitle());
        if (snippet.sessionSummary() != null) map.put("sessionSummary", snippet.sessionSummary());
        map.put("matchedMessageId", snippet.matchedMessageId());
        map.put("rank", snippet.hitRank());
        map.put("startedAt", snippet.startedAt().toString());
        map.put("endedAt", snippet.endedAt().toString());
        map.put("messages", snippet.messages().stream().map(this::messageRecordToMap).toList());
        map.put("snippet", snippet.messages().stream().map(message -> message.role() + ": " + message.effectiveContent()).toList());
        return Map.copyOf(map);
    }

    private Map<String, Object> messageRecordToMap(MessageRecord msg) {
        var map = new HashMap<String, Object>();
        map.put("role", msg.role());
        map.put("content", msg.effectiveContent());
        map.put("createdAt", msg.createdAt().toString());
        return Map.copyOf(map);
    }

    private Map<String, Object> docSearchResultToMap(DocumentSearchResult doc) {
        var map = new HashMap<String, Object>();
        map.put("chunkId", doc.chunkId());
        map.put("documentId", doc.documentId());
        map.put("content", doc.content());
        map.put("score", doc.score());
        if (!doc.headingHierarchy().isEmpty()) map.put("headingHierarchy", doc.headingHierarchy());
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

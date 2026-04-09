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
import com.lifepilot.tool.model.ToolInput;
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
import java.util.*;

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
    private static final String VALID_ENTITY_TYPES = Arrays.stream(EntityType.values())
            .map(Enum::name).collect(java.util.stream.Collectors.joining(", "));

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
        var tools = buildMemoryTools();
        tools.forEach(toolRegistry::registerBuiltinTool);
        log.info("记忆/资料检索工具注册完成: count={}", tools.size());
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
                .description("管理用户的长期记忆。用户透露身份、偏好、习惯等持久性信息时应主动调用写入。" +
                        "资料文档用 knowledge.search，精确字段用 datastore。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("search", "recall", "create", "update", "delete", "tag", "query-at-time", "search-experience"),
                                        "description", "记忆操作类型。search=搜索知识实体, recall=回忆历史对话, " +
                                                "create/update/delete=实体 CRUD, tag=建立关系, " +
                                                "query-at-time=时间点查询, search-experience=检索执行经验")),
                                Map.entry("query", Map.of("type", "string", "description", "搜索关键词或语义描述；search/recall/search-experience 使用")),
                                Map.entry("top_k", Map.of("type", "integer", "description", "返回数量；search/recall/search-experience 使用")),
                                Map.entry("name", Map.of("type", "string", "description", "实体名称；create 必填")),
                                Map.entry("entityType", Map.of("type", "string", "description", "实体类型；create 必填，query-at-time 时可选过滤", "enum", List.of("PERSON", "ORGANIZATION", "PLACE", "EVENT", "PROJECT", "TOPIC", "PREFERENCE", "HABIT", "GOAL", "SKILL", "EXPERIENCE", "CUSTOM"))),
                                Map.entry("description", Map.of("type", "string", "description", "实体描述")),
                                Map.entry("conversationId", Map.of("type", "string", "description", "来源会话 ID")),
                                Map.entry("entityId", Map.of("type", "string", "description", "实体 ID；update/delete 必填")),
                                Map.entry("sourceEntityId", Map.of("type", "string", "description", "tag 源实体 ID")),
                                Map.entry("targetEntityId", Map.of("type", "string", "description", "tag 目标实体 ID")),
                                Map.entry("relationType", Map.of("type", "string", "description", "tag 关系类型")),
                                Map.entry("strength", Map.of("type", "number", "description", "tag 关系强度 0.0-1.0，默认 0.5")),
                                Map.entry("timestamp", Map.of("type", "string", "description", "query-at-time 的 ISO 8601 时间戳")),
                                Map.entry("successOnly", Map.of("type", "boolean", "description", "search-experience 仅返回成功经验，默认 false"))
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
                                "top_k", Map.of("type", "integer", "description", "返回数量，默认 " + defaultTopK),
                                "datastoreId", Map.of("type", "string", "description", "指定检索的数据空间 ID，不传则检索会话绑定的所有数据空间")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .executor(input -> {
                    try {
                        String query = input.getParam("query", String.class);
                        int topK = input.getOptionalParam("top_k", Integer.class).orElse(defaultTopK);
                        String datastoreId = input.getOptionalParam("datastoreId", String.class).orElse(null);
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
                        if (datastoreId != null && !datastoreId.isBlank()) {
                            scopes = scopes.stream()
                                    .filter(s -> datastoreId.equals(s.datastoreId()))
                                    .toList();
                            if (scopes.isEmpty()) {
                                return ToolResult.success(Map.of("results", List.of(),
                                        "message", "当前会话未绑定指定的数据空间: " + datastoreId));
                            }
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

    ToolResult executeSearch(ToolInput input) {
        int defaultTopK = memoryProperties != null ? memoryProperties.getAgenticTool().getDefaultTopK() : 10;
        try {
            String query = input.getParam("query", String.class);
            int topK = input.getOptionalParam("top_k", Integer.class).orElse(defaultTopK);
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

    ToolResult executeRecall(ToolInput input) {
        if (episodicMemory == null) {
            return ToolResult.error("回忆对话功能不可用，改用 search 搜索知识实体");
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

    ToolResult executeCreate(ToolInput input) {
        try {
            String name = input.getParam("name", String.class);
            String typeStr = input.getParam("entityType", String.class);
            String description = input.getOptionalParam("description", String.class).orElse(null);
            String conversationId = input.getOptionalParam("conversationId", String.class).orElse(null);
            EntityType entityType = EntityType.valueOf(typeStr.toUpperCase());
            var now = Instant.now();
            var incoming = new TemporalEntity(null, entityType, name, description, Map.of(), 1, true,
                    now, null, conversationId, 1.0f, 0.5f, 0, null, now, now);
            var created = retryOnBusy(() -> semanticMemory.upsertWithConflictDetection(incoming, conversationId));
            return ToolResult.success(Map.of(
                    "id", created.id(), "name", created.name(), "type", created.type().name(), "version", created.version()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("无效的实体类型 '" + input.getOptionalParam("entityType", String.class).orElse("") + "'，有效值: " + VALID_ENTITY_TYPES);
        } catch (Exception e) {
            log.error("创建记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("创建记忆失败: " + e.getMessage());
        }
    }

    ToolResult executeUpdate(ToolInput input) {
        try {
            String entityId = input.getParam("entityId", String.class);
            var existing = semanticMemory.findById(entityId);
            if (existing.isEmpty()) {
                return ToolResult.error("实体不存在: " + entityId + "，请用 search 查找正确 ID");
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
            var result = retryOnBusy(() -> semanticMemory.upsertWithConflictDetection(updated, null));
            return ToolResult.success(Map.of(
                    "id", result.id(), "name", result.name(), "type", result.type().name(),
                    "version", result.version(), "description", result.description() != null ? result.description() : ""));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("无效的实体类型 '" + input.getOptionalParam("entityType", String.class).orElse("") + "'，有效值: " + VALID_ENTITY_TYPES);
        } catch (Exception e) {
            log.error("更新记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("更新记忆失败: " + e.getMessage());
        }
    }

    ToolResult executeDelete(ToolInput input) {
        try {
            String entityId = input.getParam("entityId", String.class);
            var existing = semanticMemory.findById(entityId);
            if (existing.isEmpty()) {
                return ToolResult.error("实体不存在: " + entityId + "，请用 search 查找正确 ID");
            }
            var entity = existing.get();
            retryOnBusy(() -> { semanticMemory.archive(entity); return null; });
            return ToolResult.success(Map.of("id", entity.id(), "name", entity.name(), "archived", true));
        } catch (Exception e) {
            log.error("删除记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("删除记忆失败: " + e.getMessage());
        }
    }

    ToolResult executeTag(ToolInput input) {
        try {
            String sourceId = input.getParam("sourceEntityId", String.class);
            String targetId = input.getParam("targetEntityId", String.class);
            String relationType = input.getParam("relationType", String.class);
            float strength = input.getOptionalParam("strength", Number.class).map(Number::floatValue).orElse(0.5f);
            String conversationId = input.getOptionalParam("conversationId", String.class).orElse(null);
            var now = Instant.now();
            var relation = new TemporalRelation(UUID.randomUUID().toString(), sourceId, targetId, relationType, strength,
                    null, now, null, conversationId, now);
            retryOnBusy(() -> { semanticMemory.addRelation(relation); return null; });
            return ToolResult.success(Map.of(
                    "id", relation.id(), "relationType", relationType,
                    "sourceEntityId", sourceId, "targetEntityId", targetId));
        } catch (Exception e) {
            log.error("添加记忆标签失败: {}", e.getMessage(), e);
            return ToolResult.error("添加记忆标签失败: " + e.getMessage());
        }
    }

    ToolResult executeQueryAtTime(ToolInput input) {
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
                    return ToolResult.error("无效的实体类型 '" + typeFilter.get() + "'，有效值: " + VALID_ENTITY_TYPES);
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

    ToolResult executeSearchExperience(ToolInput input) {
        try {
            String query = input.getParam("query", String.class);
            int topK = input.getOptionalParam("top_k", Integer.class).orElse(3);
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

    /**
     * SQLite BUSY 重试：指数退避，最多重试 3 次。
     *
     * <p>SQLite WAL 模式下并发写入可能触发 SQLITE_BUSY_SNAPSHOT，
     * 此方法在事务外层重试，确保每次重试使用新的事务和快照。</p>
     */
    private <T> T retryOnBusy(java.util.function.Supplier<T> operation) {
        int maxRetries = 3;
        long baseDelayMs = 200;
        for (int attempt = 0; ; attempt++) {
            try {
                return operation.get();
            } catch (Exception e) {
                if (attempt >= maxRetries || !isSqliteBusy(e)) {
                    throw e;
                }
                long delay = baseDelayMs * (1L << attempt); // 200, 400, 800ms
                log.debug("SQLite BUSY 重试: attempt={}, delayMs={}", attempt + 1, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 判断异常链中是否包含 SQLite BUSY 错误。 */
    private boolean isSqliteBusy(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.sqlite.SQLiteException sqliteEx && sqliteEx.getResultCode() != null
                    && sqliteEx.getResultCode().name().startsWith("SQLITE_BUSY")) {
                return true;
            }
        }
        return false;
    }
}

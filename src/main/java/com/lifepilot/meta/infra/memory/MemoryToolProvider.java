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
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.scope.MemoryOriginType;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryRealityType;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.scope.MemoryWriteContext;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.memory.support.SqliteBusyRetry;
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
    @Nullable private final ProjectContextResolver projectContextResolver;
    @Nullable private final ChatSessionRepository chatSessionRepository;

    public MemoryToolProvider(HybridRetriever hybridRetriever,
                              SemanticMemory semanticMemory,
                              @Nullable EpisodicMemory episodicMemory,
                              @Nullable DocumentRetriever documentRetriever,
                              @Nullable SessionKnowledgeBaseRepository sessionKbRepo,
                              @Nullable SessionKnowledgeScopeResolver sessionKnowledgeScopeResolver,
                              @Nullable MemoryProperties memoryProperties) {
        this(hybridRetriever, semanticMemory, episodicMemory, documentRetriever,
                sessionKbRepo, sessionKnowledgeScopeResolver, memoryProperties, null, null);
    }

    public MemoryToolProvider(HybridRetriever hybridRetriever,
                              SemanticMemory semanticMemory,
                              @Nullable EpisodicMemory episodicMemory,
                              @Nullable DocumentRetriever documentRetriever,
                              @Nullable SessionKnowledgeBaseRepository sessionKbRepo,
                              @Nullable SessionKnowledgeScopeResolver sessionKnowledgeScopeResolver,
                              @Nullable MemoryProperties memoryProperties,
                              @Nullable ProjectContextResolver projectContextResolver,
                              @Nullable ChatSessionRepository chatSessionRepository) {
        this.hybridRetriever = hybridRetriever;
        this.semanticMemory = semanticMemory;
        this.episodicMemory = episodicMemory;
        this.documentRetriever = documentRetriever;
        this.sessionKbRepo = sessionKbRepo;
        this.sessionKnowledgeScopeResolver = sessionKnowledgeScopeResolver;
        this.memoryProperties = memoryProperties;
        this.projectContextResolver = projectContextResolver;
        this.chatSessionRepository = chatSessionRepository;
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
                .description("Manage user long-term memory. Actions: search (entities), recall (conversation fragments), create, update, delete, cancel (archive goal/experience), tag, query-at-time, search-experience.")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("search", "recall", "create", "update", "delete", "cancel", "tag", "query-at-time", "search-experience"),
                                        "description", "记忆操作类型。search=搜索知识实体, recall=回忆历史对话, " +
                                                "create/update/delete=实体 CRUD, cancel=按语义批量归档已取消的目标/经验/习惯, " +
                                                "tag=建立关系, query-at-time=时间点查询, search-experience=检索执行经验")),
                                Map.entry("query", Map.of("type", "string", "description", "搜索关键词或语义描述；search/recall/search-experience/cancel 使用")),
                                Map.entry("top_k", Map.of("type", "integer", "description", "返回数量；search/recall/search-experience 使用")),
                                Map.entry("name", Map.of("type", "string", "description", "实体名称；create 必填，update 时可选改名")),
                                Map.entry("entityType", Map.of("type", "string", "description", "实体类型；create 必填，query-at-time 时可选过滤", "enum", List.of("PERSON", "ORGANIZATION", "PLACE", "EVENT", "PROJECT", "TOPIC", "PREFERENCE", "HABIT", "GOAL", "SKILL", "EXPERIENCE", "CUSTOM"))),
                                Map.entry("entityTypes", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string", "enum", List.of("PERSON", "ORGANIZATION", "PLACE", "EVENT", "PROJECT", "TOPIC", "PREFERENCE", "HABIT", "GOAL", "SKILL", "EXPERIENCE", "CUSTOM")),
                                        "description", "cancel 限定归档的实体类型集合，默认 [GOAL, EXPERIENCE, HABIT]")),
                                Map.entry("maxArchive", Map.of("type", "integer", "description", "cancel 最多归档数量，默认 5")),
                                Map.entry("minScore", Map.of("type", "number", "description", "cancel 最小相关性阈值（0-1），默认 0.5")),
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
                .tags(List.of("memory", "recall", "remember", "store", "save", "knowledge", "history", "search",
                        "forget", "cancel", "tag", "update", "delete", "create", "archive"))
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
                .description("Search documents bound to the current session by semantic similarity. Use memory(action=search) for entities and memory(action=recall) for conversations.")
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
                .tags(List.of("knowledge", "search", "rag", "retrieve", "query", "document", "session"))
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

    /**
     * 从工具输入 context 中的 sessionId 反查 ProjectContext。
     *
     * <p>resolver / chatSessionRepo 缺失、sessionId 为空或查询异常时返回 null，
     * 调用方按 null 走 fallback（{@link MemoryReadFilter#userMemory()} 等）。</p>
     */
    @Nullable
    private ProjectContext resolveProjectContext(ToolInput input) {
        if (projectContextResolver == null || chatSessionRepository == null) {
            return null;
        }
        String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            var session = chatSessionRepository.findById(sessionId);
            if (session.isEmpty()) {
                return null;
            }
            return projectContextResolver.resolve(session.get().projectId());
        } catch (Exception e) {
            log.debug("记忆工具解析 ProjectContext 失败, 回退默认 filter: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * ctx 为 null 时按 scopes 走 fallback；非 null 时按项目/主账户 space + scope 组合。
     *
     * <p>仅为保留调用点可读性；实际逻辑 delegate 到
     * {@link MemoryReadFilter#fromProjectContextOrFallback}。</p>
     */
    private MemoryReadFilter toProjectFilter(@Nullable ProjectContext ctx, Set<MemoryScope> scopes) {
        return MemoryReadFilter.fromProjectContextOrFallback(
                ctx != null,
                ctx != null ? ctx.projectSpaceId() : null,
                ctx != null ? ctx.personalSpaceId() : null,
                ctx != null ? ctx.experienceSpaceId() : null,
                ctx != null && ctx.isolated(),
                scopes);
    }

    /**
     * 按 ProjectContext 构造工具级写入上下文。
     *
     * <p>ISOLATED 项目 → spaceId=ctx.projectSpaceId() 将实体落到项目域；
     * 主账户或 SHARED → spaceId=null 让 SemanticMemory 按 entity type 推断默认 space。
     * memoryScope 一律保留 null，避免错误限定 scope（同 RealtimeExtractor 的策略）。</p>
     */
    private MemoryWriteContext toProjectWriteContext(@Nullable ProjectContext ctx,
                                                     @Nullable String sessionId) {
        String spaceId = (ctx != null && ctx.isolated()) ? ctx.projectSpaceId() : null;
        return new MemoryWriteContext(
                spaceId,
                null,
                MemoryOriginType.TOOL,
                MemoryRealityType.UNKNOWN,
                sessionId,
                sessionId,
                sessionId,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    ToolResult executeSearch(ToolInput input) {
        int defaultTopK = memoryProperties != null ? memoryProperties.getAgenticTool().getDefaultTopK() : 10;
        try {
            String query = input.getParam("query", String.class);
            int topK = input.getOptionalParam("top_k", Integer.class).orElse(defaultTopK);
            MemoryReadFilter filter = toProjectFilter(resolveProjectContext(input),
                    Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
            List<RetrievalResult> results = hybridRetriever.retrieve(query, topK, RetrievalWeights.DEFAULT, filter);
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
            String conversationId = input.getOptionalParam("conversationId", String.class)
                    .orElseGet(() -> input.getContextValue("sessionId", String.class).orElse(null));
            EntityType entityType = EntityType.valueOf(typeStr.toUpperCase());
            var now = Instant.now();
            var incoming = new TemporalEntity(null, entityType, name, description, Map.of(), 1, true,
                    now, null, conversationId, 1.0f, 0.5f, 0, null, now, now);
            MemoryWriteContext writeContext = toProjectWriteContext(resolveProjectContext(input), conversationId);
            var created = SqliteBusyRetry.execute(() ->
                    semanticMemory.upsertWithConflictDetection(incoming, conversationId, writeContext));
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
            String newName = input.getOptionalParam("name", String.class).orElse(entity.name());
            String newDesc = input.getOptionalParam("description", String.class).orElse(entity.description());
            EntityType newType = input.getOptionalParam("entityType", String.class).map(s -> EntityType.valueOf(s.toUpperCase())).orElse(entity.type());
            String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
            MemoryWriteContext writeContext = toProjectWriteContext(resolveProjectContext(input), sessionId);
            var now = Instant.now();

            // 改名时 (name, type) 是冲突检测的 identity key，直接 upsert 会被当作新实体
            // 因此走「归档旧实体 + 创建新实体」路径
            if (!newName.equals(entity.name())) {
                SqliteBusyRetry.run(() -> semanticMemory.archive(entity));
                var renamed = new TemporalEntity(null, newType, newName, newDesc,
                        entity.properties(), 1, true,
                        now, null, entity.sourceConversationId(),
                        entity.extractionConfidence(), entity.importanceScore(),
                        entity.accessCount(), entity.lastAccessedAt(), entity.createdAt(), now);
                var result = SqliteBusyRetry.execute(() ->
                        semanticMemory.upsertWithConflictDetection(renamed, sessionId, writeContext));
                return ToolResult.success(Map.of(
                        "id", result.id(), "name", result.name(), "type", result.type().name(),
                        "version", result.version(), "description", result.description() != null ? result.description() : ""));
            }

            var updated = new TemporalEntity(entity.id(), newType, entity.name(), newDesc,
                    entity.properties(), entity.version(), entity.isCurrent(),
                    entity.validFrom(), entity.validTo(), entity.sourceConversationId(),
                    entity.extractionConfidence(), entity.importanceScore(),
                    entity.accessCount(), entity.lastAccessedAt(), entity.createdAt(), now);
            var result = SqliteBusyRetry.execute(() ->
                    semanticMemory.upsertWithConflictDetection(updated, sessionId, writeContext));
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
            SqliteBusyRetry.run(() -> semanticMemory.archive(entity));
            return ToolResult.success(Map.of("id", entity.id(), "name", entity.name(), "archived", true));
        } catch (Exception e) {
            log.error("删除记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("删除记忆失败: " + e.getMessage());
        }
    }

    /** cancel 默认归档的实体类型 — 覆盖用户"不再做 X"最常踩到的三类持久意图。 */
    private static final Set<EntityType> DEFAULT_CANCEL_TYPES =
            Set.of(EntityType.GOAL, EntityType.EXPERIENCE, EntityType.HABIT);

    /** cancel 默认最多归档数量 — 防止语义召回误伤过多实体。 */
    private static final int DEFAULT_CANCEL_MAX_ARCHIVE = 5;

    /** cancel 默认最小相关性阈值 — 低于此分数的候选不归档。 */
    private static final float DEFAULT_CANCEL_MIN_SCORE = 0.5f;

    /**
     * 按语义批量归档已取消的目标/经验/习惯。
     *
     * <p>与 delete 的区别：delete 精确按 entityId 删单条；cancel 按语义描述召回候选集，
     * 批量归档最相关的若干条。适配"取消定时任务"这类需要级联清理多个旧记忆的场景。</p>
     */
    ToolResult executeCancel(ToolInput input) {
        String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
        try {
            String query = input.getParam("query", String.class);
            if (query == null || query.isBlank()) {
                return ToolResult.error("cancel 需要传入 query 参数描述要取消的事物");
            }

            Set<EntityType> targetTypes = parseCancelTypes(input);
            int maxArchive = input.getOptionalParam("maxArchive", Integer.class)
                    .filter(n -> n > 0).orElse(DEFAULT_CANCEL_MAX_ARCHIVE);
            float minScore = input.getOptionalParam("minScore", Number.class)
                    .map(Number::floatValue).orElse(DEFAULT_CANCEL_MIN_SCORE);

            // 召回候选：跨 USER_PROFILE/USER_FACT 与 AGENT_EXPERIENCE 两个域
            // （EXPERIENCE 类实体落在 AGENT_EXPERIENCE，不能用 userMemory() 过滤掉）
            // 有 ProjectContext 时按 project + 主账户 space 限定；无则回退全量
            //
            // 跨 scope 说明：隔离项目的 cancel 允许跨主账户归档 — 符合 ProjectContext 读主账户
            // 的设计（参见 MemoryReadFilter.buildForProject 的 space 合并策略）。用户在项目内
            // 说"取消 X"时，希望清掉的是包括主账户同名目标/习惯/经验在内的全量匹配，
            // 而非只在项目 space 内生效。此处不做额外限制，保持与 search/searchExperience 一致。
            int retrieveTopK = Math.max(maxArchive * 3, 10);
            MemoryReadFilter cancelFilter = toProjectFilter(resolveProjectContext(input), Set.of());
            List<RetrievalResult> candidates = hybridRetriever.retrieve(
                    query, retrieveTopK, RetrievalWeights.DEFAULT, cancelFilter);

            var toArchive = candidates.stream()
                    .filter(r -> r.fusedScore() >= minScore)
                    .filter(r -> matchesType(r.entityType(), targetTypes))
                    .limit(maxArchive)
                    .toList();

            if (toArchive.isEmpty()) {
                log.info("记忆 cancel: 无命中, query={}, types={}, sessionId={}",
                        query, formatTypes(targetTypes), sessionId);
                return ToolResult.success(Map.of(
                        "archived", List.of(),
                        "count", 0,
                        "message", "未找到相关的 " + formatTypes(targetTypes) + " 记忆，无需归档"));
            }

            var archivedList = new ArrayList<Map<String, Object>>();
            for (var r : toArchive) {
                var entityOpt = semanticMemory.findById(r.entityId());
                if (entityOpt.isEmpty()) {
                    continue;
                }
                var entity = entityOpt.get();
                SqliteBusyRetry.run(() -> semanticMemory.archive(entity));
                archivedList.add(Map.of(
                        "id", entity.id(),
                        "name", entity.name(),
                        "type", entity.type().name(),
                        "score", r.fusedScore()));
                log.info("记忆 cancel: 归档实体, id={}, name={}, type={}, score={}, sessionId={}",
                        entity.id(), entity.name(), entity.type(), r.fusedScore(), sessionId);
            }

            return ToolResult.success(Map.of(
                    "archived", archivedList,
                    "count", archivedList.size()));
        } catch (Exception e) {
            log.error("批量取消记忆失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return ToolResult.error("批量取消记忆失败: " + e.getMessage());
        }
    }

    /** 解析 cancel 的 entityTypes 参数，非法类型跳过，全非法则用默认集合。 */
    private Set<EntityType> parseCancelTypes(ToolInput input) {
        var raw = input.getOptionalParam("entityTypes", List.class);
        if (raw.isEmpty() || raw.get().isEmpty()) {
            return DEFAULT_CANCEL_TYPES;
        }
        var parsed = new LinkedHashSet<EntityType>();
        for (Object item : raw.get()) {
            if (item == null) continue;
            try {
                parsed.add(EntityType.valueOf(item.toString().toUpperCase()));
            } catch (IllegalArgumentException ignore) {
                log.debug("cancel: 跳过非法 entityType={}", item);
            }
        }
        return parsed.isEmpty() ? DEFAULT_CANCEL_TYPES : parsed;
    }

    private boolean matchesType(String entityTypeName, Set<EntityType> targetTypes) {
        if (entityTypeName == null) return false;
        try {
            return targetTypes.contains(EntityType.valueOf(entityTypeName));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String formatTypes(Set<EntityType> types) {
        return types.stream().map(Enum::name)
                .collect(java.util.stream.Collectors.joining("/"));
    }

    ToolResult executeTag(ToolInput input) {
        try {
            String sourceId = input.getParam("sourceEntityId", String.class);
            String targetId = input.getParam("targetEntityId", String.class);
            String relationType = input.getParam("relationType", String.class);
            float strength = input.getOptionalParam("strength", Number.class).map(Number::floatValue).orElse(0.5f);
            String conversationId = input.getOptionalParam("conversationId", String.class).orElse(null);
            String sessionId = input.getContextValue("sessionId", String.class).orElse(conversationId);
            MemoryWriteContext writeContext = toProjectWriteContext(resolveProjectContext(input), sessionId);
            var now = Instant.now();
            var relation = new TemporalRelation(UUID.randomUUID().toString(), sourceId, targetId, relationType, strength,
                    null, now, null, conversationId, now);
            SqliteBusyRetry.run(() -> semanticMemory.addRelation(relation, writeContext));
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

            // 三路混合检索，限定 AGENT_EXPERIENCE scope；有 ProjectContext 时同时限定 space
            var filter = toProjectFilter(resolveProjectContext(input), Set.of(MemoryScope.AGENT_EXPERIENCE));
            List<RetrievalResult> ranked = hybridRetriever.retrieve(query, topK * 3, RetrievalWeights.DEFAULT, filter);

            // 批量查询完整实体（含 properties: lessons, toolsUsed 等）
            var hitIds = ranked.stream().map(RetrievalResult::entityId)
                    .collect(java.util.stream.Collectors.toSet());
            Map<String, TemporalEntity> entityMap = hitIds.isEmpty()
                    ? Map.of()
                    : semanticMemory.findByIds(hitIds, filter);

            // 按检索排序保留语义相关性，过滤后截取 topK
            boolean crossContext = memoryProperties != null
                    && memoryProperties.getExperience().getIsolation().isCrossContextRetrieval();
            var results = ranked.stream()
                    .map(r -> entityMap.get(r.entityId()))
                    .filter(Objects::nonNull)
                    .filter(e -> crossContext || isMainAgentContext(e))
                    .filter(e -> !successOnly || Boolean.TRUE.equals(e.properties().get("success")))
                    .limit(topK)
                    .map(this::experienceEntityToMap)
                    .toList();
            return ToolResult.success(Map.of("results", results, "count", results.size()));
        } catch (Exception e) {
            log.error("经验检索工具执行失败: error={}", e.getMessage(), e);
            return ToolResult.error("经验检索失败: " + e.getMessage());
        }
    }

    private boolean isMainAgentContext(TemporalEntity e) {
        var ctx = e.properties().get("executionContext");
        return ctx == null || "MAIN_AGENT".equals(ctx.toString());
    }

    private Map<String, Object> experienceEntityToMap(TemporalEntity e) {
        var m = new LinkedHashMap<String, Object>();
        m.put("entityId", e.id());
        m.put("scenario", e.name());
        m.put("strategy", e.description() != null ? e.description() : "");
        m.put("lessons", e.properties().getOrDefault("lessons", List.of()));
        m.put("toolsUsed", e.properties().getOrDefault("toolsUsed", List.of()));
        m.put("success", e.properties().getOrDefault("success", false));
        m.put("importanceScore", e.importanceScore());
        return Map.copyOf(m);
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

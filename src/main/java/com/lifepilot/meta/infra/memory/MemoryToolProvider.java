package com.lifepilot.meta.infra.memory;

import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.knowledge.retrieve.SessionKnowledgeScopeResolver;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.memory.episodic.ConversationSnippetRecord;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.ExperienceRanking;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.memory.store.support.MemoryQuerySignals;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolution;
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
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.Objects;

/**
 * 记忆管理工具提供者。
 *
 * <p>集中管理两个元能力工具：memory / memory。</p>
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
    private final EpisodicMemory episodicMemory;
    private final DocumentRetriever documentRetriever;
    private final SessionKnowledgeBaseRepository sessionKbRepo;
    private final SessionKnowledgeScopeResolver sessionKnowledgeScopeResolver;
    private final MemoryRetrievalProperties memoryProperties;
    private final ProjectContextResolver projectContextResolver;
    private final ChatSessionRepository chatSessionRepository;
    private final MemoryAccessPolicy memoryAccessPolicy;

    public MemoryToolProvider(HybridRetriever hybridRetriever,
                              SemanticMemory semanticMemory,
                              EpisodicMemory episodicMemory,
                              DocumentRetriever documentRetriever,
                              SessionKnowledgeBaseRepository sessionKbRepo,
                              SessionKnowledgeScopeResolver sessionKnowledgeScopeResolver,
                              MemoryRetrievalProperties memoryProperties,
                              ProjectContextResolver projectContextResolver,
                              ChatSessionRepository chatSessionRepository,
                              MemoryAccessPolicy memoryAccessPolicy) {
        this.hybridRetriever = Objects.requireNonNull(hybridRetriever, "hybridRetriever");
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory");
        this.episodicMemory = Objects.requireNonNull(episodicMemory, "episodicMemory");
        this.documentRetriever = Objects.requireNonNull(documentRetriever, "documentRetriever");
        this.sessionKbRepo = Objects.requireNonNull(sessionKbRepo, "sessionKbRepo");
        this.sessionKnowledgeScopeResolver = Objects.requireNonNull(sessionKnowledgeScopeResolver, "sessionKnowledgeScopeResolver");
        this.memoryProperties = Objects.requireNonNull(memoryProperties, "memoryProperties");
        this.projectContextResolver = Objects.requireNonNull(projectContextResolver, "projectContextResolver");
        this.chatSessionRepository = Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
        this.memoryAccessPolicy = Objects.requireNonNull(memoryAccessPolicy, "memoryAccessPolicy");
    }

    public void registerTools(DynamicToolRegistry toolRegistry) {
        var tools = buildMemoryTools();
        tools.forEach(toolRegistry::registerBuiltinTool);
        log.info("记忆/资料检索工具注册完成: count={}", tools.size());
    }

    public List<BuiltinTool> buildMemoryTools() {
        var tools = new java.util.ArrayList<BuiltinTool>();
        var memoryExecutor = new MemoryActionDispatchExecutor(this);
        tools.add(buildMemoryTool(memoryExecutor));
        return List.copyOf(tools);
    }

    private BuiltinTool buildMemoryTool(MemoryActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("memory")
                .category(ToolCategory.ACTION)
                .name("记忆管理")
                .description("""
                        搜索并管理用户长期记忆。action 控制具体语义：search/recall/create/update/delete/cancel/complete/supersede/tag/query-at-time/search-experience。
                        用户说"取消/不再/以后别提/停止提醒"时优先用 cancel；带 MT-xxx 等编号时把编号原样放入 query。
                        complete 只允许 GOAL/PROJECT，禁止用于 PREFERENCE/HABIT/CUSTOM。tag 禁止把关系连到 __consolidated_profile 这类派生画像。
                        search 默认搜索长期记忆（scope=memory）；scope=knowledge 时搜索会话绑定的知识库文档。""")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("search", "recall", "create", "update", "delete",
                                                "cancel", "complete", "supersede", "tag", "query-at-time", "search-experience"),
                                        "description", "记忆操作类型。search=搜索知识实体, recall=回忆历史对话, " +
                                                "create/update/delete=实体 CRUD, cancel=撤销/取消（支持 entityId 单条或 query 精确编号/语义批量）, " +
                                                "complete=仅标记 GOAL/PROJECT 已完成，禁止用于偏好/习惯/自定义画像, supersede=旧实体被新实体替代, " +
                                                "tag=建立关系, query-at-time=时间点查询, search-experience=检索执行经验")),
                                Map.entry("query", Map.of("type", "string", "description", "搜索关键词或语义描述；search/recall/search-experience 必填；cancel 未传 entityId 时必填，若用户给出 MT-xxx/RQ-xxx 编号必须原样包含")),
                                Map.entry("topK", Map.of("type", "integer", "description", "返回数量；search/recall/search-experience 使用")),
                                Map.entry("scope", Map.of("type", "string",
                                        "enum", List.of("memory", "knowledge"),
                                        "description", "search 时有效：memory=搜索长期记忆（默认），knowledge=搜索绑定的资料文档")),

                                Map.entry("name", Map.of("type", "string", "description", "实体名称；create 必填，update 时可选改名")),
                                Map.entry("entityType", Map.of("type", "string", "description", "实体类型；create 必填，query-at-time 时可选过滤", "enum", List.of("PERSON", "ORGANIZATION", "PLACE", "EVENT", "PROJECT", "TOPIC", "PREFERENCE", "HABIT", "GOAL", "SKILL", "EXPERIENCE", "CUSTOM"))),
                                Map.entry("entityTypes", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string", "enum", List.of("PERSON", "ORGANIZATION", "PLACE", "EVENT", "PROJECT", "TOPIC", "PREFERENCE", "HABIT", "GOAL", "SKILL", "EXPERIENCE", "CUSTOM")),
                                        "description", "cancel 语义批量模式限定归档的实体类型集合，默认 [GOAL, EXPERIENCE, HABIT]")),
                                Map.entry("maxArchive", Map.of("type", "integer", "description", "cancel 语义批量模式最多归档数量，默认 5")),
                                Map.entry("minScore", Map.of("type", "number", "description", "cancel 语义批量模式最小相关性阈值，默认 0.5；精确编号命中不使用该阈值")),
                                Map.entry("description", Map.of("type", "string", "description", "实体描述")),
                                Map.entry("conversationId", Map.of("type", "string", "description", "来源会话 ID")),
                                Map.entry("entityId", Map.of("type", "string", "description", "实体 ID；update/delete/complete/supersede 必填，cancel 单条模式必填")),
                                Map.entry("newEntityId", Map.of("type", "string", "description", "supersede 专用：替代旧实体的新实体 ID，必填")),
                                Map.entry("sourceEntityId", Map.of("type", "string", "description", "tag 源实体 ID")),
                                Map.entry("targetEntityId", Map.of("type", "string", "description", "tag 目标实体 ID")),
                                Map.entry("relationType", Map.of("type", "string", "description", "tag 关系类型")),
                                Map.entry("strength", Map.of("type", "number", "description", "tag 关系强度 0.0-1.0，默认 0.5")),
                                Map.entry("timestamp", Map.of("type", "string", "description", "query-at-time 的 ISO 8601 时间戳")),
                                Map.entry("successOnly", Map.of("type", "boolean", "description", "search-experience 仅返回成功经验，默认 false"))
                        ),
                        "dependentRequired", Map.of(
                                "search", List.of("query"),
                                "recall", List.of("query"),
                                "create", List.of("name", "entityType"),
                                "update", List.of("entityId"),
                                "delete", List.of("entityId"),
                                "complete", List.of("entityId"),
                                "supersede", List.of("entityId", "newEntityId"),
                                "tag", List.of("sourceEntityId", "targetEntityId"),
                                "query-at-time", List.of("timestamp"),
                                "search-experience", List.of("query")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_MEMORY,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("entityNames", false, "name", "entityId", "sourceEntityId", "targetEntityId")
                ))
                .tags(List.of("记忆", "回忆", "记住", "保存", "知识", "历史", "搜索", "撤销", "归档",
                        "memory", "recall", "remember", "search", "cancel", "create", "update", "delete"))
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    /**
     * 从工具输入 context 中的 sessionId 反查 ProjectContext。
     *
     * <p>sessionId 缺失或找不到会话时解析为主账户上下文；查询或解析失败时返回 failed，
     * 调用方必须拒绝本次记忆操作。</p>
     */
    private ProjectContextResolution resolveProjectContext(ToolInput input) {
        Optional<String> sessionIdValue;
        try {
            sessionIdValue = optionalContextText(input, "sessionId");
        } catch (IllegalArgumentException e) {
            log.warn("记忆工具收到非法 sessionId，拒绝记忆操作: error={}", e.getMessage());
            return ProjectContextResolution.failed(e.getMessage());
        }
        if (sessionIdValue.isEmpty()) {
            try {
                return ProjectContextResolution.resolved(projectContextResolver.resolve(null));
            } catch (Exception e) {
                log.warn("记忆工具解析主账户上下文失败，拒绝记忆操作: error={}", e.getMessage());
                return ProjectContextResolution.failed("personal_context_resolution_failed");
            }
        }
        String sessionId = sessionIdValue.get();
        Optional<com.lifepilot.interaction.web.model.ChatSession> session;
        try {
            session = chatSessionRepository.findById(sessionId);
        } catch (Exception e) {
            log.warn("记忆工具查询会话项目归属失败，拒绝记忆操作: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return ProjectContextResolution.failed("chat_session_lookup_failed");
        }
        if (session.isEmpty()) {
            try {
                return ProjectContextResolution.resolved(projectContextResolver.resolve(null));
            } catch (Exception e) {
                log.warn("记忆工具解析主账户上下文失败，拒绝记忆操作: sessionId={}, error={}",
                        sessionId, e.getMessage());
                return ProjectContextResolution.failed("personal_context_resolution_failed");
            }
        }
        String projectId = session.get().projectId();
        try {
            return ProjectContextResolution.resolved(projectContextResolver.resolve(projectId));
        } catch (Exception e) {
            log.warn("记忆工具解析 ProjectContext 失败，拒绝记忆操作: sessionId={}, projectId={}, error={}",
                    sessionId, projectId != null ? projectId : "<personal>", e.getMessage());
            return ProjectContextResolution.failed("project_context_resolution_failed");
        }
    }

    private ToolResult projectContextFailureResult(ProjectContextResolution resolution) {
        return ToolResult.error("项目上下文解析失败，已拒绝记忆操作以避免写入或读取错误空间: "
                + resolution.reason());
    }

    private MemoryReadFilter toProjectFilter(ProjectContext ctx, Set<MemoryScope> scopes) {
        return memoryAccessPolicy.buildProjectReadFilter(ctx, scopes);
    }

    /**
     * 按 ProjectContext 构造工具级写入上下文。
     *
     * <p>ISOLATED 项目 → spaceId=ctx.projectSpaceId() 将实体落到项目域；
     * 主账户或 SHARED → spaceId=null 让 SemanticMemory 按 entity type 推断默认 space。
     * memoryScope 一律保留 null，避免错误限定 scope（同 RealtimeExtractor 的策略）。</p>
     */
    private MemoryWriteContext toProjectWriteContext(ProjectContext ctx,
                                                     @Nullable String sessionId) {
        return memoryAccessPolicy.buildProjectWriteContext(ctx, sessionId, null, null, sessionId);
    }

    /**
     * 构造工具写操作的实体可写范围。
     *
     * <p>隔离项目遵循单向隔离：可以继承读取主账户记忆，但显式 update/delete/cancel
     * 等写操作只能作用于项目 space。主账户 / 非隔离项目写入默认主账户 personal +
     * experience 空间。</p>
     */
    private MemoryReadFilter toWritableEntityFilter(ProjectContext ctx) {
        return memoryAccessPolicy.buildWritableEntityFilter(ctx);
    }

    private Optional<TemporalEntity> findWritableEntity(String entityId, ProjectContext ctx) {
        return semanticMemory.findByIds(List.of(entityId), toWritableEntityFilter(ctx))
                .values()
                .stream()
                .findFirst();
    }

    private Optional<TemporalEntity> findReadableEntity(String entityId, ProjectContext ctx) {
        return semanticMemory.findByIds(List.of(entityId), toProjectFilter(ctx, Set.of()))
                .values()
                .stream()
                .findFirst();
    }

    ToolResult executeSearch(ToolInput input) {
        try {
            String scope = optionalParam(input, "scope", String.class)
                    .map(value -> requireCleanText(value, "scope"))
                    .orElse("memory");
            if ("knowledge".equals(scope)) {
                return executeKnowledgeSearch(input);
            }
            if (!"memory".equals(scope)) {
                return ToolResult.error("无效的 scope 参数: " + scope);
            }
            int defaultTopK = memoryProperties.getAgenticTool().getDefaultTopK();
            String query = input.getParam("query", String.class);
            if (query.isBlank()) {
                return ToolResult.error("query 参数不能为空");
            }
            int topK = parsePositiveInt(input, "topK", defaultTopK);
            ProjectContextResolution resolution = resolveProjectContext(input);
            if (resolution.failed()) {
                return projectContextFailureResult(resolution);
            }
            MemoryReadFilter filter = toProjectFilter(resolution.context(),
                    Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
            int candidateTopK = Math.max(topK * 3, topK);
            List<RetrievalResult> rawResults = hybridRetriever.retrieve(
                    query, candidateTopK, RetrievalWeights.DEFAULT, filter);
            Map<String, TemporalEntity> entityMap = loadSearchEntities(rawResults, filter);
            List<RetrievalResult> consumableResults = rawResults.stream()
                    .filter(result -> {
                        TemporalEntity entity = entityMap.get(result.entityId());
                        return MemoryQualityPolicy.isPromptConsumable(entity);
                    })
                    .toList();
            List<RetrievalResult> results = consumableResults.stream()
                    .limit(topK)
                    .toList();
            int qualityFilteredCount = Math.max(0, rawResults.size() - consumableResults.size());
            int truncatedCount = Math.max(0, consumableResults.size() - results.size());
            if (!results.isEmpty()) {
                hybridRetriever.updateAccessCounts(results);
            }
            List<Map<String, Object>> items = results.stream()
                    .map(result -> retrievalResultToMap(result, entityMap.get(result.entityId())))
                    .toList();
            return ToolResult.success(Map.of(
                    "results", items,
                    "count", items.size(),
                    "rawCount", rawResults.size(),
                    "qualityFilteredCount", qualityFilteredCount,
                    "truncatedCount", truncatedCount,
                    "filteredOutCount", qualityFilteredCount + truncatedCount));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("搜索记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("搜索记忆失败: " + e.getMessage());
        }
    }

    private ToolResult executeKnowledgeSearch(ToolInput input) {
        int defaultTopK = memoryProperties.getAgenticTool().getDocsDefaultTopK();
        try {
            String query = input.getParam("query", String.class);
            if (query.isBlank()) {
                return ToolResult.error("query 参数不能为空");
            }
            int topK = parsePositiveInt(input, "topK", defaultTopK);
            String sessionId = optionalContextText(input, "sessionId").orElse(null);
            if (sessionId == null) {
                return ToolResult.success(Map.of("results", List.of(), "count", 0,
                        "message", "无法获取当前会话 ID"));
            }
            // 单轮 override 优先：若当前 turn 传入了 overrideKnowledgeBaseIds，使用 override 替代会话持久化绑定
            List<String> kbIds = resolveEffectiveKnowledgeBaseIds(input, sessionId);
            if (kbIds.isEmpty()) {
                return ToolResult.success(Map.of("results", List.of(), "count", 0,
                        "message", "当前会话未绑定知识库"));
            }
            List<KnowledgeSearchScope> scopes = kbIds.stream()
                    .map(KnowledgeSearchScope::new)
                    .toList();
            List<DocumentSearchResult> results = documentRetriever.retrieveByScopes(query, scopes, topK);
            List<Map<String, Object>> items = results.stream().map(this::docSearchResultToMap).toList();
            return ToolResult.success(Map.of("results", items, "count", items.size()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("检索资料失败: {}", e.getMessage(), e);
            return ToolResult.error("检索资料失败: " + e.getMessage());
        }
    }

    /** 解析本轮应生效的知识库 ID 列表：单轮 override 优先 → 会话持久化绑定兜底。 */
    private List<String> resolveEffectiveKnowledgeBaseIds(ToolInput input, String sessionId) {
        var callerState = input.getContextValue(
                com.lifepilot.tool.model.ToolContextKeys.CALLER_STATE,
                com.lifepilot.agent.model.ReactAgentState.class
        ).orElse(null);
        if (callerState != null
                && callerState.overrideKnowledgeBaseIds() != null
                && !callerState.overrideKnowledgeBaseIds().isEmpty()) {
            return requireCleanTexts(callerState.overrideKnowledgeBaseIds(), "overrideKnowledgeBaseIds");
        }
        return requireCleanTexts(sessionKbRepo.findKnowledgeBaseIdsBySessionId(sessionId), "knowledgeBaseId");
    }

    ToolResult executeRecall(ToolInput input) {
        int defaultTopK = memoryProperties.getAgenticTool().getDefaultTopK();
        try {
            String query = input.getParam("query", String.class);
            if (query.isBlank()) {
                return ToolResult.error("query 参数不能为空");
            }
            int topK = parsePositiveInt(input, "topK", defaultTopK);
            String sessionId = optionalContextText(input, "sessionId").orElse(null);
            if (sessionId == null) {
                return ToolResult.error("无法获取当前会话 ID");
            }
            List<ConversationSnippetRecord> snippets = episodicMemory.searchSnippetsExcludingSession(query, sessionId, topK);
            List<Map<String, Object>> items = snippets.stream().map(this::conversationSnippetToMap).toList();
            return ToolResult.success(Map.of("results", items, "count", items.size()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("回忆对话失败: {}", e.getMessage(), e);
            return ToolResult.error("回忆对话失败: " + e.getMessage());
        }
    }

    /**
     * 为 GOAL/EVENT/PROJECT 从名称/描述中确定性提取截止日期写入 properties.dueAt（memory-deadline-awareness）。
     * 与 RealtimeExtractor 共用 {@link DueDateExtractor}，覆盖 Agent 经 memory 工具显式建目标的路径。
     */
    private Map<String, Object> deriveDueDateProperties(EntityType type, String name, String description) {
        if (type != EntityType.GOAL && type != EntityType.EVENT && type != EntityType.PROJECT) {
            return Map.of();
        }
        String text = (name != null ? name : "") + " " + (description != null ? description : "");
        return com.lifepilot.agent.learning.extraction.DueDateExtractor.extractIsoDate(text)
                .<Map<String, Object>>map(iso -> Map.of("dueAt", iso))
                .orElse(Map.of());
    }

    ToolResult executeCreate(ToolInput input) {
        try {
            String name = requireCleanText(input.getParam("name", String.class), "name");
            String typeStr = requireCleanText(input.getParam("entityType", String.class), "entityType");
            String description = optionalParam(input, "description", String.class)
                    .map(value -> requireCleanText(value, "description"))
                    .orElse(null);
            String conversationId = optionalParam(input, "conversationId", String.class)
                    .map(value -> requireCleanText(value, "conversationId"))
                    .orElseGet(() -> optionalContextText(input, "sessionId").orElse(null));
            EntityType entityType = parseEntityType(typeStr);
            var now = Instant.now();
            var properties = deriveDueDateProperties(entityType, name, description);
            MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.USER_CONFIRMED;
            float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, 1.0f);
            MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
            var incoming = new TemporalEntity(null, entityType, name, description, properties, 1, true,
                    now, null, conversationId, 1.0f, 0.5f, 0, null, now, now,
                    LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT,
                    null, false, List.of(),
                    evidenceKind, trustLevel, trustScore, 1, now);
            ProjectContextResolution resolution = resolveProjectContext(input);
            if (resolution.failed()) {
                return projectContextFailureResult(resolution);
            }
            MemoryWriteContext writeContext = toProjectWriteContext(resolution.context(), conversationId);
            var created = SqliteBusyRetry.execute(() ->
                    semanticMemory.upsertWithConflictDetection(incoming, conversationId, writeContext));
            return ToolResult.success(Map.of(
                    "id", created.id(), "name", created.name(), "type", created.type().name(), "version", created.version()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("创建记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("创建记忆失败: " + e.getMessage());
        }
    }

    ToolResult executeUpdate(ToolInput input) {
        try {
            String entityId = requireCleanText(input.getParam("entityId", String.class), "entityId");
            ProjectContextResolution resolution = resolveProjectContext(input);
            if (resolution.failed()) {
                return projectContextFailureResult(resolution);
            }
            ProjectContext projectContext = resolution.context();
            var existing = findWritableEntity(entityId, projectContext);
            if (existing.isEmpty()) {
                if (!projectContext.isolated()) {
                    return ToolResult.error("实体不存在或不在当前可写范围内: " + entityId + "，请用 search 查找正确 ID");
                }
                var inherited = findReadableEntity(entityId, projectContext);
                if (inherited.isEmpty()) {
                    return ToolResult.error("实体不存在或不在当前可写范围内: " + entityId + "，请用 search 查找正确 ID");
                }
                return executeUpdateInheritedAsOverlay(input, inherited.get(), projectContext);
            }
            var entity = existing.get();
            String newName = optionalParam(input, "name", String.class)
                    .map(value -> requireCleanText(value, "name"))
                    .orElse(entity.name());
            String newDesc = optionalParam(input, "description", String.class)
                    .map(value -> requireCleanText(value, "description"))
                    .orElse(entity.description());
            EntityType newType = optionalParam(input, "entityType", String.class)
                    .map(this::parseEntityType)
                    .orElse(entity.type());
            String sessionId = optionalContextText(input, "sessionId").orElse(null);
            MemoryWriteContext writeContext = toProjectWriteContext(projectContext, sessionId);
            var now = Instant.now();
            MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.USER_CONFIRMED;
            float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, 1.0f);
            MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);

            // 改名时 (name, type) 是冲突检测的 identity key，直接 upsert 会被当作新实体
            // 因此走「归档旧实体 + 创建新实体」路径
            if (!newName.equals(entity.name())) {
                SqliteBusyRetry.run(() -> semanticMemory.archive(entity, ChangeSource.TOOL_EXPLICIT));
                var renamed = new TemporalEntity(null, newType, newName, newDesc,
                        entity.properties(), 1, true,
                        now, null, entity.sourceConversationId(),
                        entity.extractionConfidence(), entity.importanceScore(),
                        entity.accessCount(), entity.lastAccessedAt(), entity.createdAt(), now,
                        LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT,
                        null, false, List.of(),
                        evidenceKind, trustLevel, trustScore, 1, now);
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
                    entity.accessCount(), entity.lastAccessedAt(), entity.createdAt(), now,
                    entity.lifecycleState(), entity.lifecycleReason(), entity.expiresAt(),
                    entity.temporality(), entity.succeededBy(), entity.isDerived(), entity.derivationSources(),
                    evidenceKind, trustLevel, trustScore, 1, now);
            var result = SqliteBusyRetry.execute(() ->
                    semanticMemory.upsertWithConflictDetection(updated, sessionId, writeContext));
            return ToolResult.success(Map.of(
                    "id", result.id(), "name", result.name(), "type", result.type().name(),
                    "version", result.version(), "description", result.description() != null ? result.description() : ""));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("更新记忆失败: {}", e.getMessage(), e);
            return ToolResult.error("更新记忆失败: " + e.getMessage());
        }
    }

    private ToolResult executeUpdateInheritedAsOverlay(ToolInput input,
                                                       TemporalEntity baseEntity,
                                                       ProjectContext projectContext) {
        try {
            String newName = optionalParam(input, "name", String.class)
                    .map(value -> requireCleanText(value, "name"))
                    .orElse(baseEntity.name());
            String newDesc = optionalParam(input, "description", String.class)
                    .map(value -> requireCleanText(value, "description"))
                    .orElse(baseEntity.description());
            EntityType newType = optionalParam(input, "entityType", String.class)
                    .map(this::parseEntityType)
                    .orElse(baseEntity.type());
            String sessionId = optionalContextText(input, "sessionId").orElse(null);
            var now = Instant.now();
            MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.USER_CONFIRMED;
            float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, 1.0f);
            MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
            var overlay = new TemporalEntity(
                    null,
                    newType,
                    newName,
                    newDesc,
                    baseEntity.properties(),
                    1,
                    true,
                    now,
                    null,
                    baseEntity.sourceConversationId(),
                    baseEntity.extractionConfidence(),
                    baseEntity.importanceScore(),
                    0,
                    null,
                    now,
                    now,
                    baseEntity.lifecycleState(),
                    baseEntity.lifecycleReason(),
                    baseEntity.expiresAt(),
                    baseEntity.temporality(),
                    baseEntity.succeededBy(),
                    baseEntity.isDerived(),
                    baseEntity.derivationSources(),
                    evidenceKind,
                    trustLevel,
                    trustScore,
                    1,
                    now);
            MemoryWriteContext writeContext = toProjectWriteContext(projectContext, sessionId);
            var result = SqliteBusyRetry.execute(() ->
                    semanticMemory.upsertProjectOverlay(overlay, baseEntity, sessionId, writeContext));
            return ToolResult.success(Map.of(
                    "id", result.id(),
                    "baseEntityId", baseEntity.id(),
                    "name", result.name(),
                    "type", result.type().name(),
                    "version", result.version(),
                    "overlay", true,
                    "description", result.description() != null ? result.description() : ""));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("更新继承记忆 overlay 失败: baseEntityId={}, error={}", baseEntity.id(), e.getMessage(), e);
            return ToolResult.error("更新继承记忆失败: " + e.getMessage());
        }
    }

    ToolResult executeDelete(ToolInput input) {
        try {
            String entityId = requireCleanText(input.getParam("entityId", String.class), "entityId");
            ProjectContextResolution resolution = resolveProjectContext(input);
            if (resolution.failed()) {
                return projectContextFailureResult(resolution);
            }
            ProjectContext projectContext = resolution.context();
            var existing = findWritableEntity(entityId, projectContext);
            if (existing.isEmpty()) {
                return ToolResult.error("实体不存在或不在当前可写范围内: " + entityId + "，请用 search 查找正确 ID");
            }
            var entity = existing.get();
            SqliteBusyRetry.run(() -> semanticMemory.archive(entity, ChangeSource.TOOL_EXPLICIT));
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

    private static final String CONSOLIDATED_PROFILE_NAME = "__consolidated_profile";

    /**
     * 按语义批量归档已取消的实体，或按 entityId 单条转 CANCELLED。
     *
     * <p>两种形态：</p>
     * <ol>
     *   <li>传 {@code entityId}：走单条状态机 — 仅 ACTIVE 实体可 CANCEL，复用
     *       {@link SemanticMemory#updateLifecycleState(String, LifecycleState, String, ChangeSource)}
     *       发事件，适配"把这条具体记忆标记为已取消"场景；</li>
     *   <li>传 {@code query}：语义召回后批量归档最相关若干条 — 默认圈 GOAL/EXPERIENCE/HABIT，
     *       走 {@link SemanticMemory#archive}，适配"取消定时任务"这类需要级联清理多个旧记忆的场景。</li>
     * </ol>
     *
     * <p>与 delete 的区别：delete 是纯物理归档不转生命周期状态；cancel 明确标记为
     * CANCELLED，下游听众（如 ProactiveTaskCancelListener）能据此做级联处理。</p>
     */
    ToolResult executeCancel(ToolInput input) {
        String sessionId = null;

        try {
            sessionId = optionalContextText(input, "sessionId").orElse(null);
            // 优先走单条路径：传了 entityId 即视为明确目标
            var entityIdOpt = optionalParam(input, "entityId", String.class);
            if (entityIdOpt.isPresent()) {
                return executeCancelSingle(input, requireCleanText(entityIdOpt.get(), "entityId"));
            }

            String query = input.getParam("query", String.class);
            if (query == null || query.isBlank()) {
                return ToolResult.error("cancel 需要传入 entityId（单条）或 query（语义批量）");
            }

            Set<EntityType> targetTypes = parseCancelTypes(input);
            int maxArchive = parseCancelMaxArchive(input);
            float minScore = parseCancelMinScore(input);

            int retrieveTopK = Math.max(maxArchive * 3, 10);
            ProjectContextResolution resolution = resolveProjectContext(input);
            if (resolution.failed()) {
                return projectContextFailureResult(resolution);
            }
            ProjectContext projectContext = resolution.context();
            MemoryReadFilter cancelFilter = toWritableEntityFilter(projectContext);
            List<ScoredEntity> exactTargets = findExactCancelTargets(
                    query, cancelFilter, targetTypes, maxArchive);
            if (!exactTargets.isEmpty()) {
                var cancelled = cancelEntities(exactTargets, sessionId, "exact");
                return ToolResult.success(Map.of(
                        "cancelled", cancelled,
                        "count", cancelled.size(),
                        "exact", true));
            }

            List<RetrievalResult> candidates = hybridRetriever.retrieve(
                    query, retrieveTopK, RetrievalWeights.DEFAULT, cancelFilter);

            var toCancel = candidates.stream()
                    .filter(r -> r.fusedScore() >= minScore)
                    .filter(r -> matchesType(r.entityType(), targetTypes))
                    .limit(maxArchive)
                    .map(r -> findWritableEntity(requireCleanText(r.entityId(), "retrieval entityId"), projectContext)
                            .map(entity -> new ScoredEntity(entity, r.fusedScore()))
                            .orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();

            if (toCancel.isEmpty()) {
                log.info("记忆 cancel: 无命中, query={}, types={}, sessionId={}",
                        query, formatTypes(targetTypes), sessionId);
                return ToolResult.success(Map.of(
                        "cancelled", List.of(),
                        "count", 0,
                        "message", "未找到相关的 " + formatTypes(targetTypes) + " 记忆，无需取消"));
            }

            var cancelledList = cancelEntities(toCancel, sessionId, "semantic");

            return ToolResult.success(Map.of(
                    "cancelled", cancelledList,
                    "count", cancelledList.size()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("批量取消记忆失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return ToolResult.error("批量取消记忆失败: " + e.getMessage());
        }
    }

    private List<ScoredEntity> findExactCancelTargets(String query,
                                                      MemoryReadFilter filter,
                                                      Set<EntityType> targetTypes,
                                                      int maxArchive) {
        boolean hasHighSignal = !MemoryQuerySignals.highSignalTerms(query).isEmpty();
        List<String> lookupTerms = MemoryQuerySignals.lookupTerms(query);
        if (lookupTerms.isEmpty()) {
            return List.of();
        }
        List<TemporalEntity> entities = semanticMemory.findAllCurrent(filter);
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        float minExactScore = hasHighSignal ? 4.0f : 2.5f;
        return entities.stream()
                .filter(entity -> entity.lifecycleState() == LifecycleState.ACTIVE)
                .map(entity -> new ScoredEntity(
                        entity,
                        MemoryQuerySignals.textMatchScore(query, entity.name(), entity.description())))
                .filter(scored -> scored.score() >= minExactScore)
                .filter(scored -> hasHighSignal || targetTypes.contains(scored.entity().type()))
                .sorted(Comparator
                        .comparingDouble(ScoredEntity::score).reversed()
                        .thenComparing(scored -> scored.entity().importanceScore(), Comparator.reverseOrder())
                        .thenComparing(scored -> scored.entity().updatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(maxArchive)
                .toList();
    }

    private List<Map<String, Object>> cancelEntities(List<ScoredEntity> targets,
                                                     @Nullable String sessionId,
                                                     String sourcePath) {
        var cancelledList = new ArrayList<Map<String, Object>>();
        for (var target : targets) {
            TemporalEntity entity = target.entity();
            if (entity.lifecycleState() != LifecycleState.ACTIVE) {
                continue;
            }
            SqliteBusyRetry.run(() -> semanticMemory.updateLifecycleState(
                    entity.id(), LifecycleState.CANCELLED, "user-cancel", ChangeSource.TOOL_EXPLICIT));
            cancelledList.add(Map.of(
                    "id", entity.id(),
                    "name", entity.name(),
                    "type", entity.type().name(),
                    "score", target.score(),
                    "sourcePath", sourcePath));
            log.info("记忆 cancel: 转 CANCELLED, id={}, name={}, type={}, score={}, sourcePath={}, sessionId={}",
                    entity.id(), entity.name(), entity.type(), target.score(), sourcePath, sessionId);
        }
        return cancelledList;
    }

    /**
     * 按 entityId 单条将实体状态转为 CANCELLED — 不限类型，所有 ACTIVE 实体都可 cancel。
     *
     * <p>事件发布交给 {@link SemanticMemory#updateLifecycleState} 代办（AFTER_COMMIT），
     * 调用方不再重复 publishEvent；状态机约束由 {@link LifecycleState#canTransitionTo} 负责。</p>
     */
    private ToolResult executeCancelSingle(ToolInput input, String entityId) {
        ProjectContextResolution resolution = resolveProjectContext(input);
        if (resolution.failed()) {
            return projectContextFailureResult(resolution);
        }
        ProjectContext projectContext = resolution.context();
        var existing = findWritableEntity(entityId, projectContext).orElse(null);
        if (existing == null) {
            return ToolResult.error("实体不存在或不在当前可写范围内: " + entityId);
        }
        if (existing.lifecycleState() != LifecycleState.ACTIVE) {
            return ToolResult.error("实体非 ACTIVE 状态，无法取消（当前 "
                    + existing.lifecycleState() + "）");
        }
        try {
            SqliteBusyRetry.run(() -> semanticMemory.updateLifecycleState(
                    entityId, LifecycleState.CANCELLED, "user-cancel", ChangeSource.TOOL_EXPLICIT));
            return ToolResult.success(Map.of(
                    "id", entityId,
                    "name", existing.name(),
                    "type", existing.type().name(),
                    "lifecycleState", LifecycleState.CANCELLED.name()));
        } catch (Exception e) {
            log.error("单条取消记忆失败: entityId={}, error={}", entityId, e.getMessage(), e);
            return ToolResult.error("取消记忆失败: " + e.getMessage());
        }
    }

    /** 允许 complete 的实体类型 — GOAL / PROJECT 具备"是否做完"的自然语义。 */
    private static final Set<EntityType> COMPLETABLE_TYPES =
            Set.of(EntityType.GOAL, EntityType.PROJECT);

    /**
     * 标记实体已完成（COMPLETED）— 仅适用于 GOAL / PROJECT 类型，且必须处于 ACTIVE。
     *
     * <p>其他类型（PREFERENCE / HABIT / PERSON 等）没有"完成"语义，拒绝调用。
     * 由 {@link SemanticMemory#updateLifecycleState} 负责事件发布。</p>
     */
    ToolResult executeComplete(ToolInput input) {
        String entityId;
        try {
            entityId = requireCleanText(input.getParam("entityId", String.class), "entityId");
        } catch (Exception e) {
            return ToolResult.error("complete 需要传入 entityId");
        }
        ProjectContextResolution resolution = resolveProjectContext(input);
        if (resolution.failed()) {
            return projectContextFailureResult(resolution);
        }
        ProjectContext projectContext = resolution.context();
        var existing = findWritableEntity(entityId, projectContext).orElse(null);
        if (existing == null) {
            return ToolResult.error("实体不存在或不在当前可写范围内: " + entityId);
        }
        if (!COMPLETABLE_TYPES.contains(existing.type())) {
            return ToolResult.error("实体类型 " + existing.type().name()
                    + " 不支持 complete（仅 " + formatTypes(COMPLETABLE_TYPES) + "）");
        }
        if (existing.lifecycleState() != LifecycleState.ACTIVE) {
            return ToolResult.error("实体非 ACTIVE 状态，无法完成（当前 "
                    + existing.lifecycleState() + "）");
        }
        try {
            SqliteBusyRetry.run(() -> semanticMemory.updateLifecycleState(
                    entityId, LifecycleState.COMPLETED, "user-complete", ChangeSource.TOOL_EXPLICIT));
            return ToolResult.success(Map.of(
                    "id", entityId,
                    "name", existing.name(),
                    "type", existing.type().name(),
                    "lifecycleState", LifecycleState.COMPLETED.name()));
        } catch (Exception e) {
            log.error("标记记忆完成失败: entityId={}, error={}", entityId, e.getMessage(), e);
            return ToolResult.error("标记记忆完成失败: " + e.getMessage());
        }
    }

    /**
     * 旧实体被新实体取代 — 同时更新 {@code succeeded_by} 外键并转 SUPERSEDED 状态。
     *
     * <p>两步 UPDATE 走同一事务（Spring {@code @Transactional} 在 SemanticMemory
     * 方法上已声明）：先 {@code updateSucceededBy} 写 FK，再 {@code updateLifecycleState}
     * 转状态并发事件。事件 reason 带上 "user-supersede-by:{newId}" 便于下游溯源。</p>
     */
    ToolResult executeSupersede(ToolInput input) {
        String entityId;
        String newEntityId;
        try {
            entityId = requireCleanText(input.getParam("entityId", String.class), "entityId");
            newEntityId = requireCleanText(input.getParam("newEntityId", String.class), "newEntityId");
        } catch (Exception e) {
            return ToolResult.error("supersede 需要 entityId 和 newEntityId 参数");
        }
        if (entityId.equals(newEntityId)) {
            return ToolResult.error("supersede 的 newEntityId 不能与 entityId 相同");
        }

        ProjectContextResolution resolution = resolveProjectContext(input);
        if (resolution.failed()) {
            return projectContextFailureResult(resolution);
        }
        ProjectContext projectContext = resolution.context();
        var existing = findWritableEntity(entityId, projectContext).orElse(null);
        if (existing == null) {
            return ToolResult.error("被替代实体不存在或不在当前可写范围内: " + entityId);
        }
        var newExisting = findWritableEntity(newEntityId, projectContext).orElse(null);
        if (newExisting == null) {
            return ToolResult.error("新实体不存在或不在当前可写范围内: " + newEntityId);
        }
        if (existing.lifecycleState() != LifecycleState.ACTIVE
                && existing.lifecycleState() != LifecycleState.REGENERATION_NEEDED) {
            return ToolResult.error("被替代实体非 ACTIVE/REGENERATION_NEEDED 状态，无法 supersede（当前 "
                    + existing.lifecycleState() + "）");
        }

        try {
            SqliteBusyRetry.run(() -> {
                semanticMemory.updateSucceededBy(entityId, newEntityId);
                semanticMemory.updateLifecycleState(entityId, LifecycleState.SUPERSEDED,
                        "user-supersede-by:" + newEntityId, ChangeSource.TOOL_EXPLICIT);
            });
            return ToolResult.success(Map.of(
                    "id", entityId,
                    "name", existing.name(),
                    "type", existing.type().name(),
                    "lifecycleState", LifecycleState.SUPERSEDED.name(),
                    "succeededBy", newEntityId));
        } catch (Exception e) {
            log.error("替代记忆失败: entityId={}, newEntityId={}, error={}",
                    entityId, newEntityId, e.getMessage(), e);
            return ToolResult.error("替代记忆失败: " + e.getMessage());
        }
    }

    /** 解析 cancel 的 entityTypes 参数。 */
    private Set<EntityType> parseCancelTypes(ToolInput input) {
        var raw = optionalParam(input, "entityTypes", List.class);
        if (raw.isEmpty() || raw.get().isEmpty()) {
            return DEFAULT_CANCEL_TYPES;
        }
        var parsed = new LinkedHashSet<EntityType>();
        for (Object item : raw.get()) {
            if (!(item instanceof String typeName)) {
                throw new IllegalArgumentException("cancel entityTypes 只能包含字符串");
            }
            if (typeName.isBlank()) {
                throw new IllegalArgumentException("cancel entityTypes 不能包含空值");
            }
            if (!typeName.equals(typeName.trim())) {
                throw new IllegalArgumentException("cancel entityTypes不能包含首尾空白: " + typeName);
            }
            try {
                parsed.add(parseEntityType(typeName));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("cancel entityTypes 包含未知实体类型: " + item, ex);
            }
        }
        return java.util.Collections.unmodifiableSet(parsed);
    }

    private int parseCancelMaxArchive(ToolInput input) {
        var value = optionalParam(input, "maxArchive", Integer.class);
        if (value.isEmpty()) {
            return DEFAULT_CANCEL_MAX_ARCHIVE;
        }
        if (value.get() <= 0) {
            throw new IllegalArgumentException("cancel maxArchive 必须大于 0");
        }
        return value.get();
    }

    private float parseCancelMinScore(ToolInput input) {
        var value = optionalParam(input, "minScore", Number.class)
                .map(Number::floatValue);
        if (value.isEmpty()) {
            return DEFAULT_CANCEL_MIN_SCORE;
        }
        float score = value.get();
        if (!(score >= 0.0f)) {
            throw new IllegalArgumentException("cancel minScore 必须是非负数");
        }
        return score;
    }

    private boolean matchesType(String entityTypeName, Set<EntityType> targetTypes) {
        return targetTypes.contains(parseEntityType(entityTypeName));
    }

    private String formatTypes(Set<EntityType> types) {
        return types.stream().map(Enum::name)
                .collect(java.util.stream.Collectors.joining("/"));
    }

    ToolResult executeTag(ToolInput input) {
        try {
            String sourceId = requireCleanText(input.getParam("sourceEntityId", String.class), "sourceEntityId");
            String targetId = requireCleanText(input.getParam("targetEntityId", String.class), "targetEntityId");
            String relationType = requireCleanText(input.getParam("relationType", String.class), "relationType");
            float strength = optionalParam(input, "strength", Number.class).map(Number::floatValue).orElse(0.5f);
            if (!(strength >= 0.0f && strength <= 1.0f)) {
                return ToolResult.error("关系强度必须在 [0,1] 范围内");
            }
            String conversationId = optionalParam(input, "conversationId", String.class)
                    .map(value -> requireCleanText(value, "conversationId"))
                    .orElse(null);
            String sessionId = optionalContextText(input, "sessionId").orElse(conversationId);
            ProjectContextResolution resolution = resolveProjectContext(input);
            if (resolution.failed()) {
                return projectContextFailureResult(resolution);
            }
            ProjectContext projectContext = resolution.context();
            var sourceEntity = findWritableEntity(sourceId, projectContext).orElse(null);
            var targetEntity = findWritableEntity(targetId, projectContext).orElse(null);
            if (sourceEntity == null || targetEntity == null) {
                return ToolResult.error("关系两端实体必须都在当前可写范围内");
            }
            if (isConsolidatedProfile(sourceEntity) || isConsolidatedProfile(targetEntity)) {
                return ToolResult.error("__consolidated_profile 是派生聚合画像，不能作为关系端点；请定位具体 PREFERENCE/HABIT/GOAL 等原子实体");
            }
            MemoryWriteContext writeContext = toProjectWriteContext(projectContext, sessionId);
            var now = Instant.now();
            float relTrust = MemoryQualityPolicy.trustScoreFor(
                    MemoryEvidenceKind.USER_CONFIRMED, strength);
            var relation = new TemporalRelation(UUID.randomUUID().toString(), sourceId, targetId, relationType, strength,
                    null, now, null, conversationId, now,
                    MemoryEvidenceKind.USER_CONFIRMED,
                    MemoryQualityPolicy.trustLevelFor(MemoryEvidenceKind.USER_CONFIRMED, relTrust),
                    relTrust);
            SqliteBusyRetry.run(() -> semanticMemory.addRelation(relation, writeContext));
            return ToolResult.success(Map.of(
                    "id", relation.id(), "relationType", relationType,
                    "sourceEntityId", sourceId, "targetEntityId", targetId));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("添加记忆标签失败: {}", e.getMessage(), e);
            return ToolResult.error("添加记忆标签失败: " + e.getMessage());
        }
    }

    private boolean isConsolidatedProfile(TemporalEntity entity) {
        return entity.type() == EntityType.CUSTOM && CONSOLIDATED_PROFILE_NAME.equals(entity.name());
    }

    ToolResult executeQueryAtTime(ToolInput input) {
        try {
            String timestampStr = requireCleanText(input.getParam("timestamp", String.class), "timestamp");
            Instant instant;
            try {
                instant = Instant.parse(timestampStr);
            } catch (Exception e) {
                return ToolResult.error("无效的时间格式，请使用 ISO 8601 格式（如 2026-01-15T10:30:00Z）");
            }
            ProjectContextResolution resolution = resolveProjectContext(input);
            if (resolution.failed()) {
                return projectContextFailureResult(resolution);
            }
            MemoryReadFilter filter = toProjectFilter(resolution.context(), Set.of());
            List<TemporalEntity> entities = semanticMemory.queryAtTime(instant, filter);
            var typeFilter = optionalParam(input, "entityType", String.class);
            if (typeFilter.isPresent()) {
                try {
                    EntityType filterType = parseEntityType(typeFilter.get());
                    entities = entities.stream().filter(e -> e.type() == filterType).toList();
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
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
            int topK = parsePositiveInt(input, "topK", 3);
            boolean successOnly = optionalParam(input, "successOnly", Boolean.class).orElse(false);
            if (query.isBlank()) {
                return ToolResult.error("query 参数不能为空");
            }

            // 三路混合检索，限定 AGENT_EXPERIENCE scope；有 ProjectContext 时同时限定 space
            ProjectContextResolution resolution = resolveProjectContext(input);
            if (resolution.failed()) {
                return projectContextFailureResult(resolution);
            }
            var filter = toProjectFilter(resolution.context(), Set.of(MemoryScope.AGENT_EXPERIENCE));
            List<RetrievalResult> ranked = hybridRetriever.retrieve(query, topK * 3, RetrievalWeights.DEFAULT, filter);

            // 批量查询完整实体（含 properties: lessons, toolsUsed 等）
            var hitIds = ranked.stream().map(RetrievalResult::entityId)
                    .collect(java.util.stream.Collectors.toSet());
            Map<String, TemporalEntity> entityMap = hitIds.isEmpty()
                    ? Map.of()
                    : semanticMemory.findByIds(hitIds, filter);

            // 按检索召回，过滤后用统一 ExperienceRanking 口径排序，与热摘要 EXPERIENCE section 一致
            boolean crossContext = memoryProperties.getAgenticTool().isCrossContextRetrieval();
            Instant rankNow = Instant.now();
            var results = ranked.stream()
                    .map(r -> entityMap.get(r.entityId()))
                    .filter(Objects::nonNull)
                    .filter(e -> crossContext || isMainAgentContext(e))
                    .filter(MemoryQualityPolicy::isPromptConsumable)
                    .filter(e -> !successOnly || Boolean.TRUE.equals(e.properties().get("success")))
                    .sorted(java.util.Comparator
                            .comparingDouble((TemporalEntity e) -> ExperienceRanking.score(e, rankNow))
                            .reversed())
                    .limit(topK)
                    .map(this::experienceEntityToMap)
                    .toList();
            return ToolResult.success(Map.of("results", results, "count", results.size()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("经验检索工具执行失败: error={}", e.getMessage(), e);
            return ToolResult.error("经验检索失败: " + e.getMessage());
        }
    }

    private boolean isMainAgentContext(TemporalEntity e) {
        var ctx = e.properties().get("executionContext");
        return "MAIN_AGENT".equals(ctx != null ? ctx.toString() : null);
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
        m.put("quality", qualityMap(e));
        return Map.copyOf(m);
    }

    private Map<String, TemporalEntity> loadSearchEntities(List<RetrievalResult> results,
                                                           MemoryReadFilter filter) {
        if (results == null || results.isEmpty()) {
            return Map.of();
        }
        Set<String> ids = results.stream()
                .map(RetrievalResult::entityId)
                .map(id -> requireCleanText(id, "retrieval entityId"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return semanticMemory.findByIds(ids, filter);
    }

    private Map<String, Object> retrievalResultToMap(RetrievalResult result,
                                                     @Nullable TemporalEntity entity) {
        var map = new HashMap<String, Object>();
        map.put("entityId", result.entityId());
        map.put("entityType", result.entityType());
        map.put("name", result.name());
        if (result.description() != null) map.put("description", result.description());
        map.put("score", result.fusedScore());
        map.put("sourcePath", result.sourcePath());
        map.put("importanceScore", result.importanceScore());
        map.put("scoreBreakdown", scoreBreakdownMap(result.scoreBreakdown()));
        map.put("lifecycle", lifecycleMap(result));
        if (entity != null) {
            map.put("quality", qualityMap(entity));
        }
        // Task 30：生命周期闭环标注 — 仅在 true 时输出以节省 token，
        // LLM 据此区分"已完成/派生源失效/需复核"的语义。
        if (result.isHistorical()) map.put("isHistorical", true);
        if (result.isStale()) map.put("isStale", true);
        if (result.needsRevalidation()) map.put("needsRevalidation", true);
        return Map.copyOf(map);
    }

    private Map<String, Object> qualityMap(TemporalEntity entity) {
        var quality = new LinkedHashMap<String, Object>();
        quality.put("trustLevel", entity.trustLevel().name());
        quality.put("evidenceKind", entity.evidenceKind().name());
        quality.put("trustScore", entity.trustScore());
        quality.put("evidenceCount", entity.evidenceCount());
        if (entity.lastVerifiedAt() != null) {
            quality.put("lastVerifiedAt", entity.lastVerifiedAt().toString());
        }
        return Map.copyOf(quality);
    }

    private Map<String, Object> lifecycleMap(RetrievalResult result) {
        return Map.of(
                "isHistorical", result.isHistorical(),
                "isStale", result.isStale(),
                "needsRevalidation", result.needsRevalidation());
    }

    private Map<String, Object> scoreBreakdownMap(RetrievalResult.ScoreBreakdown breakdown) {
        if (breakdown == null) {
            return Map.of();
        }
        return Map.of(
                "vectorScore", breakdown.vectorScore(),
                "vectorWeighted", breakdown.vectorWeighted(),
                "ftsScore", breakdown.ftsScore(),
                "ftsWeighted", breakdown.ftsWeighted(),
                "graphScore", breakdown.graphScore(),
                "graphWeighted", breakdown.graphWeighted(),
                "recencyBoost", breakdown.recencyBoost(),
                "importanceBoost", breakdown.importanceBoost(),
                "trustBoost", breakdown.trustBoost(),
                "lifecycleAdjustment", breakdown.lifecycleAdjustment());
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
        return Map.copyOf(map);
    }

    private List<KnowledgeSearchScope> resolveKnowledgeScopes(String sessionId) {
        return sessionKnowledgeScopeResolver.resolveScopes(sessionId);
    }

    private EntityType parseEntityType(String raw) {
        String value = requireCleanText(raw, "entityType");
        try {
            return EntityType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的实体类型 '" + value + "'，有效值: " + VALID_ENTITY_TYPES, e);
        }
    }

    private String requireCleanText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(field + "不能包含首尾空白: " + value);
        }
        return value;
    }

    private List<String> requireCleanTexts(Collection<String> values, String field) {
        Objects.requireNonNull(values, field + "不能为空");
        return values.stream()
                .map(value -> requireCleanText(value, field))
                .toList();
    }

    private Optional<String> optionalContextText(ToolInput input, String key) {
        return input.getContextValue(key, String.class)
                .map(value -> requireCleanText(value, key));
    }

    private int parsePositiveInt(ToolInput input, String paramName, int defaultValue) {
        Object raw = input.parameters().get(paramName);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(paramName + " 必须是正整数");
        }
        double doubleValue = number.doubleValue();
        int intValue = number.intValue();
        if (!Double.isFinite(doubleValue) || doubleValue != intValue || intValue <= 0) {
            throw new IllegalArgumentException(paramName + " 必须是正整数");
        }
        return intValue;
    }

    private <T> Optional<T> optionalParam(ToolInput input, String paramName, Class<T> type) {
        Object raw = input.parameters().get(paramName);
        if (raw == null) {
            return Optional.empty();
        }
        if (!type.isInstance(raw)) {
            throw new IllegalArgumentException(
                    "参数类型不匹配: %s 期望 %s，实际 %s"
                            .formatted(paramName, type.getSimpleName(), raw.getClass().getSimpleName()));
        }
        return Optional.of(type.cast(raw));
    }

    private record ScoredEntity(TemporalEntity entity, float score) {
    }

}

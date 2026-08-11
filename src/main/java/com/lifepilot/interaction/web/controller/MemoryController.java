package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.*;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository.EntityMetadata;
import com.lifepilot.agent.learning.consolidation.ConsolidationPipeline;
import com.lifepilot.agent.learning.consolidation.EntityDeduplicator;
import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.agent.learning.extraction.MemoryChangeSummarySupport;
import com.lifepilot.agent.learning.extraction.MemoryExtractionCandidateRepository;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.agent.learning.forgetting.ForgettingLogRepository;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.governance.lifecycle.feedback.RevalidationQueueRepository;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.procedural.ProcedureTemplate;
import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryRealityType;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import jakarta.annotation.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记忆管理 REST Controller — 暴露记忆系统的查询、搜索和管理 API。
 *
 * <p>多数管理端点在记忆系统未启用时返回 503 Service Unavailable；主对话轮次状态接口会返回
 * {@code DISABLED}，用于解释本轮没有沉淀记忆。</p>
 *
 * @author zsg
 * @since 2026-03-13
 */
@RestController
@RequestMapping("/api/memories")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);
    private static final String MANUAL_SOURCE = "manual-edit";

    private final @Nullable SemanticMemory semanticMemory;
    private final @Nullable EpisodicMemory episodicMemory;
    private final @Nullable ProceduralMemory proceduralMemory;
    private final @Nullable HybridRetriever hybridRetriever;
    private final @Nullable ConsolidationPipeline consolidationPipeline;
    private final @Nullable EntityDeduplicator entityDeduplicator;
    private final @Nullable com.lifepilot.agent.learning.forgetting.ForgettingEngine forgettingEngine;
    private final @Nullable UserProfileConsolidator userProfileConsolidator;
    private final ForgettingLogRepository forgettingLogRepository;
    private final MemoryProvenanceRepository provenanceRepository;
    private final @Nullable ChatSessionRepository chatSessionRepository;
    private final ProjectContextResolver projectContextResolver;
    private final MemoryAccessPolicy memoryAccessPolicy;
    private final @Nullable MemoryExtractionCandidateRepository memoryExtractionCandidateRepository;
    private final @Nullable RevalidationQueueRepository revalidationQueueRepository;
    private final @Nullable com.lifepilot.agent.learning.consolidation.association.AssociationCandidateApplier remApplier;
    private final @Nullable com.lifepilot.memory.consumption.attention.MemoryAttentionService memoryAttentionService;
    private final AtomicBoolean consolidating = new AtomicBoolean(false);
    private final AtomicBoolean deduplicating = new AtomicBoolean(false);
    private final AtomicBoolean forgetting = new AtomicBoolean(false);

    public MemoryController(@Nullable SemanticMemory semanticMemory,
                            @Nullable EpisodicMemory episodicMemory,
                            @Nullable ProceduralMemory proceduralMemory,
                            @Nullable HybridRetriever hybridRetriever,
                            @Nullable ConsolidationPipeline consolidationPipeline,
                            @Nullable EntityDeduplicator entityDeduplicator,
                            @Nullable com.lifepilot.agent.learning.forgetting.ForgettingEngine forgettingEngine,
                            @Nullable UserProfileConsolidator userProfileConsolidator,
                            ForgettingLogRepository forgettingLogRepository,
                            MemoryProvenanceRepository provenanceRepository,
                            @Nullable ChatSessionRepository chatSessionRepository,
                            ProjectContextResolver projectContextResolver,
                            MemoryAccessPolicy memoryAccessPolicy,
                            @Nullable MemoryExtractionCandidateRepository memoryExtractionCandidateRepository,
                            @Nullable RevalidationQueueRepository revalidationQueueRepository,
                            @Nullable com.lifepilot.agent.learning.consolidation.association.AssociationCandidateApplier remApplier,
                            @Nullable com.lifepilot.memory.consumption.attention.MemoryAttentionService memoryAttentionService) {
        this.semanticMemory = semanticMemory;
        this.episodicMemory = episodicMemory;
        this.proceduralMemory = proceduralMemory;
        this.hybridRetriever = hybridRetriever;
        this.consolidationPipeline = consolidationPipeline;
        this.entityDeduplicator = entityDeduplicator;
        this.forgettingEngine = forgettingEngine;
        this.userProfileConsolidator = userProfileConsolidator;
        this.forgettingLogRepository = forgettingLogRepository;
        this.provenanceRepository = provenanceRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.projectContextResolver = Objects.requireNonNull(projectContextResolver, "projectContextResolver");
        this.memoryAccessPolicy = Objects.requireNonNull(memoryAccessPolicy, "memoryAccessPolicy");
        this.memoryExtractionCandidateRepository = memoryExtractionCandidateRepository;
        this.revalidationQueueRepository = revalidationQueueRepository;
        this.remApplier = remApplier;
        this.memoryAttentionService = memoryAttentionService;
    }

    /** 检查记忆系统是否启用，未启用时抛出 503。 */
    private void requireMemoryEnabled() {
        if (semanticMemory == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "记忆系统未启用");
        }
    }

    // ========== Req 1: 统计概览 ==========

    /**
     * 获取记忆系统统计概览。
     */
    @GetMapping("/stats")
    public ApiResponse<MemoryStatsDto> getStats() {
        requireMemoryEnabled();

        long entityCount = semanticMemory.countCurrent();
        long relationCount = semanticMemory.countCurrentRelations();

        // SQL 聚合查询，避免全量加载实体到 JVM 内存
        Map<String, Long> entityCountByType = semanticMemory.countCurrentByType();

        long conversationCount = episodicMemory != null ? episodicMemory.countConversations() : 0L;

        long templateCount = 0L;
        long preferenceCount = 0L;
        if (proceduralMemory != null) {
            templateCount = proceduralMemory.listAllTemplates().size();
            preferenceCount = proceduralMemory.listAllPreferences().size();
        }

        // 遗忘日志统计
        long forgettingLogCount = forgettingLogRepository.countAll();
        String lastForgettingTime = forgettingLogRepository.getLastForgettingTime();

        return ApiResponse.ok(new MemoryStatsDto(
                conversationCount,
                entityCount,
                entityCountByType,
                relationCount,
                templateCount,
                preferenceCount,
                forgettingLogCount,
                lastForgettingTime
        ));
    }

    // ========== 记忆健康度 ==========

    /**
     * 获取记忆系统健康度指标 — 各层统计、遗忘日志、访问活跃度。
     */
    @GetMapping("/health")
    public ApiResponse<MemoryHealthDto> getHealth() {
        requireMemoryEnabled();

        long totalEntities = semanticMemory.countCurrent();
        long totalRelations = semanticMemory.countCurrentRelations();

        // SQL 聚合查询，避免全量加载实体到 JVM 内存
        Map<String, Long> entityCountByType = semanticMemory.countCurrentByType();

        long experienceCount = entityCountByType.getOrDefault("EXPERIENCE", 0L);
        long preferenceCount = entityCountByType.getOrDefault("PREFERENCE", 0L);
        long habitCount = entityCountByType.getOrDefault("HABIT", 0L);
        long goalCount = entityCountByType.getOrDefault("GOAL", 0L);

        long templateCount = 0L;
        long ruleCount = 0L;
        if (proceduralMemory != null) {
            templateCount = proceduralMemory.listAllTemplates().size();
            ruleCount = proceduralMemory.listAllPreferences().size();
        }

        long conversationCount = episodicMemory != null ? episodicMemory.countConversations() : 0L;

        long forgettingLogCount = forgettingLogRepository.countAll();
        String lastForgettingTime = forgettingLogRepository.getLastForgettingTime();

        long recentlyAccessedCount = semanticMemory.countRecentlyAccessed(7);
        float recentAccessRatio = totalEntities > 0 ? (float) recentlyAccessedCount / totalEntities : 0f;

        float avgImportance = semanticMemory.averageImportanceScore();

        return ApiResponse.ok(new MemoryHealthDto(
                totalEntities, totalRelations, entityCountByType,
                conversationCount, templateCount, ruleCount,
                experienceCount, preferenceCount, habitCount, goalCount,
                forgettingLogCount, lastForgettingTime,
                recentAccessRatio, avgImportance));
    }

    // ========== Req 7: 统一记忆搜索 ==========

    /**
     * 统一记忆搜索 — 调用 HybridRetriever 三路并行检索。
     */
    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK,
            @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        if (q == null || q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索关键词不能为空");
        }
        requirePositive(topK, "topK");
        if (hybridRetriever == null) {
            return ApiResponse.ok(Map.of(
                    "results", List.of(),
                    "count", 0,
                    "rawCount", 0,
                    "qualityFilteredCount", 0,
                    "truncatedCount", 0,
                    "filteredOutCount", 0));
        }
        var projectContext = resolveProjectContextForRequest(projectId);
        var readFilter = toProjectReadFilter(projectContext, Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
        var rawResults = hybridRetriever.retrieve(q, topK * 3, RetrievalWeights.DEFAULT, readFilter);
        Map<String, TemporalEntity> entityById = rawResults.isEmpty()
                ? Map.of()
                : semanticMemory.findByIds(
                        rawResults.stream().map(RetrievalResult::entityId).toList(),
                        readFilter);
        var consumableResults = rawResults.stream()
                .filter(result -> MemoryQualityPolicy.isPromptConsumable(entityById.get(result.entityId())))
                .toList();
        var results = consumableResults.stream()
                .limit(topK)
                .toList();
        Map<String, EntityMetadata> metadataById = provenanceRepository.loadEntityMetadata(
                results.stream().map(r -> r.entityId()).toList()
        );
        var items = results.stream()
                .map(r -> {
                    var entity = entityById.get(r.entityId());
                    var metadata = metadataById.get(r.entityId());
                    return new MemorySearchResultDto(
                            r.entityId(),
                            r.entityType(),
                            r.name(),
                            r.description(),
                            r.fusedScore(),
                            metadata != null ? metadata.spaceId() : null,
                            metadata != null ? metadata.memoryScope() : null,
                            metadata != null ? metadata.realityType() : null,
                            entity != null ? entity.lifecycleState().name() : "UNKNOWN",
                            r.isHistorical(),
                            r.isStale(),
                            r.needsRevalidation(),
                            entity != null ? entity.evidenceKind().name() : "UNKNOWN",
                            entity != null ? entity.trustLevel().name() : "UNVERIFIED",
                            entity != null ? entity.trustScore() : 0.0f,
                            entity != null ? entity.evidenceCount() : 0,
                            entity != null ? entity.lastVerifiedAt() : null,
                            scoreBreakdownMap(r.scoreBreakdown())
                    );
                })
                .toList();
        int qualityFilteredCount = Math.max(0, rawResults.size() - consumableResults.size());
        int truncatedCount = Math.max(0, consumableResults.size() - items.size());
        return ApiResponse.ok(Map.of(
                "results", items,
                "count", items.size(),
                "rawCount", rawResults.size(),
                "qualityFilteredCount", qualityFilteredCount,
                "truncatedCount", truncatedCount,
                "filteredOutCount", qualityFilteredCount + truncatedCount));
    }

    /**
     * 查询某轮对话已经沉淀到 L3 的记忆变更。
     *
     * <p>主对话在 DONE 后用它补齐后台记忆抽取结果；只返回已经落库且能点击编辑的实体。</p>
     */
    @GetMapping("/turns/{turnId}/changes")
    public ApiResponse<List<Map<String, Object>>> getTurnMemoryChanges(
            @PathVariable String turnId,
            @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        String cleanTurnId = requireCleanPathText(turnId, "turnId");
        if (memoryExtractionCandidateRepository == null) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(buildTurnMemoryChanges(cleanTurnId, projectId));
    }

    /**
     * 查询某轮对话记忆沉淀的可见状态。
     *
     * <p>主对话用它区分“仍在后台处理”“已检查但无新增”“已沉淀”“抽取失败”和“记忆未启用”，
     * 避免只靠轮询空列表猜测结果。</p>
     */
    @GetMapping("/turns/{turnId}/changes/status")
    public ApiResponse<MemoryTurnChangesInfo> getTurnMemoryChangesStatus(
            @PathVariable String turnId,
            @RequestParam(required = false) @Nullable String projectId) {
        String cleanTurnId = requireCleanPathText(turnId, "turnId");
        if (semanticMemory == null) {
            return ApiResponse.ok(new MemoryTurnChangesInfo("DISABLED", "memory_system_disabled", List.of()));
        }
        if (memoryExtractionCandidateRepository == null) {
            return ApiResponse.ok(new MemoryTurnChangesInfo("DISABLED", "memory_repository_unavailable", List.of()));
        }
        var changes = buildTurnMemoryChanges(cleanTurnId, projectId);
        if (!changes.isEmpty()) {
            return ApiResponse.ok(new MemoryTurnChangesInfo("SETTLED", null, changes));
        }
        var extractionStatus = memoryExtractionCandidateRepository.findTurnExtractionStatus(cleanTurnId);
        if (extractionStatus.isEmpty()) {
            return ApiResponse.ok(new MemoryTurnChangesInfo("PENDING", null, List.of()));
        }
        var status = extractionStatus.get();
        String visibleStatus = switch (status.status()) {
            case "COMPLETED" -> "CHECKED_EMPTY";
            case "FAILED" -> "FAILED";
            case "RUNNING" -> "PENDING";
            default -> "PENDING";
        };
        return ApiResponse.ok(new MemoryTurnChangesInfo(visibleStatus, status.reason(), List.of()));
    }

    // ========== Req 2: L3 语义记忆实体管理 ==========

    /**
     * 实体分页列表（支持 type/q/timeFrom/timeTo/sortBy/order 过滤排序）。
     */
    @GetMapping("/entities")
    public ApiResponse<PageResult<EntitySummaryDto>> listEntities(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Nullable String type,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String spaceId,
            @RequestParam(required = false) @Nullable String memoryScope,
            @RequestParam(required = false) @Nullable String realityType,
            @RequestParam(required = false) @Nullable String originType,
            @RequestParam(required = false) @Nullable String sourceKnowledgeBaseId,
            @RequestParam(required = false) @Nullable String sourceDocumentId,
            @RequestParam(required = false) @Nullable String timeFrom,
            @RequestParam(required = false) @Nullable String timeTo,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        requireMemoryEnabled();
        validatePageRequest(page, size);

        var projectContext = resolveProjectContextForRequest(projectId);
        var requestedScope = parseMemoryScopeFilter(memoryScope);
        var entities = semanticMemory.findAllCurrent(toEntityListReadFilter(projectContext, spaceId, requestedScope));

        // type 过滤
        if (type != null) {
            try {
                var entityType = EntityType.valueOf(requireCleanText(type, "type"));
                entities = entities.stream().filter(e -> e.type() == entityType).toList();
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的实体类型: " + type);
            }
        }

        // q 关键词过滤（匹配 name 和 description）
        if (q != null && !q.isBlank()) {
            String keyword = q.toLowerCase();
            entities = entities.stream()
                    .filter(e -> e.name().toLowerCase().contains(keyword)
                            || (e.description() != null && e.description().toLowerCase().contains(keyword)))
                    .toList();
        }

        String cleanTimeFrom = cleanOptionalText(timeFrom, "timeFrom");
        String cleanTimeTo = cleanOptionalText(timeTo, "timeTo");
        if (cleanTimeFrom != null) {
            Instant from = parseInstantParam(cleanTimeFrom, "timeFrom");
            entities = entities.stream().filter(e -> !e.createdAt().isBefore(from)).toList();
        }
        if (cleanTimeTo != null) {
            Instant to = parseInstantParam(cleanTimeTo, "timeTo");
            entities = entities.stream().filter(e -> !e.createdAt().isAfter(to)).toList();
        }

        boolean requiresMetadataFiltering =
                spaceId != null
                        || memoryScope != null
                        || realityType != null;
        Map<String, EntityMetadata> filteredMetadataById = requiresMetadataFiltering
                ? provenanceRepository.loadEntityMetadata(entities.stream().map(TemporalEntity::id).toList())
                : Map.of();
        if (spaceId != null) {
            String requestedSpaceId = requireCleanText(spaceId, "spaceId");
            entities = entities.stream()
                    .filter(entity -> {
                        var metadata = filteredMetadataById.get(entity.id());
                        return metadata != null && requestedSpaceId.equals(metadata.spaceId());
                    })
                    .toList();
        }
        if (memoryScope != null) {
            String requestedMemoryScope = requestedScope.stream()
                    .findFirst()
                    .map(MemoryScope::name)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的记忆范围: " + memoryScope));
            entities = entities.stream()
                    .filter(entity -> {
                        var metadata = filteredMetadataById.get(entity.id());
                        return metadata != null && requestedMemoryScope.equals(metadata.memoryScope());
                    })
                    .toList();
        }
        if (realityType != null) {
            String requestedRealityType = requireCleanText(realityType, "realityType");
            entities = entities.stream()
                    .filter(entity -> {
                        var metadata = filteredMetadataById.get(entity.id());
                        return metadata != null && requestedRealityType.equals(metadata.realityType());
                    })
                    .toList();
        }
        Set<String> filteredEntityIdsByProvenance = provenanceRepository.findEntityIdsByProvenanceFilters(
                cleanOptionalText(originType, "originType"),
                cleanOptionalText(sourceKnowledgeBaseId, "sourceKnowledgeBaseId"),
                cleanOptionalText(sourceDocumentId, "sourceDocumentId")
        );
        if (filteredEntityIdsByProvenance != null) {
            entities = entities.stream()
                    .filter(entity -> filteredEntityIdsByProvenance.contains(entity.id()))
                    .toList();
        }

        // 排序
        Comparator<TemporalEntity> comparator = switch (requireCleanText(sortBy, "sortBy")) {
            case "name" -> Comparator.comparing(TemporalEntity::name);
            case "importanceScore" -> Comparator.comparing(TemporalEntity::importanceScore);
            case "accessCount" -> Comparator.comparingInt(TemporalEntity::accessCount);
            case "createdAt" -> Comparator.comparing(TemporalEntity::createdAt);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的排序字段: " + sortBy);
        };
        if (isDescendingOrder(order)) {
            comparator = comparator.reversed();
        }
        entities = entities.stream().sorted(comparator).toList();

        // 分页截取
        long total = entities.size();
        int fromIndex = Math.min(page * size, entities.size());
        int toIndex = Math.min(fromIndex + size, entities.size());
        var pageEntities = entities.subList(fromIndex, toIndex);
        Map<String, EntityMetadata> pageMetadataById = requiresMetadataFiltering
                ? filteredMetadataById
                : provenanceRepository.loadEntityMetadata(pageEntities.stream().map(TemporalEntity::id).toList());
        var pageItems = pageEntities.stream()
                .map(e -> toEntitySummary(e, pageMetadataById.get(e.id())))
                .toList();

        return ApiResponse.ok(new PageResult<>(pageItems, page, size, total));
    }

    /**
     * 实体详情。
     */
    @GetMapping("/entities/{id}")
    public ApiResponse<EntityDetailDto> getEntity(@PathVariable String id,
                                                  @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        String entityId = requireCleanText(id, "id");
        var projectContext = resolveProjectContextForRequest(projectId);
        var entity = findReadableEntity(entityId, projectContext)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + entityId));
        return ApiResponse.ok(toEntityDetail(entity, loadEntityMetadata(entityId)));
    }

    /**
     * 实体版本变更历史。
     */
    @GetMapping("/entities/{id}/history")
    public ApiResponse<List<EntityDetailDto>> getEntityHistory(@PathVariable String id,
                                                               @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        String entityId = requireCleanText(id, "id");
        var projectContext = resolveProjectContextForRequest(projectId);
        var readFilter = toProjectReadFilter(projectContext, Set.of());
        var entity = findReadableEntity(entityId, projectContext)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + entityId));
        var history = semanticMemory.getChangeHistory(entity.name(), entity.type());
        Map<String, EntityMetadata> metadataById = provenanceRepository.loadEntityMetadata(
                history.stream().map(TemporalEntity::id).toList());
        return ApiResponse.ok(history.stream()
                .filter(historyEntity -> matchesReadFilter(metadataById.get(historyEntity.id()), readFilter))
                .map(historyEntity -> toEntityDetail(historyEntity, metadataById.get(historyEntity.id())))
                .toList());
    }

    /**
     * 关联实体。
     */
    @GetMapping("/entities/{id}/related")
    public ApiResponse<List<EntitySummaryDto>> getRelatedEntities(
            @PathVariable String id,
            @RequestParam(defaultValue = "2") int maxDepth,
            @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        String entityId = requireCleanText(id, "id");
        requirePositive(maxDepth, "maxDepth");
        var projectContext = resolveProjectContextForRequest(projectId);
        var readFilter = toProjectReadFilter(projectContext, Set.of());
        findReadableEntity(entityId, projectContext)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + entityId));
        var relatedEntities = semanticMemory.findRelated(entityId, maxDepth);
        if (!relatedEntities.isEmpty()) {
            var visibleById = semanticMemory.findByIds(
                    relatedEntities.stream().map(TemporalEntity::id).toList(),
                    readFilter);
            relatedEntities = relatedEntities.stream()
                    .filter(e -> visibleById.containsKey(e.id()))
                    .toList();
        }
        Map<String, EntityMetadata> metadataById = provenanceRepository.loadEntityMetadata(
                relatedEntities.stream().map(TemporalEntity::id).toList()
        );
        return ApiResponse.ok(relatedEntities.stream()
                .map(e -> toEntitySummary(e, metadataById.get(e.id())))
                .toList());
    }

    /**
     * 实体来源明细。
     */
    @GetMapping("/entities/{id}/provenances")
    public ApiResponse<List<EntityProvenanceDto>> getEntityProvenances(
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String originType,
            @RequestParam(required = false) @Nullable String sourceKnowledgeBaseId,
            @RequestParam(required = false) @Nullable String sourceDocumentId) {
        requireMemoryEnabled();
        String entityId = requireCleanText(id, "id");
        var projectContext = resolveProjectContextForRequest(projectId);
        findReadableEntity(entityId, projectContext)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + entityId));
        var rawItems = provenanceRepository.findEntityProvenances(
                entityId,
                cleanOptionalText(originType, "originType"),
                cleanOptionalText(sourceKnowledgeBaseId, "sourceKnowledgeBaseId"),
                cleanOptionalText(sourceDocumentId, "sourceDocumentId"));
        return ApiResponse.ok(enrichProvenances(rawItems));
    }

    /**
     * 人工确认实体仍有效，关闭该实体下未完成的来源再验证任务。
     */
    @PostMapping("/entities/{id}/revalidation/resolve")
    public ApiResponse<Map<String, Object>> resolveEntityRevalidation(
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        String entityId = requireCleanText(id, "id");
        var projectContext = resolveProjectContextForRequest(projectId);
        findReadableEntity(entityId, projectContext)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + entityId));
        if (revalidationQueueRepository == null) {
            return ApiResponse.ok(Map.of("resolvedCount", 0, "status", "UNAVAILABLE"));
        }
        int resolvedCount = SqliteBusyRetry.execute(() -> revalidationQueueRepository.markResolvedByEntityId(entityId));
        log.info("人工确认记忆复核: entityId={}, resolvedCount={}", entityId, resolvedCount);
        return ApiResponse.ok(Map.of("resolvedCount", resolvedCount, "status", "RESOLVED"));
    }

    /**
     * 最近来源摘要。
     */
    @GetMapping("/provenances/recent")
    public ApiResponse<List<MemoryProvenanceSummaryDto>> listRecentProvenances(
            @RequestParam(required = false) @Nullable String originType,
            @RequestParam(required = false) @Nullable String sourceKnowledgeBaseId,
            @RequestParam(required = false) @Nullable String sourceDocumentId,
            @RequestParam(defaultValue = "10") int limit) {
        requireMemoryEnabled();
        requirePositive(limit, "limit");
        var rawItems = provenanceRepository.findRecentProvenanceSummaries(
                cleanOptionalText(originType, "originType"),
                cleanOptionalText(sourceKnowledgeBaseId, "sourceKnowledgeBaseId"),
                cleanOptionalText(sourceDocumentId, "sourceDocumentId"),
                limit);
        return ApiResponse.ok(enrichMemoryProvenanceSummaries(rawItems));
    }

    /**
     * 归档实体。
     */
    @DeleteMapping("/entities/{id}")
    public ApiResponse<Void> deleteEntity(@PathVariable String id,
                                          @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        String entityId = requireCleanText(id, "id");
        var projectContext = resolveProjectContextForRequest(projectId);
        var entity = findWritableEntity(entityId, projectContext).orElse(null);
        if (entity == null) {
            if (projectContext.isolated()
                    && findReadableEntity(entityId, projectContext).isPresent()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "隔离项目不能直接归档继承记忆，请先创建项目内覆盖版本");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + entityId);
        }
        SqliteBusyRetry.run(() -> semanticMemory.archive(entity, ChangeSource.UI_EDIT));
        return ApiResponse.ok();
    }

    /**
     * 手动创建实体。
     *
     * <p>extractionConfidence 固定为 1.0 表示手动创建。</p>
     */
    @PostMapping("/entities")
    public ApiResponse<EntityDetailDto> createEntity(@RequestBody EntityCreateRequest request,
                                                     @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        var projectContext = resolveProjectContextForRequest(projectId);

        // 校验 name 非空
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实体名称不能为空");
        }
        requireNoBoundaryWhitespace(request.name(), "实体名称");

        // 校验 type 为有效 EntityType
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(request.type());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的实体类型: " + request.type());
        }

        var now = Instant.now();
        float importance = request.importanceScore() != null ? request.importanceScore() : 0.5f;
        MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.USER_CONFIRMED;
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, 1.0f);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
        var entity = new TemporalEntity(
                UUID.randomUUID().toString(),
                entityType,
                request.name(),
                request.description(),
                request.properties() != null ? request.properties() : Map.of(),
                1,
                true,
                now,
                null,
                null,
                1.0f,  // extractionConfidence=1.0 表示手动创建
                importance,
                0,
                null,
                now,
                now,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                false,
                List.of(),
                evidenceKind,
                trustLevel,
                trustScore,
                1,
                now
        );

        var created = SqliteBusyRetry.execute(() ->
                semanticMemory.upsertWithConflictDetection(
                        entity,
                        MANUAL_SOURCE,
                        toManualProjectWriteContext(projectContext)));
        log.info("手动创建实体: id={}, name={}, type={}", created.id(), created.name(), created.type());
        var metadata = provenanceRepository.loadEntityMetadata(List.of(created.id())).get(created.id());
        return ApiResponse.ok(toEntityDetail(created, metadata));
    }

    /**
     * 更新实体。
     *
     * <p>仅允许更新当前有效实体（isCurrent=true），已归档实体返回 404。</p>
     */
    @PutMapping("/entities/{id}")
    public ApiResponse<EntityDetailDto> updateEntity(@PathVariable String id,
                                                     @RequestParam(required = false) @Nullable String projectId,
                                                     @RequestBody EntityUpdateRequest request) {
        requireMemoryEnabled();
        String entityId = requireCleanText(id, "id");
        var projectContext = resolveProjectContextForRequest(projectId);

        var existing = findWritableEntity(entityId, projectContext).orElse(null);
        if (existing == null) {
            if (projectContext.isolated()) {
                var inherited = findReadableEntity(entityId, projectContext)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + entityId));
                var overlay = updateInheritedEntityAsOverlay(inherited, request, projectContext);
                log.info("隔离项目更新继承实体为 overlay: baseId={}, overlayId={}, name={}",
                        inherited.id(), overlay.id(), overlay.name());
                var metadata = provenanceRepository.loadEntityMetadata(List.of(overlay.id())).get(overlay.id());
                return ApiResponse.ok(toEntityDetail(overlay, metadata));
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + entityId);
        }

        if (!existing.isCurrent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "实体已归档: " + entityId);
        }

        // 合并更新字段
        String name = request.name() != null ? requireCleanText(request.name(), "实体名称") : existing.name();
        String description = request.description() != null ? request.description() : existing.description();
        Map<String, Object> properties = request.properties() != null ? request.properties() : existing.properties();
        float importance = request.importanceScore() != null ? request.importanceScore() : existing.importanceScore();
        MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.USER_CONFIRMED;
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, 1.0f);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
        var now = Instant.now();

        var updated = new TemporalEntity(
                existing.id(),
                existing.type(),
                name,
                description,
                properties,
                existing.version(),
                true,
                existing.validFrom(),
                existing.validTo(),
                existing.sourceConversationId(),
                existing.extractionConfidence(),
                importance,
                existing.accessCount(),
                existing.lastAccessedAt(),
                existing.createdAt(),
                now,
                existing.lifecycleState(),
                existing.lifecycleReason(),
                existing.expiresAt(),
                existing.temporality(),
                existing.succeededBy(),
                existing.isDerived(),
                existing.derivationSources(),
                evidenceKind,
                trustLevel,
                trustScore,
                1,
                now
        );

        var saved = SqliteBusyRetry.execute(() -> semanticMemory.upsertWithConflictDetection(
                updated, MANUAL_SOURCE, toManualProjectWriteContext(projectContext)));
        log.info("更新实体: id={}, name={}", saved.id(), saved.name());
        var metadata = provenanceRepository.loadEntityMetadata(List.of(saved.id())).get(saved.id());
        return ApiResponse.ok(toEntityDetail(saved, metadata));
    }

    // ========== Req 3: L3 关系查询 ==========

    /**
     * 关系分页列表（附带实体名称）。
     */
    @GetMapping("/relations")
    public ApiResponse<PageResult<RelationDto>> listRelations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Nullable String entityId,
            @RequestParam(required = false) @Nullable String relationType,
            @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        validatePageRequest(page, size);
        var projectContext = resolveProjectContextForRequest(projectId);
        var readFilter = toProjectReadFilter(projectContext, Set.of());

        List<TemporalRelation> relations;
        if (entityId != null) {
            String requestedEntityId = requireCleanText(entityId, "entityId");
            if (findReadableEntity(requestedEntityId, projectContext).isEmpty()) {
                return ApiResponse.ok(new PageResult<>(List.of(), page, size, 0L));
            }
            relations = semanticMemory.findRelationsByEntityId(requestedEntityId);
        } else {
            relations = semanticMemory.findAllCurrentRelations();
        }

        // relationType 过滤
        if (relationType != null) {
            String requestedRelationType = requireCleanText(relationType, "relationType");
            relations = relations.stream()
                    .filter(r -> r.relationType().equals(requestedRelationType))
                    .toList();
        }

        // 收集实体 ID 批量查询名称
        Set<String> entityIds = new HashSet<>();
        for (var r : relations) {
            entityIds.add(r.sourceEntityId());
            entityIds.add(r.targetEntityId());
        }
        Map<String, TemporalEntity> entityMap = semanticMemory.findByIds(entityIds, readFilter);
        Map<String, TemporalEntity> visibleEntityMap = entityMap;
        relations = relations.stream()
                .filter(r -> visibleEntityMap.containsKey(r.sourceEntityId())
                        && visibleEntityMap.containsKey(r.targetEntityId()))
                .toList();
        Map<String, EntityMetadata> entityMetadataMap = provenanceRepository.loadEntityMetadata(entityIds);
        Map<String, TemporalEntity> relationEntityMap = entityMap;

        // 分页
        long total = relations.size();
        int fromIndex = Math.min(page * size, relations.size());
        int toIndex = Math.min(fromIndex + size, relations.size());
        var pageItems = relations.subList(fromIndex, toIndex).stream()
                .map(r -> {
                    var source = relationEntityMap.get(r.sourceEntityId());
                    var sourceMetadata = entityMetadataMap.get(r.sourceEntityId());
                    var target = relationEntityMap.get(r.targetEntityId());
                    var targetMetadata = entityMetadataMap.get(r.targetEntityId());
                    return new RelationDto(
                            r.id(),
                            r.sourceEntityId(),
                            source != null ? source.name() : r.sourceEntityId(),
                            source != null ? source.type().name() : "UNKNOWN",
                            sourceMetadata != null ? sourceMetadata.spaceId() : null,
                            sourceMetadata != null ? sourceMetadata.memoryScope() : null,
                            sourceMetadata != null ? sourceMetadata.realityType() : null,
                            r.targetEntityId(),
                            target != null ? target.name() : r.targetEntityId(),
                            target != null ? target.type().name() : "UNKNOWN",
                            targetMetadata != null ? targetMetadata.spaceId() : null,
                            targetMetadata != null ? targetMetadata.memoryScope() : null,
                            targetMetadata != null ? targetMetadata.realityType() : null,
                            r.relationType(),
                            r.strength(),
                            r.validFrom(),
                            r.validTo(),
                            r.createdAt());
                })
                .toList();

        return ApiResponse.ok(new PageResult<>(pageItems, page, size, total));
    }

    // ========== Req 4: L2 情景记忆浏览 ==========

    /**
     * 对话分页列表。
     */
    @GetMapping("/conversations")
    public ApiResponse<PageResult<ConversationSummaryDto>> listConversations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String timeFrom,
            @RequestParam(required = false) @Nullable String timeTo) {
        requireMemoryEnabled();
        validatePageRequest(page, size);
        if (episodicMemory == null) {
            return ApiResponse.ok(new PageResult<>(List.of(), page, size, 0L));
        }
        String cleanTimeFrom = cleanOptionalText(timeFrom, "timeFrom");
        String cleanTimeTo = cleanOptionalText(timeTo, "timeTo");

        // 如果有搜索关键词，使用 FTS5 搜索
        if (q != null && !q.isBlank()) {
            var searchResults = episodicMemory.search(q);
            // 时间范围过滤
            var filtered = filterByTime(searchResults, cleanTimeFrom, cleanTimeTo);
            long total = filtered.size();
            int fromIndex = Math.min(page * size, filtered.size());
            int toIndex = Math.min(fromIndex + size, filtered.size());
            var items = filtered.subList(fromIndex, toIndex).stream()
                    .map(this::toConversationSummary)
                    .toList();
            return ApiResponse.ok(new PageResult<>(items, page, size, total));
        }

        // 无搜索关键词，分页查询
        long total = episodicMemory.countConversations();
        var conversations = episodicMemory.listConversations(page, size);
        // 时间范围过滤（分页后过滤，简化实现）
        var filtered = filterByTime(conversations, cleanTimeFrom, cleanTimeTo);
        var items = filtered.stream().map(this::toConversationSummary).toList();
        return ApiResponse.ok(new PageResult<>(items, page, size, total));
    }

    /**
     * 对话详情。
     */
    @GetMapping("/conversations/{id}")
    public ApiResponse<ConversationRecord> getConversation(@PathVariable String id) {
        requireMemoryEnabled();
        String conversationId = requireCleanText(id, "id");
        if (episodicMemory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在: " + conversationId);
        }
        return ApiResponse.ok(episodicMemory.getById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在: " + conversationId)));
    }

    /**
     * 删除对话。
     */
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(@PathVariable String id) {
        requireMemoryEnabled();
        String conversationId = requireCleanText(id, "id");
        if (episodicMemory == null || !episodicMemory.delete(conversationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在: " + conversationId);
        }
        return ApiResponse.ok();
    }

    // ========== Req 5: L4 程序记忆浏览 ==========

    /**
     * 模板分页列表。
     */
    @GetMapping("/templates")
    public ApiResponse<PageResult<Object>> listTemplates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        requireMemoryEnabled();
        validatePageRequest(page, size);
        if (proceduralMemory == null) {
            return ApiResponse.ok(new PageResult<>(List.of(), page, size, 0L));
        }

        var templates = proceduralMemory.listAllTemplates();

        // q 关键词过滤
        if (q != null && !q.isBlank()) {
            String keyword = q.toLowerCase();
            templates = templates.stream()
                    .filter(t -> t.name().toLowerCase().contains(keyword)
                            || t.description().toLowerCase().contains(keyword))
                    .toList();
        }

        // 排序
        Comparator<ProcedureTemplate> comparator = switch (requireCleanText(sortBy, "sortBy")) {
            case "successRate" -> Comparator.comparing(ProcedureTemplate::successRate);
            case "useCount" -> Comparator.comparingInt(ProcedureTemplate::useCount);
            case "createdAt" -> Comparator.comparing(ProcedureTemplate::createdAt);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的排序字段: " + sortBy);
        };
        if (isDescendingOrder(order)) {
            comparator = comparator.reversed();
        }
        templates = templates.stream().sorted(comparator).toList();

        long total = templates.size();
        int fromIndex = Math.min(page * size, templates.size());
        int toIndex = Math.min(fromIndex + size, templates.size());
        var pageItems = templates.subList(fromIndex, toIndex);

        return ApiResponse.ok(new PageResult<>(List.copyOf(pageItems), page, size, total));
    }

    /**
     * 模板详情。
     */
    @GetMapping("/templates/{id}")
    public ApiResponse<Object> getTemplate(@PathVariable String id) {
        requireMemoryEnabled();
        String templateId = requireCleanText(id, "id");
        if (proceduralMemory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + templateId);
        }
        return ApiResponse.ok(proceduralMemory.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + templateId)));
    }

    /**
     * 删除模板。
     */
    @DeleteMapping("/templates/{id}")
    public ApiResponse<Void> deleteTemplate(@PathVariable String id) {
        requireMemoryEnabled();
        String templateId = requireCleanText(id, "id");
        if (proceduralMemory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + templateId);
        }
        proceduralMemory.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + templateId));
        proceduralMemory.delete(templateId);
        return ApiResponse.ok();
    }

    /**
     * 偏好规则列表。
     */
    @GetMapping("/preferences")
    public ApiResponse<List<Object>> listPreferences(
            @RequestParam(required = false) @Nullable String category) {
        requireMemoryEnabled();
        if (proceduralMemory == null) {
            return ApiResponse.ok(List.of());
        }
        String cleanCategory = cleanOptionalText(category, "category");
        if (cleanCategory != null) {
            return ApiResponse.ok(List.copyOf(proceduralMemory.getPreferences(cleanCategory)));
        }
        return ApiResponse.ok(List.copyOf(proceduralMemory.listAllPreferences()));
    }

    /**
     * 删除偏好规则。
     */
    @DeleteMapping("/preferences/{id}")
    public ApiResponse<Void> deletePreference(@PathVariable String id) {
        requireMemoryEnabled();
        String preferenceId = requireCleanText(id, "id");
        if (proceduralMemory == null || !proceduralMemory.deletePreference(preferenceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "偏好规则不存在: " + preferenceId);
        }
        return ApiResponse.ok();
    }

    // ========== Req 6: 遗忘日志查询 ==========

    /**
     * 遗忘日志分页列表。
     */
    @GetMapping("/forgetting-logs")
    public ApiResponse<PageResult<ForgettingLogDto>> listForgettingLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Nullable String timeFrom,
            @RequestParam(required = false) @Nullable String timeTo,
            @RequestParam(required = false) @Nullable String strategy) {
        requireMemoryEnabled();
        validatePageRequest(page, size);
        String cleanTimeFrom = cleanOptionalText(timeFrom, "timeFrom");
        String cleanTimeTo = cleanOptionalText(timeTo, "timeTo");
        if (cleanTimeFrom != null) {
            parseInstantParam(cleanTimeFrom, "timeFrom");
        }
        if (cleanTimeTo != null) {
            parseInstantParam(cleanTimeTo, "timeTo");
        }
        return ApiResponse.ok(forgettingLogRepository.findPaginated(
                cleanTimeFrom,
                cleanTimeTo,
                cleanOptionalText(strategy, "strategy"),
                page,
                size));
    }

    // ========== Req 8: 手动触发巩固 ==========

    /**
     * 手动触发记忆巩固管线。
     */
    @PostMapping("/consolidate")
    public ApiResponse<Map<String, String>> triggerConsolidation() {
        requireMemoryEnabled();
        if (consolidationPipeline == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "巩固管线未启用");
        }
        if (!consolidating.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "巩固管线正在执行中");
        }
        Thread.startVirtualThread(() -> {
            try {
                consolidationPipeline.consolidate(true);
            } finally {
                consolidating.set(false);
            }
        });
        return ApiResponse.ok(Map.of("status", "accepted", "message", "巩固任务已提交"));
    }

    /**
     * 手动触发 REM 联想候选落库 — 开发期用于验证关系图谱写入；生产由巩固周期触发。
     */
    @PostMapping("/rem/apply")
    public ApiResponse<Map<String, Object>> triggerRemApply(
            @RequestParam(required = false) @Nullable String date) {
        requireMemoryEnabled();
        if (remApplier == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "REM 落库应用器未启用");
        }
        LocalDate target = date != null
                ? parseDateParam(date, "date")
                : LocalDate.now();
        var result = remApplier.apply(target);
        return ApiResponse.ok(Map.of(
                "date", target.toString(),
                "input", result.input(),
                "applied", result.applied(),
                "skippedLowConfidence", result.skippedLowConfidence(),
                "skippedMissingEntity", result.skippedMissingEntity(),
                "skippedDuplicate", result.skippedDuplicate()));
    }

    /**
     * 记忆注意力清单（memory-proactive-foundation）—— 主动浮现"现在该关注什么、为什么"。
     *
     * <p>聚合临近到期 / 停滞高价值 / 演进活跃 / 图联想连接机会，按 score 降序返回。</p>
     */
    @GetMapping("/attention")
    public ApiResponse<List<Map<String, Object>>> getAttention(
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(defaultValue = "10") int limit) {
        requireMemoryEnabled();
        requirePositive(limit, "limit");
        if (memoryAttentionService == null) {
            return ApiResponse.ok(List.of());
        }
        var projectContext = resolveProjectContextForRequest(projectId);
        var readFilter = toProjectReadFilter(projectContext, Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
        var items = memoryAttentionService.computeAttention(readFilter, limit);
        var result = items.stream().map(item -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("entityId", item.entityId());
            map.put("name", item.name());
            map.put("entityType", item.entityType());
            map.put("kind", item.kind().name());
            map.put("score", item.score());
            map.put("reason", item.reason());
            if (item.dueAt() != null) map.put("dueAt", item.dueAt().toString());
            if (item.daysIdle() != null) map.put("daysIdle", item.daysIdle());
            if (item.pathLabels() != null) map.put("pathLabels", item.pathLabels());
            return (Map<String, Object>) map;
        }).toList();
        return ApiResponse.ok(result);
    }

    // ========== Req 9: 手动触发去重 ==========

    /**
     * 手动触发实体去重。
     */
    @PostMapping("/deduplicate")
    public ApiResponse<Map<String, String>> triggerDeduplication() {
        requireMemoryEnabled();
        if (entityDeduplicator == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "去重服务未启用");
        }
        if (!deduplicating.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "去重任务正在执行中");
        }
        Thread.startVirtualThread(() -> {
            try {
                entityDeduplicator.dedup();
            } finally {
                deduplicating.set(false);
            }
        });
        return ApiResponse.ok(Map.of("status", "accepted", "message", "去重任务已提交"));
    }

    // ========== 手动触发遗忘（开发期，验证 ForgettingEngine）==========

    /**
     * 手动触发一次 MaRS 遗忘流程 — 开发期用于验证遗忘引擎；生产由 Cron 触发。
     */
    @PostMapping("/forget")
    public ApiResponse<Map<String, Object>> triggerForgetting() {
        requireMemoryEnabled();
        if (forgettingEngine == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "遗忘引擎未启用");
        }
        if (!forgetting.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "遗忘任务正在执行中");
        }
        try {
            int count = forgettingEngine.forget();
            return ApiResponse.ok(Map.of("status", "completed", "forgottenCount", count));
        } finally {
            forgetting.set(false);
        }
    }

    // ========== 用户画像 ==========

    /**
     * 获取用户画像。
     */
    @GetMapping("/profile")
    public ApiResponse<ProfileDto> getProfile() {
        requireMemoryEnabled();
        var entity = semanticMemory.findCurrentByNameAndType(
                "__consolidated_profile", EntityType.CUSTOM);
        if (entity.isEmpty()) {
            return ApiResponse.ok(new ProfileDto("", null, null));
        }
        var e = entity.get();
        return ApiResponse.ok(new ProfileDto(
                e.description() != null ? e.description() : "",
                e.updatedAt().toString(),
                e.version()));
    }

    /**
     * 更新用户画像。
     */
    @PutMapping("/profile")
    public ApiResponse<ProfileDto> updateProfile(@RequestBody ProfileUpdateRequest request) {
        requireMemoryEnabled();
        if (request.description() == null || request.description().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "画像描述不能为空");
        }
        if (request.description().length() > 5000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "画像描述不能超过 5000 字符");
        }
        // 手动编辑直接覆盖描述，绕过 VersionMerger 的"无变化"判定
        var existing = semanticMemory.findCurrentByNameAndType(
                "__consolidated_profile", EntityType.CUSTOM);
        if (existing.isPresent()) {
            semanticMemory.updateDescription(existing.get().id(), request.description());
            var refreshed = semanticMemory.findCurrentByNameAndType(
                    "__consolidated_profile", EntityType.CUSTOM);
            var e = refreshed.orElse(existing.get());
            return ApiResponse.ok(new ProfileDto(
                    e.description() != null ? e.description() : "",
                    e.updatedAt().toString(),
                    e.version()));
        } else {
            var now = Instant.now();
            MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.USER_CONFIRMED;
            float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, 1.0f);
            MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
            var entity = new TemporalEntity(
                    null, EntityType.CUSTOM, "__consolidated_profile", request.description(),
                    Map.of(), 1, true, now, null, null,
                    1.0f, 1.0f, 0, null, now, now,
                    LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT,
                    null, false, List.of(),
                    evidenceKind, trustLevel, trustScore, 1, now);
            var created = semanticMemory.upsertWithConflictDetection(
                    entity,
                    MANUAL_SOURCE,
                    MemoryWriteContext.manual(MANUAL_SOURCE));
            return ApiResponse.ok(new ProfileDto(
                    created.description() != null ? created.description() : "",
                    created.updatedAt().toString(),
                    created.version()));
        }
    }

    record ProfileDto(String description, @Nullable String updatedAt, @Nullable Integer version) {}
    record ProfileUpdateRequest(String description) {}

    // ========== 内部辅助方法 ==========

    private ProjectContext resolveProjectContextForRequest(@Nullable String projectId) {
        if (projectId == null) {
            return projectContextResolver.resolve(null);
        }
        return projectContextResolver.resolve(requireCleanText(projectId, "projectId"));
    }

    private TurnChangeTargetSpaces targetSpacesForTurnChanges(ProjectContext ctx) {
        if (ctx.isolated()) {
            return new TurnChangeTargetSpaces(List.of(ctx.projectSpaceId()), false);
        }
        return new TurnChangeTargetSpaces(List.of(ctx.personalSpaceId(), ctx.experienceSpaceId()), true);
    }

    private List<Map<String, Object>> buildTurnMemoryChanges(String cleanTurnId, @Nullable String projectId) {
        if (memoryExtractionCandidateRepository == null) {
            return List.of();
        }
        var projectContext = resolveProjectContextForRequest(projectId);
        var targetSpaces = targetSpacesForTurnChanges(projectContext);
        return MemoryChangeSummarySupport.buildAppliedMemoryChangeSummaries(
                memoryExtractionCandidateRepository,
                cleanTurnId,
                targetSpaces.spaceIds(),
                targetSpaces.includeDefaultSpace(),
                5);
    }

    private record TurnChangeTargetSpaces(List<String> spaceIds, boolean includeDefaultSpace) {}

    private MemoryReadFilter toProjectReadFilter(ProjectContext ctx, Set<MemoryScope> scopes) {
        return memoryAccessPolicy.buildProjectReadFilter(ctx, scopes);
    }

    private MemoryReadFilter toEntityListReadFilter(ProjectContext ctx,
                                                    @Nullable String spaceId,
                                                    Set<MemoryScope> scopes) {
        if (spaceId != null) {
            return MemoryReadFilter.of(List.of(requireCleanText(spaceId, "spaceId")), scopes);
        }
        return toProjectReadFilter(ctx, scopes);
    }

    private Set<MemoryScope> parseMemoryScopeFilter(@Nullable String memoryScope) {
        if (memoryScope == null) {
            return Set.of();
        }
        try {
            return Set.of(MemoryScope.valueOf(requireCleanText(memoryScope, "memoryScope")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的记忆范围: " + memoryScope);
        }
    }

    private MemoryWriteContext toManualProjectWriteContext(ProjectContext ctx) {
        return memoryAccessPolicy.buildProjectWriteContext(
                ctx,
                null,
                null,
                null,
                MANUAL_SOURCE,
                MemoryOriginType.MANUAL,
                MemoryRealityType.UNKNOWN);
    }

    private Optional<TemporalEntity> findReadableEntity(String entityId, ProjectContext ctx) {
        return semanticMemory.findByIds(List.of(entityId), toProjectReadFilter(ctx, Set.of()))
                .values()
                .stream()
                .findFirst();
    }

    private Optional<TemporalEntity> findWritableEntity(String entityId, ProjectContext ctx) {
        return semanticMemory.findByIds(List.of(entityId), memoryAccessPolicy.buildWritableEntityFilter(ctx))
                .values()
                .stream()
                .findFirst();
    }

    private boolean matchesReadFilter(@Nullable EntityMetadata metadata, MemoryReadFilter filter) {
        if (filter.isUnrestricted()) {
            return true;
        }
        if (metadata == null) {
            return false;
        }
        boolean spaceMatches = !filter.restrictsSpaces()
                || filter.spaceIds().contains(metadata.spaceId());
        boolean scopeMatches = !filter.restrictsScopes()
                || filter.scopes().stream().anyMatch(scope -> scope.name().equals(metadata.memoryScope()));
        return spaceMatches && scopeMatches;
    }

    private TemporalEntity updateInheritedEntityAsOverlay(TemporalEntity baseEntity,
                                                          EntityUpdateRequest request,
                                                          ProjectContext projectContext) {
        var now = Instant.now();
        String name = request.name() != null ? requireCleanText(request.name(), "实体名称") : baseEntity.name();
        String description = request.description() != null ? request.description() : baseEntity.description();
        Map<String, Object> properties = request.properties() != null ? request.properties() : baseEntity.properties();
        float importance = request.importanceScore() != null ? request.importanceScore() : baseEntity.importanceScore();
        MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.USER_CONFIRMED;
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, 1.0f);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
        var overlay = new TemporalEntity(
                null,
                baseEntity.type(),
                name,
                description,
                properties,
                1,
                true,
                now,
                null,
                baseEntity.sourceConversationId(),
                baseEntity.extractionConfidence(),
                importance,
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
        return SqliteBusyRetry.execute(() -> semanticMemory.upsertProjectOverlay(
                overlay,
                baseEntity,
                MANUAL_SOURCE,
                toManualProjectWriteContext(projectContext)));
    }

    private EntityDetailDto toEntityDetail(TemporalEntity e, @Nullable EntityMetadata metadata) {
        return new EntityDetailDto(
                e.id(), e.type().name(), e.type().label(), e.name(), e.description(),
                metadata != null ? metadata.spaceId() : null,
                metadata != null ? metadata.memoryScope() : null,
                metadata != null ? metadata.realityType() : null,
                e.properties(), e.version(), e.isCurrent(),
                e.validFrom(), e.validTo(), e.sourceConversationId(),
                e.lifecycleState().name(), e.lifecycleReason(), e.expiresAt(),
                e.temporality().name(), e.succeededBy(), e.isDerived(), e.derivationSources(),
                e.evidenceKind().name(), e.trustLevel().name(), e.trustScore(),
                e.evidenceCount(), e.lastVerifiedAt(),
                e.extractionConfidence(), e.importanceScore(), e.accessCount(),
                e.lastAccessedAt(), e.createdAt(), e.updatedAt());
    }

    private EntitySummaryDto toEntitySummary(TemporalEntity e, @Nullable EntityMetadata metadata) {
        return new EntitySummaryDto(
                e.id(),
                e.type().name(),
                e.type().label(),
                e.name(),
                e.description(),
                e.importanceScore(),
                e.accessCount(),
                e.version(),
                metadata != null ? metadata.spaceId() : null,
                metadata != null ? metadata.memoryScope() : null,
                metadata != null ? metadata.realityType() : null,
                e.lifecycleState().name(),
                e.temporality().name(),
                e.expiresAt(),
                e.evidenceKind().name(),
                e.trustLevel().name(),
                e.trustScore(),
                e.evidenceCount(),
                e.lastVerifiedAt(),
                e.createdAt(),
                e.updatedAt()
        );
    }

    private Map<String, Float> scoreBreakdownMap(@Nullable RetrievalResult.ScoreBreakdown breakdown) {
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

    private List<EntityProvenanceDto> enrichProvenances(List<EntityProvenanceDto> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, String> knowledgeBaseNames = provenanceRepository.loadKnowledgeBaseNames(
                items.stream().map(EntityProvenanceDto::sourceKnowledgeBaseId).toList()
        );
        Map<String, String> documentNames = provenanceRepository.loadDocumentNames(
                items.stream().map(EntityProvenanceDto::sourceDocumentId).toList()
        );
        Map<String, String> sessionTitles = loadSessionTitles(
                items.stream().map(EntityProvenanceDto::sourceSessionId).toList()
        );
        return items.stream()
                .map(item -> new EntityProvenanceDto(
                        item.originType(),
                        item.sourceReference(),
                        item.sourceConversationId(),
                        item.sourceSessionId(),
                        firstPresent(lookupName(sessionTitles, item.sourceSessionId()), item.sourceSessionTitle()),
                        item.sourceTurnId(),
                        item.sourceEntryId(),
                        item.sourceDocumentId(),
                        lookupName(documentNames, item.sourceDocumentId()),
                        item.sourceKnowledgeBaseId(),
                        lookupName(knowledgeBaseNames, item.sourceKnowledgeBaseId()),
                        item.evidenceKind(),
                        item.trustLevel(),
                        item.trustScore(),
                        item.evidenceExcerpt(),
                        item.confidence(),
                        item.status(),
                        item.invalidatedAt(),
                        item.revalidationStatus(),
                        item.createdAt()
                ))
                .toList();
    }

    private List<MemoryProvenanceSummaryDto> enrichMemoryProvenanceSummaries(List<MemoryProvenanceSummaryDto> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, String> knowledgeBaseNames = provenanceRepository.loadKnowledgeBaseNames(
                items.stream().map(MemoryProvenanceSummaryDto::sourceKnowledgeBaseId).toList()
        );
        Map<String, String> documentNames = provenanceRepository.loadDocumentNames(
                items.stream().map(MemoryProvenanceSummaryDto::sourceDocumentId).toList()
        );
        Map<String, String> sessionTitles = loadSessionTitles(
                items.stream().map(MemoryProvenanceSummaryDto::sourceSessionId).toList()
        );
        return items.stream()
                .map(item -> new MemoryProvenanceSummaryDto(
                        item.entityId(),
                        item.entityName(),
                        item.entityType(),
                        item.entityTypeLabel(),
                        item.entityMemoryScope(),
                        item.entityRealityType(),
                        item.originType(),
                        item.sourceReference(),
                        item.sourceConversationId(),
                        item.sourceSessionId(),
                        firstPresent(lookupName(sessionTitles, item.sourceSessionId()), item.sourceSessionTitle()),
                        item.sourceTurnId(),
                        item.sourceEntryId(),
                        item.sourceDocumentId(),
                        lookupName(documentNames, item.sourceDocumentId()),
                        item.sourceKnowledgeBaseId(),
                        lookupName(knowledgeBaseNames, item.sourceKnowledgeBaseId()),
                        item.evidenceKind(),
                        item.trustLevel(),
                        item.trustScore(),
                        item.evidenceExcerpt(),
                        item.confidence(),
                        item.status(),
                        item.invalidatedAt(),
                        item.revalidationStatus(),
                        item.createdAt()
                ))
                .toList();
    }

    private Map<String, String> loadSessionTitles(Collection<String> ids) {
        if (chatSessionRepository == null) {
            return Map.of();
        }
        Map<String, String> titles = chatSessionRepository.findTitlesByIds(ids);
        return titles != null ? titles : Map.of();
    }

    @Nullable
    private String lookupName(Map<String, String> names, @Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return names.get(id);
    }

    @Nullable
    private String firstPresent(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    @Nullable
    private EntityMetadata loadEntityMetadata(String entityId) {
        return provenanceRepository.loadEntityMetadata(List.of(entityId)).get(entityId);
    }

    private String requireCleanText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不能为空");
        }
        requireNoBoundaryWhitespace(value, field);
        return value;
    }

    private String requireCleanPathText(String value, String field) {
        try {
            return requireCleanText(UriUtils.decode(value, StandardCharsets.UTF_8), field);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "格式无效", e);
        }
    }

    @Nullable
    private String cleanOptionalText(@Nullable String value, String field) {
        if (value == null) {
            return null;
        }
        return requireCleanText(value, field);
    }

    private void validatePageRequest(int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 不能小于 0");
        }
        requirePositive(size, "size");
    }

    private void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "必须大于 0");
        }
    }

    private boolean isDescendingOrder(String order) {
        return switch (requireCleanText(order, "order")) {
            case "asc" -> false;
            case "desc" -> true;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的排序方向: " + order);
        };
    }

    private Instant parseInstantParam(String value, String field) {
        String cleanValue = requireCleanText(value, field);
        try {
            return Instant.parse(cleanValue);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的时间参数: " + field, e);
        }
    }

    private LocalDate parseDateParam(String value, String field) {
        String cleanValue = requireCleanText(value, field);
        try {
            return LocalDate.parse(cleanValue);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的日期参数: " + field, e);
        }
    }

    private void requireNoBoundaryWhitespace(String value, String field) {
        if (!value.equals(value.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不能包含首尾空白: " + value);
        }
    }

    private ConversationSummaryDto toConversationSummary(ConversationRecord c) {
        return new ConversationSummaryDto(
                c.id(), c.sessionId(), c.goal(), c.summary(),
                c.messageCount(), c.createdAt());
    }

    private List<ConversationRecord> filterByTime(List<ConversationRecord> records,
                                                   @Nullable String timeFrom,
                                                   @Nullable String timeTo) {
        var result = records;
        if (timeFrom != null) {
            Instant from = parseInstantParam(timeFrom, "timeFrom");
            result = result.stream().filter(c -> !c.createdAt().isBefore(from)).toList();
        }
        if (timeTo != null) {
            Instant to = parseInstantParam(timeTo, "timeTo");
            result = result.stream().filter(c -> !c.createdAt().isAfter(to)).toList();
        }
        return result;
    }
}

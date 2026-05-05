package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.*;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository.EntityMetadata;
import com.lifepilot.memory.consolidation.ConsolidationPipeline;
import com.lifepilot.memory.consolidation.EntityDeduplicator;
import com.lifepilot.memory.consolidation.UserProfileConsolidator;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.forgetting.ForgettingLogRepository;
import com.lifepilot.memory.governance.MemoryAccessPolicy;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.scope.MemoryOriginType;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryRealityType;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.scope.MemoryWriteContext;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.support.SqliteBusyRetry;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import jakarta.annotation.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 记忆管理 REST Controller — 暴露记忆系统的查询、搜索和管理 API。
 *
 * <p>所有端点在记忆系统未启用时返回 503 Service Unavailable。</p>
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
    private final @Nullable UserProfileConsolidator userProfileConsolidator;
    private final ForgettingLogRepository forgettingLogRepository;
    private final MemoryProvenanceRepository provenanceRepository;
    private final @Nullable ProjectContextResolver projectContextResolver;
    private final MemoryAccessPolicy memoryAccessPolicy;
    private final AtomicBoolean consolidating = new AtomicBoolean(false);
    private final AtomicBoolean deduplicating = new AtomicBoolean(false);

    public MemoryController(@Nullable SemanticMemory semanticMemory,
                            @Nullable EpisodicMemory episodicMemory,
                            @Nullable ProceduralMemory proceduralMemory,
                            @Nullable HybridRetriever hybridRetriever,
                            @Nullable ConsolidationPipeline consolidationPipeline,
                            @Nullable EntityDeduplicator entityDeduplicator,
                            @Nullable UserProfileConsolidator userProfileConsolidator,
                            ForgettingLogRepository forgettingLogRepository,
                            MemoryProvenanceRepository provenanceRepository,
                            @Nullable ProjectContextResolver projectContextResolver,
                            @Nullable MemoryAccessPolicy memoryAccessPolicy) {
        this.semanticMemory = semanticMemory;
        this.episodicMemory = episodicMemory;
        this.proceduralMemory = proceduralMemory;
        this.hybridRetriever = hybridRetriever;
        this.consolidationPipeline = consolidationPipeline;
        this.entityDeduplicator = entityDeduplicator;
        this.userProfileConsolidator = userProfileConsolidator;
        this.forgettingLogRepository = forgettingLogRepository;
        this.provenanceRepository = provenanceRepository;
        this.projectContextResolver = projectContextResolver;
        this.memoryAccessPolicy = memoryAccessPolicy != null ? memoryAccessPolicy : new MemoryAccessPolicy();
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
    public ApiResponse<List<MemorySearchResultDto>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK,
            @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        if (q == null || q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索关键词不能为空");
        }
        if (hybridRetriever == null) {
            return ApiResponse.ok(List.of());
        }
        var projectContext = resolveProjectContextForRequest(projectId);
        var readFilter = toProjectReadFilter(projectContext, Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
        var results = hybridRetriever.retrieve(q, topK, RetrievalWeights.DEFAULT, readFilter);
        Map<String, EntityMetadata> metadataById = provenanceRepository.loadEntityMetadata(
                results.stream().map(r -> r.entityId()).toList()
        );
        return ApiResponse.ok(results.stream()
                .map(r -> {
                    var metadata = metadataById.get(r.entityId());
                    return new MemorySearchResultDto(
                            r.entityId(),
                            r.entityType(),
                            r.name(),
                            r.description(),
                            r.fusedScore(),
                            metadata != null ? metadata.spaceId() : null,
                            metadata != null ? metadata.memoryScope() : null,
                            metadata != null ? metadata.realityType() : null
                    );
                })
                .toList());
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

        var projectContext = resolveProjectContextForRequest(projectId);
        var entities = semanticMemory.findAllCurrent(toProjectReadFilter(projectContext, Set.of()));

        // type 过滤
        if (type != null && !type.isBlank()) {
            try {
                var entityType = EntityType.valueOf(type);
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

        // 时间范围过滤
        if (timeFrom != null && !timeFrom.isBlank()) {
            Instant from = Instant.parse(timeFrom);
            entities = entities.stream().filter(e -> !e.createdAt().isBefore(from)).toList();
        }
        if (timeTo != null && !timeTo.isBlank()) {
            Instant to = Instant.parse(timeTo);
            entities = entities.stream().filter(e -> !e.createdAt().isAfter(to)).toList();
        }

        boolean requiresMetadataFiltering =
                (spaceId != null && !spaceId.isBlank())
                        || (memoryScope != null && !memoryScope.isBlank())
                        || (realityType != null && !realityType.isBlank());
        Map<String, EntityMetadata> filteredMetadataById = requiresMetadataFiltering
                ? provenanceRepository.loadEntityMetadata(entities.stream().map(TemporalEntity::id).toList())
                : Map.of();
        if (spaceId != null && !spaceId.isBlank()) {
            String normalizedSpaceId = spaceId.trim();
            entities = entities.stream()
                    .filter(entity -> {
                        var metadata = filteredMetadataById.get(entity.id());
                        return metadata != null && normalizedSpaceId.equalsIgnoreCase(metadata.spaceId());
                    })
                    .toList();
        }
        if (memoryScope != null && !memoryScope.isBlank()) {
            String normalizedMemoryScope = memoryScope.trim();
            entities = entities.stream()
                    .filter(entity -> {
                        var metadata = filteredMetadataById.get(entity.id());
                        return metadata != null && normalizedMemoryScope.equalsIgnoreCase(metadata.memoryScope());
                    })
                    .toList();
        }
        if (realityType != null && !realityType.isBlank()) {
            String normalizedRealityType = realityType.trim();
            entities = entities.stream()
                    .filter(entity -> {
                        var metadata = filteredMetadataById.get(entity.id());
                        return metadata != null && normalizedRealityType.equalsIgnoreCase(metadata.realityType());
                    })
                    .toList();
        }
        Set<String> filteredEntityIdsByProvenance = provenanceRepository.findEntityIdsByProvenanceFilters(
                originType,
                sourceKnowledgeBaseId,
                sourceDocumentId
        );
        if (filteredEntityIdsByProvenance != null) {
            entities = entities.stream()
                    .filter(entity -> filteredEntityIdsByProvenance.contains(entity.id()))
                    .toList();
        }

        // 排序
        Comparator<TemporalEntity> comparator = switch (sortBy) {
            case "name" -> Comparator.comparing(TemporalEntity::name);
            case "importanceScore" -> Comparator.comparing(TemporalEntity::importanceScore);
            case "accessCount" -> Comparator.comparingInt(TemporalEntity::accessCount);
            default -> Comparator.comparing(TemporalEntity::createdAt);
        };
        if ("desc".equalsIgnoreCase(order)) {
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
        var projectContext = resolveProjectContextForRequest(projectId);
        var entity = findReadableEntity(id, projectContext)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id));
        return ApiResponse.ok(toEntityDetail(entity, loadEntityMetadata(id)));
    }

    /**
     * 实体版本变更历史。
     */
    @GetMapping("/entities/{id}/history")
    public ApiResponse<List<EntityDetailDto>> getEntityHistory(@PathVariable String id,
                                                               @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        var projectContext = resolveProjectContextForRequest(projectId);
        var readFilter = toProjectReadFilter(projectContext, Set.of());
        var entity = findReadableEntity(id, projectContext)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id));
        var history = semanticMemory.getChangeHistory(entity.name(), entity.type());
        Map<String, EntityMetadata> metadataById = provenanceRepository.loadEntityMetadata(
                history.stream().map(TemporalEntity::id).toList());
        return ApiResponse.ok(history.stream()
                .filter(historyEntity -> projectContext == null
                        || matchesReadFilter(metadataById.get(historyEntity.id()), readFilter))
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
        var projectContext = resolveProjectContextForRequest(projectId);
        var readFilter = toProjectReadFilter(projectContext, Set.of());
        findReadableEntity(id, projectContext)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id));
        var relatedEntities = semanticMemory.findRelated(id, maxDepth);
        if (projectContext != null && !relatedEntities.isEmpty()) {
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
        var projectContext = resolveProjectContextForRequest(projectId);
        findReadableEntity(id, projectContext)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id));
        var rawItems = provenanceRepository.findEntityProvenances(
                id, originType, sourceKnowledgeBaseId, sourceDocumentId);
        return ApiResponse.ok(enrichProvenances(rawItems));
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
        if (limit <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须大于 0");
        }
        var rawItems = provenanceRepository.findRecentProvenanceSummaries(
                originType, sourceKnowledgeBaseId, sourceDocumentId, limit);
        return ApiResponse.ok(enrichMemoryProvenanceSummaries(rawItems));
    }

    /**
     * 归档实体。
     */
    @DeleteMapping("/entities/{id}")
    public ApiResponse<Void> deleteEntity(@PathVariable String id,
                                          @RequestParam(required = false) @Nullable String projectId) {
        requireMemoryEnabled();
        var projectContext = resolveProjectContextForRequest(projectId);
        var entity = findWritableEntity(id, projectContext).orElse(null);
        if (entity == null) {
            if (projectContext != null && projectContext.isolated()
                    && findReadableEntity(id, projectContext).isPresent()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "隔离项目不能直接归档继承记忆，请先创建项目内覆盖版本");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id);
        }
        SqliteBusyRetry.run(() -> semanticMemory.archive(entity));
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

        // 校验 type 为有效 EntityType
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(request.type());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的实体类型: " + request.type());
        }

        var now = Instant.now();
        float importance = request.importanceScore() != null ? request.importanceScore() : 0.5f;
        var entity = new TemporalEntity(
                UUID.randomUUID().toString(),
                entityType,
                request.name().trim(),
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
        var projectContext = resolveProjectContextForRequest(projectId);

        var existing = findWritableEntity(id, projectContext).orElse(null);
        if (existing == null) {
            if (projectContext != null && projectContext.isolated()) {
                var inherited = findReadableEntity(id, projectContext)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id));
                var overlay = updateInheritedEntityAsOverlay(inherited, request, projectContext);
                log.info("隔离项目更新继承实体为 overlay: baseId={}, overlayId={}, name={}",
                        inherited.id(), overlay.id(), overlay.name());
                var metadata = provenanceRepository.loadEntityMetadata(List.of(overlay.id())).get(overlay.id());
                return ApiResponse.ok(toEntityDetail(overlay, metadata));
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id);
        }

        if (!existing.isCurrent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "实体已归档: " + id);
        }

        // 合并更新字段
        String description = request.description() != null ? request.description() : existing.description();
        Map<String, Object> properties = request.properties() != null ? request.properties() : existing.properties();
        float importance = request.importanceScore() != null ? request.importanceScore() : existing.importanceScore();
        EntityMetadata existingMetadata = loadEntityMetadata(id);

        var updated = new TemporalEntity(
                existing.id(),
                existing.type(),
                existing.name(),
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
                Instant.now()
        );

        var saved = SqliteBusyRetry.execute(() -> semanticMemory.upsertWithConflictDetection(
                updated, MANUAL_SOURCE, projectContext != null
                        ? toManualProjectWriteContext(projectContext)
                        : writeContextFromMetadata(existingMetadata)));
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
        var projectContext = resolveProjectContextForRequest(projectId);
        var readFilter = toProjectReadFilter(projectContext, Set.of());

        List<TemporalRelation> relations;
        if (entityId != null && !entityId.isBlank()) {
            if (projectContext != null && findReadableEntity(entityId, projectContext).isEmpty()) {
                return ApiResponse.ok(new PageResult<>(List.of(), page, size, 0L));
            }
            relations = semanticMemory.findRelationsByEntityId(entityId);
        } else {
            relations = semanticMemory.findAllCurrentRelations();
        }

        // relationType 过滤
        if (relationType != null && !relationType.isBlank()) {
            relations = relations.stream()
                    .filter(r -> r.relationType().equalsIgnoreCase(relationType))
                    .toList();
        }

        // 收集实体 ID 批量查询名称
        Set<String> entityIds = new HashSet<>();
        for (var r : relations) {
            entityIds.add(r.sourceEntityId());
            entityIds.add(r.targetEntityId());
        }
        Map<String, TemporalEntity> entityMap = semanticMemory.findByIds(entityIds);
        if (projectContext != null) {
            entityMap = semanticMemory.findByIds(entityIds, readFilter);
            Map<String, TemporalEntity> visibleEntityMap = entityMap;
            relations = relations.stream()
                    .filter(r -> visibleEntityMap.containsKey(r.sourceEntityId())
                            && visibleEntityMap.containsKey(r.targetEntityId()))
                    .toList();
        }
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
        if (episodicMemory == null) {
            return ApiResponse.ok(new PageResult<>(List.of(), page, size, 0L));
        }

        // 如果有搜索关键词，使用 FTS5 搜索
        if (q != null && !q.isBlank()) {
            var searchResults = episodicMemory.search(q);
            // 时间范围过滤
            var filtered = filterByTime(searchResults, timeFrom, timeTo);
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
        var filtered = filterByTime(conversations, timeFrom, timeTo);
        var items = filtered.stream().map(this::toConversationSummary).toList();
        return ApiResponse.ok(new PageResult<>(items, page, size, total));
    }

    /**
     * 对话详情。
     */
    @GetMapping("/conversations/{id}")
    public ApiResponse<ConversationRecord> getConversation(@PathVariable String id) {
        requireMemoryEnabled();
        if (episodicMemory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在: " + id);
        }
        return ApiResponse.ok(episodicMemory.getById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在: " + id)));
    }

    /**
     * 删除对话。
     */
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(@PathVariable String id) {
        requireMemoryEnabled();
        if (episodicMemory == null || !episodicMemory.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在: " + id);
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
        Comparator<com.lifepilot.memory.procedural.ProcedureTemplate> comparator = switch (sortBy) {
            case "successRate" -> Comparator.comparing(com.lifepilot.memory.procedural.ProcedureTemplate::successRate);
            case "useCount" -> Comparator.comparingInt(com.lifepilot.memory.procedural.ProcedureTemplate::useCount);
            default -> Comparator.comparing(com.lifepilot.memory.procedural.ProcedureTemplate::createdAt);
        };
        if ("desc".equalsIgnoreCase(order)) {
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
        if (proceduralMemory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + id);
        }
        return ApiResponse.ok(proceduralMemory.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + id)));
    }

    /**
     * 删除模板。
     */
    @DeleteMapping("/templates/{id}")
    public ApiResponse<Void> deleteTemplate(@PathVariable String id) {
        requireMemoryEnabled();
        if (proceduralMemory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + id);
        }
        proceduralMemory.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + id));
        proceduralMemory.delete(id);
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
        if (category != null && !category.isBlank()) {
            return ApiResponse.ok(List.copyOf(proceduralMemory.getPreferences(category)));
        }
        return ApiResponse.ok(List.copyOf(proceduralMemory.listAllPreferences()));
    }

    /**
     * 删除偏好规则。
     */
    @DeleteMapping("/preferences/{id}")
    public ApiResponse<Void> deletePreference(@PathVariable String id) {
        requireMemoryEnabled();
        if (proceduralMemory == null || !proceduralMemory.deletePreference(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "偏好规则不存在: " + id);
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
        return ApiResponse.ok(forgettingLogRepository.findPaginated(timeFrom, timeTo, strategy, page, size));
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
                consolidationPipeline.consolidate();
            } finally {
                consolidating.set(false);
            }
        });
        return ApiResponse.ok(Map.of("status", "accepted", "message", "巩固任务已提交"));
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
            var entity = new TemporalEntity(
                    null, EntityType.CUSTOM, "__consolidated_profile", request.description(),
                    Map.of(), 1, true, Instant.now(), null, null,
                    1.0f, 1.0f, 0, null, Instant.now(), Instant.now());
            var created = semanticMemory.upsertWithConflictDetection(entity, "manual-edit");
            return ApiResponse.ok(new ProfileDto(
                    created.description() != null ? created.description() : "",
                    created.updatedAt().toString(),
                    created.version()));
        }
    }

    record ProfileDto(String description, @Nullable String updatedAt, @Nullable Integer version) {}
    record ProfileUpdateRequest(String description) {}

    // ========== 内部辅助方法 ==========

    @Nullable
    private ProjectContext resolveProjectContextForRequest(@Nullable String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        if (projectContextResolver == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "项目上下文解析器未启用");
        }
        return projectContextResolver.resolve(projectId.trim());
    }

    private MemoryReadFilter toProjectReadFilter(@Nullable ProjectContext ctx, Set<MemoryScope> scopes) {
        return memoryAccessPolicy.buildProjectReadFilter(ctx, scopes);
    }

    private MemoryWriteContext toManualProjectWriteContext(@Nullable ProjectContext ctx) {
        return memoryAccessPolicy.buildProjectWriteContext(
                ctx,
                null,
                null,
                null,
                MANUAL_SOURCE,
                MemoryOriginType.MANUAL,
                MemoryRealityType.UNKNOWN);
    }

    private Optional<TemporalEntity> findReadableEntity(String entityId, @Nullable ProjectContext ctx) {
        if (ctx == null) {
            return semanticMemory.findById(entityId);
        }
        return semanticMemory.findByIds(List.of(entityId), toProjectReadFilter(ctx, Set.of()))
                .values()
                .stream()
                .findFirst();
    }

    private Optional<TemporalEntity> findWritableEntity(String entityId, @Nullable ProjectContext ctx) {
        if (ctx == null) {
            return semanticMemory.findById(entityId);
        }
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
        String description = request.description() != null ? request.description() : baseEntity.description();
        Map<String, Object> properties = request.properties() != null ? request.properties() : baseEntity.properties();
        float importance = request.importanceScore() != null ? request.importanceScore() : baseEntity.importanceScore();
        var overlay = new TemporalEntity(
                null,
                baseEntity.type(),
                baseEntity.name(),
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
                baseEntity.derivationSources());
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
        return items.stream()
                .map(item -> new EntityProvenanceDto(
                        item.originType(),
                        item.sourceReference(),
                        item.sourceConversationId(),
                        item.sourceSessionId(),
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
                        item.createdAt()
                ))
                .toList();
    }

    @Nullable
    private String lookupName(Map<String, String> names, @Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return names.get(id);
    }

    @Nullable
    private EntityMetadata loadEntityMetadata(String entityId) {
        return provenanceRepository.loadEntityMetadata(List.of(entityId)).get(entityId);
    }

    private MemoryWriteContext writeContextFromMetadata(@Nullable EntityMetadata metadata) {
        return new MemoryWriteContext(
                metadata != null ? metadata.spaceId() : null,
                parseMemoryScope(metadata != null ? metadata.memoryScope() : null),
                MemoryOriginType.MANUAL,
                MemoryRealityType.UNKNOWN,
                MANUAL_SOURCE,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @Nullable
    private MemoryScope parseMemoryScope(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MemoryScope.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            log.warn("实体记忆范围元数据非法，按默认范围写入: memoryScope={}", raw);
            return null;
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
        if (timeFrom != null && !timeFrom.isBlank()) {
            Instant from = Instant.parse(timeFrom);
            result = result.stream().filter(c -> !c.createdAt().isBefore(from)).toList();
        }
        if (timeTo != null && !timeTo.isBlank()) {
            Instant to = Instant.parse(timeTo);
            result = result.stream().filter(c -> !c.createdAt().isAfter(to)).toList();
        }
        return result;
    }
}

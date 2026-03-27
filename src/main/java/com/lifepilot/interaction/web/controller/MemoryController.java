package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.*;
import com.lifepilot.memory.consolidation.ConsolidationPipeline;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import jakarta.annotation.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    private final @Nullable SemanticMemory semanticMemory;
    private final @Nullable EpisodicMemory episodicMemory;
    private final @Nullable ProceduralMemory proceduralMemory;
    private final @Nullable HybridRetriever hybridRetriever;
    private final @Nullable ConsolidationPipeline consolidationPipeline;
    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean consolidating = new AtomicBoolean(false);

    public MemoryController(@Nullable SemanticMemory semanticMemory,
                            @Nullable EpisodicMemory episodicMemory,
                            @Nullable ProceduralMemory proceduralMemory,
                            @Nullable HybridRetriever hybridRetriever,
                            @Nullable ConsolidationPipeline consolidationPipeline,
                            JdbcTemplate jdbcTemplate) {
        this.semanticMemory = semanticMemory;
        this.episodicMemory = episodicMemory;
        this.proceduralMemory = proceduralMemory;
        this.hybridRetriever = hybridRetriever;
        this.consolidationPipeline = consolidationPipeline;
        this.jdbcTemplate = jdbcTemplate;
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
    public MemoryStatsDto getStats() {
        requireMemoryEnabled();

        long entityCount = semanticMemory.countCurrent();
        long relationCount = semanticMemory.countCurrentRelations();

        // 按 EntityType 分组统计
        var allCurrent = semanticMemory.findAllCurrent();
        Map<String, Long> entityCountByType = allCurrent.stream()
                .filter(TemporalEntity::isCurrent)
                .collect(Collectors.groupingBy(e -> e.type().name(), Collectors.counting()));

        long conversationCount = episodicMemory != null ? episodicMemory.countConversations() : 0L;

        long templateCount = 0L;
        long preferenceCount = 0L;
        if (proceduralMemory != null) {
            templateCount = proceduralMemory.listAllTemplates().size();
            preferenceCount = proceduralMemory.listAllPreferences().size();
        }

        // 遗忘日志统计
        var forgettingLogCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM forgetting_log", Long.class);
        var lastForgettingTime = jdbcTemplate.query(
                "SELECT created_at FROM forgetting_log ORDER BY created_at DESC LIMIT 1",
                (rs, rowNum) -> rs.getString("created_at"));

        return new MemoryStatsDto(
                conversationCount,
                entityCount,
                entityCountByType,
                relationCount,
                templateCount,
                preferenceCount,
                forgettingLogCount != null ? forgettingLogCount : 0L,
                lastForgettingTime.isEmpty() ? null : lastForgettingTime.getFirst()
        );
    }

    // ========== Req 7: 统一记忆搜索 ==========

    /**
     * 统一记忆搜索 — 调用 HybridRetriever 三路并行检索。
     */
    @GetMapping("/search")
    public List<MemorySearchResultDto> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK) {
        requireMemoryEnabled();
        if (q == null || q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索关键词不能为空");
        }
        if (hybridRetriever == null) {
            return List.of();
        }
        var results = hybridRetriever.retrieve(q, topK, RetrievalWeights.DEFAULT, MemoryReadFilter.userMemory());
        return results.stream()
                .map(r -> new MemorySearchResultDto(
                        r.entityId(), r.entityType(), r.name(), r.description(), r.fusedScore()))
                .toList();
    }

    // ========== Req 2: L3 语义记忆实体管理 ==========

    /**
     * 实体分页列表（支持 type/q/timeFrom/timeTo/sortBy/order 过滤排序）。
     */
    @GetMapping("/entities")
    public PageResult<EntitySummaryDto> listEntities(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Nullable String type,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String timeFrom,
            @RequestParam(required = false) @Nullable String timeTo,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        requireMemoryEnabled();

        var entities = semanticMemory.findAllCurrent();

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
        var pageItems = entities.subList(fromIndex, toIndex).stream()
                .map(e -> new EntitySummaryDto(
                        e.id(), e.type().name(), e.type().label(), e.name(), e.description(),
                        e.importanceScore(), e.accessCount(), e.version(),
                        e.createdAt(), e.updatedAt()))
                .toList();

        return new PageResult<>(pageItems, page, size, total);
    }

    /**
     * 实体详情。
     */
    @GetMapping("/entities/{id}")
    public EntityDetailDto getEntity(@PathVariable String id) {
        requireMemoryEnabled();
        var entity = semanticMemory.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id));
        return toEntityDetail(entity);
    }

    /**
     * 实体版本变更历史。
     */
    @GetMapping("/entities/{id}/history")
    public List<EntityDetailDto> getEntityHistory(@PathVariable String id) {
        requireMemoryEnabled();
        var entity = semanticMemory.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id));
        return semanticMemory.getChangeHistory(entity.name(), entity.type()).stream()
                .map(this::toEntityDetail)
                .toList();
    }

    /**
     * 关联实体。
     */
    @GetMapping("/entities/{id}/related")
    public List<EntitySummaryDto> getRelatedEntities(
            @PathVariable String id,
            @RequestParam(defaultValue = "2") int maxDepth) {
        requireMemoryEnabled();
        semanticMemory.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id));
        return semanticMemory.findRelated(id, maxDepth).stream()
                .map(e -> new EntitySummaryDto(
                        e.id(), e.type().name(), e.type().label(), e.name(), e.description(),
                        e.importanceScore(), e.accessCount(), e.version(),
                        e.createdAt(), e.updatedAt()))
                .toList();
    }

    /**
     * 归档实体。
     */
    @DeleteMapping("/entities/{id}")
    public ResponseEntity<Void> deleteEntity(@PathVariable String id) {
        requireMemoryEnabled();
        var entity = semanticMemory.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id));
        semanticMemory.archive(entity);
        return ResponseEntity.noContent().build();
    }

    /**
     * 手动创建实体。
     *
     * <p>extractionConfidence 固定为 1.0 表示手动创建。</p>
     */
    @PostMapping("/entities")
    public ResponseEntity<EntityDetailDto> createEntity(@RequestBody EntityCreateRequest request) {
        requireMemoryEnabled();

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

        semanticMemory.upsertWithConflictDetection(entity, null);
        log.info("手动创建实体: id={}, name={}, type={}", entity.id(), entity.name(), entity.type());
        return ResponseEntity.status(HttpStatus.CREATED).body(toEntityDetail(entity));
    }

    /**
     * 更新实体。
     *
     * <p>仅允许更新当前有效实体（isCurrent=true），已归档实体返回 404。</p>
     */
    @PutMapping("/entities/{id}")
    public EntityDetailDto updateEntity(@PathVariable String id,
                                        @RequestBody EntityUpdateRequest request) {
        requireMemoryEnabled();

        var existing = semanticMemory.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实体不存在: " + id));

        if (!existing.isCurrent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "实体已归档: " + id);
        }

        // 合并更新字段
        String description = request.description() != null ? request.description() : existing.description();
        Map<String, Object> properties = request.properties() != null ? request.properties() : existing.properties();
        float importance = request.importanceScore() != null ? request.importanceScore() : existing.importanceScore();

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

        semanticMemory.upsertWithConflictDetection(updated, null);
        log.info("更新实体: id={}, name={}", updated.id(), updated.name());
        return toEntityDetail(updated);
    }

    // ========== Req 3: L3 关系查询 ==========

    /**
     * 关系分页列表（附带实体名称）。
     */
    @GetMapping("/relations")
    public PageResult<RelationDto> listRelations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Nullable String entityId,
            @RequestParam(required = false) @Nullable String relationType) {
        requireMemoryEnabled();

        List<TemporalRelation> relations;
        if (entityId != null && !entityId.isBlank()) {
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

        // 分页
        long total = relations.size();
        int fromIndex = Math.min(page * size, relations.size());
        int toIndex = Math.min(fromIndex + size, relations.size());
        var pageItems = relations.subList(fromIndex, toIndex).stream()
                .map(r -> {
                    var source = entityMap.get(r.sourceEntityId());
                    var target = entityMap.get(r.targetEntityId());
                    return new RelationDto(
                            r.id(),
                            r.sourceEntityId(),
                            source != null ? source.name() : r.sourceEntityId(),
                            source != null ? source.type().name() : "UNKNOWN",
                            r.targetEntityId(),
                            target != null ? target.name() : r.targetEntityId(),
                            target != null ? target.type().name() : "UNKNOWN",
                            r.relationType(),
                            r.strength(),
                            r.validFrom(),
                            r.validTo(),
                            r.createdAt());
                })
                .toList();

        return new PageResult<>(pageItems, page, size, total);
    }

    // ========== Req 4: L2 情景记忆浏览 ==========

    /**
     * 对话分页列表。
     */
    @GetMapping("/conversations")
    public PageResult<ConversationSummaryDto> listConversations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String timeFrom,
            @RequestParam(required = false) @Nullable String timeTo) {
        requireMemoryEnabled();
        if (episodicMemory == null) {
            return new PageResult<>(List.of(), page, size, 0L);
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
            return new PageResult<>(items, page, size, total);
        }

        // 无搜索关键词，分页查询
        long total = episodicMemory.countConversations();
        var conversations = episodicMemory.listConversations(page, size);
        // 时间范围过滤（分页后过滤，简化实现）
        var filtered = filterByTime(conversations, timeFrom, timeTo);
        var items = filtered.stream().map(this::toConversationSummary).toList();
        return new PageResult<>(items, page, size, total);
    }

    /**
     * 对话详情。
     */
    @GetMapping("/conversations/{id}")
    public ConversationRecord getConversation(@PathVariable String id) {
        requireMemoryEnabled();
        if (episodicMemory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在: " + id);
        }
        return episodicMemory.getById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在: " + id));
    }

    /**
     * 删除对话。
     */
    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String id) {
        requireMemoryEnabled();
        if (episodicMemory == null || !episodicMemory.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在: " + id);
        }
        return ResponseEntity.noContent().build();
    }

    // ========== Req 5: L4 程序记忆浏览 ==========

    /**
     * 模板分页列表。
     */
    @GetMapping("/templates")
    public PageResult<Object> listTemplates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        requireMemoryEnabled();
        if (proceduralMemory == null) {
            return new PageResult<>(List.of(), page, size, 0L);
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

        return new PageResult<>(List.copyOf(pageItems), page, size, total);
    }

    /**
     * 模板详情。
     */
    @GetMapping("/templates/{id}")
    public Object getTemplate(@PathVariable String id) {
        requireMemoryEnabled();
        if (proceduralMemory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + id);
        }
        return proceduralMemory.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + id));
    }

    /**
     * 删除模板。
     */
    @DeleteMapping("/templates/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String id) {
        requireMemoryEnabled();
        if (proceduralMemory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + id);
        }
        proceduralMemory.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + id));
        proceduralMemory.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 偏好规则列表。
     */
    @GetMapping("/preferences")
    public List<Object> listPreferences(
            @RequestParam(required = false) @Nullable String category) {
        requireMemoryEnabled();
        if (proceduralMemory == null) {
            return List.of();
        }
        if (category != null && !category.isBlank()) {
            return List.copyOf(proceduralMemory.getPreferences(category));
        }
        return List.copyOf(proceduralMemory.listAllPreferences());
    }

    /**
     * 删除偏好规则。
     */
    @DeleteMapping("/preferences/{id}")
    public ResponseEntity<Void> deletePreference(@PathVariable String id) {
        requireMemoryEnabled();
        if (proceduralMemory == null || !proceduralMemory.deletePreference(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "偏好规则不存在: " + id);
        }
        return ResponseEntity.noContent().build();
    }

    // ========== Req 6: 遗忘日志查询 ==========

    /**
     * 遗忘日志分页列表。
     */
    @GetMapping("/forgetting-logs")
    public PageResult<ForgettingLogDto> listForgettingLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Nullable String timeFrom,
            @RequestParam(required = false) @Nullable String timeTo,
            @RequestParam(required = false) @Nullable String strategy) {
        requireMemoryEnabled();

        // 构建动态 SQL
        var conditions = new ArrayList<String>();
        var params = new ArrayList<Object>();

        if (timeFrom != null && !timeFrom.isBlank()) {
            conditions.add("created_at >= ?");
            params.add(timeFrom);
        }
        if (timeTo != null && !timeTo.isBlank()) {
            conditions.add("created_at <= ?");
            params.add(timeTo);
        }
        if (strategy != null && !strategy.isBlank()) {
            conditions.add("strategy = ?");
            params.add(strategy);
        }

        String whereClause = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

        // 查询总数
        var countSql = "SELECT COUNT(*) FROM forgetting_log" + whereClause;
        var total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());

        // 查询分页数据
        var dataSql = "SELECT * FROM forgetting_log" + whereClause + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        params.add(size);
        params.add(page * size);

        var items = jdbcTemplate.query(dataSql, (rs, rowNum) -> new ForgettingLogDto(
                rs.getString("id"),
                rs.getString("entity_id"),
                rs.getString("entity_name"),
                rs.getString("strategy"),
                rs.getString("action_taken"),
                rs.getFloat("forgetting_priority"),
                rs.getString("reason"),
                Instant.parse(rs.getString("created_at"))
        ), params.toArray());

        return new PageResult<>(items, page, size, total != null ? total : 0L);
    }

    // ========== Req 8: 手动触发巩固 ==========

    /**
     * 手动触发记忆巩固管线。
     */
    @PostMapping("/consolidate")
    public ResponseEntity<Map<String, String>> triggerConsolidation() {
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
        return ResponseEntity.accepted().body(Map.of("status", "accepted", "message", "巩固任务已提交"));
    }

    // ========== 内部辅助方法 ==========

    private EntityDetailDto toEntityDetail(TemporalEntity e) {
        return new EntityDetailDto(
                e.id(), e.type().name(), e.type().label(), e.name(), e.description(),
                e.properties(), e.version(), e.isCurrent(),
                e.validFrom(), e.validTo(), e.sourceConversationId(),
                e.extractionConfidence(), e.importanceScore(), e.accessCount(),
                e.lastAccessedAt(), e.createdAt(), e.updatedAt());
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

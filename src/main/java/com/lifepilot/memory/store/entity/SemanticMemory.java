package com.lifepilot.memory.store.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lifepilot.agent.learning.conflict.ConflictResolutionService;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.governance.lifecycle.WeightSource;
import com.lifepilot.memory.governance.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.governance.lifecycle.events.EntityWeightChanged;
import com.lifepilot.memory.semantic.*;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryRealityType;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.agent.learning.staleness.StalenessCoordinator;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 语义记忆服务 — 管理时序知识图谱的完整生命周期。
 *
 * <p>提供版本感知的 CRUD、冲突检测、时间旅行查询和图遍历能力。
 * 使用 JdbcTemplate 执行数据库操作。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SemanticMemory {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemory.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final String DEFAULT_PERSONAL_SPACE_ID = "memory-space-personal-default";
    private static final String DEFAULT_EXPERIENCE_SPACE_ID = "memory-space-experience-default";

    /**
     * temporal_entities 视图包含的全部列 — 含 V15 生命周期新字段。
     * 集中声明以确保所有 SELECT 路径与 {@link #mapRowToEntity} 对齐。
     */
    private static final String ENTITY_SELECT_COLUMNS = "id, type, name, description, properties_json, "
            + "version, is_current, valid_from, valid_to, source_conversation_id, "
            + "extraction_confidence, importance_score, access_count, last_accessed_at, created_at, updated_at, "
            + "lifecycle_state, lifecycle_reason, expires_at, temporality, succeeded_by, is_derived, derivation_sources, "
            + "evidence_kind, trust_level, trust_score, evidence_count, last_verified_at";

    private final JdbcTemplate jdbcTemplate;
    private final ConflictDetector conflictDetector;
    private final VersionMerger versionMerger;
    private final VectorSearcher vectorSearcher;
    @Nullable
    private final MemorySpaceRepository memorySpaceRepository;
    /** Spring 事件总线 — Task 11/12 发布权重与生命周期变化事件；为保持单测轻量，允许为空。 */
    @Nullable
    private ApplicationEventPublisher eventPublisher;

    /** 记忆写入回调 — 通知检索引擎数据已变更。 */
    @Nullable
    private Runnable writeCallback;

    /**
     * 冲突裁决服务 — 在 upsert 末尾对新/合并实体触发语义冲突 LLM 裁决；为保持单测轻量，
     * 允许为空（setter 注入，不改构造器签名）。
     */
    @Nullable
    private ConflictResolutionService conflictResolutionService;
    @Nullable
    private Boolean overlayTableAvailable;
    @Nullable
    private MemoryProjectionService projectionService;

    @Nullable
    private StalenessCoordinator stalenessCoordinator;

    public SemanticMemory(JdbcTemplate jdbcTemplate,
                          ConflictDetector conflictDetector,
                          VersionMerger versionMerger,
                          VectorSearcher vectorSearcher) {
        this(jdbcTemplate, conflictDetector, versionMerger, vectorSearcher, null);
    }

    public SemanticMemory(JdbcTemplate jdbcTemplate,
                          ConflictDetector conflictDetector,
                          VersionMerger versionMerger,
                          VectorSearcher vectorSearcher,
                          @Nullable MemorySpaceRepository memorySpaceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.conflictDetector = conflictDetector;
        this.versionMerger = versionMerger;
        this.vectorSearcher = vectorSearcher;
        this.memorySpaceRepository = memorySpaceRepository;
    }

    /**
     * 注入 Spring {@link ApplicationEventPublisher} — 用 setter 而非构造器注入，
     * 避免破坏现有 2-/3- 参构造器签名以及大量手工装配测试。
     *
     * @param eventPublisher 事件发布器
     */
    public void setEventPublisher(@Nullable ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 设置记忆写入回调，用于在实体写入后通知检索引擎重置空数据标记。
     *
     * <p>通过 setter 注入避免 SemanticMemory ↔ HybridRetriever 循环依赖。</p>
     *
     * @param writeCallback 写入回调
     */
    public void setWriteCallback(@Nullable Runnable writeCallback) {
        this.writeCallback = writeCallback;
    }

    /**
     * 注入冲突裁决服务 —— setter 注入避免 {@code SemanticMemory ↔ ConflictResolutionService}
     * 的循环依赖（裁决服务内部又需要回调 {@code updateLifecycleState / updateSucceededBy /
     * findById}）。Bean 装配由 {@code MemoryAutoConfiguration} 串联。
     *
     * @param conflictResolutionService 冲突裁决服务，{@code null} 表示关闭裁决
     */
    public void setConflictResolutionService(@Nullable ConflictResolutionService conflictResolutionService) {
        this.conflictResolutionService = conflictResolutionService;
    }

    /** 注入投影服务。向量 upsert/delete 必须走 outbox。 */
    public void setProjectionService(@Nullable MemoryProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    /**
     * 注入 staleness 协调器（memory-staleness spec）。
     * 在 upsertWithConflictDetection 成功返回前异步触发：识别语义冲突邻居 → 标记 STALE_CANDIDATE
     * → 生成邻居刷新候选。coordinator 为 null 时等价于 staleness 能力关闭。
     */
    public void setStalenessCoordinator(@Nullable StalenessCoordinator stalenessCoordinator) {
        this.stalenessCoordinator = stalenessCoordinator;
    }

    /**
     * 版本感知 upsert：冲突检测 → 合并/新建 → 插入 → 更新向量索引。
     *
     * @param incoming       新实体信息
     * @param conversationId 来源会话 ID
     * @return 持久化后的实体
     */
    @Transactional
    public TemporalEntity upsertWithConflictDetection(TemporalEntity incoming, String conversationId) {
        return upsertWithConflictDetection(incoming, conversationId, null);
    }

    /**
     * 带写入上下文的版本感知 upsert。
     *
     * @param incoming        新实体信息
     * @param sourceReference 来源引用（兼容旧的 conversationId/documentId/sourceId 语义）
     * @param writeContext    目标写入上下文
     * @return 持久化后的实体
     */
    @Transactional
    public TemporalEntity upsertWithConflictDetection(TemporalEntity incoming,
                                                      @Nullable String sourceReference,
                                                      @Nullable MemoryWriteContext writeContext) {
        MemoryWriteContext resolvedContext = resolveWriteContext(incoming, sourceReference, writeContext);
        incoming = MemoryQualityPolicy.applyDefaults(incoming, resolvedContext);
        var existing = conflictDetector.detectConflict(incoming, resolvedContext.spaceId());

        if (existing.isPresent()) {
            // 冲突：版本化合并
            var mergeResult = versionMerger.merge(existing.get(), incoming, sourceReference);
            if (!mergeResult.isNewVersion()) {
                log.debug("语义记忆: upsert 无变化, name={}", incoming.name());
                return existing.get();
            }
            // 关闭旧版本
            var now = Instant.now();
            closeCurrentEntityVersion(existing.get().id(), now);

            var merged = mergeResult.mergedEntity();
            // 合并分支：生命周期字段沿用 incoming（调用方最新意图），existing 的状态
            // 由 Task 12 事件路径单独改写，这里不清零。
            var entity = new TemporalEntity(
                    existing.get().id(), merged.type(), merged.name(), merged.description(),
                    merged.properties(), merged.version(), true,
                    merged.validFrom(), null, resolvedContext.sourceConversationId(),
                    merged.extractionConfidence(), merged.importanceScore(),
                    merged.accessCount(), merged.lastAccessedAt(),
                    existing.get().createdAt(), now,
                    incoming.lifecycleState(), incoming.lifecycleReason(), incoming.expiresAt(),
                    incoming.temporality(), incoming.succeededBy(),
                    incoming.isDerived(), incoming.derivationSources(),
                    incoming.evidenceKind(), incoming.trustLevel(), incoming.trustScore(),
                    Math.max(existing.get().evidenceCount(), incoming.evidenceCount()),
                    incoming.lastVerifiedAt() != null ? incoming.lastVerifiedAt() : existing.get().lastVerifiedAt());
            insertEntityVersion(entity, resolvedContext, now);
            updateEntityRoot(entity, resolvedContext, now);
            enqueueVectorUpsertProjection(entity);
            notifyWriteCallback();
            triggerConflictResolution(entity);
            triggerStalenessProcessing(entity);
            log.debug("语义记忆: 版本化更新, name={}, version={}", entity.name(), entity.version());
            return entity;
        } else {
            // 新建 — 保留 incoming 的生命周期字段
            var now = Instant.now();
            var newId = incoming.id() != null ? incoming.id() : UUID.randomUUID().toString();
            var entity = new TemporalEntity(
                    newId, incoming.type(), incoming.name(), incoming.description(),
                    incoming.properties(), 1, true,
                    now, null, resolvedContext.sourceConversationId(),
                    incoming.extractionConfidence(), incoming.importanceScore(),
                    0, null, now, now,
                    incoming.lifecycleState(), incoming.lifecycleReason(), incoming.expiresAt(),
                    incoming.temporality(), incoming.succeededBy(),
                    incoming.isDerived(), incoming.derivationSources(),
                    incoming.evidenceKind(), incoming.trustLevel(), incoming.trustScore(),
                    incoming.evidenceCount(), incoming.lastVerifiedAt());
            insertEntityRoot(entity, resolvedContext, now);
            insertEntityVersion(entity, resolvedContext, now);
            enqueueVectorUpsertProjection(entity);
            notifyWriteCallback();
            // Task 12：新建实体发布 LifecycleChanged(null → newState)，source 由 provenance 推断
            publishAfterCommit(new EntityLifecycleChanged(
                    entity.id(),
                    entity.type().name(),
                    null,
                    entity.lifecycleState(),
                    "created by " + (sourceReference != null ? sourceReference : "unknown"),
                    resolveChangeSource(sourceReference)));
            triggerConflictResolution(entity);
            triggerStalenessProcessing(entity);
            log.debug("语义记忆: 新建实体, name={}, id={}", entity.name(), entity.id());
            return entity;
        }
    }

    /**
     * 在项目 space 中创建/更新继承实体的 overlay。
     *
     * <p>该方法只写项目 space，不修改 base 实体；随后记录 overlay lineage，
     * 供读取链路在同一项目视图中遮蔽 base 实体。</p>
     *
     * @param incoming        overlay 的实体内容
     * @param baseEntity      被覆盖的继承实体
     * @param sourceReference 来源引用
     * @param writeContext    必须包含项目 spaceId
     * @return 项目内持久化后的 overlay 实体
     */
    @Transactional
    public TemporalEntity upsertProjectOverlay(TemporalEntity incoming,
                                               TemporalEntity baseEntity,
                                               @Nullable String sourceReference,
                                               MemoryWriteContext writeContext) {
        if (writeContext == null || writeContext.spaceId() == null || writeContext.spaceId().isBlank()) {
            throw new IllegalArgumentException("创建项目 overlay 必须提供项目 spaceId");
        }
        TemporalEntity overlay = upsertWithConflictDetection(incoming, sourceReference, writeContext);
        recordEntityOverlay(
                overlay.id(),
                baseEntity.id(),
                writeContext.spaceId(),
                findEntitySpaceId(baseEntity.id()).orElse(null),
                "UPDATE");
        return overlay;
    }

    /** 时间旅行查询：返回指定时间点有效的所有实体。 */
    public List<TemporalEntity> queryAtTime(Instant point) {
        return queryAtTime(point, null);
    }

    /** 时间旅行查询：返回指定时间点有效且符合读取过滤条件的实体。 */
    public List<TemporalEntity> queryAtTime(Instant point, @Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities WHERE valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)");
        List<Object> params = new ArrayList<>();
        params.add(point.toString());
        params.add(point.toString());
        appendEntityReadFilter(sql, params, filter);
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapRowToEntity(rs),
                params.toArray());
    }

    /** 变更历史：返回指定 name+type 的所有版本，按 version 升序。 */
    public List<TemporalEntity> getChangeHistory(String name, EntityType type) {
        return jdbcTemplate.query(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities WHERE name = ? AND type = ? ORDER BY version ASC",
                (rs, rowNum) -> mapRowToEntity(rs),
                name, type.name());
    }

    /** 图遍历：递归 CTE 沿当前有效关系边遍历最多 maxDepth 跳。 */
    public List<TemporalEntity> findRelated(String entityId, int maxDepth) {
        if (entityId == null || entityId.isBlank() || maxDepth <= 0) {
            return List.of();
        }
        String now = Instant.now().toString();
        return jdbcTemplate.query(
                """
                WITH RECURSIVE related(id, depth) AS (
                    SELECT tr.target_entity_id, 1
                    FROM temporal_relations tr
                    JOIN memory_relations mr ON mr.id = tr.id AND mr.status = 'ACTIVE'
                    WHERE tr.source_entity_id = ? AND tr.valid_to IS NULL
                    UNION
                    SELECT tr.source_entity_id, 1
                    FROM temporal_relations tr
                    JOIN memory_relations mr ON mr.id = tr.id AND mr.status = 'ACTIVE'
                    WHERE tr.target_entity_id = ? AND tr.valid_to IS NULL
                    UNION
                    SELECT CASE
                        WHEN tr.source_entity_id = r.id THEN tr.target_entity_id
                        ELSE tr.source_entity_id
                    END, r.depth + 1
                    FROM temporal_relations tr
                    JOIN memory_relations mr ON mr.id = tr.id AND mr.status = 'ACTIVE'
                    JOIN related r ON (tr.source_entity_id = r.id OR tr.target_entity_id = r.id)
                    WHERE tr.valid_to IS NULL AND r.depth < ?
                )
                SELECT DISTINCT te.*
                FROM temporal_entities te
                JOIN related r ON te.id = r.id
                WHERE te.is_current = 1 AND te.id != ?
                  AND te.lifecycle_state IN ('ACTIVE', 'COMPLETED', 'REGENERATION_NEEDED')
                  AND (te.valid_to IS NULL OR te.valid_to > ?)
                  AND (te.expires_at IS NULL OR te.expires_at > ?)
                """,
                (rs, rowNum) -> mapRowToEntity(rs),
                entityId, entityId, maxDepth, entityId, now, now);
    }

    /** 查找当前版本实体。 */
    public Optional<TemporalEntity> findCurrentByNameAndType(String name, EntityType type) {
        return findCurrentByNameAndType(name, type, null);
    }

    /**
     * 查找当前版本实体，并应用读取过滤。
     *
     * @param name   实体名称
     * @param type   实体类型
     * @param filter 读取过滤条件
     * @return 当前版本实体
     */
    public Optional<TemporalEntity> findCurrentByNameAndType(String name,
                                                             EntityType type,
                                                             @Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities WHERE name = ? AND type = ? AND is_current = 1");
        List<Object> params = new ArrayList<>();
        params.add(name);
        params.add(type.name());
        appendEntityReadFilter(sql, params, filter);
        var results = jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapRowToEntity(rs),
                params.toArray());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * 查询指定类型的所有当前有效实体。
     *
     * @param type 实体类型
     * @return 匹配的实体列表
     */
    public List<TemporalEntity> findCurrentByType(EntityType type) {
        return findCurrentByType(type, null);
    }

    /**
     * 查询指定类型的所有当前有效实体，并应用读取过滤。
     *
     * @param type   实体类型
     * @param filter 读取过滤条件
     * @return 匹配的实体列表
     */
    public List<TemporalEntity> findCurrentByType(EntityType type, @Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities WHERE type = ? AND is_current = 1");
        List<Object> params = new ArrayList<>();
        params.add(type.name());
        appendEntityReadFilter(sql, params, filter);
        sql.append(" ORDER BY importance_score DESC");
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapRowToEntity(rs),
                params.toArray());
    }

    /**
     * 查询符合过滤条件的所有当前实体 ID 集合 — 用于向量检索 pre-filter。
     *
     * @param filter 读取过滤条件
     * @return 符合条件的实体 ID 集合
     */
    public Set<String> findEligibleEntityIds(MemoryReadFilter filter) {
        var sql = new StringBuilder("SELECT id FROM memory_entities WHERE status = 'ACTIVE'");
        var params = new ArrayList<>();
        appendEntityReadFilter(sql, params, filter, "memory_entities");
        var ids = new LinkedHashSet<>(
                jdbcTemplate.queryForList(sql.toString(), String.class, params.toArray()));
        // 结果集过大时返回 null，由调用方回退为后过滤
        if (ids.size() > 1000) {
            log.debug("语义记忆: pre-filter 候选集过大({}), 回退为后过滤", ids.size());
            return null;
        }
        return ids;
    }

    /**
     * 归档（2-arg 兼容签名）— 等价 {@code archive(entity, ChangeSource.UI_EDIT)}。
     *
     * <p>保留给 UI/前端删除、Tool delete 等未显式指定来源的调用方：默认按"用户 UI 编辑"
     * 归因。定时清理 / 合并 / 冲突裁决等路径必须走 3-arg 重载以携带正确 {@link ChangeSource}，
     * 否则下游 DerivedEntityListener / L4SyncListener 无法区分 cron 与 UI 动作。</p>
     */
    public void archive(TemporalEntity entity) {
        archive(entity, ChangeSource.UI_EDIT);
    }

    /**
     * 归档：事务内设置 is_current=0, valid_to=now，同时归档所有当前有效关系；
     * 向量清理只登记 {@code memory_projection_outbox} DELETE 任务，避免事务回滚后留下
     * "向量已删、主库未改"的不一致。
     *
     * <p>B5 follow-up：增加 {@code source} 入参，替代原先写死的 {@link ChangeSource#UI_EDIT}。
     * 允许 {@code ForgettingEngine} / {@code ExperienceMerger} / {@code EntityDeduplicator}
     * 等调用方显式传 {@link ChangeSource#CRON_EXPIRE} 或 {@link ChangeSource#CONFLICT_RESOLVE}，
     * 保证事件 source 与实际触发原因对齐。</p>
     *
     * @param entity 待归档实体
     * @param source 归档来源 — 决定 LifecycleChanged 事件的 source 字段
     */
    @Transactional
    public void archive(TemporalEntity entity, ChangeSource source) {
        var now = Instant.now();
        closeCurrentEntityVersion(entity.id(), now);
        jdbcTemplate.update(
                """
                UPDATE memory_entities
                SET status = 'ARCHIVED',
                    lifecycle_state = 'ARCHIVED',
                    last_seen_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                now.toString(), now.toString(), entity.id());
        jdbcTemplate.update(
                """
                UPDATE memory_relation_versions
                SET is_current = 0,
                    valid_to = COALESCE(valid_to, ?),
                    updated_at = ?
                WHERE relation_id IN (
                    SELECT id FROM memory_relations
                    WHERE (source_entity_id = ? OR target_entity_id = ?)
                      AND status = 'ACTIVE'
                ) AND is_current = 1
                """,
                now.toString(), now.toString(), entity.id(), entity.id());
        jdbcTemplate.update(
                "UPDATE memory_relations SET status = 'ARCHIVED', updated_at = ? WHERE (source_entity_id = ? OR target_entity_id = ?) AND status = 'ACTIVE'",
                now.toString(), entity.id(), entity.id());

        enqueueVectorDeleteProjection(entity.id());

        // Task 12：归档发布 LifecycleChanged(旧态 → ARCHIVED)
        publishAfterCommit(new EntityLifecycleChanged(
                entity.id(),
                entity.type().name(),
                entity.lifecycleState(),
                LifecycleState.ARCHIVED,
                entity.lifecycleReason(),
                source));

        log.debug("语义记忆: 归档实体, id={}, name={}, source={}", entity.id(), entity.name(), source);
    }

    /**
     * 登记删除向量的投影任务。
     */
    private void enqueueVectorDeleteProjection(String entityId) {
        if (projectionService == null) {
            throw new IllegalStateException("MemoryProjectionService 未装配，禁止绕过 outbox 直写向量");
        }
        projectionService.enqueueVectorDeleteAfterCommit(entityId);
    }

    /**
     * Task 24：upsert 完成后触发异步 LLM 冲突裁决。
     *
     * <p>调用链：查 top-5 语义相似邻居 → 过滤掉自己 → 交 {@link ConflictResolutionService}
     * 异步裁决；裁决服务自身再做一次阈值过滤与 LLM 调用。触发流程与主事务解耦，任何
     * 异常都只记 warn 日志，不影响 upsert 返回。
     *
     * <p>若存在活跃事务，钩子注册到 {@code afterCommit}，确保新实体已对后续查询可见；
     * 否则（测试直接调 upsert）立即触发。
     *
     * @param savedEntity upsert 产出的新/合并实体
     */
    private void triggerConflictResolution(TemporalEntity savedEntity) {
        if (conflictResolutionService == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeTriggerConflictResolution(savedEntity);
                }
            });
        } else {
            safeTriggerConflictResolution(savedEntity);
        }
    }

    /**
     * memory-staleness spec：新实体落库后，异步识别冲突邻居并标记 STALE_CANDIDATE。
     * 使用 afterCommit 钩子确保新实体在邻居检测时对向量检索可见。
     */
    private void triggerStalenessProcessing(TemporalEntity savedEntity) {
        if (stalenessCoordinator == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        stalenessCoordinator.process(savedEntity);
                    } catch (RuntimeException e) {
                        log.warn("语义记忆: 触发 staleness 处理失败: entity={}, err={}",
                                savedEntity.id(), e.getMessage());
                    }
                }
            });
        } else {
            try {
                stalenessCoordinator.process(savedEntity);
            } catch (RuntimeException e) {
                log.warn("语义记忆: 触发 staleness 处理失败: entity={}, err={}",
                        savedEntity.id(), e.getMessage());
            }
        }
    }

    private void safeTriggerConflictResolution(TemporalEntity savedEntity) {
        try {
            var results = vectorSearcher.searchEntities(
                    savedEntity.textRepresentation(), 5, 0.0f);
            if (results == null || results.isEmpty()) {
                return;
            }
            // 查候选实体（排除自己 + 排除非 ACTIVE）
            var candidateIds = results.stream()
                    .map(r -> r.entityId())
                    .filter(id -> id != null && !id.equals(savedEntity.id()))
                    .toList();
            if (candidateIds.isEmpty()) {
                return;
            }
            var candidateMap = findByIds(candidateIds);
            var candidates = candidateMap.values().stream()
                    .filter(e -> e.lifecycleState() == LifecycleState.ACTIVE)
                    .toList();
            if (candidates.isEmpty()) {
                return;
            }
            conflictResolutionService.resolveAsync(savedEntity, candidates);
        } catch (Exception e) {
            log.warn("语义记忆: 冲突裁决触发失败, entityId={}, error={}",
                    savedEntity.id(), e.getMessage());
        }
    }

    /**
     * 基于 provenance 字符串推断 {@link ChangeSource} — 用于 upsert 新建分支的事件归因。
     *
     * <p>已知值：
     * <ul>
     *   <li>{@code user-edit-description} / UI 触发 → {@code UI_EDIT}</li>
     *   <li>{@code tool-cancel} / {@code tool-complete} / {@code tool-supersede} → {@code TOOL_EXPLICIT}</li>
     *   <li>其他（含 null / 未知） → {@code LLM_SEMANTIC}（默认语义识别入口）</li>
     * </ul></p>
     *
     * @param provenance 写入来源引用
     * @return 推断出的 ChangeSource
     */
    private static ChangeSource resolveChangeSource(@Nullable String provenance) {
        if (provenance == null) {
            return ChangeSource.LLM_SEMANTIC;
        }
        return switch (provenance) {
            case "user-edit-description" -> ChangeSource.UI_EDIT;
            case "tool-cancel", "tool-complete", "tool-supersede" -> ChangeSource.TOOL_EXPLICIT;
            default -> ChangeSource.LLM_SEMANTIC;
        };
    }

    /**
     * 事务提交后发布事件；无活跃事务时立即发布。
     *
     * <p>确保回滚路径下不产生幻觉事件（consumer 若基于事件更新派生存储，回滚后数据会漂移）。</p>
     *
     * @param event 任意 Spring ApplicationEvent（{@link EntityLifecycleChanged} / {@link EntityWeightChanged}）
     */
    private void publishAfterCommit(Object event) {
        if (eventPublisher == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        eventPublisher.publishEvent(event);
                    } catch (Exception e) {
                        log.warn("语义记忆: 事件发布失败, event={}, error={}", event, e.getMessage());
                    }
                }
            });
        } else {
            try {
                eventPublisher.publishEvent(event);
            } catch (Exception e) {
                log.warn("语义记忆: 事件发布失败, event={}, error={}", event, e.getMessage());
            }
        }
    }

    /** 增加访问计数。 */
    public void incrementAccessCount(String entityId) {
        var now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE memory_entities SET access_count = access_count + 1, last_accessed_at = ?, updated_at = ? WHERE id = ?",
                now, now, entityId);
    }

    /**
     * 直接更新实体的生命周期状态 + 变更原因 — 默认 source={@link ChangeSource#TOOL_EXPLICIT}。
     *
     * <p>调用方如有更精确来源（如负反馈触发），改走 {@link #updateLifecycleState(String, LifecycleState, String, ChangeSource)}。</p>
     *
     * @param entityId 实体 ID
     * @param newState 目标生命周期状态
     * @param reason   变更原因（可为 null）
     */
    public void updateLifecycleState(String entityId, LifecycleState newState, @Nullable String reason) {
        updateLifecycleState(entityId, newState, reason, ChangeSource.TOOL_EXPLICIT);
    }

    /**
     * 直接更新实体的生命周期状态 + 变更原因 — 只改 {@code memory_entities} 的 lifecycle_* 列，
     * 不 touch 版本与其他字段，完成后发布 {@link EntityLifecycleChanged}。
     *
     * <p>Task 12（修补 C）接入：UPDATE 影响行为 0 则静默跳过不发事件；有效更新时以
     * AFTER_COMMIT 发布事件，避免回滚时产生幻觉状态。</p>
     *
     * @param entityId 实体 ID
     * @param newState 目标生命周期状态
     * @param reason   变更原因（可为 null）
     * @param source   变更来源（NEGATIVE_FEEDBACK / TOOL_EXPLICIT / LLM_SEMANTIC 等）
     */
    @Transactional
    public void updateLifecycleState(String entityId,
                                     LifecycleState newState,
                                     @Nullable String reason,
                                     ChangeSource source) {
        // 先读旧状态：UPDATE 之后无法再得到 oldState，事件需携带
        LifecycleRootSnapshot snapshot = loadLifecycleRootSnapshot(entityId);
        if (snapshot == null) {
            log.warn("语义记忆: updateLifecycleState 未命中, entityId={}, newState={}", entityId, newState);
            return;
        }
        var now = Instant.now();
        int affected = jdbcTemplate.update(
                "UPDATE memory_entities SET lifecycle_state = ?, lifecycle_reason = ?, updated_at = ? WHERE id = ?",
                newState.name(), reason, now.toString(), entityId);
        if (affected == 0) {
            log.warn("语义记忆: updateLifecycleState 未命中, entityId={}, newState={}", entityId, newState);
            return;
        }
        publishAfterCommit(new EntityLifecycleChanged(
                entityId, snapshot.entityType(), snapshot.oldState(), newState, reason, source));
        log.debug("语义记忆: 更新生命周期状态, entityId={}, newState={}, reason={}, source={}",
                entityId, newState, reason, source);
    }

    /**
     * 更新 {@code succeeded_by} 外键 — 不触发版本化，也不发布事件。
     *
     * <p>供 {@code memory.supersede} 工具使用：先记录「被谁替代」关系，再由调用方
     * 通过 {@link #updateLifecycleState} 将实体转入 {@link LifecycleState#SUPERSEDED}
     * 并发布事件。二次写入放在同一事务（由 Spring 管理），保证原子性。</p>
     *
     * @param entityId     被替代实体 ID
     * @param newEntityId  新实体 ID（继承者）
     */
    @Transactional
    public void updateSucceededBy(String entityId, String newEntityId) {
        var now = Instant.now();
        int affected = jdbcTemplate.update(
                "UPDATE memory_entities SET succeeded_by = ?, updated_at = ? WHERE id = ?",
                newEntityId, now.toString(), entityId);
        if (affected == 0) {
            log.warn("语义记忆: updateSucceededBy 未命中, entityId={}, newEntityId={}",
                    entityId, newEntityId);
            return;
        }
        log.debug("语义记忆: 更新 succeeded_by, entityId={}, newEntityId={}", entityId, newEntityId);
    }

    /** 查询实体生命周期根字段快照（entity_type + 当前 lifecycle_state）用于事件。 */
    @Nullable
    private LifecycleRootSnapshot loadLifecycleRootSnapshot(String entityId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT entity_type, lifecycle_state FROM memory_entities WHERE id = ?",
                entityId);
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        String type = (String) row.get("entity_type");
        LifecycleState oldState = parseLifecycleState((String) row.get("lifecycle_state"));
        return new LifecycleRootSnapshot(type, oldState);
    }

    /** 生命周期根字段快照记录 — 仅用于 updateLifecycleState 事件发布。 */
    private record LifecycleRootSnapshot(String entityType, LifecycleState oldState) {}

    /**
     * 更新实体的 importanceScore — 兼容旧签名，默认来源 {@link WeightSource#USER_FEEDBACK}。
     *
     * <p>新代码建议显式指定 {@link WeightSource} 走 3-arg 重载。</p>
     *
     * @param entityId 实体 ID
     * @param newScore 新的 importanceScore（已裁剪到 [0.0, 1.0]）
     */
    public void updateImportanceScore(String entityId, float newScore) {
        updateImportanceScore(entityId, newScore, WeightSource.USER_FEEDBACK);
    }

    /**
     * 更新实体的 importanceScore — Task 11（修补 B）：带来源标识并发布
     * {@link EntityWeightChanged} 事件。
     *
     * <p>delta 在服务端计算：{@code newScore - oldScore}，由调用方只需提供目标分数
     * （已裁剪到 [0.0, 1.0]）。事件中的 {@code cumulativeScore} 字段承载新分数本身，
     * 供下游（如 NegativeFeedbackListener）直接比较阈值。</p>
     *
     * <p>事件在事务提交后（或无事务上下文时立刻）发布，确保回滚时不产生幻觉事件。</p>
     *
     * @param entityId 实体 ID
     * @param newScore 新的 importanceScore（已裁剪到 [0.0, 1.0]）
     * @param source   权重变化来源
     */
    @Transactional
    public void updateImportanceScore(String entityId, float newScore, WeightSource source) {
        Float oldScore = loadCurrentImportanceScore(entityId);
        var now = Instant.now().toString();
        int affected = jdbcTemplate.update(
                "UPDATE memory_entity_versions SET importance_score = ?, updated_at = ? WHERE entity_id = ? AND is_current = 1",
                newScore, now, entityId);
        if (affected == 0) {
            log.warn("语义记忆: updateImportanceScore 未命中当前版本, entityId={}, newScore={}",
                    entityId, newScore);
            return;
        }
        if (eventPublisher != null) {
            double delta = oldScore == null ? newScore : (double) newScore - (double) oldScore;
            publishAfterCommit(new EntityWeightChanged(entityId, delta, newScore, source));
        }
    }

    /** 读取当前版本的 importanceScore，不存在时返回 null（delta 计算回退为 newScore 本身）。 */
    @Nullable
    private Float loadCurrentImportanceScore(String entityId) {
        var list = jdbcTemplate.queryForList(
                "SELECT importance_score FROM memory_entity_versions WHERE entity_id = ? AND is_current = 1",
                Float.class, entityId);
        return list.isEmpty() ? null : list.getFirst();
    }

    /**
     * 手动编辑描述 — Task 10（修补 A）：不再直接 SQL 覆盖当前版本，改为产生新版本。
     *
     * <p>语义：关闭现行 {@code is_current=1} 的版本，插入 {@code version+1} 新版本，
     * 仅描述字段与 {@code withDescription} 构造的副本不同。走版本化是为了保留历史，
     * 避免 UI 编辑与 VersionMerger 的「取更长者」启发式冲突丢失短描述。</p>
     *
     * <p>采用专用版本化路径（而非 {@link #upsertWithConflictDetection}），因后者
     * 依赖 {@link VersionMerger#merge} 的「取更长者」合并规则，会在新描述更短或等长时
     * 被判为无变化 → 丢失用户编辑。这里直接复用 {@link #closeCurrentEntityVersion}
     * 与 {@link #insertEntityVersion} 完成 v→v+1 的版本化写入。</p>
     *
     * @param entityId       实体 ID
     * @param newDescription 新描述（可为 null）
     */
    @Transactional
    public void updateDescription(String entityId, @Nullable String newDescription) {
        var existing = findById(entityId)
                .orElseThrow(() -> new IllegalArgumentException("实体不存在: " + entityId));
        // 描述未变化时直接短路，不产生冗余版本
        if (java.util.Objects.equals(existing.description(), newDescription)) {
            log.debug("语义记忆: updateDescription 无变化, entityId={}", entityId);
            return;
        }

        var now = Instant.now();
        closeCurrentEntityVersion(entityId, now);

        var updated = new TemporalEntity(
                existing.id(), existing.type(), existing.name(), newDescription,
                existing.properties(), existing.version() + 1, true,
                now, null, existing.sourceConversationId(),
                existing.extractionConfidence(), existing.importanceScore(),
                existing.accessCount(), existing.lastAccessedAt(),
                existing.createdAt(), now,
                existing.lifecycleState(), existing.lifecycleReason(), existing.expiresAt(),
                existing.temporality(), existing.succeededBy(),
                existing.isDerived(), existing.derivationSources(),
                existing.evidenceKind(), existing.trustLevel(), existing.trustScore(),
                existing.evidenceCount(), existing.lastVerifiedAt());

        var writeContext = defaultWriteContext(updated, "user-edit-description");
        insertEntityVersion(updated, writeContext, now);
        // 同步根表的 updated_at（画像等场景需要感知时间刷新）
        jdbcTemplate.update(
                "UPDATE memory_entities SET updated_at = ?, last_seen_at = ? WHERE id = ?",
                now.toString(), now.toString(), entityId);
        enqueueVectorUpsertProjection(updated);
        notifyWriteCallback();
        log.debug("语义记忆: updateDescription 版本化, entityId={}, newVersion={}",
                entityId, updated.version());
    }

    /** 添加关系。 */
    public void addRelation(TemporalRelation relation) {
        addRelation(relation, null);
    }

    /** 添加关系并记录写入上下文。 */
    public void addRelation(TemporalRelation relation, @Nullable MemoryWriteContext writeContext) {
        var now = Instant.now();
        MemoryWriteContext resolvedContext = resolveRelationWriteContext(relation, writeContext);
        String spaceId = resolvedContext.spaceId() != null
                ? resolvedContext.spaceId()
                : resolveRelationSpaceId(relation.sourceEntityId(), relation.targetEntityId());
        jdbcTemplate.update(
                """
                INSERT INTO memory_relations(
                    id, space_id, source_entity_id, target_entity_id, relation_type,
                    reality_type, status, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?)
                """,
                relation.id(),
                spaceId,
                relation.sourceEntityId(),
                relation.targetEntityId(),
                relation.relationType(),
                resolvedContext.realityType().name(),
                relation.validTo() == null ? "ACTIVE" : "ARCHIVED",
                relation.createdAt().toString(),
                now.toString());
        jdbcTemplate.update(
                """
                INSERT INTO memory_relation_versions(
                    id, relation_id, version_no, strength, properties_json,
                    is_current, valid_from, valid_to, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                relation.id(),
                relation.id(),
                1,
                relation.strength(),
                relation.propertiesJson(),
                relation.validTo() == null ? 1 : 0,
                relation.validFrom().toString(),
                relation.validTo() != null ? relation.validTo().toString() : null,
                relation.createdAt().toString(),
                now.toString());
        insertRelationProvenance(relation, resolvedContext, now);
        log.debug("语义记忆: 添加关系, id={}, type={}", relation.id(), relation.relationType());
    }

    /**
     * 判断指定 (source, target, relationType) 的 ACTIVE 关系是否已存在 —— 用于关系写入幂等守卫。
     */
    public boolean relationExists(String sourceId, String targetId, String relationType) {
        if (sourceId == null || targetId == null || relationType == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM memory_relations
                WHERE source_entity_id = ? AND target_entity_id = ? AND relation_type = ? AND status = 'ACTIVE'
                """,
                Integer.class,
                sourceId, targetId, relationType);
        return count != null && count > 0;
    }

    /**
     * 判断指定实体是否为当前有效实体（is_current=1 且生命周期处于可用集）—— 用于关系端点存活校验。
     */
    public boolean existsCurrentById(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM temporal_entities
                WHERE id = ? AND is_current = 1
                  AND lifecycle_state IN ('ACTIVE', 'COMPLETED', 'REGENERATION_NEEDED')
                """,
                Integer.class,
                entityId);
        return count != null && count > 0;
    }

    /** 查找所有当前实体，按 importance_score ASC, access_count ASC。 */
    public List<TemporalEntity> findAllCurrent() {
        return findAllCurrent(null);
    }

    /**
     * 查找所有当前实体，并应用读取过滤。
     *
     * @param filter 读取过滤条件
     * @return 实体列表
     */
    public List<TemporalEntity> findAllCurrent(@Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities WHERE is_current = 1");
        List<Object> params = new ArrayList<>();
        appendEntityReadFilter(sql, params, filter);
        sql.append(" ORDER BY importance_score ASC, access_count ASC");
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapRowToEntity(rs),
                params.toArray());
    }

    /**
     * 按 ID 批量查找当前/历史实体。
     *
     * @param ids 实体 ID 集合
     * @return id → TemporalEntity 映射
     */
    public Map<String, TemporalEntity> findByIds(Collection<String> ids) {
        return findByIds(ids, null);
    }

    /**
     * 按 ID 批量查找当前/历史实体，并应用读取过滤。
     *
     * @param ids    实体 ID 集合
     * @param filter 读取过滤条件
     * @return id → TemporalEntity 映射
     */
    public Map<String, TemporalEntity> findByIds(Collection<String> ids, @Nullable MemoryReadFilter filter) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities WHERE is_current = 1");
        List<Object> params = new ArrayList<>();
        appendEntityReadFilter(sql, params, filter);
        sql.append(" AND id IN (")
                .append(buildPlaceholders(ids.size()))
                .append(")");
        params.addAll(ids);
        List<TemporalEntity> list = jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapRowToEntity(rs),
                params.toArray());
        return list.stream().collect(Collectors.toMap(TemporalEntity::id, e -> e));
    }

    /**
     * 按实体类型分组统计当前有效实体数量 — 轻量 SQL 聚合，避免全量加载实体对象。
     *
     * @param filter 读取过滤条件（可为 null）
     * @return 实体类型 → 数量映射
     */
    public Map<EntityType, Integer> countByEntityType(@Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT type, COUNT(*) AS cnt FROM temporal_entities WHERE is_current = 1");
        List<Object> params = new ArrayList<>();
        appendEntityReadFilter(sql, params, filter);
        sql.append(" GROUP BY type");
        var rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        Map<EntityType, Integer> result = new java.util.EnumMap<>(EntityType.class);
        for (var row : rows) {
            try {
                EntityType type = EntityType.valueOf((String) row.get("type"));
                int count = ((Number) row.get("cnt")).intValue();
                result.put(type, count);
            } catch (IllegalArgumentException e) {
                // 未知的实体类型，跳过
                log.debug("语义记忆: 忽略未知实体类型, type={}", row.get("type"));
            }
        }
        return result;
    }

    /**
     * 统计当前有效实体总数。
     *
     * @return 当前有效实体数量
     */
    public long countCurrent() {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM temporal_entities WHERE is_current = 1",
                Long.class);
        return count != null ? count : 0L;
    }

    /**
     * 统计当前有效关系总数。
     *
     * @return 当前有效关系数量
     */
    public long countCurrentRelations() {
        var count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM temporal_relations tr
                JOIN memory_relations mr ON mr.id = tr.id AND mr.status = 'ACTIVE'
                WHERE tr.valid_to IS NULL
                """,
                Long.class);
        return count != null ? count : 0L;
    }

    /** 按类型统计当前实体数量 — 轻量 SQL 聚合，返回 type name → 数量映射。 */
    public Map<String, Long> countCurrentByType() {
        return jdbcTemplate.query(
                "SELECT type, COUNT(*) AS cnt FROM temporal_entities WHERE is_current = 1 GROUP BY type",
                rs -> {
                    Map<String, Long> result = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("type"), rs.getLong("cnt"));
                    }
                    return result;
                });
    }

    /** 统计最近 N 天内被访问的当前实体数量。 */
    public long countRecentlyAccessed(int days) {
        String cutoff = Instant.now().minus(java.time.Duration.ofDays(days)).toString();
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM temporal_entities WHERE is_current = 1 AND last_accessed_at > ?",
                Long.class, cutoff);
        return count != null ? count : 0L;
    }

    /** 当前实体的平均重要度分数。 */
    public float averageImportanceScore() {
        var avg = jdbcTemplate.queryForObject(
                "SELECT AVG(importance_score) FROM temporal_entities WHERE is_current = 1",
                Double.class);
        return avg != null ? avg.floatValue() : 0f;
    }

    /** 可召回生命周期集合 —— 注意力信号只纳入这些状态的当前实体。 */
    private static final String RECALLABLE_LIFECYCLE = "('ACTIVE', 'COMPLETED', 'REGENERATION_NEEDED')";

    /**
     * 临近到期查询（memory-proactive-foundation）—— {@code expires_at} 落在 {@code (now, until]}
     * 的当前有效、可召回实体，按到期时间升序（最紧迫在前）。
     *
     * @param until  到期窗口上界（含）
     * @param types  限定的实体类型；为空表示不限类型
     * @param filter 读取过滤（空间/scope）
     */
    public List<TemporalEntity> findApproachingExpiry(Instant until,
                                                      Set<EntityType> types,
                                                      @Nullable MemoryReadFilter filter) {
        if (until == null) {
            return List.of();
        }
        String now = Instant.now().toString();
        StringBuilder sql = new StringBuilder(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities"
                        + " WHERE is_current = 1"
                        + " AND lifecycle_state IN " + RECALLABLE_LIFECYCLE
                        + " AND expires_at IS NOT NULL AND expires_at > ? AND expires_at <= ?");
        List<Object> params = new ArrayList<>();
        params.add(now);
        params.add(until.toString());
        appendTypeFilter(sql, params, types);
        appendEntityReadFilter(sql, params, filter);
        sql.append(" ORDER BY expires_at ASC");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRowToEntity(rs), params.toArray());
    }

    /**
     * 停滞高价值查询（memory-proactive-foundation）—— importance ≥ 阈值，且长期未被访问
     * （{@code last_accessed_at < idleBefore}，或从未访问且 {@code created_at < idleBefore}）的
     * 当前有效、可召回实体，按重要度降序。
     *
     * @param types         限定的实体类型；为空表示不限类型
     * @param idleBefore    停滞判定时间点（早于此视为停滞）
     * @param minImportance 最小重要度
     * @param filter        读取过滤（空间/scope）
     */
    public List<TemporalEntity> findNeglected(Set<EntityType> types,
                                              Instant idleBefore,
                                              float minImportance,
                                              @Nullable MemoryReadFilter filter) {
        if (idleBefore == null) {
            return List.of();
        }
        String cutoff = idleBefore.toString();
        StringBuilder sql = new StringBuilder(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities"
                        + " WHERE is_current = 1"
                        + " AND lifecycle_state IN " + RECALLABLE_LIFECYCLE
                        + " AND importance_score >= ?"
                        + " AND ((last_accessed_at IS NOT NULL AND last_accessed_at < ?)"
                        + "      OR (last_accessed_at IS NULL AND created_at < ?))");
        List<Object> params = new ArrayList<>();
        params.add(minImportance);
        params.add(cutoff);
        params.add(cutoff);
        appendTypeFilter(sql, params, types);
        appendEntityReadFilter(sql, params, filter);
        sql.append(" ORDER BY importance_score DESC");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRowToEntity(rs), params.toArray());
    }

    /** 拼接可选的实体类型 IN 过滤。 */
    private void appendTypeFilter(StringBuilder sql, List<Object> params, @Nullable Set<EntityType> types) {
        if (types == null || types.isEmpty()) {
            return;
        }
        sql.append(" AND type IN (").append(buildPlaceholders(types.size())).append(")");
        params.addAll(types.stream().map(Enum::name).toList());
    }

    /**
     * 截止日期查询（memory-deadline-awareness）—— 返回 {@code properties_json} 中含 {@code $.dueAt}
     * 的当前有效、可召回实体。dueAt 的窗口/逾期判定交由调用方解析（容忍纯日期/时间戳），
     * 避免日期字符串范围比较的脆弱性。
     *
     * @param types  限定的实体类型；为空表示不限类型
     * @param filter 读取过滤（空间/scope）
     */
    public List<TemporalEntity> findWithDueDate(Set<EntityType> types, @Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities"
                        + " WHERE is_current = 1"
                        + " AND lifecycle_state IN " + RECALLABLE_LIFECYCLE
                        + " AND json_extract(properties_json, '$.dueAt') IS NOT NULL");
        List<Object> params = new ArrayList<>();
        appendTypeFilter(sql, params, types);
        appendEntityReadFilter(sql, params, filter);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRowToEntity(rs), params.toArray());
    }

    /**
     * 查询所有当前有效关系。
     *
     * @return 当前有效关系列表
     */
    public List<TemporalRelation> findAllCurrentRelations() {
        return jdbcTemplate.query(
                """
                SELECT tr.id, tr.source_entity_id, tr.target_entity_id, tr.relation_type,
                       tr.strength, tr.properties_json, tr.valid_from, tr.valid_to,
                       tr.source_conversation_id, tr.created_at
                FROM temporal_relations tr
                JOIN memory_relations mr ON mr.id = tr.id AND mr.status = 'ACTIVE'
                WHERE tr.valid_to IS NULL
                ORDER BY tr.created_at DESC
                """,
                (rs, rowNum) -> mapRowToRelation(rs));
    }

    /**
     * 查询与指定实体相关的所有当前有效关系（作为 source 或 target）。
     *
     * @param entityId 实体 ID
     * @return 相关关系列表
     */
    public List<TemporalRelation> findRelationsByEntityId(String entityId) {
        return jdbcTemplate.query(
                """
                SELECT tr.id, tr.source_entity_id, tr.target_entity_id, tr.relation_type,
                       tr.strength, tr.properties_json, tr.valid_from, tr.valid_to,
                       tr.source_conversation_id, tr.created_at
                FROM temporal_relations tr
                JOIN memory_relations mr ON mr.id = tr.id AND mr.status = 'ACTIVE'
                WHERE (tr.source_entity_id = ? OR tr.target_entity_id = ?)
                  AND tr.valid_to IS NULL
                ORDER BY tr.created_at DESC
                """,
                (rs, rowNum) -> mapRowToRelation(rs),
                entityId, entityId);
    }

    /**
     * 按实体 ID 批量查询 provenance 中的 source_entry_id。
     *
     * <p>知识库图谱把 {@code source_entry_id} 用作 chunk id，图检索可据此从 L3 domain 实体
     * 回到具体文档分块；对话记忆等其他来源也可复用此方法做证据回溯。</p>
     *
     * @param entityIds 实体 ID 集合
     * @return entityId -> source_entry_id 列表
     */
    public Map<String, List<String>> findSourceEntryIdsByEntityIds(Collection<String> entityIds) {
        return findProvenanceValuesByEntityIds(entityIds, "source_entry_id", true);
    }

    /**
     * 按实体 ID 批量查询 provenance 中的 source_document_id。
     *
     * @param entityIds 实体 ID 集合
     * @param onlyValid 是否只返回仍有效的 provenance
     * @return entityId -> source_document_id 列表
     */
    public Map<String, List<String>> findSourceDocumentIdsByEntityIds(Collection<String> entityIds,
                                                                      boolean onlyValid) {
        return findProvenanceValuesByEntityIds(entityIds, "source_document_id", onlyValid);
    }

    private Map<String, List<String>> findProvenanceValuesByEntityIds(Collection<String> entityIds,
                                                                      String column,
                                                                      boolean onlyValid) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Map.of();
        }
        var ids = entityIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        if (!Set.of("source_entry_id", "source_document_id").contains(column)) {
            throw new IllegalArgumentException("不支持的 provenance 列: " + column);
        }
        var sql = "SELECT entity_id, " + column + " AS source_value FROM memory_entity_provenances "
                + "WHERE " + column + " IS NOT NULL AND " + column + " <> '' "
                + (onlyValid ? "AND status = 'VALID' " : "")
                + "AND entity_id IN (" + buildPlaceholders(ids.size()) + ") "
                + "ORDER BY created_at DESC";
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            grouped.computeIfAbsent(rs.getString("entity_id"), ignored -> new LinkedHashSet<>())
                    .add(rs.getString("source_value"));
        }, ids.toArray());
        return grouped.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    /**
     * 按 ID 查找单个实体。
     *
     * @param entityId 实体 ID
     * @return 实体（如存在）
     */
    public Optional<TemporalEntity> findById(String entityId) {
        var results = jdbcTemplate.query(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities WHERE id = ? ORDER BY is_current DESC, version DESC",
                (rs, rowNum) -> mapRowToEntity(rs),
                entityId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * 查询所有 TTL 已过期且仍 {@code ACTIVE} 的实体 —— 供 Task 26
     * {@code ExpirationScanner} 每小时扫描后转入 {@code EXPIRED}。
     *
     * <p>只关心当前版本行（{@code is_current = 1}）、有 {@code expires_at} 且在
     * {@code now} 之前。temporal_entities 视图由 V16 投影 memory_entities 的
     * {@code expires_at / lifecycle_state} 字段（PERSISTENT 时为 null 自动排除）。</p>
     *
     * @param now 当前时间
     * @return 待转 EXPIRED 的实体列表；无过期时返回空列表
     */
    public List<TemporalEntity> findExpiredActive(Instant now) {
        return jdbcTemplate.query(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities "
                        + "WHERE is_current = 1 "
                        + "  AND lifecycle_state = 'ACTIVE' "
                        + "  AND expires_at IS NOT NULL "
                        + "  AND expires_at < ?",
                (rs, rowNum) -> mapRowToEntity(rs),
                now.toString());
    }

    /**
     * 反查所有 {@code ACTIVE} 派生实体，其 {@code derivation_sources} JSON 数组
     * 引用了指定 {@code sourceEntityId} —— 供 {@code DerivedEntityListener} 在源失效时
     * 定位需要进入 {@code REGENERATION_NEEDED} 的目标派生实体。
     *
     * <p>SQLite 原生无 JSON 查询方便路径，此处借助 {@code LIKE '%"id"%'}：将
     * sourceEntityId 用双引号包裹匹配 JSON 数组里的字面元素，避免 id 是另一 id 子串
     * 的误匹配（UUID 形态下已足够安全）。</p>
     *
     * @param sourceEntityId 源实体 ID
     * @return 引用该源的所有 ACTIVE 派生实体
     */
    public List<TemporalEntity> findDerivedBySourceEntity(String sourceEntityId) {
        String jsonPattern = "%\"" + sourceEntityId + "\"%";
        return jdbcTemplate.query(
                "SELECT " + ENTITY_SELECT_COLUMNS + " FROM temporal_entities "
                        + "WHERE is_current = 1 "
                        + "  AND is_derived = 1 "
                        + "  AND lifecycle_state = 'ACTIVE' "
                        + "  AND derivation_sources LIKE ?",
                (rs, rowNum) -> mapRowToEntity(rs),
                jsonPattern);
    }

    // --- 内部方法 ---

    /** 通知检索引擎数据已变更。 */
    private void notifyWriteCallback() {
        if (writeCallback != null) {
            try {
                writeCallback.run();
            } catch (Exception e) {
                log.warn("语义记忆: writeCallback 执行失败, error={}", e.getMessage());
            }
        }
    }

    /** 插入或更新实体根记录 — 使用 upsert 语义防止 PK 冲突；同时写入 V15 生命周期字段。 */
    private void insertEntityRoot(TemporalEntity entity, MemoryWriteContext writeContext, Instant now) {
        String spaceId = writeContext.spaceId() != null
                ? writeContext.spaceId()
                : resolveDefaultSpaceId(entity.type());
        String memoryScope = (writeContext.memoryScope() != null
                ? writeContext.memoryScope()
                : resolveDefaultScope(entity.type())).name();
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count, last_accessed_at,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, lifecycle_reason, expires_at, temporality,
                    succeeded_by, is_derived, derivation_sources,
                    evidence_kind, trust_level, trust_score, evidence_count, last_verified_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    canonical_name = excluded.canonical_name,
                    normalized_name = excluded.normalized_name,
                    status = excluded.status,
                    access_count = excluded.access_count,
                    last_accessed_at = excluded.last_accessed_at,
                    last_seen_at = excluded.last_seen_at,
                    updated_at = excluded.updated_at,
                    lifecycle_state = excluded.lifecycle_state,
                    lifecycle_reason = excluded.lifecycle_reason,
                    expires_at = excluded.expires_at,
                    temporality = excluded.temporality,
                    succeeded_by = excluded.succeeded_by,
                    is_derived = excluded.is_derived,
                    derivation_sources = excluded.derivation_sources,
                    evidence_kind = excluded.evidence_kind,
                    trust_level = excluded.trust_level,
                    trust_score = excluded.trust_score,
                    evidence_count = excluded.evidence_count,
                    last_verified_at = excluded.last_verified_at
                """,
                entity.id(),
                spaceId,
                memoryScope,
                entity.type().name(),
                entity.name(),
                normalizeName(entity.name()),
                MemoryRealityType.UNKNOWN.name(),
                entity.validTo() == null ? "ACTIVE" : "ARCHIVED",
                entity.accessCount(),
                entity.lastAccessedAt() != null ? entity.lastAccessedAt().toString() : null,
                now.toString(),
                now.toString(),
                entity.createdAt().toString(),
                now.toString(),
                entity.lifecycleState().name(),
                entity.lifecycleReason(),
                entity.expiresAt() != null ? entity.expiresAt().toString() : null,
                entity.temporality().name(),
                entity.succeededBy(),
                entity.isDerived() ? 1 : 0,
                serializeDerivationSources(entity.derivationSources()),
                entity.evidenceKind().name(),
                entity.trustLevel().name(),
                entity.trustScore(),
                entity.evidenceCount(),
                entity.lastVerifiedAt() != null ? entity.lastVerifiedAt().toString() : null);
    }

    /** 插入实体版本与 provenance。 */
    private void insertEntityVersion(TemporalEntity entity, MemoryWriteContext writeContext, Instant now) {
        String propsJson = null;
        if (!entity.properties().isEmpty()) {
            try {
                propsJson = MAPPER.writeValueAsString(entity.properties());
            } catch (Exception e) {
                log.warn("语义记忆: properties 序列化失败, id={}", entity.id());
            }
        }
        String versionId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_versions(
                    id, entity_id, version_no, description, properties_json,
                    extraction_confidence, importance_score, is_current,
                    valid_from, valid_to, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                versionId, entity.id(), entity.version(), entity.description(),
                propsJson, entity.extractionConfidence(), entity.importanceScore(),
                entity.isCurrent() ? 1 : 0,
                entity.validFrom().toString(),
                entity.validTo() != null ? entity.validTo().toString() : null,
                now.toString(), now.toString());
        insertEntityProvenance(entity, versionId, writeContext, now);
    }

    private void updateEntityRoot(TemporalEntity entity, MemoryWriteContext writeContext, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE memory_entities
                SET memory_scope = ?,
                    entity_type = ?,
                    canonical_name = ?,
                    normalized_name = ?,
                    status = 'ACTIVE',
                    access_count = ?,
                    last_accessed_at = ?,
                    last_seen_at = ?,
                    updated_at = ?,
                    lifecycle_state = ?,
                    lifecycle_reason = ?,
                    expires_at = ?,
                    temporality = ?,
                    succeeded_by = ?,
                    is_derived = ?,
                    derivation_sources = ?,
                    evidence_kind = ?,
                    trust_level = ?,
                    trust_score = ?,
                    evidence_count = ?,
                    last_verified_at = ?
                WHERE id = ?
                """,
                (writeContext.memoryScope() != null ? writeContext.memoryScope() : resolveDefaultScope(entity.type())).name(),
                entity.type().name(),
                entity.name(),
                normalizeName(entity.name()),
                entity.accessCount(),
                entity.lastAccessedAt() != null ? entity.lastAccessedAt().toString() : null,
                now.toString(),
                now.toString(),
                entity.lifecycleState().name(),
                entity.lifecycleReason(),
                entity.expiresAt() != null ? entity.expiresAt().toString() : null,
                entity.temporality().name(),
                entity.succeededBy(),
                entity.isDerived() ? 1 : 0,
                serializeDerivationSources(entity.derivationSources()),
                entity.evidenceKind().name(),
                entity.trustLevel().name(),
                entity.trustScore(),
                entity.evidenceCount(),
                entity.lastVerifiedAt() != null ? entity.lastVerifiedAt().toString() : null,
                entity.id());
    }

    private void insertEntityProvenance(TemporalEntity entity,
                                        String versionId,
                                        MemoryWriteContext writeContext,
                                        Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_provenances(
                    id, entity_id, version_id, origin_type, source_reference, source_conversation_id,
                    source_session_id, source_turn_id, source_entry_id, source_document_id,
                    source_knowledge_base_id,
                    confidence, evidence_kind, trust_score, trust_level, created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                UUID.randomUUID().toString(),
                entity.id(),
                versionId,
                writeContext.originType().name(),
                writeContext.sourceReference(),
                writeContext.sourceConversationId(),
                writeContext.sourceSessionId(),
                writeContext.sourceTurnId(),
                writeContext.sourceEntryId(),
                writeContext.sourceDocumentId(),
                writeContext.sourceKnowledgeBaseId(),
                entity.extractionConfidence(),
                entity.evidenceKind().name(),
                entity.trustScore(),
                entity.trustLevel().name(),
                now.toString());
    }

    private void insertRelationProvenance(TemporalRelation relation,
                                          MemoryWriteContext writeContext,
                                          Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_relation_provenances(
                    id, relation_id, version_id, origin_type, source_reference, source_conversation_id,
                    source_session_id, source_turn_id, source_document_id,
                    source_knowledge_base_id,
                    confidence, created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                UUID.randomUUID().toString(),
                relation.id(),
                relation.id(),
                writeContext.originType().name(),
                writeContext.sourceReference(),
                writeContext.sourceConversationId(),
                writeContext.sourceSessionId(),
                writeContext.sourceTurnId(),
                writeContext.sourceDocumentId(),
                writeContext.sourceKnowledgeBaseId(),
                relation.strength(),
                now.toString());
    }

    private void closeCurrentEntityVersion(String entityId, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE memory_entity_versions
                SET is_current = 0,
                    valid_to = COALESCE(valid_to, ?),
                    updated_at = ?
                WHERE entity_id = ? AND is_current = 1
                """,
                now.toString(),
                now.toString(),
                entityId);
    }

    private void recordEntityOverlay(String overlayEntityId,
                                     String baseEntityId,
                                     String overlaySpaceId,
                                     @Nullable String originSpaceId,
                                     String overlayKind) {
        if (!isOverlayTableAvailable()) {
            log.debug("语义记忆: overlay 表不存在，跳过 lineage 记录, overlayEntityId={}", overlayEntityId);
            return;
        }
        var now = Instant.now().toString();
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_overlays(
                    overlay_entity_id, base_entity_id, overlay_space_id,
                    origin_space_id, overlay_kind, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(overlay_entity_id) DO UPDATE SET
                    base_entity_id = excluded.base_entity_id,
                    overlay_space_id = excluded.overlay_space_id,
                    origin_space_id = excluded.origin_space_id,
                    overlay_kind = excluded.overlay_kind,
                    updated_at = excluded.updated_at
                """,
                overlayEntityId,
                baseEntityId,
                overlaySpaceId,
                originSpaceId,
                overlayKind,
                now,
                now);
    }

    private Optional<String> findEntitySpaceId(String entityId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT space_id FROM memory_entities WHERE id = ?",
                String.class,
                entityId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst());
    }

    private String resolveRelationSpaceId(String sourceEntityId, String targetEntityId) {
        List<String> spaceIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT space_id FROM memory_entities WHERE id IN (?, ?)",
                String.class,
                sourceEntityId,
                targetEntityId
        );
        if (spaceIds.isEmpty()) {
            return DEFAULT_PERSONAL_SPACE_ID;
        }
        if (spaceIds.size() > 1) {
            throw new IllegalArgumentException("暂不支持跨记忆空间建立关系");
        }
        return spaceIds.getFirst();
    }

    private MemoryWriteContext resolveWriteContext(TemporalEntity entity,
                                                   @Nullable String sourceReference,
                                                   @Nullable MemoryWriteContext writeContext) {
        if (writeContext == null) {
            return defaultWriteContext(entity, sourceReference);
        }
        MemoryScope resolvedScope = writeContext.memoryScope() != null
                ? writeContext.memoryScope()
                : resolveDefaultScope(entity.type());
        String resolvedSpaceId = writeContext.spaceId() != null
                ? writeContext.spaceId()
                : resolveDefaultSpaceId(resolvedScope, entity.type());
        return new MemoryWriteContext(
                resolvedSpaceId,
                resolvedScope,
                writeContext.originType(),
                writeContext.realityType(),
                writeContext.sourceReference() != null ? writeContext.sourceReference() : sourceReference,
                writeContext.sourceConversationId() != null ? writeContext.sourceConversationId() : sourceReference,
                writeContext.sourceSessionId(),
                writeContext.sourceTurnId(),
                writeContext.sourceEntryId(),
                writeContext.sourceDocumentId(),
                writeContext.sourceKnowledgeBaseId()
        );
    }

    private MemoryWriteContext defaultWriteContext(TemporalEntity entity, @Nullable String sourceReference) {
        return new MemoryWriteContext(
                resolveDefaultSpaceId(entity.type()),
                resolveDefaultScope(entity.type()),
                detectOriginType(sourceReference),
                MemoryRealityType.UNKNOWN,
                sourceReference,
                sourceReference,
                null,
                null,
                null,
                null,
                null
        );
    }

    private MemoryWriteContext resolveRelationWriteContext(TemporalRelation relation,
                                                           @Nullable MemoryWriteContext writeContext) {
        if (writeContext != null) {
            return writeContext;
        }
        return new MemoryWriteContext(
                resolveRelationSpaceId(relation.sourceEntityId(), relation.targetEntityId()),
                null,
                detectOriginType(relation.sourceConversationId()),
                MemoryRealityType.UNKNOWN,
                relation.sourceConversationId(),
                relation.sourceConversationId(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private MemoryScope resolveDefaultScope(EntityType type) {
        return switch (type) {
            case EXPERIENCE -> MemoryScope.AGENT_EXPERIENCE;
            case PREFERENCE, HABIT, GOAL, SKILL -> MemoryScope.USER_PROFILE;
            default -> MemoryScope.USER_FACT;
        };
    }

    private String resolveDefaultSpaceId(MemoryScope scope, EntityType type) {
        if (scope == MemoryScope.AGENT_EXPERIENCE) {
            if (memorySpaceRepository != null) {
                return memorySpaceRepository.ensureDefaultExperienceSpace().id();
            }
            return DEFAULT_EXPERIENCE_SPACE_ID;
        }
        return resolveDefaultSpaceId(type);
    }

    private String resolveDefaultSpaceId(EntityType type) {
        if (type == EntityType.EXPERIENCE) {
            if (memorySpaceRepository != null) {
                return memorySpaceRepository.ensureDefaultExperienceSpace().id();
            }
            return DEFAULT_EXPERIENCE_SPACE_ID;
        }
        if (memorySpaceRepository != null) {
            return memorySpaceRepository.ensureDefaultPersonalSpace().id();
        }
        return DEFAULT_PERSONAL_SPACE_ID;
    }

    private MemoryOriginType detectOriginType(@Nullable String sourceReference) {
        if (sourceReference == null || sourceReference.isBlank()) {
            return MemoryOriginType.UNKNOWN;
        }
        return switch (sourceReference) {
            case "user-edit-description", "manual-edit" -> MemoryOriginType.MANUAL;
            case "subtask-reflection", "contrastive-learning", "experience-merge",
                    "user-profile-consolidation", "proactive-engine" -> MemoryOriginType.CONSOLIDATION;
            default -> MemoryOriginType.CHAT;
        };
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private void appendEntityReadFilter(StringBuilder sql,
                                        List<Object> params,
                                        @Nullable MemoryReadFilter filter) {
        appendEntityReadFilter(sql, params, filter, "temporal_entities");
    }

    private void appendEntityReadFilter(StringBuilder sql,
                                        List<Object> params,
                                        @Nullable MemoryReadFilter filter,
                                        String outerTableName) {
        if (filter == null || filter.isUnrestricted()) {
            return;
        }
        if (filter.restrictsSpaces()) {
            sql.append(" AND space_id IN (")
                    .append(buildPlaceholders(filter.spaceIds().size()))
                    .append(")");
            params.addAll(filter.spaceIds());
        }
        if (filter.restrictsScopes()) {
            sql.append(" AND memory_scope IN (")
                    .append(buildPlaceholders(filter.scopes().size()))
                    .append(")");
            params.addAll(filter.scopes().stream().map(Enum::name).toList());
        }
        appendOverlaySuppression(sql, params, filter, outerTableName);
    }

    private void appendOverlaySuppression(StringBuilder sql,
                                          List<Object> params,
                                          MemoryReadFilter filter,
                                          String outerTableName) {
        if (!filter.restrictsSpaces() || !isOverlayTableAvailable()) {
            return;
        }
        sql.append(" AND NOT EXISTS (")
                .append("SELECT 1 FROM memory_entity_overlays mo ")
                .append("JOIN memory_entities overlay_root ON overlay_root.id = mo.overlay_entity_id ")
                .append("WHERE mo.base_entity_id = ")
                .append(outerTableName)
                .append(".id ")
                .append("AND overlay_root.status = 'ACTIVE' ")
                .append("AND overlay_root.space_id IN (")
                .append(buildPlaceholders(filter.spaceIds().size()))
                .append("))");
        params.addAll(filter.spaceIds());
    }

    private boolean isOverlayTableAvailable() {
        if (overlayTableAvailable != null) {
            return overlayTableAvailable;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='memory_entity_overlays'",
                    Integer.class);
            overlayTableAvailable = count != null && count > 0;
        } catch (Exception e) {
            overlayTableAvailable = false;
        }
        return overlayTableAvailable;
    }

    private String buildPlaceholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    /** 登记向量 upsert 投影任务，避免事务回滚后留下孤儿 embedding。 */
    private void enqueueVectorUpsertProjection(TemporalEntity entity) {
        if (projectionService == null) {
            throw new IllegalStateException("MemoryProjectionService 未装配，禁止绕过 outbox 直写向量");
        }
        projectionService.enqueueVectorUpsertAfterCommit(entity);
    }

    /** ResultSet 行映射为 TemporalEntity — 含 V15 生命周期字段。 */
    @SuppressWarnings("unchecked")
    private TemporalEntity mapRowToEntity(java.sql.ResultSet rs) throws java.sql.SQLException {
        String propsJson = rs.getString("properties_json");
        Map<String, Object> properties = Map.of();
        if (propsJson != null && !propsJson.isBlank()) {
            try {
                properties = MAPPER.readValue(propsJson, Map.class);
            } catch (Exception e) {
                log.warn("语义记忆: properties_json 解析失败, id={}", rs.getString("id"));
            }
        }

        String validToStr = rs.getString("valid_to");
        String lastAccessedStr = rs.getString("last_accessed_at");
        String expiresStr = rs.getString("expires_at");
        String lifecycleStateStr = rs.getString("lifecycle_state");
        String temporalityStr = rs.getString("temporality");
        String lastVerifiedStr = rs.getString("last_verified_at");

        LifecycleState lifecycleState = parseLifecycleState(lifecycleStateStr);
        Temporality temporality = parseTemporality(temporalityStr);

        return new TemporalEntity(
                rs.getString("id"),
                EntityType.valueOf(rs.getString("type")),
                rs.getString("name"),
                rs.getString("description"),
                properties,
                rs.getInt("version"),
                rs.getInt("is_current") == 1,
                Instant.parse(rs.getString("valid_from")),
                validToStr != null ? Instant.parse(validToStr) : null,
                rs.getString("source_conversation_id"),
                rs.getFloat("extraction_confidence"),
                rs.getFloat("importance_score"),
                rs.getInt("access_count"),
                lastAccessedStr != null ? Instant.parse(lastAccessedStr) : null,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                lifecycleState,
                rs.getString("lifecycle_reason"),
                expiresStr != null ? Instant.parse(expiresStr) : null,
                temporality,
                rs.getString("succeeded_by"),
                rs.getInt("is_derived") == 1,
                deserializeDerivationSources(rs.getString("derivation_sources")),
                parseEvidenceKind(rs.getString("evidence_kind")),
                parseTrustLevel(rs.getString("trust_level")),
                rs.getFloat("trust_score"),
                rs.getInt("evidence_count"),
                lastVerifiedStr != null ? Instant.parse(lastVerifiedStr) : null
        );
    }

    /** 将 lifecycle_state 字符串解析为枚举，异常或空值回退 ACTIVE。 */
    private static LifecycleState parseLifecycleState(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return LifecycleState.ACTIVE;
        }
        try {
            return LifecycleState.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            log.warn("语义记忆: 未知 lifecycle_state={}，回退 ACTIVE", raw);
            return LifecycleState.ACTIVE;
        }
    }

    /** 将 temporality 字符串解析为枚举，异常或空值回退 PERSISTENT。 */
    private static Temporality parseTemporality(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Temporality.PERSISTENT;
        }
        try {
            return Temporality.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            log.warn("语义记忆: 未知 temporality={}，回退 PERSISTENT", raw);
            return Temporality.PERSISTENT;
        }
    }

    /** 将 evidence_kind 字符串解析为枚举，异常或空值回退 UNKNOWN。 */
    private static MemoryEvidenceKind parseEvidenceKind(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return MemoryEvidenceKind.UNKNOWN;
        }
        try {
            return MemoryEvidenceKind.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            log.warn("语义记忆: 未知 evidence_kind={}，回退 UNKNOWN", raw);
            return MemoryEvidenceKind.UNKNOWN;
        }
    }

    /** 将 trust_level 字符串解析为枚举，异常或空值回退 UNVERIFIED。 */
    private static MemoryTrustLevel parseTrustLevel(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return MemoryTrustLevel.UNVERIFIED;
        }
        try {
            return MemoryTrustLevel.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            log.warn("语义记忆: 未知 trust_level={}，回退 UNVERIFIED", raw);
            return MemoryTrustLevel.UNVERIFIED;
        }
    }

    /** 将派生来源列表序列化为 JSON 数组字符串，空集合返回 null 以保持列稀疏。 */
    @Nullable
    private static String serializeDerivationSources(@Nullable List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(sources);
        } catch (Exception e) {
            log.warn("语义记忆: derivation_sources 序列化失败, count={}", sources.size());
            return null;
        }
    }

    /** 反序列化 derivation_sources JSON 数组；解析失败回退空列表。 */
    private static List<String> deserializeDerivationSources(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, STRING_LIST_TYPE);
        } catch (Exception e) {
            log.warn("语义记忆: derivation_sources 反序列化失败, error={}", e.getMessage());
            return List.of();
        }
    }

    /** ResultSet 行映射为 TemporalRelation。 */
    private TemporalRelation mapRowToRelation(java.sql.ResultSet rs) throws java.sql.SQLException {
        String validToStr = rs.getString("valid_to");
        return new TemporalRelation(
                rs.getString("id"),
                rs.getString("source_entity_id"),
                rs.getString("target_entity_id"),
                rs.getString("relation_type"),
                rs.getFloat("strength"),
                rs.getString("properties_json"),
                Instant.parse(rs.getString("valid_from")),
                validToStr != null ? Instant.parse(validToStr) : null,
                rs.getString("source_conversation_id"),
                Instant.parse(rs.getString("created_at"))
        );
    }
}

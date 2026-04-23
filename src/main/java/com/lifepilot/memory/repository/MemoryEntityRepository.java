package com.lifepilot.memory.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.semantic.MemoryEntity;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link MemoryEntity} 数据访问层 — 直接读写基表 {@code memory_entities} 与 {@code memory_entity_versions}，
 * 承载 V15 生命周期闭环新增字段（lifecycle / temporality / 派生关系）。
 *
 * <p>与 {@link com.lifepilot.memory.semantic.SemanticMemory} 的版本化写入路径并存：
 * 本仓库用于 Listener / Scanner / 测试等需要按主键直接读写最新版本的场景，不做冲突检测与版本号递增。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Repository
public class MemoryEntityRepository {

    private static final Logger log = LoggerFactory.getLogger(MemoryEntityRepository.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    @Nullable
    private final MemorySpaceRepository memorySpaceRepository;

    public MemoryEntityRepository(JdbcTemplate jdbc,
                                  ObjectMapper objectMapper,
                                  @Nullable MemorySpaceRepository memorySpaceRepository) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.memorySpaceRepository = memorySpaceRepository;
    }

    // ========== 查询 ==========

    /** 按主键查找实体（连带当前版本的 description / importance_score）。 */
    public Optional<MemoryEntity> findById(String entityId) {
        List<MemoryEntity> rows = jdbc.query(selectSql() + " WHERE me.id = ?", ROW_MAPPER, entityId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** 按类型查找最新一条（按 {@code created_at} 降序，取第一条）。 */
    public Optional<MemoryEntity> findLatestByType(String type) {
        List<MemoryEntity> rows = jdbc.query(
                selectSql() + " WHERE me.entity_type = ? ORDER BY me.created_at DESC LIMIT 1",
                ROW_MAPPER, type);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** 按类型查找所有 ACTIVE 的实体（按重要度降序）。 */
    public List<MemoryEntity> findActiveByType(String type) {
        return jdbc.query(
                selectSql() + " WHERE me.entity_type = ? AND me.lifecycle_state = 'ACTIVE' "
                        + "ORDER BY mev.importance_score DESC",
                ROW_MAPPER, type);
    }

    // ========== 写入 ==========

    /**
     * 幂等 upsert —— 基表主字段、生命周期字段、当前版本描述与重要度一次写入。
     *
     * <p>新建时自动填充 space_id / memory_scope / first_seen_at 等基表必需列；
     * 已存在则更新 description / importance_score / lifecycle_* 字段，保持版本号不变。
     * 需要版本化历史（例如编辑 description 时生成新版本）请改走
     * {@link com.lifepilot.memory.semantic.SemanticMemory#upsertWithConflictDetection}。</p>
     */
    public void save(MemoryEntity e) {
        var now = Instant.now();
        String nowIso = now.toString();
        String spaceId = resolveDefaultSpaceId();
        String memoryScope = MemoryScope.USER_FACT.name();

        jdbc.update("""
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count, last_accessed_at,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, lifecycle_reason, expires_at, temporality,
                    succeeded_by, is_derived, derivation_sources
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    canonical_name = excluded.canonical_name,
                    normalized_name = excluded.normalized_name,
                    last_seen_at = excluded.last_seen_at,
                    updated_at = excluded.updated_at,
                    lifecycle_state = excluded.lifecycle_state,
                    lifecycle_reason = excluded.lifecycle_reason,
                    expires_at = excluded.expires_at,
                    temporality = excluded.temporality,
                    succeeded_by = excluded.succeeded_by,
                    is_derived = excluded.is_derived,
                    derivation_sources = excluded.derivation_sources
                """,
                e.id(), spaceId, memoryScope, e.type(),
                e.name(), normalizeName(e.name()),
                "UNKNOWN", "ACTIVE", 0, null,
                nowIso, nowIso, nowIso, nowIso,
                e.lifecycleState().name(), e.lifecycleReason(),
                e.expiresAt() != null ? e.expiresAt().toString() : null,
                e.temporality().name(), e.succeededBy(),
                e.isDerived() ? 1 : 0, serializeDerivationSources(e.derivationSources()));

        upsertCurrentVersion(e, now);
    }

    /**
     * 只更新生命周期状态 + 原因 —— 不 touch 版本与其他字段。
     * 由 Listener / Scanner 的状态转换路径调用。
     */
    public void updateLifecycleState(String entityId, LifecycleState newState, @Nullable String reason) {
        int affected = jdbc.update("""
                UPDATE memory_entities
                SET lifecycle_state = ?, lifecycle_reason = ?, updated_at = ?
                WHERE id = ?
                """, newState.name(), reason, Instant.now().toString(), entityId);
        if (affected == 0) {
            log.warn("记忆实体仓库: updateLifecycleState 未命中记录, id={}", entityId);
        }
    }

    /** 直接更新当前版本的重要度分数（用于反馈累计、有效性追踪等路径）。 */
    public void updateImportanceScore(String entityId, double newScore) {
        jdbc.update("""
                UPDATE memory_entity_versions
                SET importance_score = ?, updated_at = ?
                WHERE entity_id = ? AND is_current = 1
                """, newScore, Instant.now().toString(), entityId);
    }

    // ========== 内部辅助 ==========

    /** 基础 SELECT：JOIN 当前版本，得到 description / importance_score。 */
    private static String selectSql() {
        return """
                SELECT me.id AS id,
                       me.entity_type AS entity_type,
                       me.canonical_name AS canonical_name,
                       mev.description AS description,
                       mev.importance_score AS importance_score,
                       me.lifecycle_state AS lifecycle_state,
                       me.lifecycle_reason AS lifecycle_reason,
                       me.expires_at AS expires_at,
                       me.temporality AS temporality,
                       me.succeeded_by AS succeeded_by,
                       me.is_derived AS is_derived,
                       me.derivation_sources AS derivation_sources
                FROM memory_entities me
                LEFT JOIN memory_entity_versions mev
                       ON mev.entity_id = me.id AND mev.is_current = 1
                """;
    }

    private final RowMapper<MemoryEntity> ROW_MAPPER = (rs, n) -> {
        String expiresStr = rs.getString("expires_at");
        String temporalityStr = rs.getString("temporality");
        String lifecycleStr = rs.getString("lifecycle_state");
        double importance = rs.getObject("importance_score") == null ? 0.5d : rs.getDouble("importance_score");
        return new MemoryEntity(
                rs.getString("id"),
                rs.getString("entity_type"),
                rs.getString("canonical_name"),
                rs.getString("description"),
                importance,
                lifecycleStr != null ? LifecycleState.valueOf(lifecycleStr) : LifecycleState.ACTIVE,
                rs.getString("lifecycle_reason"),
                expiresStr != null ? Instant.parse(expiresStr) : null,
                temporalityStr != null ? Temporality.valueOf(temporalityStr) : Temporality.PERSISTENT,
                rs.getString("succeeded_by"),
                rs.getInt("is_derived") == 1,
                deserializeDerivationSources(rs.getString("derivation_sources"))
        );
    };

    /** 写入或更新当前版本（{@code is_current = 1}）。 */
    private void upsertCurrentVersion(MemoryEntity e, Instant now) {
        String nowIso = now.toString();
        List<String> existingVersionIds = jdbc.queryForList(
                "SELECT id FROM memory_entity_versions WHERE entity_id = ? AND is_current = 1",
                String.class, e.id());
        if (existingVersionIds.isEmpty()) {
            jdbc.update("""
                    INSERT INTO memory_entity_versions(
                        id, entity_id, version_no, description, properties_json,
                        extraction_confidence, importance_score, is_current,
                        valid_from, valid_to, created_at, updated_at
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    UUID.randomUUID().toString(), e.id(), 1, e.description(), null,
                    0.0d, e.importanceScore(), 1,
                    nowIso, null, nowIso, nowIso);
        } else {
            jdbc.update("""
                    UPDATE memory_entity_versions
                    SET description = ?, importance_score = ?, updated_at = ?
                    WHERE id = ?
                    """, e.description(), e.importanceScore(), nowIso, existingVersionIds.getFirst());
        }
    }

    private String resolveDefaultSpaceId() {
        if (memorySpaceRepository != null) {
            return memorySpaceRepository.ensureDefaultPersonalSpace().id();
        }
        return "memory-space-personal-default";
    }

    private String normalizeName(@Nullable String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private String serializeDerivationSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception ex) {
            log.warn("记忆实体仓库: derivation_sources 序列化失败, count={}, error={}",
                    sources.size(), ex.getMessage());
            return null;
        }
    }

    private List<String> deserializeDerivationSources(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            log.warn("记忆实体仓库: derivation_sources 反序列化失败, error={}", ex.getMessage());
            return List.of();
        }
    }
}

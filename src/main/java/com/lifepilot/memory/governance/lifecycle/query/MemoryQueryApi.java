package com.lifepilot.memory.governance.lifecycle.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.EntityProvenanceDto;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.SourceType;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.store.procedural.PreferenceRule;
import com.lifepilot.memory.store.procedural.ProcedureTemplate;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.procedural.TemplateStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆只读查询接口（测试专用）。
 *
 * <p>生命周期闭环场景测试的统一断言入口，封装 {@link SemanticMemory} 与
 * {@link MemoryProvenanceRepository}，只暴露 {@code findXxx} 只读方法；任何 mutator
 * 请走 {@link SemanticMemory} 本身或 {@link MemoryProvenanceRepository}。</p>
 *
 * <p>L4 相关查询（preference_rules / procedure_templates）当前仅占位：
 * 待 Task 15 {@code L4SyncListener} 引入 PreferenceRuleRepository / ProcedureTemplateRepository 后再接入。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Service
public class MemoryQueryApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PROPERTIES_TYPE = new TypeReference<>() {};

    private final SemanticMemory semanticMemory;
    private final MemoryProvenanceRepository provenanceRepository;
    private final JdbcTemplate jdbcTemplate;

    public MemoryQueryApi(SemanticMemory semanticMemory,
                          MemoryProvenanceRepository provenanceRepository,
                          JdbcTemplate jdbcTemplate) {
        this.semanticMemory = semanticMemory;
        this.provenanceRepository = provenanceRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ========== 实体查询 ==========

    /** 按 ID 查找实体。 */
    public Optional<TemporalEntity> findById(String id) {
        return semanticMemory.findById(id);
    }

    /**
     * 按类型查找最新一条当前实体（按 {@code updatedAt} 降序）。
     *
     * <p>仅在当前有效版本（{@code is_current = 1}）范围内筛选，排除已归档实体。</p>
     */
    public Optional<TemporalEntity> findLatestByType(String typeName) {
        EntityType type = parseEntityType(typeName);
        if (type == null) {
            return Optional.empty();
        }
        return semanticMemory.findCurrentByType(type).stream()
                .max((a, b) -> a.updatedAt().compareTo(b.updatedAt()));
    }

    /** 语法糖：{@code findLatestByType("GOAL")} 的常用别名。必须存在，否则抛 {@code NoSuchElementException}。 */
    public String findLatestGoalId() {
        return findLatestByType(EntityType.GOAL.name())
                .map(TemporalEntity::id)
                .orElseThrow();
    }

    /** 按类型找所有生命周期处于 {@code ACTIVE} 的当前实体（按重要度降序）。 */
    public List<TemporalEntity> findActiveByType(String typeName) {
        EntityType type = parseEntityType(typeName);
        if (type == null) {
            return List.of();
        }
        return semanticMemory.findCurrentByType(type).stream()
                .filter(e -> e.lifecycleState() == LifecycleState.ACTIVE)
                .toList();
    }

    /**
     * 查询指定实体的所有历史版本 — 按 {@code version_no} 升序返回。
     *
     * <p>扫描 {@code temporal_entities} 视图中同一 {@code id} 的全部行（含 is_current=0
     * 的历史版本），用于 Task 10（updateDescription 版本化）断言两次修改产生两条版本记录。</p>
     *
     * @param entityId 实体 ID
     * @return 按版本号升序的所有版本实体列表
     */
    public List<TemporalEntity> findAllVersions(String entityId) {
        return jdbcTemplate.query(
                """
                SELECT id, type, name, description, properties_json,
                       version, is_current, valid_from, valid_to, source_conversation_id,
                       extraction_confidence, importance_score, access_count, last_accessed_at,
                       created_at, updated_at,
                       lifecycle_state, lifecycle_reason, expires_at, temporality,
                       succeeded_by, is_derived, derivation_sources
                FROM temporal_entities
                WHERE id = ?
                ORDER BY version ASC
                """,
                (rs, rowNum) -> mapVersionRow(rs),
                entityId);
    }

    /** 视图行映射为 {@link TemporalEntity}（轻量版，字段对齐 SemanticMemory.mapRowToEntity）。 */
    @SuppressWarnings("unchecked")
    private static TemporalEntity mapVersionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String propsJson = rs.getString("properties_json");
        Map<String, Object> properties = Map.of();
        if (propsJson != null && !propsJson.isBlank()) {
            try {
                properties = MAPPER.readValue(propsJson, PROPERTIES_TYPE);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "MemoryQueryApi: properties_json 解析失败, id=" + rs.getString("id"), e);
            }
        }
        String validToStr = rs.getString("valid_to");
        String lastAccessedStr = rs.getString("last_accessed_at");
        String expiresStr = rs.getString("expires_at");
        String derivationSourcesJson = rs.getString("derivation_sources");
        List<String> derivationSources = List.of();
        if (derivationSourcesJson != null && !derivationSourcesJson.isBlank()) {
            try {
                derivationSources = MAPPER.readValue(
                        derivationSourcesJson, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                throw new IllegalStateException(
                        "MemoryQueryApi: derivation_sources 解析失败, id=" + rs.getString("id"), e);
            }
        }
        LifecycleState lifecycleState = parseLifecycleState(rs.getString("lifecycle_state"));
        Temporality temporality = parseTemporality(rs.getString("temporality"));
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
                derivationSources
        );
    }

    private static LifecycleState parseLifecycleState(String raw) {
        return parseRequiredEnum("lifecycle_state", raw, LifecycleState.class);
    }

    private static Temporality parseTemporality(String raw) {
        return parseRequiredEnum("temporality", raw, Temporality.class);
    }

    private static <E extends Enum<E>> E parseRequiredEnum(
            String columnName,
            String raw,
            Class<E> enumType) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("MemoryQueryApi: " + columnName + " 不能为空");
        }
        try {
            return Enum.valueOf(enumType, raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "MemoryQueryApi: " + columnName + " 包含未知值: " + raw, ex);
        }
    }

    // ========== Provenance 查询 ==========

    /** 查找指定来源对象关联的所有实体 ID（去重）。 */
    public List<String> findEntityIdsBySource(SourceType sourceType, String sourceId) {
        return provenanceRepository.findEntityIdsBySource(sourceType, sourceId);
    }

    /**
     * 查询指定实体的所有 provenance 明细（按创建时间降序）。
     *
     * @param entityId 实体 ID
     * @return provenance 明细列表
     */
    public List<EntityProvenanceDto> findProvenancesByEntityId(String entityId) {
        return provenanceRepository.findEntityProvenances(entityId, null, null, null);
    }

    // ========== L4 查询 ==========

    /**
     * 按来源实体 ID 查找 L4 偏好规则。
     *
     * @param sourceEntityId L3 源实体 ID
     * @return 匹配的偏好规则，未找到时返回 null
     */
    public PreferenceRule findRuleBySourceEntity(String sourceEntityId) {
        var results = jdbcTemplate.query(
                "SELECT rule_id, category, key, value, confidence, learned_from_json, observation_count, created_at, updated_at, source_entity_id, deactivated_reason FROM preference_rules WHERE source_entity_id = ?",
                (rs, rowNum) -> mapPreferenceRow(rs),
                sourceEntityId);
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * 按来源实体 ID 查找 L4 程序模板。
     *
     * @param sourceEntityId L3 源实体 ID
     * @return 匹配的程序模板，未找到时返回 null
     */
    public ProcedureTemplate findProcedureBySourceEntity(String sourceEntityId) {
        var results = jdbcTemplate.query(
                "SELECT template_id, name, description, trigger_intent, steps_json, variables_json, success_rate, use_count, last_used_at, source_trace_ids_json, created_at, updated_at, source_entity_id, deactivated_reason FROM procedure_templates WHERE source_entity_id = ?",
                (rs, rowNum) -> mapTemplateRow(rs),
                sourceEntityId);
        return results.isEmpty() ? null : results.getFirst();
    }

    @SuppressWarnings("unchecked")
    private PreferenceRule mapPreferenceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PreferenceRule(
                rs.getString("rule_id"), rs.getString("category"),
                rs.getString("key"), rs.getString("value"),
                rs.getFloat("confidence"), rs.getString("learned_from_json"),
                rs.getInt("observation_count"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getString("source_entity_id"),
                rs.getString("deactivated_reason"));
    }

    @SuppressWarnings("unchecked")
    private ProcedureTemplate mapTemplateRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String stepsJson = rs.getString("steps_json");
        List<TemplateStep> steps = List.of();
        if (stepsJson != null && !stepsJson.isBlank()) {
            try { steps = MAPPER.readValue(stepsJson, new TypeReference<>() {}); }
            catch (Exception ignored) {}
        }
        String varsJson = rs.getString("variables_json");
        Map<String, String> variables = Map.of();
        if (varsJson != null && !varsJson.isBlank()) {
            try { variables = MAPPER.readValue(varsJson, new TypeReference<Map<String, String>>() {}); }
            catch (Exception ignored) {}
        }
        String sourceTraceJson = rs.getString("source_trace_ids_json");
        List<String> sourceTraceIds = List.of();
        if (sourceTraceJson != null && !sourceTraceJson.isBlank()) {
            try { sourceTraceIds = MAPPER.readValue(sourceTraceJson, new TypeReference<List<String>>() {}); }
            catch (Exception ignored) {}
        }
        String lastUsedStr = rs.getString("last_used_at");
        return new ProcedureTemplate(
                rs.getString("template_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("trigger_intent"),
                steps, variables,
                rs.getFloat("success_rate"), rs.getInt("use_count"),
                lastUsedStr != null ? Instant.parse(lastUsedStr) : null,
                sourceTraceIds,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getString("source_entity_id"),
                rs.getString("deactivated_reason"));
    }

    // ========== 内部辅助 ==========

    /** 尝试解析实体类型字符串为枚举；非法值返回 null（调用方视作空结果）。 */
    private static EntityType parseEntityType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        try {
            return EntityType.valueOf(typeName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

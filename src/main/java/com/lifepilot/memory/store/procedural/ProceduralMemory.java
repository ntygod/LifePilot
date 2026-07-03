package com.lifepilot.memory.store.procedural;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * L4 程序记忆服务 — 管理操作模板和偏好规则的 CRUD 和检索。
 *
 * <p>操作模板的 triggerIntent 向量是 L4 派生投影，写入/删除必须登记
 * {@code memory_projection_outbox}，由投影 processor 幂等消费。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class ProceduralMemory {

    private static final Logger log = LoggerFactory.getLogger(ProceduralMemory.class);

    private final JdbcTemplate jdbcTemplate;
    private final MemoryProjectionService projectionService;
    private final ObjectMapper objectMapper;

    public ProceduralMemory(JdbcTemplate jdbcTemplate,
                            MemoryProjectionService projectionService,
                            ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate 不能为空");
        this.projectionService = Objects.requireNonNull(projectionService, "MemoryProjectionService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空");
    }

    // ========== 模板 CRUD ==========

    /**
     * 保存操作模板 — 插入数据库并创建 triggerIntent 向量索引。
     *
     * @param template 操作模板
     */
    public void save(ProcedureTemplate template) {
        try {
            String stepsJson = objectMapper.writeValueAsString(template.steps());
            String variablesJson = objectMapper.writeValueAsString(template.variables());
            String sourceTraceIdsJson = objectMapper.writeValueAsString(template.sourceTraceIds());

            // source_entity_id / deactivated_reason 供 L4SyncListener 反查并级联失活。
            jdbcTemplate.update(
                    """
                    INSERT INTO procedure_templates(
                        template_id, name, description, trigger_intent,
                        steps_json, variables_json, success_rate, use_count,
                        last_used_at, source_trace_ids_json, created_at, updated_at,
                        source_entity_id, deactivated_reason
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    template.templateId(), template.name(), template.description(),
                    template.triggerIntent(), stepsJson, variablesJson,
                    template.successRate(), template.useCount(),
                    template.lastUsedAt() != null ? template.lastUsedAt().toString() : null,
                    sourceTraceIdsJson,
                    template.createdAt().toString(), template.updatedAt().toString(),
                    template.sourceEntityId(), template.deactivatedReason());

            projectionService.enqueueProcedureTemplateVectorUpsertAfterCommit(
                    template.templateId(), template.triggerIntent());

            log.info("程序记忆: 保存模板, id={}, name={}", template.templateId(), template.name());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("程序记忆: JSON 序列化失败, templateId=" + template.templateId(), e);
        }
    }

    /**
     * 按 ID 查询操作模板。
     *
     * @param templateId 模板 ID
     * @return 模板（如存在）
     */
    public Optional<ProcedureTemplate> findById(String templateId) {
        var results = jdbcTemplate.query(
                "SELECT template_id, name, description, trigger_intent, steps_json, variables_json, success_rate, use_count, last_used_at, source_trace_ids_json, created_at, updated_at, source_entity_id, deactivated_reason FROM procedure_templates WHERE template_id = ? AND deactivated_reason IS NULL",
                (rs, rowNum) -> mapRowToTemplate(rs),
                templateId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * 按源实体 ID 查询仍生效（{@code deactivated_reason IS NULL}）的操作模板。
     *
     * <p>经验提升（{@code ExperiencePromoter}）按此判据去重：模板 templateId 为独立 UUID，
     * 与源 L3 EXPERIENCE 实体 id 解耦以避免向量索引串号，因此重复提升的判定改为
     * 按 {@code source_entity_id} 反查现存模板。</p>
     *
     * @param sourceEntityId 源 L3 实体 ID
     * @return 模板（如存在活跃模板）
     */
    public Optional<ProcedureTemplate> findBySourceEntityId(String sourceEntityId) {
        var results = jdbcTemplate.query(
                "SELECT template_id, name, description, trigger_intent, steps_json, variables_json, success_rate, use_count, last_used_at, source_trace_ids_json, created_at, updated_at, source_entity_id, deactivated_reason FROM procedure_templates WHERE source_entity_id = ? AND deactivated_reason IS NULL",
                (rs, rowNum) -> mapRowToTemplate(rs),
                sourceEntityId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * 更新操作模板 — 更新所有字段并刷新 triggerIntent 向量索引。
     *
     * @param template 更新后的操作模板
     */
    public void update(ProcedureTemplate template) {
        try {
            String stepsJson = objectMapper.writeValueAsString(template.steps());
            String variablesJson = objectMapper.writeValueAsString(template.variables());
            String sourceTraceIdsJson = objectMapper.writeValueAsString(template.sourceTraceIds());

            // 模板巩固去重会更新源 entity id，手工失活会写入 deactivated_reason。
            int updated = jdbcTemplate.update(
                    """
                    UPDATE procedure_templates SET
                        name = ?, description = ?, trigger_intent = ?,
                        steps_json = ?, variables_json = ?, success_rate = ?,
                        use_count = ?, last_used_at = ?, source_trace_ids_json = ?,
                        updated_at = ?, source_entity_id = ?, deactivated_reason = ?
                    WHERE template_id = ?
                    """,
                    template.name(), template.description(), template.triggerIntent(),
                    stepsJson, variablesJson, template.successRate(),
                    template.useCount(),
                    template.lastUsedAt() != null ? template.lastUsedAt().toString() : null,
                    sourceTraceIdsJson,
                    template.updatedAt().toString(),
                    template.sourceEntityId(), template.deactivatedReason(),
                    template.templateId());
            requireUpdated(updated, "程序记忆: 更新模板失败，模板不存在, templateId=" + template.templateId());

            projectionService.enqueueProcedureTemplateVectorUpsertAfterCommit(
                    template.templateId(), template.triggerIntent());

            log.info("程序记忆: 更新模板, id={}, name={}", template.templateId(), template.name());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("程序记忆: JSON 序列化失败, templateId=" + template.templateId(), e);
        }
    }

    /**
     * 删除操作模板 — 同时登记向量投影删除任务。
     *
     * @param templateId 模板 ID
     */
    public void delete(String templateId) {
        int deleted = jdbcTemplate.update("DELETE FROM procedure_templates WHERE template_id = ?", templateId);
        requireUpdated(deleted, "程序记忆: 删除模板失败，模板不存在, templateId=" + templateId);
        projectionService.enqueueProcedureTemplateVectorDeleteAfterCommit(templateId);
        log.info("程序记忆: 删除模板, id={}", templateId);
    }

    // ========== 成功率追踪 ==========

    /**
     * 记录模板执行结果 — 加权平均更新 successRate，递增 useCount，更新 lastUsedAt。
     *
     * <p>加权平均公式：{@code newRate = (oldRate × oldCount + (success ? 1.0 : 0.0)) / (oldCount + 1)}</p>
     *
     * @param templateId 模板 ID
     * @param success    本次执行是否成功
     */
    public void recordExecution(String templateId, boolean success) {
        var results = jdbcTemplate.query(
                "SELECT success_rate, use_count FROM procedure_templates WHERE template_id = ? AND deactivated_reason IS NULL",
                (rs, rowNum) -> new float[]{rs.getFloat("success_rate"), rs.getInt("use_count")},
                templateId);

        if (results.isEmpty()) {
            throw new IllegalStateException("程序记忆: 记录执行结果失败，模板不存在, templateId=" + templateId);
        }

        float oldRate = results.getFirst()[0];
        int oldCount = (int) results.getFirst()[1];
        float newRate = (oldRate * oldCount + (success ? 1.0f : 0.0f)) / (oldCount + 1);
        String now = Instant.now().toString();

        int updated = jdbcTemplate.update(
                """
                UPDATE procedure_templates
                SET success_rate = ?, use_count = ?, last_used_at = ?, updated_at = ?
                WHERE template_id = ?
                """,
                newRate, oldCount + 1, now, now, templateId);
        requireUpdated(updated, "程序记忆: 记录执行结果失败，模板不存在, templateId=" + templateId);

        log.debug("程序记忆: 记录执行结果, templateId={}, success={}, newRate={}, newCount={}",
                templateId, success, newRate, oldCount + 1);
    }

    // ========== 偏好规则 ==========

    /**
     * 保存偏好规则 — 使用 INSERT OR REPLACE 保证 (category, key) 唯一性。
     *
     * @param rule 偏好规则
     */
    public void savePreference(PreferenceRule rule) {
        // source_entity_id / deactivated_reason 供 L4SyncListener 级联失活偏好规则。
        jdbcTemplate.update(
                """
                INSERT OR REPLACE INTO preference_rules(
                    rule_id, category, key, value, confidence,
                    learned_from_json, observation_count, created_at, updated_at,
                    source_entity_id, deactivated_reason
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,
                rule.ruleId(), rule.category(), rule.key(), rule.value(),
                rule.confidence(), rule.learnedFrom(), rule.observationCount(),
                rule.createdAt().toString(), rule.updatedAt().toString(),
                rule.sourceEntityId(), rule.deactivatedReason());

        log.info("程序记忆: 保存偏好规则, ruleId={}, category={}, key={}, sourceEntityId={}",
                rule.ruleId(), rule.category(), rule.key(), rule.sourceEntityId());
    }

    /**
     * 按 category 和 key 查询偏好规则。
     *
     * @param category 类别
     * @param key      键
     * @return 偏好规则（如存在）
     */
    public Optional<PreferenceRule> findPreference(String category, String key) {
        var results = jdbcTemplate.query(
                "SELECT rule_id, category, key, value, confidence, learned_from_json, observation_count, created_at, updated_at, source_entity_id, deactivated_reason FROM preference_rules WHERE category = ? AND key = ? AND deactivated_reason IS NULL",
                (rs, rowNum) -> mapRowToPreference(rs),
                category, key);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * 获取指定类别下的所有偏好规则。
     *
     * @param category 类别
     * @return 偏好规则列表
     */
    public List<PreferenceRule> getPreferences(String category) {
        var results = jdbcTemplate.query(
                "SELECT rule_id, category, key, value, confidence, learned_from_json, observation_count, created_at, updated_at, source_entity_id, deactivated_reason FROM preference_rules WHERE category = ? AND deactivated_reason IS NULL",
                (rs, rowNum) -> mapRowToPreference(rs),
                category);
        return List.copyOf(results);
    }

    /**
     * 强化偏好规则 — 递增 observationCount 并提升 confidence。
     *
     * <p>confidence 提升步长为 0.05，上限为 1.0。若 ruleId 不存在，记录 WARN 日志。</p>
     *
     * @param ruleId 偏好规则 ID
     */
    public void reinforcePreference(String ruleId) {
        String now = Instant.now().toString();
        int updated = jdbcTemplate.update(
                """
                UPDATE preference_rules
                SET observation_count = observation_count + 1,
                    confidence = MIN(1.0, confidence + 0.05),
                    updated_at = ?
                WHERE rule_id = ? AND deactivated_reason IS NULL
                """,
                now, ruleId);

        if (updated == 0) {
            throw new IllegalStateException("程序记忆: 强化偏好规则失败，规则不存在, ruleId=" + ruleId);
        }
        log.debug("程序记忆: 强化偏好规则, ruleId={}", ruleId);
    }

    /**
     * 查询所有操作模板，按 created_at 降序。
     *
     * @return 操作模板列表
     */
    public List<ProcedureTemplate> listAllTemplates() {
        var results = jdbcTemplate.query(
                "SELECT template_id, name, description, trigger_intent, steps_json, variables_json, success_rate, use_count, last_used_at, source_trace_ids_json, created_at, updated_at, source_entity_id, deactivated_reason FROM procedure_templates ORDER BY created_at DESC",
                (rs, rowNum) -> mapRowToTemplate(rs));
        return List.copyOf(results);
    }

    /**
     * 查询所有偏好规则，按 category 和 key 排序。
     *
     * @return 偏好规则列表
     */
    public List<PreferenceRule> listAllPreferences() {
        var results = jdbcTemplate.query(
                "SELECT rule_id, category, key, value, confidence, learned_from_json, observation_count, created_at, updated_at, source_entity_id, deactivated_reason FROM preference_rules ORDER BY category, key",
                (rs, rowNum) -> mapRowToPreference(rs));
        return List.copyOf(results);
    }

    /**
     * 删除指定偏好规则。
     *
     * @param ruleId 偏好规则 ID
     * @return 是否删除成功
     */
    public boolean deletePreference(String ruleId) {
        int rows = jdbcTemplate.update("DELETE FROM preference_rules WHERE rule_id = ?", ruleId);
        if (rows > 0) {
            log.info("程序记忆: 删除偏好规则, ruleId={}", ruleId);
        }
        return rows > 0;
    }

    // ========== 内部方法 ==========

    /**
     * ResultSet 行映射为 PreferenceRule。
     */
    private PreferenceRule mapRowToPreference(ResultSet rs) throws SQLException {
        return new PreferenceRule(
                rs.getString("rule_id"),
                rs.getString("category"),
                rs.getString("key"),
                rs.getString("value"),
                rs.getFloat("confidence"),
                rs.getString("learned_from_json"),
                rs.getInt("observation_count"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getString("source_entity_id"),
                rs.getString("deactivated_reason")
        );
    }

    /**
     * ResultSet 行映射为 ProcedureTemplate。
     */
    private ProcedureTemplate mapRowToTemplate(ResultSet rs) throws SQLException {
        String templateId = rs.getString("template_id");
        String stepsJson = rs.getString("steps_json");
        String variablesJson = rs.getString("variables_json");
        String sourceTraceIdsJson = rs.getString("source_trace_ids_json");
        String lastUsedAtStr = rs.getString("last_used_at");

        List<TemplateStep> steps = deserializeSteps(templateId, stepsJson);
        Map<String, String> variables = deserializeVariables(templateId, variablesJson);
        List<String> sourceTraceIds = deserializeStringList(templateId, sourceTraceIdsJson);

        return new ProcedureTemplate(
                templateId,
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("trigger_intent"),
                steps,
                variables,
                rs.getFloat("success_rate"),
                rs.getInt("use_count"),
                lastUsedAtStr != null ? Instant.parse(lastUsedAtStr) : null,
                sourceTraceIds,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getString("source_entity_id"),
                rs.getString("deactivated_reason")
        );
    }

    /** 反序列化 steps_json 为 List<TemplateStep>。 */
    private List<TemplateStep> deserializeSteps(String templateId, String json) {
        requireJson("steps_json", templateId, json);
        try {
            return List.copyOf(objectMapper.readValue(json, new TypeReference<List<TemplateStep>>() {}));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "程序记忆: steps_json 反序列化失败, templateId=" + templateId, e);
        }
    }

    /** 反序列化 variables_json 为 Map<String, String>。 */
    private Map<String, String> deserializeVariables(String templateId, String json) {
        requireJson("variables_json", templateId, json);
        try {
            return Map.copyOf(objectMapper.readValue(json, new TypeReference<Map<String, String>>() {}));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "程序记忆: variables_json 反序列化失败, templateId=" + templateId, e);
        }
    }

    /** 反序列化 source_trace_ids_json 为 List<String>。 */
    private List<String> deserializeStringList(String templateId, String json) {
        requireJson("source_trace_ids_json", templateId, json);
        try {
            return List.copyOf(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "程序记忆: source_trace_ids_json 反序列化失败, templateId=" + templateId, e);
        }
    }

    private void requireJson(String columnName, String templateId, String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException(
                    "程序记忆: " + columnName + " 不能为空, templateId=" + templateId);
        }
    }

    private static void requireUpdated(int updated, String message) {
        if (updated != 1) {
            throw new IllegalStateException(message + ", updated=" + updated);
        }
    }
}

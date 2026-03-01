package com.lifepilot.memory.procedural;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L4 程序记忆服务 — 管理操作模板、偏好规则、策略模式的 CRUD 和检索。
 *
 * <p>初始化时程序化创建 {@code procedure_intent_vec} 和 {@code strategy_situation_vec}
 * 两个 sqlite-vec 向量索引虚拟表（与 {@code entity_embeddings} 保持一致的创建方式）。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class ProceduralMemory {

    private static final Logger log = LoggerFactory.getLogger(ProceduralMemory.class);

    private final JdbcTemplate jdbcTemplate;
    private final VectorSearcher vectorSearcher;
    private final MemoryProperties properties;
    private final ObjectMapper objectMapper;

    public ProceduralMemory(JdbcTemplate jdbcTemplate,
                            VectorSearcher vectorSearcher,
                            MemoryProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorSearcher = vectorSearcher;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();

        // 程序化创建 vec0 向量索引虚拟表
        initVec0Tables();
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

            jdbcTemplate.update(
                    """
                    INSERT INTO procedure_templates(
                        template_id, name, description, trigger_intent,
                        steps_json, variables_json, success_rate, use_count,
                        last_used_at, source_trace_ids_json, created_at, updated_at
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    template.templateId(), template.name(), template.description(),
                    template.triggerIntent(), stepsJson, variablesJson,
                    template.successRate(), template.useCount(),
                    template.lastUsedAt() != null ? template.lastUsedAt().toString() : null,
                    sourceTraceIdsJson,
                    template.createdAt().toString(), template.updatedAt().toString());

            // 创建 triggerIntent 向量索引
            upsertIntentVector(template.templateId(), template.triggerIntent());

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
                "SELECT * FROM procedure_templates WHERE template_id = ?",
                (rs, rowNum) -> mapRowToTemplate(rs),
                templateId);
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

            jdbcTemplate.update(
                    """
                    UPDATE procedure_templates SET
                        name = ?, description = ?, trigger_intent = ?,
                        steps_json = ?, variables_json = ?, success_rate = ?,
                        use_count = ?, last_used_at = ?, source_trace_ids_json = ?,
                        updated_at = ?
                    WHERE template_id = ?
                    """,
                    template.name(), template.description(), template.triggerIntent(),
                    stepsJson, variablesJson, template.successRate(),
                    template.useCount(),
                    template.lastUsedAt() != null ? template.lastUsedAt().toString() : null,
                    sourceTraceIdsJson,
                    template.updatedAt().toString(),
                    template.templateId());

            // 刷新 triggerIntent 向量索引
            upsertIntentVector(template.templateId(), template.triggerIntent());

            log.info("程序记忆: 更新模板, id={}, name={}", template.templateId(), template.name());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("程序记忆: JSON 序列化失败, templateId=" + template.templateId(), e);
        }
    }

    /**
     * 删除操作模板 — 同时删除向量索引。
     *
     * @param templateId 模板 ID
     */
    public void delete(String templateId) {
        jdbcTemplate.update("DELETE FROM procedure_templates WHERE template_id = ?", templateId);
        vectorSearcher.deleteEntityVector(templateId);
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
                "SELECT success_rate, use_count FROM procedure_templates WHERE template_id = ?",
                (rs, rowNum) -> new float[]{rs.getFloat("success_rate"), rs.getInt("use_count")},
                templateId);

        if (results.isEmpty()) {
            log.warn("程序记忆: 记录执行结果失败, 模板不存在, templateId={}", templateId);
            return;
        }

        float oldRate = results.getFirst()[0];
        int oldCount = (int) results.getFirst()[1];
        float newRate = (oldRate * oldCount + (success ? 1.0f : 0.0f)) / (oldCount + 1);
        String now = Instant.now().toString();

        jdbcTemplate.update(
                """
                UPDATE procedure_templates
                SET success_rate = ?, use_count = ?, last_used_at = ?, updated_at = ?
                WHERE template_id = ?
                """,
                newRate, oldCount + 1, now, now, templateId);

        log.debug("程序记忆: 记录执行结果, templateId={}, success={}, newRate={}, newCount={}",
                templateId, success, newRate, oldCount + 1);
    }

    // ========== 内部方法 ==========

    /**
     * 程序化创建 procedure_intent_vec 和 strategy_situation_vec 虚拟表。
     * 与 VectorSearcher 中 entity_embeddings 的创建方式保持一致。
     */
    private void initVec0Tables() {
        int dimensions = properties.getEmbeddingDimensions();
        try {
            jdbcTemplate.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS procedure_intent_vec USING vec0(" +
                    "entity_id TEXT PRIMARY KEY, " +
                    "embedding FLOAT[" + dimensions + "]" +
                    ")");
            jdbcTemplate.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS strategy_situation_vec USING vec0(" +
                    "entity_id TEXT PRIMARY KEY, " +
                    "embedding FLOAT[" + dimensions + "]" +
                    ")");
            log.info("程序记忆: vec0 向量索引表初始化完成, dimensions={}", dimensions);
        } catch (Exception e) {
            log.warn("程序记忆: vec0 表创建失败，向量检索功能将不可用", e);
        }
    }

    /**
     * 插入/更新 triggerIntent 向量索引。
     *
     * @param templateId    模板 ID
     * @param triggerIntent 触发意图文本
     */
    private void upsertIntentVector(String templateId, String triggerIntent) {
        try {
            vectorSearcher.upsertEntityVector(templateId, triggerIntent);
        } catch (Exception e) {
            log.warn("程序记忆: triggerIntent 向量索引更新失败, templateId={}, error={}",
                    templateId, e.getMessage());
        }
    }

    /**
     * ResultSet 行映射为 ProcedureTemplate。
     */
    private ProcedureTemplate mapRowToTemplate(ResultSet rs) throws SQLException {
        String stepsJson = rs.getString("steps_json");
        String variablesJson = rs.getString("variables_json");
        String sourceTraceIdsJson = rs.getString("source_trace_ids_json");
        String lastUsedAtStr = rs.getString("last_used_at");

        List<TemplateStep> steps = deserializeSteps(stepsJson);
        Map<String, String> variables = deserializeVariables(variablesJson);
        List<String> sourceTraceIds = deserializeStringList(sourceTraceIdsJson);

        return new ProcedureTemplate(
                rs.getString("template_id"),
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
                Instant.parse(rs.getString("updated_at"))
        );
    }

    /** 反序列化 steps_json 为 List<TemplateStep>。 */
    private List<TemplateStep> deserializeSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(json, new TypeReference<List<TemplateStep>>() {}));
        } catch (JsonProcessingException e) {
            log.warn("程序记忆: steps_json 反序列化失败, json={}", json);
            return List.of();
        }
    }

    /** 反序列化 variables_json 为 Map<String, String>。 */
    private Map<String, String> deserializeVariables(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(objectMapper.readValue(json, new TypeReference<Map<String, String>>() {}));
        } catch (JsonProcessingException e) {
            log.warn("程序记忆: variables_json 反序列化失败, json={}", json);
            return Map.of();
        }
    }

    /** 反序列化 source_trace_ids_json 为 List<String>。 */
    private List<String> deserializeStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (JsonProcessingException e) {
            log.warn("程序记忆: source_trace_ids_json 反序列化失败, json={}", json);
            return List.of();
        }
    }
}

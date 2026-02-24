package com.lifepilot.memory.semantic;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.retrieval.VectorSearcher;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 三级冲突检测器 — 精确匹配 → 语义匹配 → LLM 消歧义。
 *
 * <p>使用 JdbcTemplate 直接查询精确匹配，避免与 SemanticMemory 的循环依赖。
 * LLM 调用失败时降级为仅精确匹配。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ConflictDetector {

    private static final Logger log = LoggerFactory.getLogger(ConflictDetector.class);

    private final JdbcTemplate jdbcTemplate;
    private final VectorSearcher vectorSearcher;
    @Nullable
    private final LlmRouter llmRouter;
    private final float semanticMatchThreshold;

    /**
     * 构造 ConflictDetector。
     *
     * @param jdbcTemplate           主数据库 JdbcTemplate
     * @param vectorSearcher         向量检索器
     * @param llmRouter              LLM 路由器（可选，用于消歧义）
     * @param semanticMatchThreshold 语义匹配阈值
     */
    public ConflictDetector(JdbcTemplate jdbcTemplate,
                            VectorSearcher vectorSearcher,
                            @Nullable LlmRouter llmRouter,
                            float semanticMatchThreshold) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorSearcher = vectorSearcher;
        this.llmRouter = llmRouter;
        this.semanticMatchThreshold = semanticMatchThreshold;
    }

    /**
     * 三级冲突检测：精确匹配 → 语义匹配 → LLM 消歧义。
     *
     * @param newEntity 待检测的新实体
     * @return 冲突的已有实体，无冲突时返回 Optional.empty()
     */
    public Optional<TemporalEntity> detectConflict(TemporalEntity newEntity) {
        // 第一级：精确匹配（name + type）
        var exactMatch = findExactMatch(newEntity.name(), newEntity.type());
        if (exactMatch.isPresent()) {
            log.debug("冲突检测: 精确匹配命中, name={}, type={}", newEntity.name(), newEntity.type());
            return exactMatch;
        }

        // 第二级：语义匹配
        try {
            var vectorResults = vectorSearcher.searchEntities(
                    newEntity.textRepresentation(), 1, semanticMatchThreshold);
            if (!vectorResults.isEmpty()) {
                var topResult = vectorResults.getFirst();
                var candidate = findEntityById(topResult.entityId());
                if (candidate.isPresent()) {
                    // 第三级：LLM 消歧义
                    if (llmRouter != null) {
                        try {
                            boolean isSame = llmDisambiguate(newEntity, candidate.get());
                            if (isSame) {
                                log.debug("冲突检测: LLM 确认同一实体, name={}, candidateId={}",
                                        newEntity.name(), candidate.get().id());
                                return candidate;
                            }
                        } catch (Exception e) {
                            log.warn("冲突检测: LLM 消歧义失败，降级为仅精确匹配, error={}", e.getMessage());
                            return Optional.empty();
                        }
                    } else {
                        // 无 LLM 时，语义匹配超过阈值直接视为冲突
                        log.debug("冲突检测: 语义匹配命中（无 LLM 消歧义）, similarity={}",
                                topResult.similarity());
                        return candidate;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("冲突检测: 语义匹配失败，降级为仅精确匹配, error={}", e.getMessage());
        }

        return Optional.empty();
    }

    /** 精确匹配：name + type + is_current=1。 */
    private Optional<TemporalEntity> findExactMatch(String name, EntityType type) {
        var results = jdbcTemplate.query(
                "SELECT * FROM temporal_entities WHERE name = ? AND type = ? AND is_current = 1",
                (rs, rowNum) -> mapRowToEntity(rs),
                name, type.name());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /** 按 ID 查找当前版本实体。 */
    private Optional<TemporalEntity> findEntityById(String entityId) {
        var results = jdbcTemplate.query(
                "SELECT * FROM temporal_entities WHERE id = ? AND is_current = 1",
                (rs, rowNum) -> mapRowToEntity(rs),
                entityId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /** LLM 消歧义：判断两个实体是否为同一实体。 */
    private boolean llmDisambiguate(TemporalEntity newEntity, TemporalEntity candidate) {
        var prompt = "判断以下两个实体是否为同一实体，只回答 true 或 false：\n" +
                "实体A: " + newEntity.textRepresentation() + "\n" +
                "实体B: " + candidate.textRepresentation();
        var response = llmRouter.call("knowledge_extraction", prompt, null);
        return response.content().trim().toLowerCase().contains("true");
    }

    /** ResultSet 行映射为 TemporalEntity。 */
    @SuppressWarnings("unchecked")
    private TemporalEntity mapRowToEntity(java.sql.ResultSet rs) throws java.sql.SQLException {
        String propsJson = rs.getString("properties_json");
        Map<String, Object> properties = Map.of();
        if (propsJson != null && !propsJson.isBlank()) {
            try {
                properties = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(propsJson, Map.class);
            } catch (Exception e) {
                log.warn("冲突检测: properties_json 解析失败, id={}", rs.getString("id"));
            }
        }

        String validToStr = rs.getString("valid_to");
        String lastAccessedStr = rs.getString("last_accessed_at");

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
                Instant.parse(rs.getString("updated_at"))
        );
    }
}

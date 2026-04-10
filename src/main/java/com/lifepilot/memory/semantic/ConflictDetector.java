package com.lifepilot.memory.semantic;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
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
    private final GenerationRouter generationRouter;
    private final float semanticMatchThreshold;
    private final PromptRegistry promptRegistry;

    /**
     * 构造 ConflictDetector。
     *
     * @param jdbcTemplate           主数据库 JdbcTemplate
     * @param vectorSearcher         向量检索器
     * @param llmRouter              LLM 路由器（可选，用于消歧义）
     * @param semanticMatchThreshold 语义匹配阈值
     * @param promptRegistry         提示词注册中心
     */
    public ConflictDetector(JdbcTemplate jdbcTemplate,
                            VectorSearcher vectorSearcher,
                            @Nullable GenerationRouter generationRouter,
                            float semanticMatchThreshold,
                            PromptRegistry promptRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorSearcher = vectorSearcher;
        this.generationRouter = generationRouter;
        this.semanticMatchThreshold = semanticMatchThreshold;
        this.promptRegistry = promptRegistry;
    }

    /**
     * 三级冲突检测：精确匹配 → 语义匹配 → LLM 消歧义。
     *
     * @param newEntity 待检测的新实体
     * @return 冲突的已有实体，无冲突时返回 Optional.empty()
     */
    public Optional<TemporalEntity> detectConflict(TemporalEntity newEntity) {
        return detectConflict(newEntity, null);
    }

    public Optional<TemporalEntity> detectConflict(TemporalEntity newEntity, @Nullable String spaceId) {
        // 第一级：精确匹配（name + type）
        var exactMatch = findExactMatch(newEntity.name(), newEntity.type(), spaceId);
        if (exactMatch.isPresent()) {
            log.debug("冲突检测: 精确匹配命中, name={}, type={}", newEntity.name(), newEntity.type());
            return exactMatch;
        }

        // 第二级：语义匹配
        try {
            var vectorResults = vectorSearcher.searchEntities(
                    newEntity.textRepresentation(), 10, semanticMatchThreshold);
            for (var topResult : vectorResults) {
                var candidate = findEntityById(topResult.entityId(), spaceId);
                if (candidate.isPresent()) {
                    // 第三级：LLM 消歧义
                    if (generationRouter != null) {
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
    private Optional<TemporalEntity> findExactMatch(String name, EntityType type, @Nullable String spaceId) {
        var results = jdbcTemplate.query(
                """
                SELECT * FROM temporal_entities
                WHERE name = ? AND type = ? AND is_current = 1
                  AND (? IS NULL OR space_id = ?)
                """,
                (rs, rowNum) -> mapRowToEntity(rs),
                name, type.name(), spaceId, spaceId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /** 按 ID 查找当前版本实体。 */
    private Optional<TemporalEntity> findEntityById(String entityId, @Nullable String spaceId) {
        var results = jdbcTemplate.query(
                """
                SELECT * FROM temporal_entities
                WHERE id = ? AND is_current = 1
                  AND (? IS NULL OR space_id = ?)
                """,
                (rs, rowNum) -> mapRowToEntity(rs),
                entityId, spaceId, spaceId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /** LLM 消歧义：判断两个实体是否为同一实体，解析 JSON 响应 {"isSame": bool, "confidence": float}。 */
    private boolean llmDisambiguate(TemporalEntity newEntity, TemporalEntity candidate) {
        var prompt = promptRegistry.render("semantic/entity-disambiguation", Map.of(
                "entityA", newEntity.textRepresentation(),
                "entityB", candidate.textRepresentation()
        ));
        var response = generationRouter.call(
                LlmScene.KNOWLEDGE_EXTRACTION,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                null);
        var content = response.content().trim();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(content);
            boolean isSame = node.path("isSame").asBoolean(false);
            double confidence = node.path("confidence").asDouble(0.0);
            log.debug("冲突检测: LLM 消歧义结果, isSame={}, confidence={}", isSame, confidence);
            return isSame && confidence >= 0.6;
        } catch (Exception e) {
            // JSON 解析失败时降级为旧逻辑
            log.debug("冲突检测: LLM 消歧义 JSON 解析失败，降级为文本匹配, content={}", content);
            return content.toLowerCase().contains("true");
        }
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

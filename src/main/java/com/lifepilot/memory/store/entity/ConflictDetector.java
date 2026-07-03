package com.lifepilot.memory.store.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 三级冲突检测器 — 精确匹配 → 语义匹配 → LLM 消歧义。
 *
 * <p>使用 JdbcTemplate 直接查询精确匹配，避免与 SemanticMemory 的循环依赖。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ConflictDetector {

    private static final Logger log = LoggerFactory.getLogger(ConflictDetector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final String ENTITY_SELECT_COLUMNS = "id, type, name, description, properties_json, "
            + "version, is_current, valid_from, valid_to, source_conversation_id, "
            + "extraction_confidence, importance_score, access_count, last_accessed_at, created_at, updated_at, "
            + "lifecycle_state, lifecycle_reason, expires_at, temporality, succeeded_by, is_derived, derivation_sources, "
            + "evidence_kind, trust_level, trust_score, evidence_count, last_verified_at";

    private final JdbcTemplate jdbcTemplate;
    private final VectorSearcher vectorSearcher;
    private final GenerationRouter generationRouter;
    private final float semanticMatchThreshold;
    private final PromptRegistry promptRegistry;

    /**
     * 构造 ConflictDetector。
     *
     * @param jdbcTemplate           主数据库 JdbcTemplate
     * @param vectorSearcher         向量检索器
     * @param generationRouter       LLM 路由器，用于语义候选消歧义
     * @param semanticMatchThreshold 语义匹配阈值
     * @param promptRegistry         提示词注册中心
     */
    public ConflictDetector(JdbcTemplate jdbcTemplate,
                            VectorSearcher vectorSearcher,
                            GenerationRouter generationRouter,
                            float semanticMatchThreshold,
                            PromptRegistry promptRegistry) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为空");
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher, "vectorSearcher 不能为空");
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter 不能为空");
        this.semanticMatchThreshold = semanticMatchThreshold;
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry 不能为空");
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
        if (spaceId == null || spaceId.isBlank()) {
            log.debug("冲突检测: 缺少写入空间，跳过冲突检测以避免跨空间合并, name={}, type={}",
                    newEntity.name(), newEntity.type());
            return Optional.empty();
        }
        // 第一级：精确匹配（name + type）
        var exactMatch = findExactMatch(newEntity.name(), newEntity.type(), spaceId);
        if (exactMatch.isPresent()) {
            log.debug("冲突检测: 精确匹配命中, name={}, type={}", newEntity.name(), newEntity.type());
            return exactMatch;
        }

        var vectorResults = vectorSearcher.searchEntities(
                newEntity.textRepresentation(), 10, semanticMatchThreshold);
        for (var topResult : vectorResults) {
            var candidate = findEntityById(topResult.entityId(), spaceId);
            if (candidate.isPresent()) {
                // 第三级：LLM 消歧义
                boolean isSame = llmDisambiguate(newEntity, candidate.get());
                if (isSame) {
                    log.debug("冲突检测: LLM 确认同一实体, name={}, candidateId={}",
                            newEntity.name(), candidate.get().id());
                    return candidate;
                }
            }
        }

        return Optional.empty();
    }

    /** 精确匹配：name + type + is_current=1。 */
    private Optional<TemporalEntity> findExactMatch(String name, EntityType type, @Nullable String spaceId) {
        var results = jdbcTemplate.query(
                """
                SELECT %s FROM temporal_entities
                WHERE name = ? AND type = ? AND is_current = 1
                  AND space_id = ?
                """.formatted(ENTITY_SELECT_COLUMNS),
                (rs, rowNum) -> mapRowToEntity(rs),
                name, type.name(), spaceId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /** 按 ID 查找当前版本实体。 */
    private Optional<TemporalEntity> findEntityById(String entityId, @Nullable String spaceId) {
        var results = jdbcTemplate.query(
                """
                SELECT %s FROM temporal_entities
                WHERE id = ? AND is_current = 1
                  AND space_id = ?
                """.formatted(ENTITY_SELECT_COLUMNS),
                (rs, rowNum) -> mapRowToEntity(rs),
                entityId, spaceId);
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
        var content = Objects.requireNonNull(response, "冲突消歧响应不能为空").content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("冲突消歧响应内容不能为空");
        }
        try {
            var node = MAPPER.readTree(content);
            if (!node.isObject()) {
                throw new IllegalStateException("冲突消歧响应顶层必须是 JSON 对象");
            }
            var isSameNode = node.get("isSame");
            if (isSameNode == null || !isSameNode.isBoolean()) {
                throw new IllegalStateException("冲突消歧响应缺少布尔字段 isSame");
            }
            var confidenceNode = node.get("confidence");
            if (confidenceNode == null || !confidenceNode.isNumber()) {
                throw new IllegalStateException("冲突消歧响应缺少数值字段 confidence");
            }
            boolean isSame = isSameNode.booleanValue();
            double confidence = confidenceNode.doubleValue();
            if (!(confidence >= 0.0 && confidence <= 1.0)) {
                throw new IllegalStateException("冲突消歧 confidence 必须在 [0,1] 范围内: " + confidence);
            }
            log.debug("冲突检测: LLM 消歧义结果, isSame={}, confidence={}", isSame, confidence);
            return isSame && confidence >= 0.6;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("冲突消歧响应不是合法 JSON", e);
        }
    }

    /** ResultSet 行映射为 TemporalEntity。 */
    @SuppressWarnings("unchecked")
    private TemporalEntity mapRowToEntity(java.sql.ResultSet rs) throws java.sql.SQLException {
        String propsJson = rs.getString("properties_json");
        Map<String, Object> properties = Map.of();
        if (propsJson != null && !propsJson.isBlank()) {
            try {
                properties = MAPPER.readValue(propsJson, Map.class);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "冲突检测: properties_json 解析失败, id=" + rs.getString("id"), e);
            }
        }

        String validToStr = rs.getString("valid_to");
        String lastAccessedStr = rs.getString("last_accessed_at");
        String expiresStr = rs.getString("expires_at");
        String lastVerifiedStr = rs.getString("last_verified_at");

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
                parseRequiredEnum("lifecycle_state", rs.getString("lifecycle_state"), LifecycleState.class),
                rs.getString("lifecycle_reason"),
                expiresStr != null ? Instant.parse(expiresStr) : null,
                parseRequiredEnum("temporality", rs.getString("temporality"), Temporality.class),
                rs.getString("succeeded_by"),
                rs.getInt("is_derived") == 1,
                deserializeDerivationSources(rs.getString("derivation_sources"), rs.getString("id")),
                parseRequiredEnum("evidence_kind", rs.getString("evidence_kind"), MemoryEvidenceKind.class),
                parseRequiredEnum("trust_level", rs.getString("trust_level"), MemoryTrustLevel.class),
                rs.getFloat("trust_score"),
                rs.getInt("evidence_count"),
                lastVerifiedStr != null ? Instant.parse(lastVerifiedStr) : null
        );
    }

    private static List<String> deserializeDerivationSources(@Nullable String raw, String entityId) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(raw, STRING_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("冲突检测: derivation_sources 解析失败, id=" + entityId, e);
        }
    }

    private static <E extends Enum<E>> E parseRequiredEnum(String column, @Nullable String raw, Class<E> enumType) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("冲突检测: " + column + " 不能为空");
        }
        try {
            return Enum.valueOf(enumType, raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("冲突检测: " + column + " 非法: " + raw, e);
        }
    }
}

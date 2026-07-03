package com.lifepilot.agent.learning.consolidation.association;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REM 式联想候选生成器。
 *
 * <p>流程：
 * <ol>
 *   <li>从 L3 语义记忆选 top-K 高 importance seed 实体（类型白名单可配）</li>
 *   <li>对每个 seed 用 {@link HybridRetriever} 取 top-N 相邻实体</li>
 *   <li>组装 prompt 让 LLM 输出 JSON 数组 {sourceId, targetId, relationType, confidence, evidence}</li>
 *   <li>解析 JSON → {@link AssociationCandidate} 列表</li>
 * </ol>
 * </p>
 *
 * <p>无匹配邻居返回空列表；检索、LLM 调用或响应契约失败直接抛出，由巩固管线做阶段隔离。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class AssociationCandidateGenerator {

    private static final Logger log = LoggerFactory.getLogger(AssociationCandidateGenerator.class);

    /** Prompt 模板 key（不依赖 PromptRegistry 资源；本地 hardcoded 更稳定）。 */
    private static final String SCENE = "memory_rem_association";

    private final SemanticMemory semanticMemory;
    private final HybridRetriever hybridRetriever;
    private final GenerationRouter generationRouter;
    private final AgentLearningProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public AssociationCandidateGenerator(SemanticMemory semanticMemory,
                                          HybridRetriever hybridRetriever,
                                          GenerationRouter generationRouter,
                                          AgentLearningProperties properties) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
        this.hybridRetriever = Objects.requireNonNull(hybridRetriever, "hybridRetriever 不能为空");
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    /** 主入口：生成本轮所有候选。 */
    public List<AssociationCandidate> generate() {
        if (!properties.getRem().isEnabled()) {
            return List.of();
        }

        List<TemporalEntity> seeds = selectSeeds();
        if (seeds.isEmpty()) return List.of();

        Instant now = Instant.now();
        List<AssociationCandidate> result = new ArrayList<>();
        for (var seed : seeds) {
            List<RetrievalResult> neighbors = fetchNeighbors(seed);
            if (neighbors.isEmpty()) continue;
            List<AssociationCandidate> parsed = callLlmForAssociations(seed, neighbors, now);
            result.addAll(parsed);
        }
        log.info("REM 联想: 生成 seeds={}, candidates={}", seeds.size(), result.size());
        return result;
    }

    /** 选择 seed 实体：按类型白名单拉全量 → importance 降序 → 取 top-K。 */
    List<TemporalEntity> selectSeeds() {
        int limit = positive(properties.getRem().getSeedLimit(), "REM seed 数量上限");
        var types = properties.getRem().getSeedTypes();
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("REM seedTypes 不能为空");
        }
        List<TemporalEntity> all = new ArrayList<>();
        for (String typeName : types) {
            EntityType type = parseSeedType(typeName);
            all.addAll(requireEntities(
                    semanticMemory.findCurrentByType(type),
                    "REM seed 查询: " + type.name()));
        }
        return all.stream()
                .filter(e -> e.description() != null && !e.description().isBlank())
                .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore).reversed())
                .limit(limit)
                .toList();
    }

    /** 用 HybridRetriever 取 seed 的相邻实体。 */
    List<RetrievalResult> fetchNeighbors(TemporalEntity seed) {
        requireSeed(seed);
        int neighborLimit = positive(properties.getRem().getNeighborLimit(), "REM 邻居数量上限");
        String query = seed.name() + " " + seed.description();
        List<RetrievalResult> raw = requireRetrievalResults(
                hybridRetriever.retrieve(query, neighborLimit + 1, RetrievalWeights.DEFAULT));
        // 排除 seed 自己
        return raw.stream()
                .filter(r -> !Objects.equals(r.entityId(), seed.id()))
                .limit(neighborLimit)
                .toList();
    }

    /** 调 LLM 并解析响应为候选列表。 */
    List<AssociationCandidate> callLlmForAssociations(TemporalEntity seed,
                                                       List<RetrievalResult> neighbors,
                                                       Instant now) {
        requireSeed(seed);
        Set<String> neighborIds = requireNeighborIds(neighbors);
        String prompt = buildPrompt(seed, neighbors);
        Duration timeout = Duration.ofSeconds(positive(properties.getRem().getLlmTimeoutSeconds(), "REM LLM 超时秒数"));
        LlmResponse response = generationRouter.call(SCENE, prompt, null, null, null,
                GenerationCapability.CHAT, timeout, true);
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new AssociationResponseContractException("REM 联想响应不能为空");
        }
        List<AssociationCandidate> candidates = parseResponse(response.content(), seed.id(), now);
        validateCandidateTopology(candidates, seed.id(), neighborIds);
        return candidates;
    }

    /** 解析 LLM 输出为 AssociationCandidate 列表。 */
    List<AssociationCandidate> parseResponse(String content, String seedId, Instant now) {
        if (content == null || content.isBlank()) {
            throw new AssociationResponseContractException("REM 联想响应不能为空");
        }
        requireCanonicalText(seedId, "REM seedId");
        Objects.requireNonNull(now, "REM generatedAt 不能为空");
        try {
            List<Map<String, Object>> raw = mapper.readValue(content, new TypeReference<>() {});
            List<AssociationCandidate> list = new ArrayList<>();
            for (var entry : raw) {
                if (entry == null) {
                    throw new AssociationResponseContractException("REM 联想候选不能为 null");
                }
                String sourceId = requiredText(entry, "sourceId");
                String targetId = requiredText(entry, "targetId");
                String typeStr = requiredText(entry, "relationType");
                AssociationType type = parseAssociationType(typeStr);
                float confidence = requiredConfidence(entry.get("confidence"));
                String evidence = optionalText(entry, "evidence");
                list.add(new AssociationCandidate(
                        sourceId, targetId, type, confidence, evidence, seedId, now));
            }
            return list;
        } catch (JsonProcessingException e) {
            throw new AssociationResponseContractException("REM 联想数组解析失败: " + e.getOriginalMessage(), e);
        }
    }

    private static final class AssociationResponseContractException extends IllegalStateException {
        private AssociationResponseContractException(String message) {
            super(message);
        }

        private AssociationResponseContractException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private String buildPrompt(TemporalEntity seed, List<RetrievalResult> neighbors) {
        requireSeed(seed);
        requireRetrievalResults(neighbors);
        StringBuilder sb = new StringBuilder(2048);
        sb.append("你正在对用户的语义记忆做睡眠阶段联想整合（REM-style consolidation）。\n");
        sb.append("给定以下 seed 实体及其相邻实体，请识别它们之间未被显式记录但确实存在的语义关系。\n\n");
        sb.append("seed:\n");
        sb.append("- id=").append(seed.id())
          .append(", type=").append(seed.type().name())
          .append(", name=").append(seed.name())
          .append(", description=").append(seed.description() != null ? seed.description() : "").append('\n');
        sb.append("\nneighbors:\n");
        for (var n : neighbors) {
            sb.append("- id=").append(n.entityId())
              .append(", type=").append(n.entityType())
              .append(", name=").append(n.name())
              .append(", description=").append(n.description() != null ? n.description() : "").append('\n');
        }
        sb.append("\n请严格输出 JSON 数组（允许空数组 []），每条包含：\n");
        sb.append("{\n");
        sb.append("  \"sourceId\": \"<neighbor id>\",\n");
        sb.append("  \"targetId\": \"<seed id>\",\n");
        sb.append("  \"relationType\": \"RELATED_TO|CAUSES|SIMILAR_TO|SUPPORTS|CONTRADICTS\",\n");
        sb.append("  \"confidence\": 0.0-1.0,\n");
        sb.append("  \"evidence\": \"简要推理依据\"\n");
        sb.append("}\n\n");
        sb.append("只输出置信度 >= 0.6 的关系，不确定时宁缺毋滥。不要输出其他内容。");
        return sb.toString();
    }

    private static List<TemporalEntity> requireEntities(List<TemporalEntity> entities, String label) {
        if (entities == null) {
            throw new IllegalStateException(label + "返回 null");
        }
        for (TemporalEntity entity : entities) {
            Objects.requireNonNull(entity, label + "返回 null 实体");
            requireCanonicalText(entity.id(), "REM seed id");
            Objects.requireNonNull(entity.type(), "REM seed type 不能为空");
            requireCanonicalText(entity.name(), "REM seed name");
        }
        return entities;
    }

    private static void requireSeed(TemporalEntity seed) {
        Objects.requireNonNull(seed, "REM seed 不能为空");
        requireCanonicalText(seed.id(), "REM seed id");
        Objects.requireNonNull(seed.type(), "REM seed type 不能为空");
        requireCanonicalText(seed.name(), "REM seed name");
        requireCanonicalText(seed.description(), "REM seed description");
    }

    private static List<RetrievalResult> requireRetrievalResults(List<RetrievalResult> results) {
        if (results == null) {
            throw new IllegalStateException("REM 邻居检索结果不能为空");
        }
        for (RetrievalResult result : results) {
            if (result == null) {
                throw new IllegalStateException("REM 邻居检索结果不能包含 null 元素");
            }
            requireCanonicalText(result.entityId(), "REM 邻居 entityId");
            requireCanonicalText(result.entityType(), "REM 邻居 entityType");
            requireCanonicalText(result.name(), "REM 邻居 name");
            if (!Float.isFinite(result.fusedScore())) {
                throw new IllegalStateException("REM 邻居 fusedScore 必须是有限数值: " + result.fusedScore());
            }
            if (!Float.isFinite(result.importanceScore())) {
                throw new IllegalStateException("REM 邻居 importanceScore 必须是有限数值: "
                        + result.importanceScore());
            }
        }
        return results;
    }

    private static Set<String> requireNeighborIds(List<RetrievalResult> neighbors) {
        Set<String> ids = new HashSet<>();
        for (RetrievalResult neighbor : requireRetrievalResults(neighbors)) {
            ids.add(neighbor.entityId());
        }
        return Set.copyOf(ids);
    }

    private static void validateCandidateTopology(List<AssociationCandidate> candidates,
                                                  String seedId,
                                                  Set<String> neighborIds) {
        for (AssociationCandidate candidate : candidates) {
            if (!seedId.equals(candidate.targetEntityId())) {
                throw new AssociationResponseContractException(
                        "REM 联想候选 targetId 必须等于 seedId: " + candidate.targetEntityId());
            }
            if (!neighborIds.contains(candidate.sourceEntityId())) {
                throw new AssociationResponseContractException(
                        "REM 联想候选 sourceId 不在邻居集中: " + candidate.sourceEntityId());
            }
        }
    }

    private static String requiredText(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new AssociationResponseContractException("REM 联想候选缺少必填字段: " + key);
        }
        requireCanonicalText(text, "REM 联想候选 " + key);
        return text;
    }

    private static String optionalText(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new AssociationResponseContractException("REM 联想候选字段必须是非空字符串: " + key);
        }
        requireCanonicalText(text, "REM 联想候选 " + key);
        return text;
    }

    private static float requiredConfidence(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("confidence 不能为空");
        }
        float confidence;
        if (value instanceof Number n) {
            confidence = n.floatValue();
        } else {
            throw new IllegalArgumentException("confidence 必须是数值: " + value);
        }
        if (!(confidence >= 0.0f && confidence <= 1.0f)) {
            throw new IllegalArgumentException("confidence 必须在 [0,1] 范围内: " + value);
        }
        return confidence;
    }

    private static AssociationType parseAssociationType(String raw) {
        requireCanonicalText(raw, "REM relationType");
        try {
            return AssociationType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知关系类型: " + raw, e);
        }
    }

    private static EntityType parseSeedType(String raw) {
        requireCanonicalText(raw, "REM seedTypes");
        try {
            return EntityType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知 REM seedTypes 实体类型: " + raw, e);
        }
    }

    private static void requireCanonicalText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + "不能包含首尾空白: " + value);
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于 0: " + value);
        }
        return value;
    }
}

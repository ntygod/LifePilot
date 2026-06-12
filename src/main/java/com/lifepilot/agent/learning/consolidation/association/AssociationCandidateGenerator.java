package com.lifepilot.agent.learning.consolidation.association;

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
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>LLM 解析失败、无匹配邻居等异常场景统一返回空列表，不阻塞巩固管线。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class AssociationCandidateGenerator {

    private static final Logger log = LoggerFactory.getLogger(AssociationCandidateGenerator.class);

    /** Prompt 模板 key（不依赖 PromptRegistry 资源；本地 hardcoded 更稳定）。 */
    private static final String SCENE = "memory_rem_association";

    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile(
            "\\[.*?\\]", Pattern.DOTALL);

    private final SemanticMemory semanticMemory;
    @Nullable private final HybridRetriever hybridRetriever;
    @Nullable private final GenerationRouter generationRouter;
    private final AgentLearningProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public AssociationCandidateGenerator(SemanticMemory semanticMemory,
                                          @Nullable HybridRetriever hybridRetriever,
                                          @Nullable GenerationRouter generationRouter,
                                          AgentLearningProperties properties) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory);
        this.hybridRetriever = hybridRetriever;
        this.generationRouter = generationRouter;
        this.properties = Objects.requireNonNull(properties);
    }

    /** 主入口：生成本轮所有候选。 */
    public List<AssociationCandidate> generate() {
        if (!properties.getRem().isEnabled()) {
            return List.of();
        }
        if (hybridRetriever == null || generationRouter == null) {
            log.debug("REM 联想: 缺少依赖 (retriever={}, router={})，跳过",
                    hybridRetriever != null, generationRouter != null);
            return List.of();
        }

        List<TemporalEntity> seeds = selectSeeds();
        if (seeds.isEmpty()) return List.of();

        Instant now = Instant.now();
        List<AssociationCandidate> result = new ArrayList<>();
        for (var seed : seeds) {
            try {
                List<RetrievalResult> neighbors = fetchNeighbors(seed);
                if (neighbors.isEmpty()) continue;
                List<AssociationCandidate> parsed = callLlmForAssociations(seed, neighbors, now);
                result.addAll(parsed);
            } catch (Exception e) {
                log.debug("REM 联想: seed={} 生成失败: {}", seed.id(), e.getMessage());
            }
        }
        log.info("REM 联想: 生成 seeds={}, candidates={}", seeds.size(), result.size());
        return result;
    }

    /** 选择 seed 实体：按类型白名单拉全量 → importance 降序 → 取 top-K。 */
    List<TemporalEntity> selectSeeds() {
        int limit = Math.max(1, properties.getRem().getSeedLimit());
        var types = properties.getRem().getSeedTypes();
        List<TemporalEntity> all = new ArrayList<>();
        for (String typeName : types) {
            try {
                EntityType type = EntityType.valueOf(typeName);
                all.addAll(semanticMemory.findCurrentByType(type));
            } catch (IllegalArgumentException e) {
                log.debug("REM 联想: 未知实体类型 {}", typeName);
            }
        }
        return all.stream()
                .filter(e -> e.description() != null && !e.description().isBlank())
                .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore).reversed())
                .limit(limit)
                .toList();
    }

    /** 用 HybridRetriever 取 seed 的相邻实体。 */
    List<RetrievalResult> fetchNeighbors(TemporalEntity seed) {
        int neighborLimit = Math.max(1, properties.getRem().getNeighborLimit());
        String query = seed.name() + (seed.description() != null ? " " + seed.description() : "");
        List<RetrievalResult> raw = hybridRetriever.retrieve(query, neighborLimit + 1, RetrievalWeights.DEFAULT);
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
        if (generationRouter == null) return List.of();
        String prompt = buildPrompt(seed, neighbors);
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getRem().getLlmTimeoutSeconds()));
        LlmResponse response;
        try {
            response = generationRouter.call(SCENE, prompt, null, null, null,
                    GenerationCapability.CHAT, timeout, true);
        } catch (Exception e) {
            log.debug("REM 联想: LLM 调用失败 seed={}, error={}", seed.id(), e.getMessage());
            return List.of();
        }
        if (response == null || response.content() == null || response.content().isBlank()) {
            return List.of();
        }
        return parseResponse(response.content(), seed.id(), now);
    }

    /** 解析 LLM 输出为 AssociationCandidate 列表。 */
    List<AssociationCandidate> parseResponse(String content, String seedId, Instant now) {
        String json = extractJsonArray(content);
        if (json == null) return List.of();
        try {
            List<Map<String, Object>> raw = mapper.readValue(json, new TypeReference<>() {});
            List<AssociationCandidate> list = new ArrayList<>();
            for (var entry : raw) {
                try {
                    String sourceId = strVal(entry.get("sourceId"));
                    String targetId = strVal(entry.get("targetId"));
                    String typeStr = strVal(entry.get("relationType"));
                    if (sourceId == null || targetId == null || typeStr == null) continue;
                    AssociationType type;
                    try {
                        type = AssociationType.valueOf(typeStr.trim().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        type = AssociationType.RELATED_TO;
                    }
                    float confidence = floatVal(entry.get("confidence"), 0.0f);
                    String evidence = strVal(entry.get("evidence"));
                    list.add(new AssociationCandidate(
                            sourceId, targetId, type, confidence, evidence, seedId, now));
                } catch (Exception ex) {
                    log.debug("REM 联想: 条目解析失败: {}", ex.getMessage());
                }
            }
            return list;
        } catch (Exception e) {
            log.debug("REM 联想: JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildPrompt(TemporalEntity seed, List<RetrievalResult> neighbors) {
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

    private String extractJsonArray(String content) {
        Matcher m = JSON_ARRAY_PATTERN.matcher(content);
        return m.find() ? m.group() : null;
    }

    @Nullable
    private static String strVal(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static float floatVal(Object o, float fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.floatValue();
        try {
            return Float.parseFloat(o.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

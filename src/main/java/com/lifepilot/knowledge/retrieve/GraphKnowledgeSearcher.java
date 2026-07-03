package com.lifepilot.knowledge.retrieve;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemorySpaceKeys;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 图谱检索服务 -- 通过知识图谱遍历找到与查询相关的文档分块。
 *
 * <p>算法：从查询中提取候选实体名 -> 匹配 SemanticMemory 中的实体 -> 2-hop 图遍历 ->
 * 通过实体 provenance 的 sourceEntryId 定位 chunk。</p>
 *
 * <p>评分规则：
 * <ul>
 *   <li>直接命中实体 -> 基础分 1.0 * importanceScore</li>
 *   <li>1-hop 关联实体 -> 基础分 0.5 * importanceScore</li>
 *   <li>2-hop 关联实体 -> 基础分 0.3 * importanceScore</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-07
 */
public class GraphKnowledgeSearcher {

    private static final Logger log = LoggerFactory.getLogger(GraphKnowledgeSearcher.class);

    /** 用于分割查询文本的正则：空格、中文标点 */
    private static final Pattern SEGMENT_SPLITTER = Pattern.compile("[\\s,;，；。！？、\\|]+");

    /** 图谱检索中尝试匹配的实体类型 */
    private static final List<EntityType> SEARCH_ENTITY_TYPES = List.of(
            EntityType.PERSON,
            EntityType.ORGANIZATION,
            EntityType.TOPIC,
            EntityType.PROJECT,
            EntityType.EVENT,
            EntityType.PLACE
    );

    /** 各跳距的基础分数权重 */
    private static final double DIRECT_HIT_SCORE = 1.0;
    private static final double ONE_HOP_SCORE = 0.5;
    private static final double TWO_HOP_SCORE = 0.3;

    private final SemanticMemory semanticMemory;
    private final DocumentChunkRepository chunkRepository;
    private final MemorySpaceRepository memorySpaceRepository;

    /**
     * 构造图谱检索服务。
     *
     * @param semanticMemory  语义记忆（知识图谱）
     * @param chunkRepository 分块数据访问层
     * @param memorySpaceRepository 记忆空间仓储
     */
    public GraphKnowledgeSearcher(SemanticMemory semanticMemory,
                                  DocumentChunkRepository chunkRepository,
                                  MemorySpaceRepository memorySpaceRepository) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "SemanticMemory 不能为空");
        this.chunkRepository = Objects.requireNonNull(chunkRepository, "DocumentChunkRepository 不能为空");
        this.memorySpaceRepository = Objects.requireNonNull(memorySpaceRepository, "MemorySpaceRepository 不能为空");
        log.info("GraphKnowledgeSearcher 初始化完成");
    }

    /**
     * 通过知识图谱检索与查询相关的文档分块。
     *
     * @param query  查询文本
     * @param scopes 知识库检索范围
     * @param topK   返回数量上限
     * @return 按图谱相关性降序排列的检索结果
     */
    public List<DocumentSearchResult> search(String query, List<KnowledgeSearchScope> scopes, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // 1. 分词提取候选实体名
        var segments = extractCandidateNames(query);
        if (segments.isEmpty()) {
            return List.of();
        }

        // 2. 实体名匹配 — 优先收窄到本次检索的知识库 domain space
        var readFilterOpt = buildReadFilter(scopes);
        if (readFilterOpt.isEmpty()) {
            log.debug("图谱检索: 目标知识库没有可用 domain space, scopes={}", scopes);
            return List.of();
        }
        var readFilter = readFilterOpt.get();
        var directEntities = matchEntities(segments, readFilter);
        if (directEntities.isEmpty()) {
            log.debug("图谱检索: 查询无实体命中, query={}", query);
            return List.of();
        }

        // 3. 2-hop 图遍历，收集关联实体
        var entityScores = new LinkedHashMap<String, Double>(); // entityId -> 最高分

        for (var entity : directEntities) {
            double score = DIRECT_HIT_SCORE * normalizeImportance(entity.importanceScore());
            entityScores.merge(entity.id(), score, Math::max);

            // 1-hop + 2-hop 遍历
            var related = semanticMemory.findRelated(entity.id(), 2);
            for (var rel : related) {
                // 判断跳距：1-hop 实体有直接关系边，其余为 2-hop
                double relScore = isDirectlyRelated(entity.id(), rel.id())
                        ? ONE_HOP_SCORE * normalizeImportance(rel.importanceScore())
                        : TWO_HOP_SCORE * normalizeImportance(rel.importanceScore());
                entityScores.merge(rel.id(), relScore, Math::max);
            }
        }

        // 4. 从实体 provenance 回到 chunk，避免图命中后粗暴返回整篇文档
        var kbIds = extractKnowledgeBaseIds(scopes);
        var chunkScores = resolveChunkScores(entityScores);
        var chunkResults = buildChunkResults(chunkScores, kbIds);
        if (!chunkResults.isEmpty()) {
            log.debug("图谱检索: directEntities={}, totalEntities={}, chunks={}",
                    directEntities.size(), entityScores.size(), chunkResults.size());
            return limit(chunkResults, topK);
        }

        log.debug("图谱检索: 实体命中但无 chunk 级证据, directEntities={}", directEntities.size());
        return List.of();
    }

    private Map<String, Double> resolveChunkScores(Map<String, Double> entityScores) {
        if (entityScores.isEmpty()) {
            return Map.of();
        }
        var sourceEntries = semanticMemory.findSourceEntryIdsByEntityIds(entityScores.keySet());
        var chunkScores = new LinkedHashMap<String, Double>();
        for (var entry : entityScores.entrySet()) {
            var chunkIds = sourceEntries.getOrDefault(entry.getKey(), List.of());
            for (var chunkId : chunkIds) {
                chunkScores.merge(chunkId, entry.getValue(), Math::max);
            }
        }
        return chunkScores;
    }

    private List<DocumentSearchResult> buildChunkResults(Map<String, Double> chunkScores, List<String> kbIds) {
        if (chunkScores.isEmpty()) {
            return List.of();
        }
        var chunks = chunkRepository.findByIds(new ArrayList<>(chunkScores.keySet()));
        var results = new ArrayList<DocumentSearchResult>();
        for (var chunk : chunks) {
            if (!kbIds.isEmpty() && !kbIds.contains(chunk.knowledgeBaseId())) {
                continue;
            }
            double score = chunkScores.getOrDefault(chunk.id(), 0.0);
            if (score <= 0.0) {
                continue;
            }
            results.add(buildSearchResult(chunk, score));
        }
        results.sort(Comparator.comparingDouble(DocumentSearchResult::score).reversed());
        return results;
    }

    /**
     * 从查询文本中提取候选实体名称片段。
     */
    private List<String> extractCandidateNames(String query) {
        var segments = SEGMENT_SPLITTER.splitAsStream(query.trim())
                .filter(s -> !s.isBlank() && s.length() >= 2)
                .distinct()
                .toList();
        // 同时将完整查询作为候选（覆盖不含分隔符的实体名）
        var result = new ArrayList<>(segments);
        String trimmed = query.trim();
        if (!result.contains(trimmed) && trimmed.length() >= 2) {
            result.addFirst(trimmed);
        }
        return result;
    }

    private Optional<MemoryReadFilter> buildReadFilter(List<KnowledgeSearchScope> scopes) {
        var kbIds = extractKnowledgeBaseIds(scopes);
        if (kbIds.isEmpty()) {
            return Optional.of(MemoryReadFilter.of(List.of(), List.of(MemoryScope.DOMAIN_MEMORY)));
        }
        var spaceIds = kbIds.stream()
                .map(MemorySpaceKeys::knowledgeBaseDomain)
                .map(memorySpaceRepository::findBySpaceKey)
                .flatMap(Optional::stream)
                .map(space -> space.id())
                .distinct()
                .toList();
        if (spaceIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(MemoryReadFilter.of(spaceIds, List.of(MemoryScope.DOMAIN_MEMORY)));
    }

    /**
     * 在 SemanticMemory 中按名称和类型匹配实体。
     */
    private List<TemporalEntity> matchEntities(List<String> candidateNames,
                                                MemoryReadFilter readFilter) {
        var matched = new LinkedHashMap<String, TemporalEntity>(); // 按 id 去重
        for (var name : candidateNames) {
            for (var type : SEARCH_ENTITY_TYPES) {
                var entityOpt = semanticMemory.findCurrentByNameAndType(name, type, readFilter);
                entityOpt.ifPresent(e -> matched.putIfAbsent(e.id(), e));
            }
        }
        return new ArrayList<>(matched.values());
    }

    /**
     * 判断两个实体是否有直接关系边（1-hop）。
     */
    private boolean isDirectlyRelated(String entityId, String relatedId) {
        var relations = semanticMemory.findRelationsByEntityId(entityId);
        return relations.stream().anyMatch(r ->
                r.sourceEntityId().equals(relatedId) || r.targetEntityId().equals(relatedId));
    }

    /**
     * 将 importanceScore 归一化到 [0.3, 1.0] 区间，避免低分实体被完全忽略。
     */
    private double normalizeImportance(float importanceScore) {
        return Math.max(0.3, Math.min(1.0, importanceScore));
    }

    /**
     * 构建检索结果。
     */
    private DocumentSearchResult buildSearchResult(DocumentChunk chunk, double score) {
        return new DocumentSearchResult(
                chunk.id(),
                chunk.documentId(),
                chunk.knowledgeBaseId(),
                chunk.content(),
                chunk.contextPrefix(),
                chunk.headingHierarchy(),
                score,
                "graph",
                chunk.metadata(),
                Optional.of(new ScoreBreakdown(0.0, 0.0, score, 0.0, Optional.empty())),
                Optional.empty(),
                chunk.sourceType()
        );
    }

    private List<DocumentSearchResult> limit(List<DocumentSearchResult> results, int topK) {
        if (topK <= 0 || results.size() <= topK) {
            return List.copyOf(results);
        }
        return List.copyOf(results.subList(0, topK));
    }

    private List<String> extractKnowledgeBaseIds(List<KnowledgeSearchScope> scopes) {
        if (scopes == null) return List.of();
        return scopes.stream()
                .map(KnowledgeSearchScope::knowledgeBaseId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}

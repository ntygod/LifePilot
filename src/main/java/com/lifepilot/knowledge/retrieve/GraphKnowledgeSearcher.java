package com.lifepilot.knowledge.retrieve;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 图谱检索服务 -- 通过知识图谱遍历找到与查询相关的文档分块。
 *
 * <p>算法：从查询中提取候选实体名 -> 匹配 SemanticMemory 中的实体 -> 2-hop 图遍历 ->
 * 通过实体的 sourceConversationId 定位文档 -> 返回该文档的 parent 分块（chunkLevel=0）。
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
    private final DocumentRepository docRepository;

    /**
     * 构造图谱检索服务。
     *
     * @param semanticMemory  语义记忆（知识图谱）
     * @param chunkRepository 分块数据访问层
     * @param docRepository   文档数据访问层
     */
    public GraphKnowledgeSearcher(SemanticMemory semanticMemory,
                                   DocumentChunkRepository chunkRepository,
                                   DocumentRepository docRepository) {
        this.semanticMemory = semanticMemory;
        this.chunkRepository = chunkRepository;
        this.docRepository = docRepository;
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

        // 2. 实体名匹配 — 在 DOMAIN_MEMORY 范围内查找
        var readFilter = MemoryReadFilter.of(List.of(), List.of(MemoryScope.DOMAIN_MEMORY));
        var directEntities = matchEntities(segments, readFilter);
        if (directEntities.isEmpty()) {
            log.debug("图谱检索: 查询无实体命中, query={}", query);
            return List.of();
        }

        // 3. 2-hop 图遍历，收集关联实体
        var entityScores = new LinkedHashMap<String, Double>(); // entityId -> 最高分
        var entityMap = new LinkedHashMap<String, TemporalEntity>(); // entityId -> 实体

        for (var entity : directEntities) {
            double score = DIRECT_HIT_SCORE * normalizeImportance(entity.importanceScore());
            entityScores.merge(entity.id(), score, Math::max);
            entityMap.putIfAbsent(entity.id(), entity);

            // 1-hop + 2-hop 遍历
            try {
                var related = semanticMemory.findRelated(entity.id(), 2);
                for (var rel : related) {
                    entityMap.putIfAbsent(rel.id(), rel);
                    // 判断跳距：1-hop 实体有直接关系边，其余为 2-hop
                    double relScore = isDirectlyRelated(entity.id(), rel.id())
                            ? ONE_HOP_SCORE * normalizeImportance(rel.importanceScore())
                            : TWO_HOP_SCORE * normalizeImportance(rel.importanceScore());
                    entityScores.merge(rel.id(), relScore, Math::max);
                }
            } catch (Exception e) {
                log.warn("图谱检索: 图遍历失败, entityId={}, error={}", entity.id(), e.getMessage());
            }
        }

        // 4. 收集 sourceConversationId（= 文档 ID）并按 scope 过滤
        var kbIds = extractKnowledgeBaseIds(scopes);
        var docScores = new LinkedHashMap<String, Double>(); // docId -> 最高分

        for (var entry : entityScores.entrySet()) {
            var entity = entityMap.get(entry.getKey());
            if (entity == null || entity.sourceConversationId() == null) {
                continue;
            }
            String docId = entity.sourceConversationId();
            // 验证文档存在且属于目标知识库
            var docOpt = docRepository.findById(docId);
            if (docOpt.isEmpty()) {
                continue;
            }
            var doc = docOpt.get();
            if (!kbIds.isEmpty() && !kbIds.contains(doc.knowledgeBaseId())) {
                continue;
            }
            docScores.merge(docId, entry.getValue(), Math::max);
        }

        if (docScores.isEmpty()) {
            log.debug("图谱检索: 实体命中但无关联文档, directEntities={}", directEntities.size());
            return List.of();
        }

        // 5. 获取每篇文档的 parent 分块（chunkLevel=0），构建结果
        var results = new ArrayList<DocumentSearchResult>();

        for (var entry : docScores.entrySet()) {
            String docId = entry.getKey();
            double docScore = entry.getValue();

            var chunks = chunkRepository.findByDocumentId(docId);
            // 优先选择 parent 分块（level=0），如果没有 parent-child 结构则取所有分块
            var parentChunks = chunks.stream()
                    .filter(c -> c.chunkLevel() == 0)
                    .toList();
            var targetChunks = parentChunks.isEmpty() ? chunks : parentChunks;

            for (var chunk : targetChunks) {
                results.add(buildSearchResult(chunk, docScore));
            }
        }

        // 6. 按分数降序排列，截取 topK
        results.sort(Comparator.comparingDouble(DocumentSearchResult::score).reversed());
        var finalResults = results.size() > topK ? results.subList(0, topK) : results;

        log.debug("图谱检索: directEntities={}, totalEntities={}, docs={}, chunks={}",
                directEntities.size(), entityScores.size(), docScores.size(), finalResults.size());

        return List.copyOf(finalResults);
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

    /**
     * 在 SemanticMemory 中按名称和类型匹配实体。
     */
    private List<TemporalEntity> matchEntities(List<String> candidateNames,
                                                MemoryReadFilter readFilter) {
        var matched = new LinkedHashMap<String, TemporalEntity>(); // 按 id 去重
        for (var name : candidateNames) {
            for (var type : SEARCH_ENTITY_TYPES) {
                try {
                    var entityOpt = semanticMemory.findCurrentByNameAndType(name, type, readFilter);
                    entityOpt.ifPresent(e -> matched.putIfAbsent(e.id(), e));
                } catch (Exception e) {
                    log.debug("图谱检索: 实体查询异常, name={}, type={}, error={}", name, type, e.getMessage());
                }
            }
        }
        return new ArrayList<>(matched.values());
    }

    /**
     * 判断两个实体是否有直接关系边（1-hop）。
     */
    private boolean isDirectlyRelated(String entityId, String relatedId) {
        try {
            var relations = semanticMemory.findRelationsByEntityId(entityId);
            return relations.stream().anyMatch(r ->
                    r.sourceEntityId().equals(relatedId) || r.targetEntityId().equals(relatedId));
        } catch (Exception e) {
            return false;
        }
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
                Optional.of(new ScoreBreakdown(0.0, 0.0, 0.0, 0.0, Optional.empty())),
                Optional.empty(),
                chunk.sourceType(),
                Optional.ofNullable(chunk.sourceDatastoreId()),
                Optional.ofNullable(chunk.sourceCollectionId())
        );
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

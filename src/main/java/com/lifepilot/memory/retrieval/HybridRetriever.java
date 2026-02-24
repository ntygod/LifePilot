package com.lifepilot.memory.retrieval;

import com.lifepilot.memory.semantic.SemanticMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 三路混合检索引擎 — 并行执行向量检索、全文搜索、图遍历，通过加权 RRF 融合排序。
 *
 * <p>使用 CompletableFuture + Virtual Thread 并行执行三路检索，
 * 自适应权重调整，加权 RRF 融合，时间衰减 + 重要度加成，按 entity_id 去重。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final VectorSearcher vectorSearcher;
    private final FtsSearcher ftsSearcher;
    private final GraphTraverser graphTraverser;
    private final SemanticMemory semanticMemory;
    private final ExecutorService virtualThreadExecutor;

    public HybridRetriever(VectorSearcher vectorSearcher,
                           FtsSearcher ftsSearcher,
                           GraphTraverser graphTraverser,
                           SemanticMemory semanticMemory) {
        this.vectorSearcher = vectorSearcher;
        this.ftsSearcher = ftsSearcher;
        this.graphTraverser = graphTraverser;
        this.semanticMemory = semanticMemory;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 三路混合检索。
     *
     * <ol>
     *   <li>CompletableFuture + Virtual Thread 并行执行三路检索</li>
     *   <li>向量结果转换为 RankedItem（查询实体详情）</li>
     *   <li>自适应权重调整（向量 Top-1 &lt; 0.5 时）</li>
     *   <li>加权 RRF 融合 + 时间衰减 + 重要度加成</li>
     *   <li>按 entity_id 去重，保留 fusedScore 最高</li>
     *   <li>批量更新 access_count</li>
     * </ol>
     *
     * @param query   查询文本
     * @param topK    返回前 K 个结果
     * @param weights 检索权重配置
     * @return 融合排序后的检索结果列表（fusedScore 降序）
     */
    public List<RetrievalResult> retrieve(String query, int topK, RetrievalWeights weights) {
        // 1. 并行执行三路检索
        var vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearcher.searchEntities(query, topK, 0.0f), virtualThreadExecutor);
        var ftsFuture = CompletableFuture.supplyAsync(
                () -> ftsSearcher.search(query, topK), virtualThreadExecutor);
        var graphFuture = CompletableFuture.supplyAsync(
                () -> graphTraverser.traverse(query, topK), virtualThreadExecutor);

        // 收集结果，任一路失败时使用空列表
        List<VectorSearchResult> vectorResults = safeGet(vectorFuture, "向量检索");
        List<RankedItem> ftsResults = safeGet(ftsFuture, "全文搜索");
        List<RankedItem> graphResults = safeGet(graphFuture, "图遍历");

        // 2. 向量结果转换为 RankedItem
        List<RankedItem> vectorItems = convertVectorResults(vectorResults);

        // 3. 自适应权重调整
        float topVectorScore = vectorItems.isEmpty() ? 0.0f : vectorItems.getFirst().score();
        RetrievalWeights adaptedWeights = weights.adaptForLowVectorConfidence(topVectorScore);

        // 4. 加权 RRF 融合
        Map<String, FusionAccumulator> accumulators = new HashMap<>();
        applyRrf(accumulators, vectorItems, adaptedWeights.vectorWeight(), adaptedWeights.rrfK(), "vector");
        applyRrf(accumulators, ftsResults, adaptedWeights.ftsWeight(), adaptedWeights.rrfK(), "fts");
        applyRrf(accumulators, graphResults, adaptedWeights.graphWeight(), adaptedWeights.rrfK(), "graph");

        // 5. 计算最终分数（时间衰减 + 重要度加成）并构建结果
        var now = Instant.now();
        List<RetrievalResult> results = new ArrayList<>();
        for (var entry : accumulators.entrySet()) {
            var acc = entry.getValue();
            float rrfScore = acc.rrfScore;

            // 时间衰减: RecencyFactor = exp(-recencyDecay × daysSinceLastAccess)
            float recencyBoost = 0.0f;
            if (acc.lastAccessedAt != null) {
                long daysSince = Duration.between(acc.lastAccessedAt, now).toDays();
                recencyBoost = (float) Math.exp(-adaptedWeights.recencyDecay() * daysSince);
            }

            // 重要度加成: ImportanceBoost = importanceBoost × importanceScore
            float impBoost = adaptedWeights.importanceBoost() * acc.importanceScore;

            float fusedScore = rrfScore + recencyBoost + impBoost;

            var breakdown = new RetrievalResult.ScoreBreakdown(
                    acc.vectorScore, acc.vectorScore * adaptedWeights.vectorWeight(),
                    acc.ftsScore, acc.ftsScore * adaptedWeights.ftsWeight(),
                    acc.graphScore, acc.graphScore * adaptedWeights.graphWeight(),
                    recencyBoost, impBoost);

            results.add(new RetrievalResult(
                    acc.entityId, acc.entityType, acc.name, acc.description,
                    fusedScore, breakdown, acc.sourcePath,
                    acc.lastAccessedAt, acc.importanceScore));
        }

        // 6. 按 entity_id 去重（保留 fusedScore 最高），排序，截取 topK
        Map<String, RetrievalResult> deduped = new HashMap<>();
        for (var result : results) {
            deduped.merge(result.entityId(), result,
                    (existing, incoming) -> incoming.fusedScore() > existing.fusedScore() ? incoming : existing);
        }

        List<RetrievalResult> finalResults = deduped.values().stream()
                .sorted()
                .limit(topK)
                .toList();

        // 7. 批量更新 access_count
        updateAccessCounts(finalResults);

        log.debug("混合检索: query={}, 向量={}, FTS={}, 图={}, 融合结果={}",
                query, vectorItems.size(), ftsResults.size(), graphResults.size(), finalResults.size());
        return finalResults;
    }

    // --- 内部方法 ---

    /** 安全获取 CompletableFuture 结果，失败时返回空列表。 */
    private <T> List<T> safeGet(CompletableFuture<List<T>> future, String pathName) {
        try {
            return future.join();
        } catch (Exception e) {
            log.warn("混合检索: {} 路径失败，使用空结果继续融合, error={}", pathName, e.getMessage());
            return List.of();
        }
    }

    /** 向量检索结果转换为 RankedItem（查询实体详情补全元数据）。 */
    private List<RankedItem> convertVectorResults(List<VectorSearchResult> vectorResults) {
        List<RankedItem> items = new ArrayList<>();
        for (var vr : vectorResults) {
            try {
                // 从 SemanticMemory 的 findAllCurrent 中查找实体详情
                // 这里直接用 JdbcTemplate 查询会更高效，但为避免 HybridRetriever 直接依赖 JdbcTemplate，
                // 通过 SemanticMemory 间接获取
                var allCurrent = semanticMemory.findAllCurrent();
                var entity = allCurrent.stream()
                        .filter(e -> e.id().equals(vr.entityId()))
                        .findFirst();
                if (entity.isPresent()) {
                    var e = entity.get();
                    items.add(new RankedItem(
                            e.id(), e.type().name(), e.name(), e.description(),
                            vr.similarity(), e.lastAccessedAt(), e.importanceScore()));
                } else {
                    // 实体可能已归档，仅用 entityId 和 similarity 构建
                    items.add(new RankedItem(
                            vr.entityId(), "UNKNOWN", vr.entityId(), null,
                            vr.similarity(), null, 0.0f));
                }
            } catch (Exception e) {
                log.warn("混合检索: 向量结果转换失败, entityId={}, error={}", vr.entityId(), e.getMessage());
            }
        }
        return items;
    }

    /** 对单路排名列表应用加权 RRF，累加到 accumulators。 */
    private void applyRrf(Map<String, FusionAccumulator> accumulators,
                          List<RankedItem> rankedItems,
                          float weight, int k, String source) {
        for (int rank = 0; rank < rankedItems.size(); rank++) {
            var item = rankedItems.get(rank);
            float rrfContribution = weight / (k + rank + 1); // rank 从 1 开始

            var acc = accumulators.computeIfAbsent(item.entityId(),
                    id -> new FusionAccumulator(item));
            acc.rrfScore += rrfContribution;

            // 记录各路原始分数
            switch (source) {
                case "vector" -> acc.vectorScore = item.score();
                case "fts" -> acc.ftsScore = item.score();
                case "graph" -> acc.graphScore = item.score();
            }

            // 记录来源路径
            if (!acc.sourcePath.contains(source)) {
                acc.sourcePath = acc.sourcePath.isEmpty() ? source : acc.sourcePath + "+" + source;
            }
        }
    }

    /** 批量更新结果实体的 access_count。 */
    private void updateAccessCounts(List<RetrievalResult> results) {
        try {
            for (var result : results) {
                semanticMemory.incrementAccessCount(result.entityId());
            }
        } catch (Exception e) {
            log.warn("混合检索: 批量更新 access_count 失败, error={}", e.getMessage());
        }
    }

    /** 融合累加器 — 收集各路 RRF 贡献和元数据。 */
    private static class FusionAccumulator {
        final String entityId;
        final String entityType;
        final String name;
        final String description;
        final Instant lastAccessedAt;
        final float importanceScore;
        float rrfScore;
        float vectorScore;
        float ftsScore;
        float graphScore;
        String sourcePath = "";

        FusionAccumulator(RankedItem item) {
            this.entityId = item.entityId();
            this.entityType = item.entityType();
            this.name = item.name();
            this.description = item.description();
            this.lastAccessedAt = item.lastAccessedAt();
            this.importanceScore = item.importanceScore();
        }
    }
}

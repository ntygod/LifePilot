package com.lifepilot.memory.retrieval;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.procedural.IntentMatcher;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.working.ReasoningSlot;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    @Nullable
    private final IntentMatcher intentMatcher;
    private final MemoryProperties memoryProperties;
    private final ExecutorService virtualThreadExecutor;

    /** 最近一次 retrieve() 中 L4 程序记忆匹配结果（线程安全，每次 retrieve 重置）。 */
    private volatile ReasoningSlot lastProcedureSlot;

    /** 空数据短路标记 — 三路检索全部返回空时设为 true，记忆写入后重置。volatile 保证可见性。 */
    private volatile boolean knownEmpty = false;

    public HybridRetriever(VectorSearcher vectorSearcher,
                           FtsSearcher ftsSearcher,
                           GraphTraverser graphTraverser,
                           SemanticMemory semanticMemory,
                           @Nullable IntentMatcher intentMatcher,
                           MemoryProperties memoryProperties) {
        this.vectorSearcher = vectorSearcher;
        this.ftsSearcher = ftsSearcher;
        this.graphTraverser = graphTraverser;
        this.semanticMemory = semanticMemory;
        this.intentMatcher = intentMatcher;
        this.memoryProperties = memoryProperties;
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
        // 重置 L4 匹配结果
        this.lastProcedureSlot = null;

        // 空数据短路：已知三路检索全部为空时直接返回
        if (knownEmpty) {
            log.debug("混合检索: 已知数据为空，短路返回");
            return List.of();
        }

        // 1. 并行执行三路检索 + 可选 L4 意图匹配
        var vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearcher.searchEntities(query, topK, 0.0f), virtualThreadExecutor);
        var ftsFuture = CompletableFuture.supplyAsync(
                () -> ftsSearcher.search(query, topK), virtualThreadExecutor);
        var graphFuture = CompletableFuture.supplyAsync(
                () -> graphTraverser.traverse(query, topK), virtualThreadExecutor);

        // L4: 并行执行 IntentMatcher（不参与 RRF 融合）
        if (intentMatcher != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    var matchOpt = intentMatcher.match(query);
                    matchOpt.ifPresent(match -> {
                        var template = match.template();
                        var context = "操作模板建议: %s (匹配度=%.2f, 成功率=%.2f, 步骤数=%d)"
                                .formatted(template.name(), match.score(),
                                        template.successRate(), template.steps().size());
                        this.lastProcedureSlot = ReasoningSlot.retrievalContext(context, context.length() / 4);
                        log.debug("混合检索: L4 意图匹配命中, template={}, score={}",
                                template.name(), match.score());
                    });
                } catch (Exception e) {
                    log.warn("混合检索: L4 意图匹配失败, error={}", e.getMessage());
                }
            }, virtualThreadExecutor).join(); // join 确保 L4 结果在 retrieve 返回前可用
        }

        // 收集结果，任一路失败时使用空列表
        List<VectorSearchResult> vectorResults = safeGet(vectorFuture, "向量检索");
        List<RankedItem> ftsResults = safeGet(ftsFuture, "全文搜索");
        List<RankedItem> graphResults = safeGet(graphFuture, "图遍历");

        // 三路检索全部返回空时，设置 knownEmpty 短路标记
        if (vectorResults.isEmpty() && ftsResults.isEmpty() && graphResults.isEmpty()) {
            knownEmpty = true;
            log.debug("混合检索: 三路检索全部返回空，设置 knownEmpty=true");
            return List.of();
        }

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
                    acc.lastAccessedAt, acc.importanceScore, acc.validTo));
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

        // 新增：fusedScore 阈值过滤
        float minScore = memoryProperties.getRetrieval().getMinFusedScore();
        if (minScore > 0.0f) {
            int beforeCount = finalResults.size();
            finalResults = finalResults.stream()
                    .filter(r -> r.fusedScore() >= minScore)
                    .toList();
            if (log.isDebugEnabled() && beforeCount != finalResults.size()) {
                log.debug("混合检索: 相关性过滤, 阈值={}, 过滤前={}, 过滤后={}",
                        minScore, beforeCount, finalResults.size());
            }
        }

        // 7. 批量更新 access_count
        updateAccessCounts(finalResults);

        log.debug("混合检索: query={}, 向量={}, FTS={}, 图={}, 融合结果={}",
                query, vectorItems.size(), ftsResults.size(), graphResults.size(), finalResults.size());
        return finalResults;
    }

    /**
     * 获取最近一次 retrieve() 中 L4 程序记忆匹配结果。
     *
     * <p>L4 匹配结果不参与 RRF 融合排序，作为独立的执行建议注入 ReasoningSlot。
     * 每次 retrieve() 调用后重置，无匹配时返回 Optional.empty()。</p>
     *
     * @return L4 程序记忆匹配的 ReasoningSlot
     */
    public Optional<ReasoningSlot> getLastProcedureSlot() {
        return Optional.ofNullable(lastProcedureSlot);
    }

    /**
     * 重置空数据标记，供记忆写入后调用。
     */
    public void resetEmptyFlag() {
        this.knownEmpty = false;
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

    /** 向量检索结果转换为 RankedItem（批量查询实体详情补全元数据）。 */
    private List<RankedItem> convertVectorResults(List<VectorSearchResult> vectorResults) {
        if (vectorResults.isEmpty()) {
            return List.of();
        }

        List<RankedItem> items = new ArrayList<>();
        try {
            Set<String> ids = new HashSet<>();
            for (var vr : vectorResults) {
                if (vr.entityId() != null && !vr.entityId().isBlank()) {
                    ids.add(vr.entityId());
                }
            }
            Map<String, TemporalEntity> entityMap = semanticMemory.findByIds(ids);

            for (var vr : vectorResults) {
                TemporalEntity entity = entityMap.get(vr.entityId());
                if (entity != null) {
                    items.add(new RankedItem(
                            entity.id(),
                            entity.type().name(),
                            entity.name(),
                            entity.description(),
                            vr.similarity(),
                            entity.lastAccessedAt(),
                            entity.importanceScore(),
                            entity.validTo()));
                } else {
                    // 实体可能已归档，仅用 entityId 和 similarity 构建
                    items.add(new RankedItem(
                            vr.entityId(),
                            "UNKNOWN",
                            vr.entityId(),
                            null,
                            vr.similarity(),
                            null,
                            0.0f,
                            null));
                }
            }
        } catch (Exception e) {
            log.warn("混合检索: 向量结果批量转换失败, error={}", e.getMessage());
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
        final Instant validTo;
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
            this.validTo = item.validTo();
        }
    }
}

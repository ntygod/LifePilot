package com.lifepilot.memory.retrieval;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.knowledge.rerank.RerankCandidate;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.rerank.router.RerankRouter;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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
    private static final float EXACT_LEXICAL_MATCH_THRESHOLD = 4.0f;

    private final VectorSearcher vectorSearcher;
    private final FtsSearcher ftsSearcher;
    private final GraphTraverser graphTraverser;
    private final SemanticMemory semanticMemory;
    @Nullable
    private final IntentMatcher intentMatcher;
    private final MemoryRetrievalProperties memoryProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService virtualThreadExecutor;
    private final RerankRouter rerankRouter;
    private final MemoryProvenanceRepository provenanceRepository;

    /** 最近一次 retrieve() 中 L4 程序记忆匹配结果（线程安全，每次 retrieve 重置）。 */

    public HybridRetriever(VectorSearcher vectorSearcher,
                           FtsSearcher ftsSearcher,
                           GraphTraverser graphTraverser,
                           SemanticMemory semanticMemory,
                           @Nullable IntentMatcher intentMatcher,
                           MemoryRetrievalProperties memoryProperties,
                           JdbcTemplate jdbcTemplate,
                           RerankRouter rerankRouter,
                           MemoryProvenanceRepository provenanceRepository) {
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher, "vectorSearcher");
        this.ftsSearcher = Objects.requireNonNull(ftsSearcher, "ftsSearcher");
        this.graphTraverser = Objects.requireNonNull(graphTraverser, "graphTraverser");
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory");
        this.intentMatcher = intentMatcher;
        this.memoryProperties = Objects.requireNonNull(memoryProperties, "memoryProperties");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.rerankRouter = Objects.requireNonNull(rerankRouter, "rerankRouter");
        this.provenanceRepository = Objects.requireNonNull(provenanceRepository, "provenanceRepository");
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
        return retrieve(query, topK, weights, null);
    }

    /**
     * 带读取过滤的三路混合检索。
     *
     * @param query    查询文本
     * @param topK     返回前 K 个结果
     * @param weights  检索权重配置
     * @param filter   读取过滤条件
     * @return 融合排序后的检索结果列表
     */
    public List<RetrievalResult> retrieve(String query,
                                          int topK,
                                          RetrievalWeights weights,
                                          @Nullable MemoryReadFilter filter) {
        validateRetrieveArguments(query, topK, weights);
        String normalizedQuery = query.trim();
        long startTime = System.currentTimeMillis();

        // 1. 并行执行三路检索 + 可选 L4 意图匹配
        float minVecSim = memoryProperties.getMinVectorSimilarity();
        // 向量路径 pre-filter：filter 生效时，预查合规实体 ID
        final Set<String> eligibleIds;
        if (filter != null && !filter.isUnrestricted()) {
            eligibleIds = requireEntityIds(
                    semanticMemory.findEligibleEntityIds(filter), "读取过滤可用实体 ID");
        } else {
            eligibleIds = null;
        }
        var vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearcher.searchEntities(normalizedQuery, topK, minVecSim, eligibleIds), virtualThreadExecutor);
        var ftsFuture = CompletableFuture.supplyAsync(
                () -> ftsSearcher.search(normalizedQuery, topK), virtualThreadExecutor);
        var graphFuture = CompletableFuture.supplyAsync(
                () -> graphTraverser.traverse(normalizedQuery, topK, filter), virtualThreadExecutor);

        // L4: 并行执行 IntentMatcher（不参与 RRF 融合，与三路检索一起等待）
        CompletableFuture<Void> intentFuture = null;
        if (intentMatcher != null) {
            intentFuture = CompletableFuture.runAsync(() -> {
                var matchOpt = intentMatcher.match(normalizedQuery);
                matchOpt.ifPresent(match -> {
                    var template = match.template();
                    log.debug("混合检索: L4 意图匹配命中, template={}, score={}",
                            template.name(), match.score());
                });
            }, virtualThreadExecutor);
        }

        // 等待所有并行任务完成（三路检索 + 可选 L4 意图匹配）
        if (intentFuture != null) {
            awaitAll(vectorFuture, ftsFuture, graphFuture, intentFuture);
        } else {
            awaitAll(vectorFuture, ftsFuture, graphFuture);
        }

        List<VectorSearchResult> vectorResults = getRequired(vectorFuture, "向量检索");
        List<RankedItem> ftsResults = filterRankedItems(getRequired(ftsFuture, "全文搜索"), filter);
        List<RankedItem> graphResults = filterRankedItems(getRequired(graphFuture, "图遍历"), filter);
        if (intentFuture != null) {
            waitRequired(intentFuture, "L4 意图匹配");
        }

        if (vectorResults.isEmpty() && ftsResults.isEmpty() && graphResults.isEmpty()) {
            return List.of();
        }

        // 2. 向量结果转换为 RankedItem + 收集实体 → lifecycleState 映射
        //    lifecycleStateMap 用作：① 融合阶段给 FusionAccumulator 补 state；
        //                           ② 最终结果阶段打 isHistorical / isStale 标注。
        Map<String, LifecycleState> lifecycleStateMap = new HashMap<>();
        Map<String, Float> trustScoreMap = new HashMap<>();
        List<RankedItem> vectorItems = convertVectorResults(vectorResults, filter, lifecycleStateMap);
        // convertVectorResults 已查到完整实体信息，但 RankedItem 不承载 trustScore，
        // 此处通过 lifecycleStateMap 补齐后的二次查询统一收集 trustScore。

        // 对 FTS / Graph 路径同样按 lifecycle 过滤：SQL 已拦截不可召回态，但防御性再走一次
        //  —— 若其 id 不在 lifecycleStateMap 里，需要补查（因向量路径可能未召回）。
        ftsResults = filterAndTrackLifecycle(ftsResults, filter, lifecycleStateMap);
        graphResults = filterAndTrackLifecycle(graphResults, filter, lifecycleStateMap);
        collectTrustScores(vectorItems, ftsResults, graphResults, filter, trustScoreMap);

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

            // 可信度加成：trustScoreBoostWeight × trust_score（质量门槛之外的排序维度）
            Float trustScore = trustScoreMap.get(acc.entityId);
            if (trustScore == null) {
                throw new IllegalStateException("混合检索: 候选实体缺少 trustScore: " + acc.entityId);
            }
            float trustBoost = memoryProperties.getTrustScoreBoostWeight() * trustScore;

            float lexicalBoost = acc.ftsScore >= EXACT_LEXICAL_MATCH_THRESHOLD
                    ? acc.ftsScore * adaptedWeights.ftsWeight()
                    : 0.0f;

            // 生命周期调整：REGENERATION_NEEDED / STALE_CANDIDATE 明确降权；COMPLETED 轻微降权。
            LifecycleState lifecycleState = lifecycleStateMap.get(acc.entityId);
            float lifecycleAdjustment = 0.0f;
            if (lifecycleState == LifecycleState.REGENERATION_NEEDED) {
                lifecycleAdjustment -= memoryProperties.getStaleLifecyclePenalty();
            } else if (lifecycleState == LifecycleState.STALE_CANDIDATE) {
                // memory-staleness spec：旧事实被新证据挑战，显著降权但仍可召回
                float stalenessPenalty = memoryProperties.getStalenessRetrievalPenalty();
                lifecycleAdjustment -= stalenessPenalty;
                if (log.isDebugEnabled()) {
                    log.debug("retrieval: STALE_CANDIDATE 降权 entity={} penalty={}",
                            acc.entityId, stalenessPenalty);
                }
            } else if (lifecycleState == LifecycleState.COMPLETED) {
                lifecycleAdjustment -= memoryProperties.getHistoricalLifecyclePenalty();
            }

            float fusedScore = rrfScore + recencyBoost + impBoost + trustBoost + lifecycleAdjustment + lexicalBoost;

            // 时间衰减: 基于 updatedAt 与当前时间的天数差，线性衰减
            float timeDecayFactor = 1.0f;
            if (acc.updatedAt != null) {
                long daysSinceUpdate = Duration.between(acc.updatedAt, now).toDays();
                float decayRate = memoryProperties.getTimeDecayRate();
                float minDecay = memoryProperties.getMinTimeDecayFactor();
                timeDecayFactor = Math.max(minDecay, 1.0f - daysSinceUpdate * decayRate);
            }
            float finalScore = fusedScore * timeDecayFactor;

            var breakdown = new RetrievalResult.ScoreBreakdown(
                    acc.vectorScore, acc.vectorScore * adaptedWeights.vectorWeight(),
                    acc.ftsScore, lexicalBoost,
                    acc.graphScore, acc.graphScore * adaptedWeights.graphWeight(),
                    recencyBoost, impBoost,
                    trustBoost, lifecycleAdjustment);

            results.add(new RetrievalResult(
                    acc.entityId, acc.entityType, acc.name, acc.description,
                    finalScore, breakdown, acc.sourcePath,
                    acc.lastAccessedAt, acc.importanceScore, acc.validTo,
                    false, false, false));
        }

        // 6. 按 entity_id 去重（保留 fusedScore 最高），排序，截取 topK
        // 5.5 可选精排（Reranker 可用且记忆精排已启用时）
        // enabled=false 为强制关闭；enabled=true 时还需 RerankRouter 的记忆精排设置启用。
        var memRerankerDefaults = memoryProperties.getReranker();
        boolean memRerankEnabled = memRerankerDefaults.isEnabled()
                && rerankRouter.isMemoryRerankEnabled();
        if (memRerankEnabled) {
            try {
                var candidates = results.stream()
                        .map(r -> new RerankCandidate(
                                r.entityId(),
                                r.name() + " " + (r.description() != null ? r.description() : ""),
                                r.fusedScore()))
                        .toList();
                var reranked = rerankRouter.rerankMemoryCandidates(normalizedQuery, candidates);
                var resultMap = new HashMap<String, RetrievalResult>();
                for (var r : results) resultMap.put(r.entityId(), r);
                results = reranked.stream()
                        .map(rc -> {
                            var original = resultMap.get(rc.id());
                            if (original == null) {
                                throw new IllegalStateException("记忆精排返回未知候选 ID: " + rc.id());
                            }
                            return new RetrievalResult(
                                    original.entityId(), original.entityType(),
                                    original.name(), original.description(),
                                    (float) rc.score(), original.scoreBreakdown(),
                                    original.sourcePath(), original.lastAccessedAt(),
                                    original.importanceScore(), original.validTo(),
                                    original.isHistorical(), original.isStale(),
                                    original.needsRevalidation());
                        })
                        .collect(Collectors.toCollection(ArrayList::new));
                log.debug("记忆精排完成: input={}, output={}", candidates.size(), results.size());
            } catch (Exception e) {
                throw new IllegalStateException("记忆精排失败", e);
            }
        }

        // 6.5 按 entity_id 去重（保留 fusedScore 最高），排序，截取 topK
        Map<String, RetrievalResult> deduped = new HashMap<>();
        for (var result : results) {
            deduped.merge(result.entityId(), result,
                    (existing, incoming) -> incoming.fusedScore() > existing.fusedScore() ? incoming : existing);
        }

        List<RetrievalResult> finalResults = deduped.values().stream()
                .sorted()
                .limit(topK)
                .toList();

        // 兜底过期过滤：过滤掉 validTo 已过期的结果（SQL 层已过滤，此处防御向量路径遗漏）
        {
            int beforeExpiry = finalResults.size();
            finalResults = finalResults.stream()
                    .filter(r -> r.validTo() == null || r.validTo().isAfter(now))
                    .toList();
            if (log.isDebugEnabled() && beforeExpiry != finalResults.size()) {
                log.debug("混合检索: 过期过滤, 过滤前={}, 过滤后={}", beforeExpiry, finalResults.size());
            }
        }

        // 新增：fusedScore 阈值过滤
        float minScore = memoryProperties.getMinFusedScore();
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

        // Task 30：为最终结果批量打生命周期标注
        //   isHistorical = COMPLETED，isStale = REGENERATION_NEEDED，
        //   needsRevalidation = 实体存在任一 STALE provenance。
        finalResults = annotateLifecycle(finalResults, lifecycleStateMap);

        // 记录检索事件日志
        long durationMs = System.currentTimeMillis() - startTime;
        float topFused = finalResults.isEmpty() ? 0.0f : finalResults.getFirst().fusedScore();
        logRetrievalEvent(normalizedQuery, topK,
                vectorItems.size(), ftsResults.size(), graphResults.size(),
                deduped.size(), finalResults.size(), topFused, durationMs);

        log.debug("混合检索: query={}, 向量={}, FTS={}, 图={}, 融合结果={}, 耗时={}ms",
                normalizedQuery, vectorItems.size(), ftsResults.size(), graphResults.size(),
                finalResults.size(), durationMs);
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
    // --- 内部方法 ---

    /** 获取 CompletableFuture 结果，核心检索路径失败时直接暴露。 */
    private void awaitAll(CompletableFuture<?>... futures) {
        try {
            CompletableFuture.allOf(futures).join();
        } catch (CompletionException ignored) {
            // 各 future 的异常由 getRequired/waitRequired 带路径名重新抛出。
        }
    }

    private <T> List<T> getRequired(CompletableFuture<List<T>> future, String pathName) {
        try {
            List<T> result = future.join();
            if (result == null) {
                throw new IllegalStateException(pathName + " 返回 null");
            }
            if (result.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException(pathName + " 返回 null 元素");
            }
            return result;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("混合检索: " + pathName + " 路径失败", cause);
        }
    }

    private void validateRetrieveArguments(String query, int topK, RetrievalWeights weights) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("混合检索 query 不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("混合检索 topK 必须大于 0: " + topK);
        }
        Objects.requireNonNull(weights, "检索权重不能为空");
    }

    private Set<String> requireEntityIds(Set<String> ids, String label) {
        if (ids == null) {
            throw new IllegalStateException("混合检索: " + label + "返回 null");
        }
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("混合检索: " + label + "包含空实体 ID");
            }
            if (!id.equals(id.trim())) {
                throw new IllegalStateException("混合检索: " + label + "包含首尾空白实体 ID: " + id);
            }
        }
        return ids;
    }

    private void waitRequired(CompletableFuture<Void> future, String pathName) {
        try {
            future.join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("混合检索: " + pathName + " 路径失败", cause);
        }
    }

    /**
     * 向量检索结果转换为 RankedItem（批量查询实体详情补全元数据）。
     *
     * <p>Task 30：新增按 {@link LifecycleState#isRetrievable()} 过滤 —— EXPIRED/SUPERSEDED/
     * ARCHIVED/CANCELLED 实体即使向量库残留 embedding 也不会回放到上下文；同时把本路径已知的
     * {@code entityId → lifecycleState} 写入 {@code lifecycleStateMap} 供融合阶段复用，
     * 避免后续再次查库。</p>
     */
    private List<RankedItem> convertVectorResults(List<VectorSearchResult> vectorResults,
                                                  @Nullable MemoryReadFilter filter,
                                                  Map<String, LifecycleState> lifecycleStateMap) {
        if (vectorResults.isEmpty()) {
            return List.of();
        }

        List<RankedItem> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var vr : vectorResults) {
            ids.add(vr.entityId());
        }
        Map<String, TemporalEntity> entityMap = requiredEntityMap(filter != null
                ? semanticMemory.findByIds(ids, filter)
                : semanticMemory.findByIds(ids), "向量实体批量查询");

        for (var vr : vectorResults) {
            TemporalEntity entity = entityMap.get(vr.entityId());
            if (entity == null) {
                // findByIds 已按 is_current=1 过滤，归档实体本就不应注入上下文。
                continue;
            }
            LifecycleState state = entity.lifecycleState();
            if (!state.isRetrievable()) {
                // 默认过滤 EXPIRED/SUPERSEDED/ARCHIVED/CANCELLED — 防止向量层残留
                log.debug("混合检索: 向量路径过滤不可召回实体, id={}, state={}", entity.id(), state);
                continue;
            }
            lifecycleStateMap.put(entity.id(), state);
            items.add(new RankedItem(
                    entity.id(),
                    entity.type().name(),
                    entity.name(),
                    entity.description(),
                    vr.similarity(),
                    entity.lastAccessedAt(),
                    entity.importanceScore(),
                    entity.validTo(),
                    entity.updatedAt()));
        }
        return items;
    }

    private List<RankedItem> filterRankedItems(List<RankedItem> items, @Nullable MemoryReadFilter filter) {
        if (items.isEmpty() || filter == null || filter.isUnrestricted()) {
            return items;
        }
        Set<String> ids = items.stream()
                .map(RankedItem::entityId)
                .collect(Collectors.toSet());
        Set<String> readableIds = requiredEntityMap(
                semanticMemory.findByIds(ids, filter), "读取过滤实体批量查询").keySet();
        return items.stream()
                .filter(item -> readableIds.contains(item.entityId()))
                .toList();
    }

    /**
     * 对 FTS / 图遍历路径的结果应用生命周期过滤并把 {@code entityId → lifecycleState}
     * 写入共享映射。RankedItem 自身不携带 lifecycleState，所以需要（对未命中向量路径的 id）
     * 回查 {@code semanticMemory.findByIds} 补齐 —— SQL 层已经过滤了不可召回态，但为了
     * 统一拿到 state 打 isHistorical/isStale 标注，这里强制做一次。
     */
    private List<RankedItem> filterAndTrackLifecycle(List<RankedItem> items,
                                                     @Nullable MemoryReadFilter filter,
                                                     Map<String, LifecycleState> lifecycleStateMap) {
        if (items.isEmpty()) {
            return items;
        }
        // 收集尚未记入映射的 id — 避免重复查库
        Set<String> missingIds = items.stream()
                .map(RankedItem::entityId)
                .filter(id -> !lifecycleStateMap.containsKey(id))
                .collect(Collectors.toSet());
        if (!missingIds.isEmpty()) {
            Map<String, TemporalEntity> entityMap = requiredEntityMap(filter != null
                    ? semanticMemory.findByIds(missingIds, filter)
                    : semanticMemory.findByIds(missingIds), "生命周期实体批量查询");
            for (var entry : entityMap.entrySet()) {
                lifecycleStateMap.put(entry.getKey(), entry.getValue().lifecycleState());
            }
        }
        // 按 isRetrievable 过滤：SQL 侧已拦截不可召回态，此处是防御性补位
        return items.stream()
                .filter(item -> {
                    LifecycleState s = lifecycleStateMap.get(item.entityId());
                    // 不在映射 → findByIds 未命中（is_current=0 或被 read filter 剔除），直接丢弃
                    return s != null && s.isRetrievable();
                })
                .toList();
    }

    private void collectTrustScores(List<RankedItem> vectorItems,
                                    List<RankedItem> ftsItems,
                                    List<RankedItem> graphItems,
                                    @Nullable MemoryReadFilter filter,
                                    Map<String, Float> trustScoreMap) {
        Set<String> ids = new HashSet<>();
        for (var i : vectorItems) ids.add(i.entityId());
        for (var i : ftsItems) ids.add(i.entityId());
        for (var i : graphItems) ids.add(i.entityId());
        if (ids.isEmpty()) {
            return;
        }
        Map<String, TemporalEntity> entities = requiredEntityMap(filter != null
                ? semanticMemory.findByIds(ids, filter)
                : semanticMemory.findByIds(ids), "可信度实体批量查询");
        requireAllRequestedPresent(ids, entities, "可信度实体批量查询");
        for (var e : entities.values()) {
            if (!MemoryQualityPolicy.isPromptConsumable(e)) {
                throw new IllegalStateException("混合检索: 候选实体未通过消费质量门槛: " + e.id());
            }
            trustScoreMap.put(e.id(), e.trustScore());
        }
    }

    /** 对单路排名列表应用加权 RRF，累加到 accumulators。 */
    private Map<String, TemporalEntity> requiredEntityMap(Map<String, TemporalEntity> entityMap, String pathName) {
        if (entityMap == null) {
            throw new IllegalStateException("混合检索: " + pathName + " 返回 null");
        }
        for (var entry : entityMap.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalStateException("混合检索: " + pathName + " 返回空实体 ID");
            }
            if (!entry.getKey().equals(entry.getKey().trim())) {
                throw new IllegalStateException("混合检索: " + pathName + " 返回首尾空白实体 ID: "
                        + entry.getKey());
            }
            if (entry.getValue() == null) {
                throw new IllegalStateException("混合检索: " + pathName + " 返回 null 实体");
            }
            if (!entry.getKey().equals(entry.getValue().id())) {
                throw new IllegalStateException("混合检索: " + pathName + " 返回实体 ID 与 key 不一致: key="
                        + entry.getKey() + ", entityId=" + entry.getValue().id());
            }
        }
        return entityMap;
    }

    private static void requireAllRequestedPresent(Set<String> ids,
                                                   Map<String, TemporalEntity> entityMap,
                                                   String pathName) {
        var missingIds = ids.stream()
                .filter(id -> !entityMap.containsKey(id))
                .toList();
        if (!missingIds.isEmpty()) {
            throw new IllegalStateException("混合检索: " + pathName + " 缺少候选实体: " + missingIds);
        }
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

    /**
     * 批量更新结果实体的 access_count。
     *
     * <p>由 ContextAssembler 在 truncateByBudget 之后调用，
     * 仅对最终注入上下文的实体更新访问计数。</p>
     *
     * @param results 最终注入上下文的检索结果列表
     */
    public void updateAccessCounts(List<RetrievalResult> results) {
        try {
            for (var result : results) {
                semanticMemory.incrementAccessCount(result.entityId());
            }
        } catch (Exception e) {
            throw new IllegalStateException("混合检索: 批量更新 access_count 失败", e);
        }
    }

    /**
     * 记录检索事件日志到 retrieval_event_log 表。
     *
     * <p>写入失败表示检索审计持久化契约异常，直接暴露。</p>
     */
    private void logRetrievalEvent(String query, int topK,
                                    int vectorCount, int ftsCount, int graphCount,
                                    int fusedCount, int finalCount,
                                    float topFusedScore, long durationMs) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO retrieval_event_log(id, query, top_k, vector_count, fts_count, graph_count, fused_count, final_count, top_fused_score, duration_ms, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    query, topK,
                    vectorCount, ftsCount, graphCount,
                    fusedCount, finalCount,
                    topFusedScore, durationMs,
                    Instant.now().toString());
        } catch (Exception e) {
            throw new IllegalStateException("检索事件日志写入失败", e);
        }
    }

    /**
     * Task 30：按 {@link #provenanceRepository} 查询最终结果的 STALE provenance，
     * 并用 {@link RetrievalResult#withLifecycleAnnotations} 回写 isHistorical / isStale /
     * needsRevalidation 三个标注。
     *
     * <p>批量 IN 查询避免 N+1；查询失败直接暴露，避免把待再验证记忆伪装成普通结果。</p>
     */
    private List<RetrievalResult> annotateLifecycle(List<RetrievalResult> results,
                                                    Map<String, LifecycleState> lifecycleStateMap) {
        if (results.isEmpty()) {
            return results;
        }
        Set<String> staleIds = Set.of();
        Set<String> ids = results.stream()
                .map(RetrievalResult::entityId)
                .collect(Collectors.toSet());
        staleIds = provenanceRepository.findStaleEntityIds(ids);
        if (staleIds == null) {
            throw new IllegalStateException("混合检索: provenance 待复核查询返回 null");
        }
        final Set<String> finalStaleIds = staleIds;
        return results.stream()
                .map(r -> {
                    LifecycleState state = lifecycleStateMap.get(r.entityId());
                    boolean isHistorical = state == LifecycleState.COMPLETED;
                    boolean isStale = state == LifecycleState.REGENERATION_NEEDED
                            || state == LifecycleState.STALE_CANDIDATE;
                    boolean needsRevalidation = finalStaleIds.contains(r.entityId())
                            || state == LifecycleState.STALE_CANDIDATE;
                    if (!isHistorical && !isStale && !needsRevalidation) {
                        return r;
                    }
                    return r.withLifecycleAnnotations(isHistorical, isStale, needsRevalidation);
                })
                .toList();
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
        final Instant updatedAt;
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
            this.updatedAt = item.updatedAt();
        }
    }
}

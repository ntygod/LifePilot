package com.lifepilot.memory.retrieval;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.knowledge.rerank.RerankCandidate;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.procedural.IntentMatcher;
import com.lifepilot.memory.quality.MemoryQualityPolicy;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private final MemoryProperties memoryProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService virtualThreadExecutor;
    @Nullable
    private final RerankRouter rerankRouter;
    /**
     * V16 Task 30：批量查询 STALE provenance —— null 时回退为 needsRevalidation 全部 false，
     * 保留向后兼容以免破坏大量手工装配 HybridRetriever 的单测。
     */
    @Nullable
    private final MemoryProvenanceRepository provenanceRepository;

    /** 最近一次 retrieve() 中 L4 程序记忆匹配结果（线程安全，每次 retrieve 重置）。 */

    /**
     * 兼容构造器 — 旧 8 参签名，新生命周期闭环依赖（provenanceRepository）默认为 null。
     *
     * <p>保留给现有 MemoryAutoConfiguration / 精排测试 / 作用域测试等已手工装配的调用点。
     * 新调用点应走下面 9 参 canonical 构造器，以获得 needsRevalidation 标注能力。</p>
     */
    public HybridRetriever(VectorSearcher vectorSearcher,
                           FtsSearcher ftsSearcher,
                           GraphTraverser graphTraverser,
                           SemanticMemory semanticMemory,
                           @Nullable IntentMatcher intentMatcher,
                           MemoryProperties memoryProperties,
                           JdbcTemplate jdbcTemplate,
                           @Nullable RerankRouter rerankRouter) {
        this(vectorSearcher, ftsSearcher, graphTraverser, semanticMemory,
                intentMatcher, memoryProperties, jdbcTemplate, rerankRouter, null);
    }

    /**
     * V16 Task 30 canonical constructor — 额外接入 {@link MemoryProvenanceRepository}
     * 用于批量判定实体是否存在 STALE provenance。
     */
    public HybridRetriever(VectorSearcher vectorSearcher,
                           FtsSearcher ftsSearcher,
                           GraphTraverser graphTraverser,
                           SemanticMemory semanticMemory,
                           @Nullable IntentMatcher intentMatcher,
                           MemoryProperties memoryProperties,
                           JdbcTemplate jdbcTemplate,
                           @Nullable RerankRouter rerankRouter,
                           @Nullable MemoryProvenanceRepository provenanceRepository) {
        this.vectorSearcher = vectorSearcher;
        this.ftsSearcher = ftsSearcher;
        this.graphTraverser = graphTraverser;
        this.semanticMemory = semanticMemory;
        this.intentMatcher = intentMatcher;
        this.memoryProperties = memoryProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.rerankRouter = rerankRouter;
        this.provenanceRepository = provenanceRepository;
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
        long startTime = System.currentTimeMillis();

        // 1. 并行执行三路检索 + 可选 L4 意图匹配
        float minVecSim = memoryProperties.getRetrieval().getMinVectorSimilarity();
        // 向量路径 pre-filter：filter 生效时，预查合规实体 ID
        final Set<String> eligibleIds;
        if (filter != null && !filter.isUnrestricted()) {
            eligibleIds = semanticMemory.findEligibleEntityIds(filter);
        } else {
            eligibleIds = null;
        }
        var vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearcher.searchEntities(query, topK, minVecSim, eligibleIds), virtualThreadExecutor);
        var ftsFuture = CompletableFuture.supplyAsync(
                () -> ftsSearcher.search(query, topK), virtualThreadExecutor);
        var graphFuture = CompletableFuture.supplyAsync(
                () -> graphTraverser.traverse(query, topK, filter), virtualThreadExecutor);

        // L4: 并行执行 IntentMatcher（不参与 RRF 融合，与三路检索一起等待）
        CompletableFuture<Void> intentFuture = null;
        if (intentMatcher != null) {
            intentFuture = CompletableFuture.runAsync(() -> {
                try {
                    var matchOpt = intentMatcher.match(query);
                    matchOpt.ifPresent(match -> {
                        var template = match.template();
                        var context = "操作模板建议: %s (匹配度=%.2f, 成功率=%.2f, 步骤数=%d)"
                                .formatted(template.name(), match.score(),
                                        template.successRate(), template.steps().size());
                        log.debug("混合检索: L4 意图匹配命中, template={}, score={}",
                                template.name(), match.score());
                    });
                } catch (Exception e) {
                    log.warn("混合检索: L4 意图匹配失败, error={}", e.getMessage());
                }
            }, virtualThreadExecutor);
        }

        // 等待所有并行任务完成（三路检索 + 可选 L4 意图匹配）
        if (intentFuture != null) {
            CompletableFuture.allOf(vectorFuture, ftsFuture, graphFuture, intentFuture).join();
        } else {
            CompletableFuture.allOf(vectorFuture, ftsFuture, graphFuture).join();
        }

        // 收集结果，任一路失败时使用空列表
        List<VectorSearchResult> vectorResults = safeGet(vectorFuture, "向量检索");
        List<RankedItem> ftsResults = filterRankedItems(safeGet(ftsFuture, "全文搜索"), filter);
        List<RankedItem> graphResults = filterRankedItems(safeGet(graphFuture, "图遍历"), filter);

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
            float trustScore = trustScoreMap.getOrDefault(acc.entityId, 0.0f);
            float trustBoost = memoryProperties.getRetrieval().getTrustScoreBoostWeight() * trustScore;

            float lexicalBoost = acc.ftsScore >= EXACT_LEXICAL_MATCH_THRESHOLD
                    ? acc.ftsScore * adaptedWeights.ftsWeight()
                    : 0.0f;

            // 生命周期调整：REGENERATION_NEEDED 明确降权；COMPLETED 轻微降权（仍可召回）。
            LifecycleState lifecycleState = lifecycleStateMap.get(acc.entityId);
            float lifecycleAdjustment = 0.0f;
            if (lifecycleState == LifecycleState.REGENERATION_NEEDED) {
                lifecycleAdjustment -= memoryProperties.getRetrieval().getStaleLifecyclePenalty();
            } else if (lifecycleState == LifecycleState.COMPLETED) {
                lifecycleAdjustment -= memoryProperties.getRetrieval().getHistoricalLifecyclePenalty();
            }

            float fusedScore = rrfScore + recencyBoost + impBoost + trustBoost + lifecycleAdjustment + lexicalBoost;

            // 时间衰减: 基于 updatedAt 与当前时间的天数差，线性衰减
            float timeDecayFactor = 1.0f;
            if (acc.updatedAt != null) {
                long daysSinceUpdate = Duration.between(acc.updatedAt, now).toDays();
                float decayRate = memoryProperties.getRetrieval().getTimeDecayRate();
                float minDecay = memoryProperties.getRetrieval().getMinTimeDecayFactor();
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
        // enabled 语义变更为"强制关闭开关"：enabled=false → 强制禁用；enabled=true 或未配置 → Reranker 可用时自动启用
        var memRerankerDefaults = memoryProperties.getReranker();
        boolean memRerankEnabled = memRerankerDefaults.isEnabled()
                && rerankRouter != null
                && rerankRouter.isMemoryRerankEnabled();
        int memRerankTopK = rerankRouter != null
                ? rerankRouter.memoryTopK()
                : memRerankerDefaults.getTopK();
        if (rerankRouter != null && memRerankEnabled) {
            try {
                var candidates = results.stream()
                        .map(r -> new RerankCandidate(
                                r.entityId(),
                                r.name() + " " + (r.description() != null ? r.description() : ""),
                                r.fusedScore()))
                        .toList();
                var reranked = rerankRouter.rerankMemoryCandidates(query, candidates);
                var resultMap = new HashMap<String, RetrievalResult>();
                for (var r : results) resultMap.put(r.entityId(), r);
                results = reranked.stream()
                        .map(rc -> {
                            var original = resultMap.get(rc.id());
                            if (original == null) return null;
                            return new RetrievalResult(
                                    original.entityId(), original.entityType(),
                                    original.name(), original.description(),
                                    (float) rc.score(), original.scoreBreakdown(),
                                    original.sourcePath(), original.lastAccessedAt(),
                                    original.importanceScore(), original.validTo(),
                                    original.isHistorical(), original.isStale(),
                                    original.needsRevalidation());
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(ArrayList::new));
                log.debug("记忆精排完成: input={}, output={}", candidates.size(), results.size());
            } catch (Exception e) {
                log.warn("记忆精排失败，降级使用未精排结果: {}", e.getMessage());
            }
        } else if (memRerankEnabled && rerankRouter == null) {
            log.warn("记忆精排已启用但 Reranker Bean 不存在，跳过精排");
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

        // Task 30：为最终结果批量打生命周期标注
        //   isHistorical = COMPLETED，isStale = REGENERATION_NEEDED，
        //   needsRevalidation = 实体存在任一 STALE provenance。
        finalResults = annotateLifecycle(finalResults, lifecycleStateMap);

        // 记录检索事件日志
        long durationMs = System.currentTimeMillis() - startTime;
        float topFused = finalResults.isEmpty() ? 0.0f : finalResults.getFirst().fusedScore();
        logRetrievalEvent(query, topK,
                vectorItems.size(), ftsResults.size(), graphResults.size(),
                deduped.size(), finalResults.size(), topFused, durationMs);

        log.debug("混合检索: query={}, 向量={}, FTS={}, 图={}, 融合结果={}, 耗时={}ms",
                query, vectorItems.size(), ftsResults.size(), graphResults.size(),
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
    /**
     * 兼容旧写入回调入口。检索 miss 不再设置全局空库缓存，因此这里保留为空实现。
     */
    public void resetEmptyFlag() {
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
        try {
            Set<String> ids = new HashSet<>();
            for (var vr : vectorResults) {
                if (vr.entityId() != null && !vr.entityId().isBlank()) {
                    ids.add(vr.entityId());
                }
            }
            Map<String, TemporalEntity> entityMap = filter != null
                    ? semanticMemory.findByIds(ids, filter)
                    : semanticMemory.findByIds(ids);

            for (var vr : vectorResults) {
                TemporalEntity entity = entityMap.get(vr.entityId());
                if (entity == null) {
                    // 未命中 findByIds 的向量结果不再以 UNKNOWN 回退：
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
        } catch (Exception e) {
            log.warn("混合检索: 向量结果批量转换失败, error={}", e.getMessage());
        }
        return items;
    }

    private List<RankedItem> filterRankedItems(List<RankedItem> items, @Nullable MemoryReadFilter filter) {
        if (items.isEmpty() || filter == null || filter.isUnrestricted()) {
            return items;
        }
        Set<String> ids = items.stream()
                .map(RankedItem::entityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<String> readableIds = semanticMemory.findByIds(ids, filter).keySet();
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
                .filter(Objects::nonNull)
                .filter(id -> !lifecycleStateMap.containsKey(id))
                .collect(Collectors.toSet());
        if (!missingIds.isEmpty()) {
            try {
                Map<String, TemporalEntity> entityMap = filter != null
                        ? semanticMemory.findByIds(missingIds, filter)
                        : semanticMemory.findByIds(missingIds);
                for (var entry : entityMap.entrySet()) {
                    lifecycleStateMap.put(entry.getKey(), entry.getValue().lifecycleState());
                }
            } catch (Exception e) {
                log.warn("混合检索: 生命周期补齐查询失败, error={}", e.getMessage());
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
        try {
            Set<String> ids = new HashSet<>();
            for (var i : vectorItems) ids.add(i.entityId());
            for (var i : ftsItems) ids.add(i.entityId());
            for (var i : graphItems) ids.add(i.entityId());
            if (ids.isEmpty()) {
                return;
            }
            Map<String, TemporalEntity> entities = filter != null
                    ? semanticMemory.findByIds(ids, filter)
                    : semanticMemory.findByIds(ids);
            for (var e : entities.values()) {
                if (e == null) continue;
                // 防御：质量门槛在消费侧过滤，但排序阶段也避免被 UNVERIFIED 拖动
                if (!MemoryQualityPolicy.isPromptConsumable(e)) continue;
                trustScoreMap.put(e.id(), e.trustScore());
            }
        } catch (Exception e) {
            log.debug("混合检索: trustScore 收集失败，降级为无加成: {}", e.getMessage());
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
            log.warn("混合检索: 批量更新 access_count 失败, error={}", e.getMessage());
        }
    }

    /**
     * 记录检索事件日志到 retrieval_event_log 表。
     *
     * <p>写入失败时 WARN 日志降级，不影响检索结果返回。</p>
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
            log.warn("检索事件日志写入失败: error={}", e.getMessage());
        }
    }

    /**
     * Task 30：按 {@link #provenanceRepository} 查询最终结果的 STALE provenance，
     * 并用 {@link RetrievalResult#withLifecycleAnnotations} 回写 isHistorical / isStale /
     * needsRevalidation 三个标注。
     *
     * <p>行为：
     * <ul>
     *   <li>{@code provenanceRepository == null} 时 needsRevalidation 全部 false（向后兼容）。</li>
     *   <li>批量 IN 查询避免 N+1。</li>
     *   <li>查询失败降级：打 WARN 日志 + needsRevalidation 置 false，不影响整体检索。</li>
     * </ul>
     */
    private List<RetrievalResult> annotateLifecycle(List<RetrievalResult> results,
                                                    Map<String, LifecycleState> lifecycleStateMap) {
        if (results.isEmpty()) {
            return results;
        }
        Set<String> staleIds = Set.of();
        if (provenanceRepository != null) {
            try {
                Set<String> ids = results.stream()
                        .map(RetrievalResult::entityId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                staleIds = provenanceRepository.findStaleEntityIds(ids);
            } catch (Exception e) {
                log.warn("混合检索: 批量 STALE provenance 查询失败，降级为无标注, error={}", e.getMessage());
                staleIds = Set.of();
            }
        }
        final Set<String> finalStaleIds = staleIds;
        return results.stream()
                .map(r -> {
                    LifecycleState state = lifecycleStateMap.get(r.entityId());
                    boolean isHistorical = state == LifecycleState.COMPLETED;
                    boolean isStale = state == LifecycleState.REGENERATION_NEEDED;
                    boolean needsRevalidation = finalStaleIds.contains(r.entityId());
                    if (!isHistorical && !isStale && !needsRevalidation) {
                        return r; // 保持默认（兼容构造器 false / false / false）
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

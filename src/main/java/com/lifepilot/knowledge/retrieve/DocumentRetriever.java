package com.lifepilot.knowledge.retrieve;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.index.FtsIndexer;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.rerank.router.RerankRouter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;


/**
 * 文档混合检索服务 — 向量 + FTS5 + 自适应 RRF 融合 + 上下文窗口扩展 + 可选 Reranker。
 *
 * <p>并行执行向量相似度搜索和 FTS5 全文搜索，通过自适应 Reciprocal Rank Fusion（RRF）
 * 融合两路结果，支持上下文窗口扩展和可选 Reranker 精排。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(DocumentRetriever.class);

    @Nullable
    private final VectorIndexer vectorIndexer;
    private final FtsIndexer ftsIndexer;
    @Nullable
    private final RerankRouter rerankRouter;
    @Nullable
    private final QueryEnhancer queryEnhancer;
    @Nullable
    private final GraphKnowledgeSearcher graphSearcher;
    @Nullable
    private final RetrievalQualityEvaluator qualityEvaluator;
    private final DocumentChunkRepository chunkRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeBaseProperties.Retrieval config;
    private final ChunkDeduplicator chunkDeduplicator;
    private final ApplicationEventPublisher eventPublisher;
    private final Timer retrievalTimer;
    private final DistributionSummary topScoreSummary;

    /**
     * 构造文档混合检索服务。
     *
     * @param vectorIndexer          向量索引服务
     * @param ftsIndexer             FTS5 索引服务
     * @param rerankRouter           精排路由器（可选）
     * @param queryEnhancer          查询增强器（可选）
     * @param graphSearcher          图谱检索服务（可选）
     * @param qualityEvaluator       检索质量评估器（可选，Corrective RAG）
     * @param chunkRepository        分块数据访问层（上下文窗口扩展）
     * @param kbRepository           知识库数据访问层（per-KB 模型解析）
     * @param config                 检索配置
     * @param chunkDeduplicator      检索结果去重器
     * @param meterRegistry          Micrometer 指标注册器
     * @param eventPublisher         Spring 事件发布器
     */
    public DocumentRetriever(@Nullable VectorIndexer vectorIndexer, FtsIndexer ftsIndexer,
                              @Nullable RerankRouter rerankRouter,
                              @Nullable QueryEnhancer queryEnhancer,
                              @Nullable GraphKnowledgeSearcher graphSearcher,
                              @Nullable RetrievalQualityEvaluator qualityEvaluator,
                              DocumentChunkRepository chunkRepository,
                              KnowledgeBaseRepository kbRepository,
                              KnowledgeBaseProperties.Retrieval config,
                              ChunkDeduplicator chunkDeduplicator,
                              MeterRegistry meterRegistry,
                              ApplicationEventPublisher eventPublisher) {
        this.vectorIndexer = vectorIndexer;
        this.ftsIndexer = ftsIndexer;
        this.rerankRouter = rerankRouter;
        this.queryEnhancer = queryEnhancer;
        this.graphSearcher = graphSearcher;
        this.qualityEvaluator = qualityEvaluator;
        this.chunkRepository = chunkRepository;
        this.kbRepository = kbRepository;
        this.config = config;
        this.chunkDeduplicator = chunkDeduplicator;
        this.eventPublisher = eventPublisher;
        this.retrievalTimer = meterRegistry.timer("knowledge.retrieval.duration");
        this.topScoreSummary = meterRegistry.summary("knowledge.retrieval.top_score");
        log.info("DocumentRetriever 初始化完成: topK={}, rrfK={}, reranker={}, queryEnhancer={}, graph={}, correction={}, contextWindowSize={}, dedup={}",
                config.defaultTopK(), config.rrfK(),
                rerankRouter != null ? "启用" : "未启用",
                queryEnhancer != null ? "启用" : "未启用",
                graphSearcher != null && config.graphEnabled() ? "启用" : "未启用",
                qualityEvaluator != null && config.correctionEnabled() ? "启用" : "未启用",
                config.contextWindowSize(),
                config.deduplicationThreshold());
    }

    /**
     * 混合检索文档分块。
     *
     * @param query 查询文本
     * @param kbIds 知识库 ID 列表
     * @param topK  返回数量
     * @return 检索结果列表（按相关性降序）
     */
    public List<DocumentSearchResult> retrieve(String query, List<String> kbIds, int topK) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        return retrieveByScopes(query, kbIds.stream()
                .map(KnowledgeSearchScope::new)
                .toList(), topK);
    }

    /**
     * 混合检索文档分块，并按知识域范围过滤。
     */
    public List<DocumentSearchResult> retrieveByScopes(String query, List<KnowledgeSearchScope> scopes, int topK) {
        if (query == null || query.isBlank() || scopes == null || scopes.isEmpty()) {
            return List.of();
        }

        return retrievalTimer.record(() -> doRetrieveByScopes(query, scopes, topK));
    }

    /**
     * 实际检索逻辑 — 被 Micrometer Timer 包裹。
     */
    private List<DocumentSearchResult> doRetrieveByScopes(String query, List<KnowledgeSearchScope> scopes, int topK) {
        return doRetrieveByScopes(query, scopes, topK, false);
    }

    /**
     * 核心检索逻辑，支持跳过 Corrective RAG 以避免递归。
     *
     * @param query          查询文本
     * @param scopes         知识域范围
     * @param topK           返回数量
     * @param skipCorrection 是否跳过 Corrective RAG（重试时为 true）
     */
    private List<DocumentSearchResult> doRetrieveByScopes(String query, List<KnowledgeSearchScope> scopes,
                                                           int topK, boolean skipCorrection) {
        var startTime = Instant.now();
        long startMs = System.currentTimeMillis();
        int effectiveTopK = topK > 0 ? topK : config.defaultTopK();
        // 有精排时拉取更多候选给 Reranker 重排序
        int candidateK = (rerankRouter != null && rerankRouter.isKnowledgeRerankEnabled())
                ? effectiveTopK * 5
                : effectiveTopK * 3;

        // 0. 解析 per-KB 模型配置
        String embeddingModel = resolveEmbeddingModel(scopes);
        String rerankerModel = resolveRerankerModel(scopes);

        // 1. 查询增强与搜索并行 — 增强不阻塞主搜索，模型慢（如思考模型）也不影响检索延迟
        var primaryQuery = new QueryEnhancer.EnhancedQuery(query, List.of(), Optional.empty());
        var enhanceFuture = new CompletableFuture<QueryEnhancer.EnhancedQuery>();
        Thread.ofVirtual().start(() -> {
            try {
                enhanceFuture.complete(enhanceQuery(query));
            } catch (Exception e) {
                enhanceFuture.complete(primaryQuery);
            }
        });

        // 2. 用原始查询立即并行搜索（不等待增强结果）
        List<DocumentSearchResult> vectorResults;
        List<DocumentSearchResult> ftsResults;
        List<DocumentSearchResult> graphResults;
        long vectorMs, ftsMs, graphMs;

        record Timed<T>(T result, long durationMs) {}

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var vectorFuture = CompletableFuture.supplyAsync(() -> {
                long t0 = System.currentTimeMillis();
                var r = safeVectorSearch(primaryQuery, scopes, candidateK, embeddingModel);
                return new Timed<>(r, System.currentTimeMillis() - t0);
            }, executor);
            var ftsFuture = CompletableFuture.supplyAsync(() -> {
                long t0 = System.currentTimeMillis();
                var r = safeFtsSearch(primaryQuery, scopes, candidateK);
                return new Timed<>(r, System.currentTimeMillis() - t0);
            }, executor);
            var graphFuture = CompletableFuture.supplyAsync(() -> {
                long t0 = System.currentTimeMillis();
                var r = graphSearch(primaryQuery, scopes, candidateK);
                return new Timed<>(r, System.currentTimeMillis() - t0);
            }, executor);

            var vectorTimed = vectorFuture.join();
            vectorResults = new ArrayList<>(vectorTimed.result());
            vectorMs = vectorTimed.durationMs();

            var ftsTimed = ftsFuture.join();
            ftsResults = new ArrayList<>(ftsTimed.result());
            ftsMs = ftsTimed.durationMs();

            var graphTimed = graphFuture.join();
            graphResults = graphTimed.result();
            graphMs = graphTimed.durationMs();
        }

        // 2.5 主搜索完成后，检查增强是否也完成了（不额外等待）
        var enhanced = enhanceFuture.getNow(primaryQuery);
        if (!enhanced.rewrittenQueries().isEmpty()) {
            log.debug("查询增强已完成，执行补充搜索: rewrites={}", enhanced.rewrittenQueries().size());
            supplementWithRewrites(enhanced.rewrittenQueries(), scopes, candidateK,
                    embeddingModel, vectorResults, ftsResults);
        } else if (enhanced.hydeEmbedding().isPresent() && vectorIndexer != null) {
            try {
                var hydeResults = vectorIndexer.searchByEmbeddingByScopes(
                        enhanced.hydeEmbedding().get(), scopes, candidateK);
                mergeResults(vectorResults, hydeResults);
            } catch (Exception e) {
                log.warn("HyDE 向量搜索失败: {}", e.getMessage());
            }
        }

        // 3. 自适应 RRF 融合（向量 + FTS + 图谱三路）
        long fusionStart = System.currentTimeMillis();
        var fused = adaptiveRrfFusion(vectorResults, ftsResults, graphResults, effectiveTopK);
        long fusionMs = System.currentTimeMillis() - fusionStart;

        // 3.3 检索结果去重
        var beforeDedup = fused.size();
        fused = chunkDeduplicator.deduplicate(fused);
        int deduplicatedCount = beforeDedup - fused.size();

        // 3.5 Parent-Child 解析：如果命中的是 child 块，解析并返回对应 parent 块
        fused = resolveParentChunks(fused);

        // 4. 最低相关性阈值过滤
        if (config.minRelevanceScore() > 0.0) {
            fused = fused.stream()
                    .filter(r -> r.score() >= config.minRelevanceScore())
                    .toList();
        }

        // 5. 可选精排 — 在上下文扩展之前执行，基于原始匹配内容评分
        long rerankMs = 0;
        if (rerankRouter != null && rerankRouter.isKnowledgeRerankEnabled() && !fused.isEmpty()) {
            long rerankStart = System.currentTimeMillis();
            try {
                var reranked = rerankRouter.rerankDocuments(query, fused, effectiveTopK, rerankerModel);
                log.debug("精排完成: input={}, output={}", fused.size(), reranked.size());
                fused = reranked;
            } catch (Exception e) {
                log.warn("Reranker 不可用，跳过精排: {}", e.getMessage());
            }
            rerankMs = System.currentTimeMillis() - rerankStart;
        }

        // 6. 上下文窗口扩展 — 在精排之后执行，扩展是展示层增强不影响排序
        if (config.contextWindowSize() > 0) {
            fused = expandContextWindow(fused);
        }

        // 7. Corrective RAG（可选）— 评估检索质量，必要时改写查询重试
        if (!skipCorrection && qualityEvaluator != null && config.correctionEnabled() && !fused.isEmpty()) {
            var eval = qualityEvaluator.evaluate(query, fused);
            if (eval.quality() == RetrievalQualityEvaluator.Quality.LOW
                    && eval.suggestedRewrite().isPresent()) {
                log.info("Corrective RAG: quality={}, rewrite={}", eval.quality(), eval.suggestedRewrite().get());
                // 使用改写查询重试检索（skipCorrection=true 避免递归）
                fused = doRetrieveByScopes(eval.suggestedRewrite().get(), scopes, topK, true);
            } else if (eval.quality() == RetrievalQualityEvaluator.Quality.VERY_LOW) {
                log.info("Corrective RAG: quality=VERY_LOW, 标记低置信度");
                // 标记所有结果为低置信度
                fused = fused.stream().map(r -> new DocumentSearchResult(
                        r.chunkId(), r.documentId(), r.knowledgeBaseId(),
                        r.content(), r.contextPrefix(), r.headingHierarchy(),
                        r.score(), r.sourcePath(),
                        mergeMeta(r.metadata(), "retrieval_confidence", "very_low"),
                        r.scoreBreakdown(), r.expandedContent(),
                        r.sourceType()
                )).toList();
            }
        }

        // 8. 构建并发布 RetrievalTrace
        long elapsed = System.currentTimeMillis() - startMs;
        double topScore = fused.isEmpty() ? 0.0 : fused.getFirst().score();
        var trace = RetrievalTrace.of(UUID.randomUUID().toString(), query, startTime,
                elapsed, vectorMs, ftsMs, graphMs, fusionMs, rerankMs,
                vectorResults.size(), ftsResults.size(), graphResults.size(),
                deduplicatedCount, fused.size(), fused.size(), topScore);
        eventPublisher.publishEvent(trace);
        topScoreSummary.record(topScore);

        log.debug("检索完成: vector={}, fts={}, graph={}, 去重移除={}, 融合后={}, 最终={}, 耗时={}ms",
                vectorResults.size(), ftsResults.size(), graphResults.size(),
                deduplicatedCount, fused.size(), fused.size(), elapsed);

        return List.copyOf(fused);
    }

    /**
     * 解析跨知识库的 embeddingModel。
     * 所有 KB 的 embeddingModel 一致时返回该模型，否则返回 null（回退到默认）。
     */
    private @Nullable String resolveEmbeddingModel(List<KnowledgeSearchScope> scopes) {
        var models = extractKnowledgeBaseIds(scopes).stream()
                .map(id -> kbRepository.findById(id).map(KnowledgeBase::embeddingModel).orElse(null))
                .filter(m -> m != null && !m.isBlank() && !"default".equals(m))
                .distinct()
                .toList();
        if (models.size() == 1) return models.getFirst();
        if (models.size() > 1) {
            log.warn("跨知识库搜索检测到不同 embeddingModel，回退到默认: models={}", models);
        }
        return null;
    }

    /**
     * 解析跨知识库的 rerankerModel。
     * 所有 KB 的 rerankerModel 一致时返回该模型，否则返回 null（回退到默认）。
     */
    private @Nullable String resolveRerankerModel(List<KnowledgeSearchScope> scopes) {
        var models = extractKnowledgeBaseIds(scopes).stream()
                .map(id -> kbRepository.findById(id).map(KnowledgeBase::rerankerModel).orElse(null))
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .toList();
        if (models.size() == 1) return models.getFirst();
        if (models.size() > 1) {
            log.warn("跨知识库搜索检测到不同 rerankerModel，回退到默认: models={}", models);
        }
        return null;
    }

    /**
     * 增强查询（如果 QueryEnhancer 可用）。
     */
    private QueryEnhancer.EnhancedQuery enhanceQuery(String query) {
        if (queryEnhancer != null) {
            return queryEnhancer.enhance(query);
        }
        return new QueryEnhancer.EnhancedQuery(query, List.of(), Optional.empty());
    }

    /**
     * 自适应 RRF 融合 — 根据向量 Top-1 分数动态调整权重，支持三路融合（向量 + FTS + 图谱）。
     *
     * <p>向量 Top-1 分数 &lt; lowConfidenceThreshold 时提升 FTS 权重。
     * 图谱检索权重独立于向量/FTS 自适应调整，始终使用配置值。
     */
    List<DocumentSearchResult> adaptiveRrfFusion(List<DocumentSearchResult> vectorResults,
                                                  List<DocumentSearchResult> ftsResults,
                                                  List<DocumentSearchResult> graphResults,
                                                  int topK) {
        int k = config.rrfK();

        // 自适应权重调整 — 连续插值，消除阈值跳变
        double vectorWeight = config.vectorWeight();
        double ftsWeight = config.ftsWeight();
        double graphWeight = config.graphEnabled() ? config.graphWeight() : 0.0;
        if (!vectorResults.isEmpty()) {
            double topVectorScore = vectorResults.getFirst().score();
            double threshold = config.lowConfidenceThreshold();
            if (topVectorScore < threshold) {
                // 线性插值: score=0 → vectorWeight=0.3/ftsWeight=0.7, score=threshold → 原始权重
                double alpha = Math.max(0.0, topVectorScore / threshold);
                vectorWeight = 0.3 + alpha * (config.vectorWeight() - 0.3);
                ftsWeight = 0.7 - alpha * (0.7 - config.ftsWeight());
                log.debug("自适应权重插值: topScore={}, alpha={}, vectorWeight={}, ftsWeight={}",
                        topVectorScore, alpha, vectorWeight, ftsWeight);
            }
        }

        // chunkId -> 各路原始分数
        var vectorScoreMap = new HashMap<String, Double>();
        var ftsScoreMap = new HashMap<String, Double>();
        var graphScoreMap = new HashMap<String, Double>();
        var rrfScoreMap = new LinkedHashMap<String, Double>();
        var resultMap = new HashMap<String, DocumentSearchResult>();

        // 向量搜索 RRF 分数
        for (int rank = 0; rank < vectorResults.size(); rank++) {
            var result = vectorResults.get(rank);
            double rrfScore = vectorWeight / (k + rank + 1);
            rrfScoreMap.merge(result.chunkId(), rrfScore, Double::sum);
            resultMap.putIfAbsent(result.chunkId(), result);
            vectorScoreMap.put(result.chunkId(), result.score());
        }

        // FTS5 搜索 RRF 分数
        for (int rank = 0; rank < ftsResults.size(); rank++) {
            var result = ftsResults.get(rank);
            double rrfScore = ftsWeight / (k + rank + 1);
            rrfScoreMap.merge(result.chunkId(), rrfScore, Double::sum);
            resultMap.putIfAbsent(result.chunkId(), result);
            ftsScoreMap.put(result.chunkId(), result.score());
        }

        // 图谱搜索 RRF 分数
        if (graphWeight > 0.0) {
            for (int rank = 0; rank < graphResults.size(); rank++) {
                var result = graphResults.get(rank);
                double rrfScore = graphWeight / (k + rank + 1);
                rrfScoreMap.merge(result.chunkId(), rrfScore, Double::sum);
                resultMap.putIfAbsent(result.chunkId(), result);
                graphScoreMap.put(result.chunkId(), result.score());
            }
        }

        // 按 RRF 分数降序排序，构建 ScoreBreakdown
        return rrfScoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    var original = resultMap.get(entry.getKey());
                    var breakdown = new ScoreBreakdown(
                            vectorScoreMap.getOrDefault(entry.getKey(), 0.0),
                            ftsScoreMap.getOrDefault(entry.getKey(), 0.0),
                            graphScoreMap.getOrDefault(entry.getKey(), 0.0),
                            entry.getValue(),
                            Optional.empty()
                    );
                    return new DocumentSearchResult(
                            original.chunkId(),
                            original.documentId(),
                            original.knowledgeBaseId(),
                            original.content(),
                            original.contextPrefix(),
                            original.headingHierarchy(),
                            entry.getValue(),
                            "fused",
                            original.metadata(),
                            Optional.of(breakdown),
                            Optional.empty(),
                            original.sourceType()
                    );
                })
                .toList();
    }

    /**
     * Parent-Child 解析 — 如果命中的是 child 块，查找并返回对应 parent 块。
     *
     * <p>同一 parent 下多个 child 命中时，取最高分 child 的分数，只返回一次 parent。
     * 非 child 块（level=0 或无 parentChunkId）直接保留。
     */
    private List<DocumentSearchResult> resolveParentChunks(List<DocumentSearchResult> results) {
        // 收集需要解析 parent 的 child 结果
        var parentScoreMap = new LinkedHashMap<String, Double>();    // parentId → 最高分
        var parentSourceMap = new LinkedHashMap<String, DocumentSearchResult>(); // parentId → 最高分的 child result
        var parentChildCountMap = new LinkedHashMap<String, Integer>(); // parentId → child 命中次数
        var directResults = new ArrayList<DocumentSearchResult>();

        var parentIdMap = chunkRepository.findParentChunkIdsByChunkIds(
                results.stream().map(DocumentSearchResult::chunkId).toList());
        for (var result : results) {
            var parentId = parentIdMap.get(result.chunkId());
            if (parentId != null) {
                if (!parentScoreMap.containsKey(parentId) || result.score() > parentScoreMap.get(parentId)) {
                    parentScoreMap.put(parentId, result.score());
                    parentSourceMap.put(parentId, result);
                }
                parentChildCountMap.merge(parentId, 1, Integer::sum);
            } else {
                directResults.add(result);
            }
        }

        if (parentScoreMap.isEmpty()) {
            return results; // 无 child 块，直接返回
        }

        // 批量查询 parent 块
        var parentChunks = chunkRepository.findByIds(new ArrayList<>(parentScoreMap.keySet()));
        var parentMap = new LinkedHashMap<String, DocumentChunk>();
        for (var chunk : parentChunks) {
            parentMap.put(chunk.id(), chunk);
        }

        // 构建 parent 结果
        var resolved = new ArrayList<DocumentSearchResult>(directResults);
        for (var entry : parentScoreMap.entrySet()) {
            var parentChunk = parentMap.get(entry.getKey());
            var childResult = parentSourceMap.get(entry.getKey());
            if (parentChunk != null) {
                int childHits = parentChildCountMap.getOrDefault(entry.getKey(), 1);
                double coverageBoost = Math.min(0.2, (childHits - 1) * 0.05);
                double finalScore = entry.getValue() * (1.0 + coverageBoost);
                resolved.add(new DocumentSearchResult(
                        parentChunk.id(),
                        childResult.documentId(),
                        childResult.knowledgeBaseId(),
                        parentChunk.content(),
                        parentChunk.contextPrefix(),
                        parentChunk.headingHierarchy(),
                        finalScore,
                        childResult.sourcePath(),
                        childResult.metadata(),
                        childResult.scoreBreakdown(),
                        Optional.empty(),
                        childResult.sourceType()
                ));
            }
        }

        // 按分数降序排列
        resolved.sort(Comparator.comparingDouble(DocumentSearchResult::score).reversed());
        log.debug("Parent-Child 解析: child命中={}, 解析后parent={}, 直接结果={}",
                parentScoreMap.size(), parentMap.size(), directResults.size());
        return resolved;
    }

    /**
     * 上下文窗口扩展 — 对每个命中分块，查询同文档的相邻分块并拼接。
     */
    private List<DocumentSearchResult> expandContextWindow(List<DocumentSearchResult> results) {
        int windowSize = config.contextWindowSize();
        var expanded = new ArrayList<DocumentSearchResult>(results.size());

        for (var result : results) {
            try {
                // 按分块 ID 精确查询 chunkIndex，避免加载同文档所有分块
                var hitIndexOpt = chunkRepository.findChunkIndexById(result.chunkId());
                if (hitIndexOpt.isEmpty()) {
                    expanded.add(result);
                    continue;
                }
                int hitIndex = hitIndexOpt.getAsInt();

                int fromIndex = Math.max(0, hitIndex - windowSize);
                int toIndex = hitIndex + windowSize;
                var windowChunks = chunkRepository.findByDocumentIdAndChunkIndexRange(
                        result.documentId(), fromIndex, toIndex);

                // 拼接内容，在命中分块前后插入标记
                var sb = new StringBuilder();
                for (var chunk : windowChunks) {
                    if (chunk.chunkIndex() == hitIndex) {
                        sb.append("<!-- hit-start -->\n");
                        sb.append(chunk.content());
                        sb.append("\n<!-- hit-end -->\n");
                    } else {
                        sb.append(chunk.content()).append("\n");
                    }
                }

                expanded.add(new DocumentSearchResult(
                        result.chunkId(),
                        result.documentId(),
                        result.knowledgeBaseId(),
                        result.content(),
                        result.contextPrefix(),
                        result.headingHierarchy(),
                        result.score(),
                        result.sourcePath(),
                        result.metadata(),
                        result.scoreBreakdown(),
                        Optional.of(sb.toString().trim()),
                        result.sourceType()
                ));
            } catch (Exception e) {
                log.warn("上下文窗口扩展失败，返回原始分块: chunkId={}, error={}",
                        result.chunkId(), e.getMessage());
                expanded.add(result);
            }
        }
        return expanded;
    }

    /**
     * 安全执行向量搜索，支持 HyDE 和 Rewrite 模式。
     */
    private List<DocumentSearchResult> safeVectorSearch(QueryEnhancer.EnhancedQuery enhanced,
                                                         List<KnowledgeSearchScope> scopes, int topK,
                                                         @Nullable String embeddingModel) {
        if (vectorIndexer == null) {
            return List.of();
        }
        try {
            // HyDE 模式：使用假设文档 Embedding
            if (enhanced.hydeEmbedding().isPresent()) {
                return vectorIndexer.searchByEmbeddingByScopes(enhanced.hydeEmbedding().get(), scopes, topK);
            }

            // Rewrite 模式：对每个改写查询分别检索，合并去重
            if (!enhanced.rewrittenQueries().isEmpty()) {
                var allResults = new LinkedHashMap<String, DocumentSearchResult>();
                // 先检索原始查询
                for (var r : vectorIndexer.searchSimilarByScopes(enhanced.primaryQuery(), scopes, topK, embeddingModel)) {
                    allResults.putIfAbsent(r.chunkId(), r);
                }
                // 再检索改写查询
                for (var rewrite : enhanced.rewrittenQueries()) {
                    for (var r : vectorIndexer.searchSimilarByScopes(rewrite, scopes, topK, embeddingModel)) {
                        allResults.putIfAbsent(r.chunkId(), r);
                    }
                }
                return new ArrayList<>(allResults.values());
            }

            return vectorIndexer.searchSimilarByScopes(enhanced.primaryQuery(), scopes, topK, embeddingModel);
        } catch (Exception e) {
            log.warn("向量搜索失败，降级跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 安全执行 FTS5 搜索，支持 Rewrite 模式。
     */
    private List<DocumentSearchResult> safeFtsSearch(QueryEnhancer.EnhancedQuery enhanced,
                                                      List<KnowledgeSearchScope> scopes, int topK) {
        try {
            if (!enhanced.rewrittenQueries().isEmpty()) {
                var allResults = new LinkedHashMap<String, DocumentSearchResult>();
                for (var r : ftsIndexer.searchByScopes(enhanced.primaryQuery(), scopes, topK)) {
                    allResults.putIfAbsent(r.chunkId(), r);
                }
                for (var rewrite : enhanced.rewrittenQueries()) {
                    for (var r : ftsIndexer.searchByScopes(rewrite, scopes, topK)) {
                        allResults.putIfAbsent(r.chunkId(), r);
                    }
                }
                return new ArrayList<>(allResults.values());
            }
            return ftsIndexer.searchByScopes(enhanced.primaryQuery(), scopes, topK);
        } catch (Exception e) {
            log.warn("FTS5 搜索失败，降级跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 执行图谱搜索。
     */
    private List<DocumentSearchResult> graphSearch(QueryEnhancer.EnhancedQuery enhanced,
                                                   List<KnowledgeSearchScope> scopes, int topK) {
        if (graphSearcher == null || !config.graphEnabled()) {
            return List.of();
        }
        return graphSearcher.search(enhanced.primaryQuery(), scopes, topK);
    }

    private List<String> extractKnowledgeBaseIds(List<KnowledgeSearchScope> scopes) {
        return scopes.stream()
                .map(KnowledgeSearchScope::knowledgeBaseId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 用改写查询补充搜索结果 — 仅搜索改写，去重后合并到主搜索结果中。
     *
     * <p>前置条件：主搜索使用的 primaryQuery 不包含 rewrittenQueries（为空列表），
     * 因此 safeVectorSearch/safeFtsSearch 内部的 rewrite 分支不会被触发，不会双重搜索。
     */
    private void supplementWithRewrites(List<String> rewrites, List<KnowledgeSearchScope> scopes,
                                         int candidateK, @Nullable String embeddingModel,
                                         List<DocumentSearchResult> vectorResults,
                                         List<DocumentSearchResult> ftsResults) {
        var existingVectorIds = vectorResults.stream().map(DocumentSearchResult::chunkId).collect(Collectors.toSet());
        var existingFtsIds = ftsResults.stream().map(DocumentSearchResult::chunkId).collect(Collectors.toSet());

        for (var rewrite : rewrites) {
            var rewriteQuery = new QueryEnhancer.EnhancedQuery(rewrite, List.of(), Optional.empty());
            // 补充向量搜索
            for (var r : safeVectorSearch(rewriteQuery, scopes, candidateK, embeddingModel)) {
                if (existingVectorIds.add(r.chunkId())) {
                    vectorResults.add(r);
                }
            }
            // 补充 FTS 搜索
            for (var r : safeFtsSearch(rewriteQuery, scopes, candidateK)) {
                if (existingFtsIds.add(r.chunkId())) {
                    ftsResults.add(r);
                }
            }
        }
    }

    /**
     * 合并搜索结果，去重。
     */
    private void mergeResults(List<DocumentSearchResult> target, List<DocumentSearchResult> source) {
        var existingIds = target.stream().map(DocumentSearchResult::chunkId).collect(Collectors.toSet());
        for (var r : source) {
            if (existingIds.add(r.chunkId())) {
                target.add(r);
            }
        }
    }

    /**
     * 合并元数据 — 在原有 metadata 基础上追加一个键值对。
     */
    private Map<String, String> mergeMeta(Map<String, String> original, String key, String value) {
        var merged = new HashMap<>(original);
        merged.put(key, value);
        return Map.copyOf(merged);
    }
}

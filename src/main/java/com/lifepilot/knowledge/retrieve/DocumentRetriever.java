package com.lifepilot.knowledge.retrieve;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.index.FtsIndexer;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.rerank.router.RerankRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

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
    private final DocumentChunkRepository chunkRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeBaseProperties.Retrieval config;

    /**
     * 构造文档混合检索服务。
     *
     * @param vectorIndexer          向量索引服务
     * @param ftsIndexer             FTS5 索引服务
     * @param reranker               可选 Reranker（精排）
     * @param rerankRouter           精排路由器（可选）
     * @param queryEnhancer          查询增强器（可选）
     * @param chunkRepository        分块数据访问层（上下文窗口扩展）
     * @param kbRepository           知识库数据访问层（per-KB 模型解析）
     * @param config                 检索配置
     */
    public DocumentRetriever(@Nullable VectorIndexer vectorIndexer, FtsIndexer ftsIndexer,
                              @Nullable RerankRouter rerankRouter,
                              @Nullable QueryEnhancer queryEnhancer,
                              DocumentChunkRepository chunkRepository,
                              KnowledgeBaseRepository kbRepository,
                              KnowledgeBaseProperties.Retrieval config) {
        this.vectorIndexer = vectorIndexer;
        this.ftsIndexer = ftsIndexer;
        this.rerankRouter = rerankRouter;
        this.queryEnhancer = queryEnhancer;
        this.chunkRepository = chunkRepository;
        this.kbRepository = kbRepository;
        this.config = config;
        log.info("DocumentRetriever 初始化完成: topK={}, rrfK={}, reranker={}, queryEnhancer={}, contextWindowSize={}",
                config.defaultTopK(), config.rrfK(),
                rerankRouter != null ? "启用" : "未启用",
                queryEnhancer != null ? "启用" : "未启用",
                config.contextWindowSize());
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
        if (query == null || query.isBlank() || kbIds.isEmpty()) {
            return List.of();
        }

        long startTime = System.currentTimeMillis();
        int effectiveTopK = topK > 0 ? topK : config.defaultTopK();
        int candidateK = effectiveTopK * 3;

        // 0. 解析 per-KB 模型配置
        String embeddingModel = resolveEmbeddingModel(kbIds);
        String rerankerModel = resolveRerankerModel(kbIds);

        // 1. 查询增强
        QueryEnhancer.EnhancedQuery enhanced = enhanceQuery(query);

        // 2. 并行执行向量搜索和 FTS5 搜索
        List<DocumentSearchResult> vectorResults;
        List<DocumentSearchResult> ftsResults;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // HyDE 模式使用假设文档 Embedding 进行向量搜索
            var vectorFuture = CompletableFuture.supplyAsync(
                    () -> safeVectorSearch(enhanced, kbIds, candidateK, embeddingModel), executor);
            var ftsFuture = CompletableFuture.supplyAsync(
                    () -> safeFtsSearch(enhanced, kbIds, candidateK), executor);

            vectorResults = vectorFuture.join();
            ftsResults = ftsFuture.join();
        }

        // 3. 自适应 RRF 融合
        var fused = adaptiveRrfFusion(vectorResults, ftsResults, effectiveTopK);

        // 4. 最低相关性阈值过滤
        if (config.minRelevanceScore() > 0.0) {
            fused = fused.stream()
                    .filter(r -> r.score() >= config.minRelevanceScore())
                    .toList();
        }

        // 5. 上下文窗口扩展
        if (config.contextWindowSize() > 0) {
            fused = expandContextWindow(fused);
        }

        // 6. 可选精排（检查运行时 enabled 状态）
        if (rerankRouter != null && rerankRouter.isKnowledgeRerankEnabled() && !fused.isEmpty()) {
            try {
                var reranked = rerankRouter.rerankDocuments(query, fused, effectiveTopK, rerankerModel);
                log.debug("精排完成: input={}, output={}", fused.size(), reranked.size());
                fused = reranked;
            } catch (Exception e) {
                log.warn("Reranker 不可用，跳过精排: {}", e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.debug("检索完成: vector={}, fts={}, 融合后={}, 最终={}, 耗时={}ms",
                vectorResults.size(), ftsResults.size(),
                fused.size(), fused.size(), elapsed);

        return List.copyOf(fused);
    }

    /**
     * 解析跨知识库的 embeddingModel。
     * 所有 KB 的 embeddingModel 一致时返回该模型，否则返回 null（回退到默认）。
     */
    private @Nullable String resolveEmbeddingModel(List<String> kbIds) {
        var models = kbIds.stream()
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
    private @Nullable String resolveRerankerModel(List<String> kbIds) {
        var models = kbIds.stream()
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
     * 自适应 RRF 融合 — 根据向量 Top-1 分数动态调整权重。
     *
     * <p>向量 Top-1 分数 &lt; lowConfidenceThreshold 时提升 FTS 权重。
     */
    List<DocumentSearchResult> adaptiveRrfFusion(List<DocumentSearchResult> vectorResults,
                                                  List<DocumentSearchResult> ftsResults,
                                                  int topK) {
        int k = config.rrfK();

        // 自适应权重调整
        double vectorWeight = config.vectorWeight();
        double ftsWeight = config.ftsWeight();
        if (!vectorResults.isEmpty()) {
            double topVectorScore = vectorResults.getFirst().score();
            if (topVectorScore < config.lowConfidenceThreshold()) {
                vectorWeight = 0.3;
                ftsWeight = 0.7;
                log.debug("向量 Top-1 分数低于阈值，提升 FTS 权重: topScore={}, threshold={}",
                        topVectorScore, config.lowConfidenceThreshold());
            }
        }

        // chunkId → 各路原始分数
        var vectorScoreMap = new HashMap<String, Double>();
        var ftsScoreMap = new HashMap<String, Double>();
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

        // 按 RRF 分数降序排序，构建 ScoreBreakdown
        return rrfScoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    var original = resultMap.get(entry.getKey());
                    var breakdown = new ScoreBreakdown(
                            vectorScoreMap.getOrDefault(entry.getKey(), 0.0),
                            ftsScoreMap.getOrDefault(entry.getKey(), 0.0),
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
                            Optional.empty()
                    );
                })
                .toList();
    }

    /**
     * 上下文窗口扩展 — 对每个命中分块，查询同文档的相邻分块并拼接。
     */
    private List<DocumentSearchResult> expandContextWindow(List<DocumentSearchResult> results) {
        int windowSize = config.contextWindowSize();
        var expanded = new ArrayList<DocumentSearchResult>(results.size());

        for (var result : results) {
            try {
                // 需要知道命中分块的 chunkIndex，从 metadata 或 chunkRepository 获取
                var chunks = chunkRepository.findByDocumentId(result.documentId());
                int hitIndex = -1;
                for (var chunk : chunks) {
                    if (chunk.id().equals(result.chunkId())) {
                        hitIndex = chunk.chunkIndex();
                        break;
                    }
                }

                if (hitIndex < 0) {
                    expanded.add(result);
                    continue;
                }

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
                        Optional.of(sb.toString().trim())
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
                                                         List<String> kbIds, int topK,
                                                         @Nullable String embeddingModel) {
        if (vectorIndexer == null) {
            return List.of();
        }
        try {
            // HyDE 模式：使用假设文档 Embedding
            if (enhanced.hydeEmbedding().isPresent()) {
                return vectorIndexer.searchByEmbedding(enhanced.hydeEmbedding().get(), kbIds, topK);
            }

            // Rewrite 模式：对每个改写查询分别检索，合并去重
            if (!enhanced.rewrittenQueries().isEmpty()) {
                var allResults = new LinkedHashMap<String, DocumentSearchResult>();
                // 先检索原始查询
                for (var r : vectorIndexer.searchSimilar(enhanced.primaryQuery(), kbIds, topK, embeddingModel)) {
                    allResults.putIfAbsent(r.chunkId(), r);
                }
                // 再检索改写查询
                for (var rewrite : enhanced.rewrittenQueries()) {
                    for (var r : vectorIndexer.searchSimilar(rewrite, kbIds, topK, embeddingModel)) {
                        allResults.putIfAbsent(r.chunkId(), r);
                    }
                }
                return new ArrayList<>(allResults.values());
            }

            return vectorIndexer.searchSimilar(enhanced.primaryQuery(), kbIds, topK, embeddingModel);
        } catch (Exception e) {
            log.warn("向量搜索失败，降级跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 安全执行 FTS5 搜索，支持 Rewrite 模式。
     */
    private List<DocumentSearchResult> safeFtsSearch(QueryEnhancer.EnhancedQuery enhanced,
                                                      List<String> kbIds, int topK) {
        try {
            if (!enhanced.rewrittenQueries().isEmpty()) {
                var allResults = new LinkedHashMap<String, DocumentSearchResult>();
                for (var r : ftsIndexer.search(enhanced.primaryQuery(), kbIds, topK)) {
                    allResults.putIfAbsent(r.chunkId(), r);
                }
                for (var rewrite : enhanced.rewrittenQueries()) {
                    for (var r : ftsIndexer.search(rewrite, kbIds, topK)) {
                        allResults.putIfAbsent(r.chunkId(), r);
                    }
                }
                return new ArrayList<>(allResults.values());
            }
            return ftsIndexer.search(enhanced.primaryQuery(), kbIds, topK);
        } catch (Exception e) {
            log.warn("FTS5 搜索失败，降级跳过: {}", e.getMessage());
            return List.of();
        }
    }
}

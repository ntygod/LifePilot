package com.lifepilot.knowledge.retrieve;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.index.FtsIndexer;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.rerank.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * 文档混合检索服务 — 向量 + FTS5 + RRF 融合 + 可选 Reranker。
 *
 * <p>并行执行向量相似度搜索和 FTS5 全文搜索，通过 Reciprocal Rank Fusion（RRF）
 * 融合两路结果，可选使用 Reranker 进行精排。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(DocumentRetriever.class);

    @Nullable
    private final VectorIndexer vectorIndexer;
    private final FtsIndexer ftsIndexer;
    private final Optional<Reranker> reranker;
    private final KnowledgeBaseProperties.Retrieval config;

    /**
     * 构造文档混合检索服务。
     *
     * @param vectorIndexer 向量索引服务
     * @param ftsIndexer    FTS5 索引服务
     * @param reranker      可选 Reranker（精排）
     * @param config        检索配置
     */
    public DocumentRetriever(@Nullable VectorIndexer vectorIndexer, FtsIndexer ftsIndexer,
                              Optional<Reranker> reranker,
                              KnowledgeBaseProperties.Retrieval config) {
        this.vectorIndexer = vectorIndexer;
        this.ftsIndexer = ftsIndexer;
        this.reranker = reranker;
        this.config = config;
        log.info("DocumentRetriever 初始化完成: topK={}, rrfK={}, reranker={}, vectorIndexer={}",
                config.defaultTopK(), config.rrfK(),
                reranker.isPresent() ? "启用" : "未启用",
                vectorIndexer != null ? "启用" : "未启用（仅 FTS5）");
    }

    /**
     * 混合检索文档分块。
     *
     * <p>并行执行向量搜索和 FTS5 搜索，通过 RRF 融合结果，可选精排。
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

        int effectiveTopK = topK > 0 ? topK : config.defaultTopK();
        // 检索阶段多取一些候选，供 RRF 融合和精排使用
        int candidateK = effectiveTopK * 3;

        // 并行执行向量搜索和 FTS5 搜索
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var vectorFuture = CompletableFuture.supplyAsync(
                    () -> safeVectorSearch(query, kbIds, candidateK), executor);
            var ftsFuture = CompletableFuture.supplyAsync(
                    () -> safeFtsSearch(query, kbIds, candidateK), executor);

            var vectorResults = vectorFuture.join();
            var ftsResults = ftsFuture.join();

            log.debug("检索完成: vector={}, fts={}", vectorResults.size(), ftsResults.size());

            // RRF 融合
            var fused = rrfFusion(vectorResults, ftsResults, effectiveTopK);

            // 可选精排
            if (reranker.isPresent() && !fused.isEmpty()) {
                try {
                    var reranked = reranker.get().rerank(query, fused, effectiveTopK);
                    log.debug("精排完成: input={}, output={}", fused.size(), reranked.size());
                    return List.copyOf(reranked);
                } catch (Exception e) {
                    log.warn("Reranker 不可用，跳过精排: {}", e.getMessage());
                }
            }

            return List.copyOf(fused);
        }
    }

    /**
     * RRF 融合两路检索结果。
     *
     * <p>公式：score(d) = Σ 1 / (k + rank_i(d))，k 为 RRF 常数（默认 60）。
     *
     * @param vectorResults 向量搜索结果
     * @param ftsResults    FTS5 搜索结果
     * @param topK          返回数量
     * @return 融合后的结果列表（按 RRF 分数降序）
     */
    List<DocumentSearchResult> rrfFusion(List<DocumentSearchResult> vectorResults,
                                         List<DocumentSearchResult> ftsResults,
                                         int topK) {
        int k = config.rrfK();
        // chunkId → 累计 RRF 分数
        var scoreMap = new LinkedHashMap<String, Double>();
        // chunkId → 原始结果（保留内容信息）
        var resultMap = new HashMap<String, DocumentSearchResult>();

        // 向量搜索结果的 RRF 分数
        for (int rank = 0; rank < vectorResults.size(); rank++) {
            var result = vectorResults.get(rank);
            double rrfScore = 1.0 / (k + rank + 1); // rank 从 1 开始
            scoreMap.merge(
                    result.chunkId(),
                    Double.valueOf(rrfScore),
                    (a, b) -> Double.valueOf(a.doubleValue() + b.doubleValue())
            );
            resultMap.putIfAbsent(result.chunkId(), result);
        }

        // FTS5 搜索结果的 RRF 分数
        for (int rank = 0; rank < ftsResults.size(); rank++) {
            var result = ftsResults.get(rank);
            double rrfScore = 1.0 / (k + rank + 1);
            scoreMap.merge(
                    result.chunkId(),
                    Double.valueOf(rrfScore),
                    (a, b) -> Double.valueOf(a.doubleValue() + b.doubleValue())
            );
            resultMap.putIfAbsent(result.chunkId(), result);
        }

        // 按 RRF 分数降序排序，取 topK
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    var original = resultMap.get(entry.getKey());
                    return new DocumentSearchResult(
                            original.chunkId(),
                            original.documentId(),
                            original.knowledgeBaseId(),
                            original.content(),
                            original.contextPrefix(),
                            original.headingHierarchy(),
                            entry.getValue(), // RRF 融合分数
                            "fused",
                            original.metadata()
                    );
                })
                .toList();
    }

    /**
     * 安全执行向量搜索，异常时返回空列表。
     */
    private List<DocumentSearchResult> safeVectorSearch(String query, List<String> kbIds, int topK) {
        if (vectorIndexer == null) {
            return List.of();
        }
        try {
            return vectorIndexer.searchSimilar(query, kbIds, topK);
        } catch (Exception e) {
            log.warn("向量搜索失败，降级跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 安全执行 FTS5 搜索，异常时返回空列表。
     */
    private List<DocumentSearchResult> safeFtsSearch(String query, List<String> kbIds, int topK) {
        try {
            return ftsIndexer.search(query, kbIds, topK);
        } catch (Exception e) {
            log.warn("FTS5 搜索失败，降级跳过: {}", e.getMessage());
            return List.of();
        }
    }
}

package com.lifepilot.tool.search;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.tool.ToolContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.stream.IntStream;

/**
 * 工具语义搜索索引 — 基于向量相似度的工具发现引擎。
 *
 * <p>启动时对所有工具的 name + description 做向量化，
 * 搜索时通过 cosine similarity 匹配最相关的工具。
 * 当 EmbeddingRouter 不可用时回退到简单子串匹配。</p>
 *
 * @author zsg
 * @since 2026-04-09
 */
public class ToolSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchIndex.class);

    /**
     * 搜索结果。
     *
     * @param toolId 工具 ID
     * @param name 工具显示名称
     * @param description 工具描述
     * @param category 工具分组
     * @param score 相似度分数
     */
    public record SearchResult(String toolId, String name, String description,
                                String category, double score) {}

    private record IndexedTool(String toolId, String name, String description,
                                String category, String searchText, @Nullable float[] embedding) {}

    @Nullable
    private final EmbeddingRouter embeddingRouter;
    private volatile List<IndexedTool> indexedTools;

    public ToolSearchIndex(@Nullable EmbeddingRouter embeddingRouter) {
        this.embeddingRouter = embeddingRouter;
    }

    /**
     * 从工具列表构建搜索索引。
     *
     * <p>synchronized 防止并发构建导致重复 embedding 调用。
     * 构建结果赋值到 volatile 字段，search 方法读取无需同步。</p>
     *
     * @param tools 工具列表
     */
    public synchronized void buildIndex(List<ToolContract> tools) {
        List<String> searchTexts = tools.stream()
                .map(t -> t.name() + " — " + t.description())
                .toList();

        float[][] embeddings = null;
        if (embeddingRouter != null) {
            try {
                embeddings = embeddingRouter.embedBatch(searchTexts, EmbeddingUseCase.DEFAULT, null, null);
                log.info("工具搜索索引向量化完成: count={}", tools.size());
            } catch (Exception e) {
                log.warn("工具搜索索引向量化失败，回退到子串匹配: {}", e.getMessage());
            }
        } else {
            log.info("EmbeddingRouter 不可用，工具搜索索引使用子串匹配模式: count={}", tools.size());
        }

        float[][] finalEmbeddings = embeddings;
        this.indexedTools = IntStream.range(0, tools.size())
                .mapToObj(i -> {
                    ToolContract t = tools.get(i);
                    float[] emb = finalEmbeddings != null ? finalEmbeddings[i] : null;
                    return new IndexedTool(t.id(), t.name(), t.description(),
                            t.category().displayName(), searchTexts.get(i), emb);
                })
                .toList();
    }

    /**
     * 搜索匹配的工具。
     *
     * @param query 搜索查询
     * @param maxResults 最大返回数量
     * @param minScore 最低分数阈值
     * @param excludeIds 排除的工具 ID
     * @return 排序后的搜索结果列表
     */
    public List<SearchResult> search(String query, int maxResults,
                                      double minScore, Set<String> excludeIds) {
        var tools = this.indexedTools;
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        // 判断是否使用向量搜索
        boolean useEmbedding = embeddingRouter != null && tools.getFirst().embedding() != null;

        if (useEmbedding) {
            return searchByEmbedding(query, maxResults, minScore, excludeIds, tools);
        } else {
            return searchBySubstring(query, maxResults, minScore, excludeIds, tools);
        }
    }

    /** 向量语义搜索。 */
    private List<SearchResult> searchByEmbedding(String query, int maxResults,
                                                   double minScore, Set<String> excludeIds,
                                                   List<IndexedTool> tools) {
        float[] queryVec;
        try {
            queryVec = embeddingRouter.embed(query, EmbeddingUseCase.DEFAULT, null, null);
        } catch (Exception e) {
            log.warn("查询向量化失败，回退到子串匹配: {}", e.getMessage());
            return searchBySubstring(query, maxResults, minScore, excludeIds, tools);
        }

        return tools.stream()
                .filter(t -> !excludeIds.contains(t.toolId()))
                .map(t -> new SearchResult(
                        t.toolId(), t.name(), t.description(), t.category(),
                        cosineSimilarity(queryVec, t.embedding())))
                .filter(r -> r.score() >= minScore)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(maxResults)
                .toList();
    }

    /** 子串匹配回退。 */
    private List<SearchResult> searchBySubstring(String query, int maxResults,
                                                   double minScore, Set<String> excludeIds,
                                                   List<IndexedTool> tools) {
        String lowerQuery = query.toLowerCase();
        String[] queryTokens = lowerQuery.split("[\\s.\\-_]+");

        return tools.stream()
                .filter(t -> !excludeIds.contains(t.toolId()))
                .map(t -> {
                    String lowerText = t.searchText().toLowerCase() + " " + t.toolId().toLowerCase();
                    double score = 0;
                    // 完整子串匹配
                    if (lowerText.contains(lowerQuery)) {
                        score += 0.8;
                    }
                    // 分词匹配
                    for (String token : queryTokens) {
                        if (!token.isBlank() && lowerText.contains(token)) {
                            score += 0.3;
                        }
                    }
                    // 归一化到 0-1
                    score = Math.min(1.0, score);
                    return new SearchResult(t.toolId(), t.name(), t.description(), t.category(), score);
                })
                .filter(r -> r.score() > 0 && r.score() >= minScore)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(maxResults)
                .toList();
    }

    /** 余弦相似度计算。 */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    /** 使索引失效，下次使用时重建。 */
    public void invalidate() {
        this.indexedTools = null;
    }

    /** 索引是否已构建。 */
    public boolean isBuilt() {
        return this.indexedTools != null;
    }
}

package com.lifepilot.knowledge.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * LLM 精排器 — 支持 Pointwise 和 Listwise 两种模式。
 *
 * <p>Pointwise 模式：逐个评分 query-document 对，支持 Virtual Thread 并行分批。
 * <p>Listwise 模式：一次 LLM 调用排序所有候选，候选数超限时滑动窗口分批。
 *
 * @author zsg
 * @since 2026-02-25
 */
public final class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);
    private static final String SCENE = "knowledge_rerank";

    private final LlmRouter llmRouter;
    private final KnowledgeBaseProperties.Reranker config;

    public LlmReranker(LlmRouter llmRouter, KnowledgeBaseProperties.Reranker config) {
        this.llmRouter = llmRouter;
        this.config = config;
        log.info("LlmReranker 初始化: mode={}, listwiseMaxCandidates={}",
                config.llmMode(), config.listwiseMaxCandidates());
    }

    @Override
    public List<DocumentSearchResult> rerank(String query, List<DocumentSearchResult> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        return switch (config.llmMode()) {
            case "listwise" -> rerankListwise(query, candidates, topK);
            default -> rerankPointwise(query, candidates, topK);
        };
    }

    /**
     * Listwise 精排：一次 LLM 调用排序所有候选。
     * 候选数 > listwiseMaxCandidates 时使用滑动窗口分批排序。
     */
    private List<DocumentSearchResult> rerankListwise(String query,
                                                       List<DocumentSearchResult> candidates,
                                                       int topK) {
        try {
            List<DocumentSearchResult> sorted;
            if (candidates.size() <= config.listwiseMaxCandidates()) {
                sorted = listwiseSinglePass(query, candidates);
            } else {
                sorted = listwiseSlidingWindow(query, candidates);
            }
            return sorted.stream().limit(topK).toList();
        } catch (Exception e) {
            log.warn("Listwise 精排失败，回退到 Pointwise: {}", e.getMessage());
            return rerankPointwise(query, candidates, topK);
        }
    }

    /**
     * 单次 Listwise 排序。
     */
    private List<DocumentSearchResult> listwiseSinglePass(String query,
                                                           List<DocumentSearchResult> candidates) {
        var idMap = new LinkedHashMap<String, DocumentSearchResult>();
        var sb = new StringBuilder();
        sb.append("请根据与查询的相关性对以下文档排序，返回排序后的文档 ID JSON 数组。\n");
        sb.append("查询：").append(query).append("\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            var c = candidates.get(i);
            String docId = "doc_" + i;
            idMap.put(docId, c);
            sb.append("文档 ").append(docId).append("：\n");
            sb.append(truncateContent(c.content(), 500)).append("\n\n");
        }
        sb.append("返回格式：[\"doc_0\", \"doc_2\", \"doc_1\", ...]");

        List<String> rankedIds = llmRouter.callEntity(SCENE, sb.toString(), RankedIds.class).ids();
        if (rankedIds == null || rankedIds.isEmpty()) {
            throw new RuntimeException("Listwise 返回空排序列表");
        }

        // 按 LLM 返回的顺序构建结果，未出现的文档追加到末尾
        var result = new ArrayList<DocumentSearchResult>();
        var seen = new HashSet<String>();
        for (var id : rankedIds) {
            var doc = idMap.get(id);
            if (doc != null && seen.add(id)) {
                double score = 1.0 - (result.size() / (double) candidates.size());
                result.add(withRerankerScore(doc, score));
            }
        }
        // 追加未出现的文档
        for (var entry : idMap.entrySet()) {
            if (seen.add(entry.getKey())) {
                result.add(withRerankerScore(entry.getValue(), 0.0));
            }
        }
        log.debug("Listwise 精排完成: input={}, output={}", candidates.size(), result.size());
        return result;
    }

    /**
     * 滑动窗口 Listwise 排序：每次取 listwiseMaxCandidates 个候选排序，
     * 保留前半部分，与下一批合并继续排序。
     */
    private List<DocumentSearchResult> listwiseSlidingWindow(String query,
                                                              List<DocumentSearchResult> candidates) {
        int windowSize = config.listwiseMaxCandidates();
        var remaining = new ArrayList<>(candidates);
        var finalized = new ArrayList<DocumentSearchResult>();

        while (remaining.size() > windowSize) {
            var window = remaining.subList(0, windowSize);
            var sorted = listwiseSinglePass(query, new ArrayList<>(window));
            int keepCount = windowSize / 2;
            finalized.addAll(sorted.subList(0, keepCount));
            remaining = new ArrayList<>(sorted.subList(keepCount, sorted.size()));
            remaining.addAll(candidates.subList(
                    Math.min(finalized.size() + remaining.size(), candidates.size()),
                    candidates.size()));
            // 防止无限循环
            if (remaining.size() >= candidates.size()) break;
        }

        // 最后一批直接排序
        if (!remaining.isEmpty()) {
            var sorted = listwiseSinglePass(query, remaining);
            finalized.addAll(sorted);
        }
        return finalized;
    }

    /**
     * Pointwise 精排增强：支持 Virtual Thread 并行分批 + 低分阈值过滤。
     */
    private List<DocumentSearchResult> rerankPointwise(String query,
                                                        List<DocumentSearchResult> candidates,
                                                        int topK) {
        var scored = new ArrayList<Map.Entry<DocumentSearchResult, Double>>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<CompletableFuture<Map.Entry<DocumentSearchResult, Double>>>();
            for (var candidate : candidates) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    double score = scoreCandidate(query, candidate);
                    return Map.entry(candidate, score);
                }, executor));
            }
            for (var future : futures) {
                try {
                    scored.add(future.join());
                } catch (Exception e) {
                    log.debug("Pointwise 评分异常: {}", e.getMessage());
                }
            }
        }

        return scored.stream()
                .filter(entry -> entry.getValue() > 0.0)
                .sorted(Comparator.<Map.Entry<DocumentSearchResult, Double>>comparingDouble(
                        Map.Entry::getValue).reversed())
                .limit(topK)
                .map(entry -> withRerankerScore(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 评估单个 query-document 对的相关性分数。
     */
    private double scoreCandidate(String query, DocumentSearchResult candidate) {
        var prompt = """
                请评估以下查询和文档的相关性，返回 JSON 格式：
                {"score": 0.85}
                
                查询：%s
                文档：%s""".formatted(query, truncateContent(candidate.content(), 500));

        try {
            ScoreResponse response = llmRouter.callEntity(SCENE, prompt, ScoreResponse.class);
            if (response != null && response.score() != null) {
                return Math.max(0.0, Math.min(1.0, response.score()));
            }
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.debug("LLM 精排 callEntity 解析失败: error={}", e.getMessage());
        }

        // 降级到手动解析
        try {
            var response = llmRouter.call(SCENE, prompt, null);
            return Double.parseDouble(response.content().trim());
        } catch (LlmUnavailableException e) {
            log.warn("LLM 精排不可用: {}", e.getMessage());
            return candidate.score();
        } catch (Exception e) {
            log.debug("LLM 精排手动解析失败: {}", e.getMessage());
            return candidate.score();
        }
    }

    /**
     * 为检索结果附加 Reranker 分数。
     */
    private DocumentSearchResult withRerankerScore(DocumentSearchResult original, double rerankerScore) {
        var breakdown = original.scoreBreakdown()
                .map(b -> new ScoreBreakdown(b.vectorScore(), b.ftsScore(), b.rrfFusedScore(),
                        Optional.of(rerankerScore)))
                .orElse(new ScoreBreakdown(0.0, 0.0, 0.0, Optional.of(rerankerScore)));
        return new DocumentSearchResult(
                original.chunkId(), original.documentId(), original.knowledgeBaseId(),
                original.content(), original.contextPrefix(), original.headingHierarchy(),
                rerankerScore, "reranked", original.metadata(),
                Optional.of(breakdown), original.expandedContent());
    }

    /**
     * 截断内容到指定最大字符数。
     */
    private String truncateContent(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "...";
    }

    /** Pointwise 评分响应。 */
    private record ScoreResponse(@JsonProperty("score") Double score) {}

    /** Listwise 排序响应。 */
    private record RankedIds(@JsonProperty("ids") List<String> ids) {
        RankedIds { if (ids == null) ids = List.of(); }
    }
}

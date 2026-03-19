package com.lifepilot.knowledge.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

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
    private final RerankerConfigProvider configProvider;
    private final PromptRegistry promptRegistry;

    public LlmReranker(LlmRouter llmRouter,
                       RerankerConfigProvider configProvider,
                       PromptRegistry promptRegistry) {
        this.llmRouter = llmRouter;
        this.configProvider = configProvider;
        this.promptRegistry = promptRegistry;
        var config = configProvider.getConfig();
        log.info("LlmReranker 初始化: mode={}, listwiseMaxCandidates={}",
                config.llmMode(), config.listwiseMaxCandidates());
    }

    @Override
    public List<DocumentSearchResult> rerank(String query, List<DocumentSearchResult> candidates, int topK) {
        return rerank(query, candidates, topK, null);
    }

    @Override
    public List<DocumentSearchResult> rerank(String query, List<DocumentSearchResult> candidates,
                                              int topK, @Nullable String modelName) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        var config = configProvider.getConfig();
        return switch (config.llmMode()) {
            case "listwise" -> rerankListwise(query, candidates, topK, modelName, config);
            default -> rerankPointwise(query, candidates, topK, modelName);
        };
    }

    @Override
    public List<RerankCandidate> rerankGeneric(String query, List<RerankCandidate> candidates, int topK) {
        if (candidates.isEmpty()) return candidates;
        var config = configProvider.getConfig();
        String modelName = config.model() != null && !config.model().isBlank() ? config.model() : null;
        try {
            // 尊重 llmMode 配置：listwise 时转换为 DocumentSearchResult 复用 listwise 逻辑
            if ("listwise".equals(config.llmMode())) {
                return rerankGenericListwise(query, candidates, topK, modelName, config);
            }
            var scored = scoreCandidatesGeneric(query, candidates, modelName);
            return scored.stream()
                    .sorted(Comparator.<Map.Entry<RerankCandidate, Double>>comparingDouble(Map.Entry::getValue).reversed())
                    .limit(topK)
                    .map(e -> new RerankCandidate(e.getKey().id(), e.getKey().content(), e.getValue()))
                    .toList();
        } catch (Exception e) {
            log.warn("LlmReranker rerankGeneric 失败，降级返回原始结果: {}", e.getMessage());
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(RerankCandidate::score).reversed())
                    .limit(topK)
                    .toList();
        }
    }

    /**
     * Listwise 模式的通用精排 — 将 RerankCandidate 转换为 DocumentSearchResult 复用 listwise 逻辑。
     */
    private List<RerankCandidate> rerankGenericListwise(String query, List<RerankCandidate> candidates,
                                                         int topK, @Nullable String modelName,
                                                         KnowledgeBaseProperties.Reranker config) {
        // 转换为 DocumentSearchResult 以复用 listwiseSinglePass
        var docCandidates = candidates.stream()
                .map(c -> new DocumentSearchResult(
                        c.id(), c.id(), null, c.content(), Optional.empty(), List.of(),
                        c.score(), "generic", Map.of(), Optional.empty(), Optional.empty()))
                .toList();
        List<DocumentSearchResult> reranked = rerankListwise(query, docCandidates, topK, modelName, config);
        return reranked.stream()
                .map(d -> new RerankCandidate(d.chunkId(), d.content(), d.score()))
                .toList();
    }

    /**
     * 对通用候选列表进行 Pointwise 评分 — 复用 LLM 评分逻辑。
     */
    private List<Map.Entry<RerankCandidate, Double>> scoreCandidatesGeneric(String query,
                                                                             List<RerankCandidate> candidates,
                                                                             @Nullable String modelName) {
        var scored = new ArrayList<Map.Entry<RerankCandidate, Double>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<CompletableFuture<Map.Entry<RerankCandidate, Double>>>();
            for (var candidate : candidates) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    double score = scoreCandidateGeneric(query, candidate, modelName);
                    return Map.entry(candidate, score);
                }, executor));
            }
            for (var future : futures) {
                try {
                    scored.add(future.join());
                } catch (Exception e) {
                    if (e.getCause() instanceof LlmUnavailableException lue) throw lue;
                    log.debug("Pointwise 通用评分异常: {}", e.getMessage());
                }
            }
        }
        return scored;
    }

    /**
     * 评估单个通用候选项的相关性分数。
     */
    private double scoreCandidateGeneric(String query, RerankCandidate candidate,
                                          @Nullable String modelName) {
        var prompt = promptRegistry.render("knowledge/rerank-pointwise", Map.of(
                "query", query,
                "document", truncateContent(candidate.content(), 500)));
        try {
            ScoreResponse response = llmRouter.callEntity(
                    LlmRequest.builder(SCENE, prompt).modelName(modelName).build(), ScoreResponse.class);
            if (response != null && response.score() != null) {
                return Math.max(0.0, Math.min(1.0, response.score()));
            }
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.debug("LLM 通用精排 callEntity 解析失败: error={}", e.getMessage());
        }
        // 降级到手动解析
        try {
            var response = llmRouter.call(
                    LlmRequest.builder(SCENE, prompt).modelName(modelName).build());
            return Double.parseDouble(response.content().trim());
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.debug("LLM 通用精排手动解析失败: {}", e.getMessage());
            return candidate.score();
        }
    }

    /**
     * Listwise 精排：一次 LLM 调用排序所有候选。
     * 候选数 > listwiseMaxCandidates 时使用滑动窗口分批排序。
     */
    private List<DocumentSearchResult> rerankListwise(String query,
                                                       List<DocumentSearchResult> candidates,
                                                       int topK, @Nullable String modelName,
                                                       KnowledgeBaseProperties.Reranker config) {
        try {
            List<DocumentSearchResult> sorted;
            if (candidates.size() <= config.listwiseMaxCandidates()) {
                sorted = listwiseSinglePass(query, candidates, modelName);
            } else {
                sorted = listwiseSlidingWindow(query, candidates, modelName);
            }
            return sorted.stream().limit(topK).toList();
        } catch (LlmUnavailableException e) {
            log.warn("Listwise 精排 LLM 不可用，回退到 Pointwise: scene={}, error={}", SCENE, e.getMessage());
            try {
                return rerankPointwise(query, candidates, topK, modelName);
            } catch (LlmUnavailableException e2) {
                log.warn("Pointwise 回退也失败，降级返回原始候选列表: scene={}, error={}", SCENE, e2.getMessage());
                return degradeFallback(candidates, topK);
            }
        } catch (Exception e) {
            log.warn("Listwise 精排失败，回退到 Pointwise: {}", e.getMessage());
            return rerankPointwise(query, candidates, topK, modelName);
        }
    }

    /**
     * 单次 Listwise 排序。
     */
    private List<DocumentSearchResult> listwiseSinglePass(String query,
                                                           List<DocumentSearchResult> candidates,
                                                           @Nullable String modelName) {
        var idMap = new LinkedHashMap<String, DocumentSearchResult>();
        var docsText = new StringBuilder();

        for (int i = 0; i < candidates.size(); i++) {
            var c = candidates.get(i);
            String docId = "doc_" + i;
            idMap.put(docId, c);
            docsText.append("文档 ").append(docId).append("：\n");
            docsText.append(truncateContent(c.content(), 500)).append("\n\n");
        }

        var prompt = promptRegistry.render("knowledge/rerank-listwise", Map.of(
                "query", query,
                "documents", docsText.toString()));

        List<String> rankedIds = llmRouter.callEntity(
                LlmRequest.builder(SCENE, prompt).modelName(modelName).build(),
                RankedIds.class).ids();
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
                                                              List<DocumentSearchResult> candidates,
                                                              @Nullable String modelName) {
        int windowSize = configProvider.getConfig().listwiseMaxCandidates();
        var remaining = new ArrayList<>(candidates);
        var finalized = new ArrayList<DocumentSearchResult>();

        while (remaining.size() > windowSize) {
            var window = remaining.subList(0, windowSize);
            var sorted = listwiseSinglePass(query, new ArrayList<>(window), modelName);
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
            var sorted = listwiseSinglePass(query, remaining, modelName);
            finalized.addAll(sorted);
        }
        return finalized;
    }

    /**
     * Pointwise 精排增强：支持 Virtual Thread 并行分批 + 低分阈值过滤。
     * LLM 完全不可用时降级返回原始候选列表按分数降序截取 topK。
     */
    private List<DocumentSearchResult> rerankPointwise(String query,
                                                        List<DocumentSearchResult> candidates,
                                                        int topK,
                                                        @Nullable String modelName) {
        var scored = new ArrayList<Map.Entry<DocumentSearchResult, Double>>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<CompletableFuture<Map.Entry<DocumentSearchResult, Double>>>();
            for (var candidate : candidates) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    double score = scoreCandidate(query, candidate, modelName);
                    return Map.entry(candidate, score);
                }, executor));
            }
            for (var future : futures) {
                try {
                    scored.add(future.join());
                } catch (Exception e) {
                    // CompletableFuture 包装的 LlmUnavailableException 向上传播
                    if (e.getCause() instanceof LlmUnavailableException lue) {
                        throw lue;
                    }
                    log.debug("Pointwise 评分异常: {}", e.getMessage());
                }
            }
        } catch (LlmUnavailableException e) {
            log.warn("Pointwise 精排 LLM 不可用，降级返回原始候选列表: scene={}, error={}", SCENE, e.getMessage());
            return degradeFallback(candidates, topK);
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
    private double scoreCandidate(String query, DocumentSearchResult candidate,
                                   @Nullable String modelName) {
        var prompt = promptRegistry.render("knowledge/rerank-pointwise", Map.of(
                "query", query,
                "document", truncateContent(candidate.content(), 500)));

        try {
            ScoreResponse response = llmRouter.callEntity(
                    LlmRequest.builder(SCENE, prompt).modelName(modelName).build(),
                    ScoreResponse.class);
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
            var response = llmRouter.call(
                    LlmRequest.builder(SCENE, prompt).modelName(modelName).build());
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
     * 降级回退：返回原始候选列表按分数降序截取 topK。
     */
    private List<DocumentSearchResult> degradeFallback(List<DocumentSearchResult> candidates, int topK) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(DocumentSearchResult::score).reversed())
                .limit(topK)
                .toList();
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

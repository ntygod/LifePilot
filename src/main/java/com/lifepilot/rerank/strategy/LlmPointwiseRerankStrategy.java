package com.lifepilot.rerank.strategy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import com.lifepilot.knowledge.rerank.RerankCandidate;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * LLM Pointwise 精排策略。
 *
 * @author zsg
 * @since 2026-03-24
 */
@Component
public class LlmPointwiseRerankStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmPointwiseRerankStrategy.class);
    private static final String SCENE = "knowledge_rerank";

    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;

    public LlmPointwiseRerankStrategy(GenerationRouter generationRouter, PromptRegistry promptRegistry) {
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
    }

    public List<DocumentSearchResult> rerankDocuments(String query,
                                                      List<DocumentSearchResult> candidates,
                                                      int topK,
                                                      @Nullable String modelName,
                                                      @Nullable String serviceId) {
        var scored = scoreCandidates(query, candidates, modelName, serviceId);
        return scored.stream()
                .sorted(Comparator.<Map.Entry<DocumentSearchResult, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .limit(topK)
                .map(entry -> withRerankerScore(entry.getKey(), entry.getValue()))
                .toList();
    }

    public List<RerankCandidate> rerankCandidates(String query,
                                                  List<RerankCandidate> candidates,
                                                  int topK,
                                                  @Nullable String modelName,
                                                  @Nullable String serviceId) {
        var scored = scoreGeneric(query, candidates, modelName, serviceId);
        return scored.stream()
                .sorted(Comparator.<Map.Entry<RerankCandidate, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .limit(topK)
                .map(entry -> new RerankCandidate(entry.getKey().id(), entry.getKey().content(), entry.getValue()))
                .toList();
    }

    private List<Map.Entry<DocumentSearchResult, Double>> scoreCandidates(String query,
                                                                          List<DocumentSearchResult> candidates,
                                                                          @Nullable String modelName,
                                                                          @Nullable String serviceId) {
        List<Map.Entry<DocumentSearchResult, Double>> scored = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<CompletableFuture<Map.Entry<DocumentSearchResult, Double>>>();
            for (DocumentSearchResult candidate : candidates) {
                futures.add(CompletableFuture.supplyAsync(() ->
                        Map.entry(candidate, scoreText(query, candidate.content(), modelName, serviceId)), executor));
            }
            for (var future : futures) {
                scored.add(future.join());
            }
        }
        return scored;
    }

    private List<Map.Entry<RerankCandidate, Double>> scoreGeneric(String query,
                                                                  List<RerankCandidate> candidates,
                                                                  @Nullable String modelName,
                                                                  @Nullable String serviceId) {
        List<Map.Entry<RerankCandidate, Double>> scored = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<CompletableFuture<Map.Entry<RerankCandidate, Double>>>();
            for (RerankCandidate candidate : candidates) {
                futures.add(CompletableFuture.supplyAsync(() ->
                        Map.entry(candidate, scoreText(query, candidate.content(), modelName, serviceId)), executor));
            }
            for (var future : futures) {
                scored.add(future.join());
            }
        }
        return scored;
    }

    private double scoreText(String query,
                             String content,
                             @Nullable String modelName,
                             @Nullable String serviceId) {
        String prompt = promptRegistry.render("knowledge/rerank-pointwise", Map.of(
                "query", query,
                "document", truncate(content, 500)));
        try {
            ScoreResponse response = generationRouter.callEntity(
                    SCENE, prompt, ScoreResponse.class, serviceId, modelName, null);
            if (response != null && response.score() != null) {
                return clampScore(response.score());
            }
        } catch (Exception e) {
            log.debug("Pointwise 结构化精排失败，尝试手工解析: {}", e.getMessage());
        }
        try {
            return clampScore(Double.parseDouble(generationRouter.call(
                    SCENE, prompt, null, serviceId, modelName, GenerationCapability.CHAT, null).content().trim()));
        } catch (Exception e) {
            log.debug("Pointwise 精排失败，回退为 0: {}", e.getMessage());
            return 0.0;
        }
    }

    private DocumentSearchResult withRerankerScore(DocumentSearchResult original, double rerankerScore) {
        ScoreBreakdown breakdown = original.scoreBreakdown()
                .map(existing -> new ScoreBreakdown(
                        existing.vectorScore(), existing.ftsScore(), existing.rrfFusedScore(), java.util.Optional.of(rerankerScore)))
                .orElse(new ScoreBreakdown(0.0, 0.0, 0.0, java.util.Optional.of(rerankerScore)));
        return new DocumentSearchResult(
                original.chunkId(), original.documentId(), original.knowledgeBaseId(),
                original.content(), original.contextPrefix(), original.headingHierarchy(),
                rerankerScore, "reranked", original.metadata(),
                java.util.Optional.of(breakdown), original.expandedContent());
    }

    private double clampScore(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private record ScoreResponse(@JsonProperty("score") Double score) {
    }
}

package com.lifepilot.rerank.strategy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import com.lifepilot.knowledge.rerank.RerankCandidate;
import com.lifepilot.prompt.PromptRegistry;
import org.springframework.lang.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Listwise 精排策略。
 *
 * @author zsg
 * @since 2026-03-24
 */
public class LlmListwiseRerankStrategy {

    private static final String SCENE = "knowledge_rerank";

    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;

    public LlmListwiseRerankStrategy(GenerationRouter generationRouter, PromptRegistry promptRegistry) {
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
    }

    public List<DocumentSearchResult> rerankDocuments(String query,
                                                      List<DocumentSearchResult> candidates,
                                                      int topK,
                                                      @Nullable String modelName,
                                                      @Nullable String serviceId) {
        var idMap = new LinkedHashMap<String, DocumentSearchResult>();
        StringBuilder documents = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            DocumentSearchResult candidate = candidates.get(i);
            String docId = "doc_" + i;
            idMap.put(docId, candidate);
            documents.append("文档 ").append(docId).append("：\n");
            documents.append(truncate(candidate.content(), 500)).append("\n\n");
        }
        RankedIds rankedIds = generationRouter.callEntity(
                SCENE,
                promptRegistry.render("knowledge/rerank-listwise", Map.of("query", query, "documents", documents.toString())),
                RankedIds.class,
                serviceId,
                modelName,
                null);
        return orderDocuments(idMap, rankedIds != null ? rankedIds.ids() : List.of(), topK);
    }

    public List<RerankCandidate> rerankCandidates(String query,
                                                  List<RerankCandidate> candidates,
                                                  int topK,
                                                  @Nullable String modelName,
                                                  @Nullable String serviceId) {
        List<DocumentSearchResult> docs = candidates.stream()
                .map(candidate -> new DocumentSearchResult(
                        candidate.id(), candidate.id(), null, candidate.content(),
                        java.util.Optional.empty(), List.of(), candidate.score(), "generic", Map.of(),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        null))
                .toList();
        return rerankDocuments(query, docs, topK, modelName, serviceId).stream()
                .map(doc -> new RerankCandidate(doc.chunkId(), doc.content(), doc.score()))
                .toList();
    }

    private List<DocumentSearchResult> orderDocuments(Map<String, DocumentSearchResult> idMap,
                                                      List<String> rankedIds,
                                                      int topK) {
        List<DocumentSearchResult> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String rankedId : rankedIds) {
            DocumentSearchResult doc = idMap.get(rankedId);
            if (doc != null && seen.add(rankedId)) {
                double score = 1.0 - (result.size() / (double) Math.max(idMap.size(), 1));
                result.add(withRerankerScore(doc, score));
            }
        }
        for (Map.Entry<String, DocumentSearchResult> entry : idMap.entrySet()) {
            if (seen.add(entry.getKey())) {
                result.add(withRerankerScore(entry.getValue(), 0.0));
            }
        }
        return result.stream().limit(topK).toList();
    }

    private DocumentSearchResult withRerankerScore(DocumentSearchResult original, double rerankerScore) {
        ScoreBreakdown breakdown = original.scoreBreakdown()
                .map(existing -> new ScoreBreakdown(
                        existing.vectorScore(), existing.ftsScore(), existing.graphScore(), existing.rrfFusedScore(), java.util.Optional.of(rerankerScore)))
                .orElse(new ScoreBreakdown(0.0, 0.0, 0.0, 0.0, java.util.Optional.of(rerankerScore)));
        return new DocumentSearchResult(
                original.chunkId(), original.documentId(), original.knowledgeBaseId(),
                original.content(), original.contextPrefix(), original.headingHierarchy(),
                rerankerScore, "reranked", original.metadata(),
                java.util.Optional.of(breakdown), original.expandedContent(),
                original.sourceType());
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private record RankedIds(@JsonProperty("ids") List<String> ids) {
    }
}

package com.lifepilot.knowledge.rerank;

import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * LLM 精排器 — 通过 LLM 评分 query-document 对进行精排。
 *
 * @author zsg
 * @since 2026-02-25
 */
public final class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);
    private static final String SCENE = "knowledge_rerank";

    private final LlmRouter llmRouter;

    public LlmReranker(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    @Override
    public List<DocumentSearchResult> rerank(String query, List<DocumentSearchResult> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        var scored = new ArrayList<Map.Entry<DocumentSearchResult, Double>>();

        for (var candidate : candidates) {
            try {
                double score = scoreCandidate(query, candidate);
                scored.add(Map.entry(candidate, score));
            } catch (LlmUnavailableException e) {
                log.warn("LLM 精排不可用，返回原始结果: {}", e.getMessage());
                return candidates.stream().limit(topK).toList();
            } catch (Exception e) {
                // 单个评分失败，使用原始分数
                scored.add(Map.entry(candidate, candidate.score()));
            }
        }

        return scored.stream()
                .sorted(Comparator.<Map.Entry<DocumentSearchResult, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .limit(topK)
                .map(entry -> new DocumentSearchResult(
                        entry.getKey().chunkId(), entry.getKey().documentId(),
                        entry.getKey().knowledgeBaseId(), entry.getKey().content(),
                        entry.getKey().contextPrefix(), entry.getKey().headingHierarchy(),
                        entry.getValue(), "reranked", entry.getKey().metadata()))
                .toList();
    }

    private double scoreCandidate(String query, DocumentSearchResult candidate) {
        var prompt = """
                请评估以下查询和文档的相关性，返回 0.0 到 1.0 之间的分数。
                只返回数字，不要其他内容。
                
                查询：%s
                文档：%s""".formatted(query, candidate.content());

        var response = llmRouter.call(SCENE, prompt, null);
        try {
            return Double.parseDouble(response.content().trim());
        } catch (NumberFormatException e) {
            return candidate.score();
        }
    }
}

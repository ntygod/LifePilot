package com.lifepilot.knowledge.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
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
                请评估以下查询和文档的相关性，返回 JSON 格式：
                {"score": 0.85}
                
                查询：%s
                文档：%s""".formatted(query, candidate.content());

        try {
            // 优先使用 callEntity 进行类型安全解析
            ScoreResponse response = llmRouter.callEntity(SCENE, prompt, ScoreResponse.class);
            if (response != null && response.score() != null) {
                return Math.max(0.0, Math.min(1.0, response.score()));
            }
        } catch (Exception e) {
            log.debug("LLM 精排 callEntity 解析失败，降级到手动解析: error={}", e.getMessage());
        }
        
        // 降级到手动解析
        try {
            var response = llmRouter.call(SCENE, prompt, null);
            return Double.parseDouble(response.content().trim());
        } catch (NumberFormatException e) {
            log.debug("LLM 精排手动解析失败，使用原始分数: error={}", e.getMessage());
            return candidate.score();
        } catch (LlmUnavailableException e) {
            log.warn("LLM 精排不可用，使用原始分数: {}", e.getMessage());
            return candidate.score();
        }
    }
    
    /**
     * 评分响应结构（用于 callEntity 解析）。
     */
    private record ScoreResponse(
            @JsonProperty("score") Double score
    ) {}
}

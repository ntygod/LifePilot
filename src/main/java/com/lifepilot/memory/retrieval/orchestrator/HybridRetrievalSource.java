package com.lifepilot.memory.retrieval.orchestrator;

import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 包装既有 {@link HybridRetriever} 为 {@link SourceAdapter}。
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class HybridRetrievalSource implements SourceAdapter {

    private final HybridRetriever hybridRetriever;

    public HybridRetrievalSource(HybridRetriever hybridRetriever) {
        this.hybridRetriever = Objects.requireNonNull(hybridRetriever, "hybridRetriever 不能为空");
    }

    @Override
    public String name() { return "hybrid"; }

    @Override
    public List<EvidenceItem> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("检索 source topK 必须大于 0: " + topK);
        }
        List<RetrievalResult> results = Objects.requireNonNull(
                hybridRetriever.retrieve(query, topK, RetrievalWeights.DEFAULT),
                "HybridRetriever 返回结果不能为空");
        return results.stream()
                .map(r -> new EvidenceItem(
                        r.entityId(),
                        r.entityType(),
                        r.name(),
                        r.description(),
                        r.fusedScore(),
                        "hybrid:" + r.sourcePath(),
                        r.importanceScore(),
                        Map.of(
                                "isHistorical", r.isHistorical(),
                                "isStale", r.isStale(),
                                "needsRevalidation", r.needsRevalidation()
                        )
                ))
                .toList();
    }
}

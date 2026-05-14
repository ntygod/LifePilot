package com.lifepilot.memory.retrieval.orchestrator;

import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 包装既有 {@link HybridRetriever} 为 {@link SourceAdapter}。
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class HybridRetrievalSource implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalSource.class);

    @Nullable
    private final HybridRetriever hybridRetriever;

    public HybridRetrievalSource(@Nullable HybridRetriever hybridRetriever) {
        this.hybridRetriever = hybridRetriever;
    }

    @Override
    public String name() { return "hybrid"; }

    @Override
    public boolean isAvailable() { return hybridRetriever != null; }

    @Override
    public List<EvidenceItem> retrieve(String query, int topK) {
        if (hybridRetriever == null || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        try {
            List<RetrievalResult> results = hybridRetriever.retrieve(query, topK, RetrievalWeights.DEFAULT);
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
        } catch (Exception e) {
            log.debug("HybridRetrievalSource retrieve failed: {}", e.getMessage());
            return List.of();
        }
    }
}

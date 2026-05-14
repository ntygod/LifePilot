package com.lifepilot.memory.retrieval.orchestrator;

import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索源 — 委派给 {@link DocumentRetriever} 执行向量 + FTS + 图融合检索。
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class KnowledgeBaseSource implements SourceAdapter {

    @Nullable
    private final DocumentRetriever documentRetriever;
    @Nullable
    private final KnowledgeBaseRepository kbRepository;

    /** 无参构造 — 保持向后兼容，{@code isAvailable()} 返回 false。 */
    public KnowledgeBaseSource() {
        this(null, null);
    }

    public KnowledgeBaseSource(@Nullable DocumentRetriever documentRetriever,
                               @Nullable KnowledgeBaseRepository kbRepository) {
        this.documentRetriever = documentRetriever;
        this.kbRepository = kbRepository;
    }

    @Override
    public String name() { return "knowledge-base"; }

    @Override
    public boolean isAvailable() { return documentRetriever != null && kbRepository != null; }

    @Override
    public List<EvidenceItem> retrieve(String query, int topK) {
        if (documentRetriever == null || kbRepository == null) return List.of();
        var allKbs = kbRepository.findAll();
        if (allKbs.isEmpty()) return List.of();
        var kbIds = allKbs.stream().map(KnowledgeBase::id).toList();
        var results = documentRetriever.retrieve(query, kbIds, topK);
        if (results == null || results.isEmpty()) return List.of();
        return results.stream()
                .map(r -> new EvidenceItem(
                        r.chunkId(),
                        "KB_CHUNK",
                        r.chunkId(),
                        r.content(),
                        (float) r.score(),
                        "knowledge-base",
                        (float) r.score(),
                        Map.of("knowledgeBaseId", r.knowledgeBaseId(),
                               "documentId", r.documentId())))
                .toList();
    }
}

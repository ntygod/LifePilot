package com.lifepilot.memory.retrieval.orchestrator;

import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 知识库检索源 — 委派给 {@link DocumentRetriever} 执行向量 + FTS + 图融合检索。
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class KnowledgeBaseSource implements SourceAdapter {

    private final DocumentRetriever documentRetriever;
    private final KnowledgeBaseRepository kbRepository;

    public KnowledgeBaseSource(DocumentRetriever documentRetriever,
                               KnowledgeBaseRepository kbRepository) {
        this.documentRetriever = Objects.requireNonNull(documentRetriever, "documentRetriever 不能为空");
        this.kbRepository = Objects.requireNonNull(kbRepository, "kbRepository 不能为空");
    }

    @Override
    public String name() { return "knowledge-base"; }

    @Override
    public List<EvidenceItem> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("检索 source topK 必须大于 0: " + topK);
        }
        var allKbs = Objects.requireNonNull(kbRepository.findAll(), "知识库列表不能为空");
        if (allKbs.isEmpty()) return List.of();
        var kbIds = allKbs.stream().map(KnowledgeBase::id).toList();
        var results = Objects.requireNonNull(
                documentRetriever.retrieve(query, kbIds, topK),
                "DocumentRetriever 返回结果不能为空");
        if (results.isEmpty()) return List.of();
        return results.stream()
                .map(r -> new EvidenceItem(
                        r.chunkId(),
                        "KB_CHUNK",
                        r.chunkId(),
                        r.content(),
                        (float) r.score(),
                        "knowledge-base",
                        1.0f,
                        Map.of("knowledgeBaseId", r.knowledgeBaseId(),
                               "documentId", r.documentId())))
                .toList();
    }
}

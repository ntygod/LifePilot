package com.lifepilot.memory.retrieval.orchestrator;

import java.util.List;

/**
 * 知识库检索源 — 占位实现。
 *
 * <p>本 spec 暂不集成知识库（避免扩大改动范围）；{@code isAvailable()} 返回 false，
 * {@link RetrievalOrchestrator} 会跳过。未来真实集成时替换实现即可。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class KnowledgeBaseSource implements SourceAdapter {

    @Override
    public String name() { return "knowledge-base"; }

    @Override
    public boolean isAvailable() { return false; }

    @Override
    public List<EvidenceItem> retrieve(String query, int topK) {
        return List.of();
    }
}

package com.lifepilot.memory.retrieval.orchestrator;

import java.util.List;

/**
 * 检索源适配器 — 把各类底层检索能力统一为"给定 query 返回 {@link EvidenceItem} 列表"。
 *
 * <p>sealed 避免匿名类；新增 source 类型需在 permits 列表显式声明。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public sealed interface SourceAdapter
        permits HybridRetrievalSource, ExperienceRetrievalSource, KnowledgeBaseSource {

    /** 适配器名称（如 "hybrid" / "experience" / "knowledge-base"）。 */
    String name();

    /** 给定 query 返回最多 topK 个证据 item。 */
    List<EvidenceItem> retrieve(String query, int topK);

    /** 适配器当前是否可用（底层依赖缺失时应返回 false，Orchestrator 会跳过）。 */
    boolean isAvailable();
}

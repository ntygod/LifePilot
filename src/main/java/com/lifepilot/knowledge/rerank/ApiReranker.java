package com.lifepilot.knowledge.rerank;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * API 精排器 — 调用外部 Reranker API（Jina, Cohere 等）进行精排。
 *
 * <p>当前为降级实现，直接返回前 topK 条原始结果。
 * 后续接入具体 Reranker API 后替换为真实精排逻辑。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public final class ApiReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(ApiReranker.class);

    private final KnowledgeBaseProperties.Reranker config;

    public ApiReranker(KnowledgeBaseProperties.Reranker config) {
        this.config = config;
        log.info("ApiReranker 初始化: model={}", config.model());
    }

    @Override
    public List<DocumentSearchResult> rerank(String query, List<DocumentSearchResult> candidates, int topK) {
        // 降级实现：直接返回前 topK 条结果
        log.warn("ApiReranker 尚未接入外部 API，返回原始结果");
        return candidates.stream().limit(topK).toList();
    }
}

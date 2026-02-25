package com.lifepilot.knowledge.rerank;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * API 精排器 — 调用外部 Reranker API（Jina, Cohere 等）进行精排。
 *
 * <p>当前为占位实现，后续接入具体 API。降级时返回原始结果。
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
        // 占位实现：后续接入 Jina/Cohere API
        log.warn("ApiReranker 尚未接入外部 API，返回原始结果");
        return candidates.stream().limit(topK).toList();
    }
}

package com.lifepilot.knowledge.rerank;

import com.lifepilot.knowledge.model.DocumentSearchResult;

import java.util.List;

/**
 * 精排接口 — 对初步检索结果进行二次排序。
 *
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface Reranker permits LlmReranker, ApiReranker {

    /**
     * 对候选结果进行精排。
     *
     * @param query      查询文本
     * @param candidates 候选结果列表
     * @param topK       返回数量
     * @return 精排后的结果列表
     */
    List<DocumentSearchResult> rerank(String query, List<DocumentSearchResult> candidates, int topK);
}

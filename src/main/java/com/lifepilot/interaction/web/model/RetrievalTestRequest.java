package com.lifepilot.interaction.web.model;

/**
 * 知识库测试检索请求。
 *
 * @param query 查询问题
 * @param topK  返回结果数量（默认 5）
 * @author zsg
 * @since 2026-03-02
 */
public record RetrievalTestRequest(
        String query,
        Integer topK
) {}

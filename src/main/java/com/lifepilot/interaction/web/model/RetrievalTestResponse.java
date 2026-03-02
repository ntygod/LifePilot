package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 知识库测试检索响应。
 *
 * @param chunks 命中的文档分段列表
 * @param answer 基于检索内容的回答（可选）
 */
public record RetrievalTestResponse(
        List<ChunkResult> chunks,
        String answer
) {}

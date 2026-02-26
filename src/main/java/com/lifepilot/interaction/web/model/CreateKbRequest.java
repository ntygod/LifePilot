package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 创建知识库请求。
 *
 * @param name           知识库名称
 * @param description    知识库描述
 * @param embeddingModel 嵌入模型（可选）
 * @author zsg
 * @since 2026-02-27
 */
public record CreateKbRequest(
        String name,
        String description,
        @Nullable String embeddingModel
) {
}

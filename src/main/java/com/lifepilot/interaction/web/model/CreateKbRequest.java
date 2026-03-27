package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 创建知识库请求。
 *
 * @param name             知识库名称
 * @param description      知识库描述
 * @param embeddingModel   嵌入模型（可选）
 * @param rerankerModel    重排序模型（可选）
 * @param chunkingStrategy 分块策略（可选，默认 "smart"）
 * @param chunkingConfig   分块配置参数（可选）
 * @param tags             标签列表（可选）
 * @author zsg
 * @since 2026-02-27
 */
public record CreateKbRequest(
        String name,
        String description,
        @Nullable String embeddingModel,
        @Nullable String rerankerModel,
        @Nullable String chunkingStrategy,
        @Nullable Map<String, Object> chunkingConfig,
        @Nullable List<String> tags,
        @Nullable List<String> datastoreIds
) {
}

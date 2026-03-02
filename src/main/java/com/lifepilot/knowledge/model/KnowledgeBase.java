package com.lifepilot.knowledge.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识库 — 文档的逻辑分组容器。
 *
 * <p>每个知识库独立配置 Embedding 模型和分块策略，
 * 作为文档管理和检索的顶层组织单元。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public record KnowledgeBase(
        String id,
        String name,
        String description,
        String embeddingModel,
        Optional<String> rerankerModel,
        String chunkingStrategy,
        Map<String, Object> chunkingConfig,
        int documentCount,
        int totalChunks,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 创建新知识库的工厂方法。
     *
     * <p>自动生成 UUID，设置默认分块策略为 "smart"，
     * 文档数和分块数初始化为 0。</p>
     *
     * @param name           知识库名称
     * @param description    知识库描述
     * @param embeddingModel Embedding 模型标识
     * @return 新创建的知识库实例
     */
    public static KnowledgeBase create(String name, String description,
                                       String embeddingModel) {
        Instant now = Instant.now();
        return new KnowledgeBase(
                UUID.randomUUID().toString(),
                name, description, embeddingModel,
                Optional.empty(), "smart", Map.of(),
                0, 0, List.of(), now, now);
    }
}

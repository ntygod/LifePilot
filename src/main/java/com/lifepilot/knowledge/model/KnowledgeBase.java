package com.lifepilot.knowledge.model;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        @Nullable String rerankerModel,
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
     * <p>自动生成 UUID，文档数和分块数初始化为 0。
     * 可选参数为 null 时使用默认值。</p>
     *
     * @param name             知识库名称
     * @param description      知识库描述
     * @param embeddingModel   Embedding 模型标识
     * @param rerankerModel    重排序模型标识（可选）
     * @param chunkingStrategy 分块策略（可选，默认 "smart"）
     * @param chunkingConfig   分块配置参数（可选）
     * @param tags             标签列表（可选）
     * @return 新创建的知识库实例
     */
    public static KnowledgeBase create(String name, String description,
                                       String embeddingModel,
                                       @Nullable String rerankerModel,
                                       @Nullable String chunkingStrategy,
                                       @Nullable Map<String, Object> chunkingConfig,
                                       @Nullable List<String> tags) {
        Instant now = Instant.now();
        return new KnowledgeBase(
                UUID.randomUUID().toString(),
                name, description, embeddingModel,
                rerankerModel,
                chunkingStrategy != null ? chunkingStrategy : "smart",
                chunkingConfig != null ? chunkingConfig : Map.of(),
                0, 0,
                tags != null ? tags : List.of(),
                now, now);
    }
}

package com.lifepilot.datastore.model;

import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 数据集合 — 文档的逻辑容器。
 *
 * <p>每个集合具有唯一名称和类型（DOCUMENT / NOTE / METRIC），
 * 可选的属性定义（propertiesJson）用于类型校验和 Generated Column 索引，
 * 可选的元数据（metadataJson）用于存储扩展信息。</p>
 *
 * @param id             集合唯一标识（UUID）
 * @param name           集合名称（唯一）
 * @param description    集合描述（可选）
 * @param type           集合类型
 * @param propertiesJson 属性定义 JSON（可选，List&lt;PropertyDefinition&gt; 序列化）
 * @param projectionConfigJson 向量投影配置 JSON（可选）
 * @param metadataJson   元数据 JSON（可选）
 * @param createdBy      创建者（可选）
 * @param createdAt      创建时间（ISO 8601）
 * @param updatedAt      更新时间（ISO 8601）
 * @author zsg
 * @since 2026-03-10
 */
@Builder(toBuilder = true)
public record Collection(
        String id,
        String name,
        @Nullable String description,
        CollectionType type,
        @Nullable String propertiesJson,
        @Nullable String projectionConfigJson,
        @Nullable String metadataJson,
        @Nullable String defaultKnowledgeBaseId,
        @Nullable String createdBy,
        String createdAt,
        String updatedAt
) {

    public Collection(
            String id,
            String name,
            @Nullable String description,
            CollectionType type,
            @Nullable String propertiesJson,
            @Nullable String projectionConfigJson,
            @Nullable String metadataJson,
            @Nullable String createdBy,
            String createdAt,
            String updatedAt
    ) {
        this(id, name, description, type, propertiesJson, projectionConfigJson, metadataJson,
                null, createdBy, createdAt, updatedAt);
    }
}

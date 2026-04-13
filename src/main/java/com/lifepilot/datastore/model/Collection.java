package com.lifepilot.datastore.model;

import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 数据集合 — 文档的逻辑容器。
 *
 * <p>每个集合绑定一个内部知识库，文档直接存入知识库表。
 * {@code timeSeries} 标记决定是否为时序集合（支持 recordedAt 和聚合查询）。</p>
 *
 * @param id                     集合唯一标识（UUID）
 * @param name                   集合名称（唯一）
 * @param description            集合描述（可选）
 * @param timeSeries             是否为时序集合
 * @param fieldHintsJson         字段提示 JSON（可选，List&lt;FieldHint&gt; 序列化）
 * @param defaultKnowledgeBaseId 绑定的内部知识库 ID
 * @param createdBy              创建者（可选）
 * @param createdAt              创建时间（ISO 8601）
 * @param updatedAt              更新时间（ISO 8601）
 * @author zsg
 * @since 2026-04-13
 */
@Builder(toBuilder = true)
public record Collection(
        String id,
        String name,
        @Nullable String description,
        boolean timeSeries,
        @Nullable String fieldHintsJson,
        @Nullable String defaultKnowledgeBaseId,
        @Nullable String createdBy,
        String createdAt,
        String updatedAt
) {}

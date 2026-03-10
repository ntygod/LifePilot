package com.lifepilot.datastore.model;

import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 文档 — 集合中的数据条目。
 *
 * <p>文档以 JSON 格式存储数据（dataJson），归属于某个集合。
 * METRIC 类型集合的文档必须包含 recordedAt 时间戳用于时序聚合。</p>
 *
 * @param id           文档唯一标识（UUID）
 * @param collectionId 所属集合 ID
 * @param dataJson     文档数据 JSON
 * @param recordedAt   记录时间（可选，METRIC 类型必填，ISO 8601）
 * @param createdAt    创建时间（ISO 8601）
 * @param updatedAt    更新时间（ISO 8601）
 * @author zsg
 * @since 2026-03-10
 */
@Builder(toBuilder = true)
public record Document(
        String id,
        String collectionId,
        String dataJson,
        @Nullable String recordedAt,
        String createdAt,
        String updatedAt
) {
}

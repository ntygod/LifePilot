package com.lifepilot.datastore.model;

import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 文档 — 集合中的数据条目。
 *
 * <p>文档以 JSON 格式存储数据（dataJson），归属于某个集合。
 * METRIC 类型集合的文档必须包含 recordedAt 时间戳用于时序聚合。</p>
 *
 * <p>{@code sourceType} 区分文档类型：DATA（结构化数据，默认）和 FILE_REF（文件引用元数据）。
 * 文件引用文档的 dataJson 存储文件元信息（fileName/fileSize/mimeType），
 * {@code knowledgeDocumentId} 关联知识库中的实际文档记录。</p>
 *
 * @param id                    文档唯一标识（UUID）
 * @param collectionId          所属集合 ID
 * @param dataJson              文档数据 JSON
 * @param recordedAt            记录时间（可选，METRIC 类型必填，ISO 8601）
 * @param sourceType            文档来源类型（DATA / FILE_REF，默认 DATA）
 * @param knowledgeDocumentId   关联的知识库文档 ID（仅 FILE_REF 类型使用）
 * @param createdAt             创建时间（ISO 8601）
 * @param updatedAt             更新时间（ISO 8601）
 * @author zsg
 * @since 2026-03-10
 */
@Builder(toBuilder = true)
public record Document(
        String id,
        String collectionId,
        String dataJson,
        @Nullable String recordedAt,
        @Nullable String sourceType,
        @Nullable String knowledgeDocumentId,
        String createdAt,
        String updatedAt
) {

    /** 结构化数据文档。 */
    public static final String SOURCE_TYPE_DATA = "DATA";

    /** 文件引用元数据文档。 */
    public static final String SOURCE_TYPE_FILE_REF = "FILE_REF";

    /** 向后兼容的6参数构造器。 */
    public Document(String id, String collectionId, String dataJson,
                    @Nullable String recordedAt, String createdAt, String updatedAt) {
        this(id, collectionId, dataJson, recordedAt, SOURCE_TYPE_DATA, null, createdAt, updatedAt);
    }
}

package com.lifepilot.interaction.web.model;

import java.util.Map;

/**
 * 检索结果中的单个文档分段。
 *
 * @param chunkId      分块 ID
 * @param documentId   文档 ID
 * @param documentName 文档名称
 * @param content      分段内容
 * @param score        相似度分数
 * @param metadata     元数据
 */
public record ChunkResult(
        String chunkId,
        String documentId,
        String documentName,
        String content,
        Double score,
        Map<String, String> metadata,
        String sourceType,
        String sourceDatastoreId,
        String sourceCollectionId
) {

    public ChunkResult(
            String chunkId,
            String documentId,
            String documentName,
            String content,
            Double score,
            Map<String, String> metadata
    ) {
        this(chunkId, documentId, documentName, content, score, metadata, null, null, null);
    }
}

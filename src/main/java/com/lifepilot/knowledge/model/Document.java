package com.lifepilot.knowledge.model;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * 文档 — 知识库中的单个文档记录。
 *
 * <p>包含文件元数据、处理状态和关联的知识库信息。
 * 通过 {@link DocumentStatus} 跟踪文档从上传到就绪的完整生命周期。</p>
 *
 * <p>Datastore 文档使用 {@code content} 字段存储富文本正文，
 * {@code recordedAt} 字段存储时序集合的时间戳。
 * 文件类文档这两个字段为 null。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public record Document(
        String id,
        String knowledgeBaseId,
        String fileName,
        String filePath,
        long fileSize,
        String mimeType,
        String contentHash,
        DocumentStatus status,
        int chunkCount,
        int entityCount,
        @Nullable String errorMessage,
        @Nullable String lastProcessedStage,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        DocumentSourceType sourceType,
        String sourceKey,
        @Nullable String sourceDatastoreId,
        @Nullable String sourceCollectionId,
        Map<String, Object> sourceRef,
        @Nullable String content,
        @Nullable String recordedAt
) {

    public Document {
        sourceType = sourceType != null ? sourceType : DocumentSourceType.FILE;
        sourceKey = sourceKey != null && !sourceKey.isBlank() ? sourceKey : "FILE:" + id;
        sourceRef = sourceRef != null ? Map.copyOf(sourceRef) : Map.of();
    }

    /** 向后兼容构造函数 — 文件类文档使用，content 和 recordedAt 为 null。 */
    public Document(
            String id,
            String knowledgeBaseId,
            String fileName,
            String filePath,
            long fileSize,
            String mimeType,
            String contentHash,
            DocumentStatus status,
            int chunkCount,
            int entityCount,
            @Nullable String errorMessage,
            @Nullable String lastProcessedStage,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, knowledgeBaseId, fileName, filePath, fileSize, mimeType, contentHash, status,
                chunkCount, entityCount, errorMessage, lastProcessedStage, metadata, createdAt,
                updatedAt, DocumentSourceType.FILE, "FILE:" + id, null, null, Map.of(),
                null, null);
    }

    /** 带来源信息的构造函数 — 向后兼容，content 和 recordedAt 为 null。 */
    public Document(
            String id,
            String knowledgeBaseId,
            String fileName,
            String filePath,
            long fileSize,
            String mimeType,
            String contentHash,
            DocumentStatus status,
            int chunkCount,
            int entityCount,
            @Nullable String errorMessage,
            @Nullable String lastProcessedStage,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt,
            DocumentSourceType sourceType,
            String sourceKey,
            @Nullable String sourceDatastoreId,
            @Nullable String sourceCollectionId,
            Map<String, Object> sourceRef
    ) {
        this(id, knowledgeBaseId, fileName, filePath, fileSize, mimeType, contentHash, status,
                chunkCount, entityCount, errorMessage, lastProcessedStage, metadata, createdAt,
                updatedAt, sourceType, sourceKey, sourceDatastoreId, sourceCollectionId, sourceRef,
                null, null);
    }
}

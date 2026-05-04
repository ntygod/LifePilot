package com.lifepilot.knowledge.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * 文档 — 知识库中的单个文档记录。
 *
 * <p>包含文件元数据、处理状态和关联的知识库信息。
 * 通过 {@link DocumentStatus} 跟踪文档从上传到就绪的完整生命周期。</p>
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
        @JsonIgnore
        DocumentSourceType sourceType,
        @JsonIgnore
        String sourceKey,
        @JsonIgnore
        Map<String, Object> sourceRef
) {

    public Document {
        sourceType = sourceType != null ? sourceType : DocumentSourceType.FILE;
        sourceKey = sourceKey != null && !sourceKey.isBlank() ? sourceKey : "FILE:" + id;
        sourceRef = sourceRef != null ? Map.copyOf(sourceRef) : Map.of();
    }

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
                updatedAt, DocumentSourceType.FILE, "FILE:" + id, Map.of());
    }

}

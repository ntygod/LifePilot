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
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt
) {}

package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;

import java.time.Instant;

/**
 * 记忆来源摘要 DTO。
 *
 * @author zsg
 * @since 2026-03-27
 */
public record MemoryProvenanceSummaryDto(
        String entityId,
        String entityName,
        String entityType,
        String entityTypeLabel,
        @Nullable String entityMemoryScope,
        @Nullable String entityRealityType,
        String originType,
        @Nullable String sourceReference,
        @Nullable String sourceConversationId,
        @Nullable String sourceSessionId,
        @Nullable String sourceTurnId,
        @Nullable String sourceEntryId,
        @Nullable String sourceDocumentId,
        @Nullable String sourceDocumentName,
        @Nullable String sourceKnowledgeBaseId,
        @Nullable String sourceKnowledgeBaseName,
        float confidence,
        Instant createdAt
) {}

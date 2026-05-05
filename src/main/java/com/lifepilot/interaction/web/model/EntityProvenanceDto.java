package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;

import java.time.Instant;

/**
 * 实体来源明细 DTO。
 *
 * @author zsg
 * @since 2026-03-27
 */
public record EntityProvenanceDto(
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
        @Nullable String evidenceKind,
        @Nullable String trustLevel,
        float trustScore,
        @Nullable String evidenceExcerpt,
        float confidence,
        Instant createdAt
) {}

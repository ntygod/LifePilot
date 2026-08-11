package com.lifepilot.memory.store.scope;

import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * 记忆写入上下文。
 *
 * @author zsg
 * @since 2026-03-27
 */
public record MemoryWriteContext(
        @Nullable String spaceId,
        @Nullable MemoryScope memoryScope,
        MemoryOriginType originType,
        MemoryRealityType realityType,
        @Nullable String sourceReference,
        @Nullable String sourceConversationId,
        @Nullable String sourceSessionId,
        @Nullable String sourceTurnId,
        @Nullable String sourceEntryId,
        @Nullable String sourceDocumentId,
        @Nullable String sourceKnowledgeBaseId,
        @Nullable String evidenceExcerpt
) {

    public MemoryWriteContext {
        Objects.requireNonNull(originType, "记忆来源类型不能为空");
        Objects.requireNonNull(realityType, "记忆现实类型不能为空");
    }

    public MemoryWriteContext(
            @Nullable String spaceId,
            @Nullable MemoryScope memoryScope,
            MemoryOriginType originType,
            MemoryRealityType realityType,
            @Nullable String sourceReference,
            @Nullable String sourceConversationId,
            @Nullable String sourceSessionId,
            @Nullable String sourceTurnId,
            @Nullable String sourceEntryId,
            @Nullable String sourceDocumentId,
            @Nullable String sourceKnowledgeBaseId
    ) {
        this(spaceId, memoryScope, originType, realityType, sourceReference,
                sourceConversationId, sourceSessionId, sourceTurnId, sourceEntryId,
                sourceDocumentId, sourceKnowledgeBaseId, null);
    }

    public MemoryWriteContext withEvidenceExcerpt(@Nullable String newEvidenceExcerpt) {
        return new MemoryWriteContext(
                spaceId,
                memoryScope,
                originType,
                realityType,
                sourceReference,
                sourceConversationId,
                sourceSessionId,
                sourceTurnId,
                sourceEntryId,
                sourceDocumentId,
                sourceKnowledgeBaseId,
                newEvidenceExcerpt);
    }

    public static MemoryWriteContext conversation(@Nullable String conversationId) {
        return new MemoryWriteContext(
                null,
                null,
                MemoryOriginType.CHAT,
                MemoryRealityType.UNKNOWN,
                conversationId,
                conversationId,
                conversationId,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static MemoryWriteContext consolidation(String sourceReference) {
        return fromSource(MemoryOriginType.CONSOLIDATION, sourceReference);
    }

    public static MemoryWriteContext manual(String sourceReference) {
        return fromSource(MemoryOriginType.MANUAL, sourceReference);
    }

    public static MemoryWriteContext tool(String sourceReference) {
        return fromSource(MemoryOriginType.TOOL, sourceReference);
    }

    public static MemoryWriteContext fromSource(MemoryOriginType originType,
                                                @Nullable String sourceReference) {
        return new MemoryWriteContext(
                null,
                null,
                originType,
                MemoryRealityType.UNKNOWN,
                sourceReference,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}

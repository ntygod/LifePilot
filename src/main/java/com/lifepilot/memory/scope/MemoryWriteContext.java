package com.lifepilot.memory.scope;

import org.springframework.lang.Nullable;

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
        @Nullable String sourceKnowledgeBaseId
) {

    public MemoryWriteContext {
        originType = originType != null ? originType : MemoryOriginType.UNKNOWN;
        realityType = realityType != null ? realityType : MemoryRealityType.UNKNOWN;
    }

    public static MemoryWriteContext empty() {
        return new MemoryWriteContext(
                null,
                null,
                MemoryOriginType.UNKNOWN,
                MemoryRealityType.UNKNOWN,
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

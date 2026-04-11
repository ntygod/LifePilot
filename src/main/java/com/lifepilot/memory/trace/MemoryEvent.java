package com.lifepilot.memory.trace;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆事件 — 统一描述记忆系统中的关键操作，用于审计与可观测性。
 *
 * <p>典型事件类型示例：
 * FORMATION / RETRIEVAL / CONSOLIDATION / FORGETTING / COMPRESSION / UPDATING / L1_FLUSH / L1_APPEND。
 * </p>
 *
 * @author zsg
 * @since 2026-03-05
 */
public record MemoryEvent(
        String id,
        String eventType,
        String layer,
        String sessionId,
        String conversationId,
        String entityId,
        String action,
        String description,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public static MemoryEvent create(String eventType,
                                     String layer,
                                     String sessionId,
                                     String conversationId,
                                     String entityId,
                                     String action,
                                     String description,
                                     Map<String, Object> metadata) {
        return new MemoryEvent(
                UUID.randomUUID().toString(),
                eventType,
                layer,
                sessionId,
                conversationId,
                entityId,
                action,
                description,
                metadata,
                Instant.now()
        );
    }
}


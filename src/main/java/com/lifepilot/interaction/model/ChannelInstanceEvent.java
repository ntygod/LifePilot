package com.lifepilot.interaction.model;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * 渠道实例运行事件。
 *
 * @author zsg
 * @since 2026-03-29
 */
public record ChannelInstanceEvent(
        String id,
        String instanceId,
        String eventType,
        @Nullable String message,
        @Nullable Map<String, Object> payload,
        Instant createdAt
) {

    public ChannelInstanceEvent {
        payload = payload != null ? Map.copyOf(payload) : null;
        createdAt = createdAt != null ? createdAt : Instant.now();
    }
}

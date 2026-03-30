package com.lifepilot.interaction.model;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * 渠道实例。
 *
 * @author zsg
 * @since 2026-03-29
 */
public record ChannelInstance(
        String instanceId,
        String pluginId,
        String platform,
        String displayName,
        boolean enabled,
        ChannelInstanceStatus status,
        Map<String, Object> config,
        @Nullable Map<String, Object> secretConfig,
        @Nullable Map<String, Object> routingPolicy,
        @Nullable Instant lastHeartbeatAt,
        @Nullable String lastError,
        Instant createdAt,
        Instant updatedAt
) {

    public ChannelInstance {
        status = status != null ? status : ChannelInstanceStatus.CREATED;
        config = config != null ? Map.copyOf(config) : Map.of();
        secretConfig = secretConfig != null ? Map.copyOf(secretConfig) : null;
        routingPolicy = routingPolicy != null ? Map.copyOf(routingPolicy) : null;
        createdAt = createdAt != null ? createdAt : Instant.now();
        updatedAt = updatedAt != null ? updatedAt : createdAt;
    }
}

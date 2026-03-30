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

    /** 变更状态、错误信息和心跳时间，保留其余字段不变。 */
    public ChannelInstance withStatus(ChannelInstanceStatus newStatus,
                                     @Nullable String newLastError,
                                     @Nullable Instant newLastHeartbeatAt) {
        return new ChannelInstance(
                instanceId, pluginId, platform, displayName, enabled,
                newStatus, config, secretConfig, routingPolicy,
                newLastHeartbeatAt, newLastError,
                createdAt, Instant.now()
        );
    }

    /** 变更启用状态，保留其余字段不变。 */
    public ChannelInstance withEnabled(boolean newEnabled) {
        return new ChannelInstance(
                instanceId, pluginId, platform, displayName, newEnabled,
                status, config, secretConfig, routingPolicy,
                lastHeartbeatAt, lastError,
                createdAt, Instant.now()
        );
    }

    /** 替换 secretConfig，保留其余字段不变。 */
    public ChannelInstance withSecretConfig(@Nullable Map<String, Object> newSecretConfig) {
        return new ChannelInstance(
                instanceId, pluginId, platform, displayName, enabled,
                status, config, newSecretConfig, routingPolicy,
                lastHeartbeatAt, lastError,
                createdAt, Instant.now()
        );
    }
}

package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 创建渠道实例请求。
 *
 * @author zsg
 * @since 2026-03-29
 */
public record CreateChannelInstanceRequest(
        String pluginId,
        @Nullable String instanceId,
        @Nullable String displayName,
        @Nullable Map<String, Object> config,
        @Nullable Map<String, Object> secretConfig,
        @Nullable Map<String, Object> routingPolicy,
        @Nullable Boolean enabled
) {

    public CreateChannelInstanceRequest {
        config = config != null ? Map.copyOf(config) : Map.of();
        secretConfig = secretConfig != null ? Map.copyOf(secretConfig) : null;
        routingPolicy = routingPolicy != null ? Map.copyOf(routingPolicy) : null;
        enabled = enabled != null ? enabled : Boolean.TRUE;
    }
}

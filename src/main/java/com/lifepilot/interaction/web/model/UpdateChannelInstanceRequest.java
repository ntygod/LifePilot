package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 更新渠道实例请求。
 *
 * @author zsg
 * @since 2026-03-29
 */
public record UpdateChannelInstanceRequest(
        @Nullable String displayName,
        @Nullable Boolean enabled,
        @Nullable Map<String, Object> config,
        @Nullable Map<String, Object> secretConfig,
        @Nullable Map<String, Object> routingPolicy
) {

    public UpdateChannelInstanceRequest {
        config = config != null ? Map.copyOf(config) : null;
        secretConfig = secretConfig != null ? Map.copyOf(secretConfig) : null;
        routingPolicy = routingPolicy != null ? Map.copyOf(routingPolicy) : null;
    }
}

package com.lifepilot.interaction.runtime.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 渠道实例运行时命令请求。
 *
 * @author zsg
 * @since 2026-03-29
 */
public record ChannelRuntimeInstanceCommandRequest(
        String instanceId,
        String pluginId,
        String platform,
        Map<String, Object> config,
        @Nullable Map<String, Object> secretConfig,
        @Nullable Map<String, Object> routingPolicy
) {

    public ChannelRuntimeInstanceCommandRequest {
        config = config != null ? Map.copyOf(config) : Map.of();
        secretConfig = secretConfig != null ? Map.copyOf(secretConfig) : null;
        routingPolicy = routingPolicy != null ? Map.copyOf(routingPolicy) : null;
    }
}

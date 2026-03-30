package com.lifepilot.interaction.model;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 渠道插件描述。
 *
 * @author zsg
 * @since 2026-03-29
 */
public record ChannelPluginDescriptor(
        String pluginId,
        String name,
        String version,
        String vendor,
        String platform,
        ConnectorMode connectorMode,
        @Nullable Map<String, Object> connectorSpec,
        List<String> capabilities,
        Map<String, Object> configSchema,
        List<String> secretFields,
        @Nullable Map<String, Object> setupGuide,
        @Nullable ChannelPluginResources resources
) {

    public ChannelPluginDescriptor {
        connectorMode = connectorMode != null ? connectorMode : ConnectorMode.EXTERNAL;
        connectorSpec = connectorSpec != null ? Map.copyOf(connectorSpec) : null;
        capabilities = capabilities != null ? List.copyOf(capabilities) : List.of();
        configSchema = configSchema != null ? Map.copyOf(configSchema) : Map.of();
        secretFields = secretFields != null ? List.copyOf(secretFields) : List.of();
        setupGuide = setupGuide != null ? Map.copyOf(setupGuide) : null;
    }
}

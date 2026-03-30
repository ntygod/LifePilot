package com.lifepilot.interaction.runtime;

import org.springframework.lang.Nullable;

/**
 * 官方 connector 托管规格。
 *
 * @author zsg
 * @since 2026-03-30
 */
public record ManagedConnectorSpec(
        String pluginId,
        String strategy,
        @Nullable String artifactPath,
        @Nullable String workspace,
        String healthPath,
        int preferredPort,
        boolean available
) {
}

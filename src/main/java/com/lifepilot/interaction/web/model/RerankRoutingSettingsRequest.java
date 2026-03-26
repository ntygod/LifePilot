package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 精排路由设置更新请求。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record RerankRoutingSettingsRequest(
        @Nullable Boolean enabled,
        @Nullable String mode,
        @Nullable String nativeServiceId,
        @Nullable String llmServiceId,
        @Nullable Integer knowledgeTopK,
        @Nullable Boolean memoryEnabled,
        @Nullable Integer memoryTopK
) {
}

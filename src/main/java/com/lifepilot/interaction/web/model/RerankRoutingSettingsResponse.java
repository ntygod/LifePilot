package com.lifepilot.interaction.web.model;

/**
 * 精排路由设置响应。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record RerankRoutingSettingsResponse(
        boolean enabled,
        String mode,
        String nativeServiceId,
        String llmServiceId,
        int knowledgeTopK,
        boolean memoryEnabled,
        int memoryTopK
) {
}

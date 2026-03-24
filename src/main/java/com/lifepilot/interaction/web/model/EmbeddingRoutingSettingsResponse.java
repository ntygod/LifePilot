package com.lifepilot.interaction.web.model;

/**
 * 向量路由设置响应。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record EmbeddingRoutingSettingsResponse(
        String defaultServiceId,
        String knowledgeBaseServiceId,
        String memoryServiceId
) {
}

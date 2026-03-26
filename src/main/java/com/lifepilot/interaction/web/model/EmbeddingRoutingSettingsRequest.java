package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 向量路由设置更新请求。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record EmbeddingRoutingSettingsRequest(
        @Nullable String defaultServiceId,
        @Nullable String knowledgeBaseServiceId,
        @Nullable String memoryServiceId
) {
}

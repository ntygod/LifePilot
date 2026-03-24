package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 生成路由设置更新请求。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record GenerationRoutingSettingsRequest(
        @Nullable String defaultServiceId,
        @Nullable Map<String, String> sceneServiceBindings
) {
}

package com.lifepilot.interaction.web.model;

import java.util.Map;

/**
 * 生成路由设置响应。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record GenerationRoutingSettingsResponse(
        String defaultServiceId,
        Map<String, String> sceneServiceBindings
) {
}

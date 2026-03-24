package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 模型服务响应。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record ModelServiceResponse(
        String id,
        String kind,
        String type,
        String apiUrl,
        String modelName,
        int timeoutSeconds,
        int priority,
        boolean enabled,
        List<String> scenes,
        List<String> capabilities,
        Integer costPerInputToken,
        Integer costPerOutputToken,
        Integer maxContextWindow,
        Integer embeddingDimension,
        boolean supportsStreaming,
        boolean isPreset,
        String displayName,
        String description
) {
}

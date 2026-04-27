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
        String profileId,
        String vendorKey,
        String apiUrl,
        String modelName,
        int timeoutSeconds,
        int priority,
        boolean enabled,
        boolean isReasoning,
        String thinkingMode,
        List<String> scenes,
        List<String> capabilities,
        Double costPerInputToken,
        Double costPerOutputToken,
        Integer maxContextWindow,
        Integer embeddingDimension,
        boolean supportsStreaming,
        String displayName,
        String description
) {
}

package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 创建模型服务请求。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record CreateModelServiceRequest(
        String id,
        String kind,
        String type,
        String apiUrl,
        @Nullable String apiKey,
        String modelName,
        @Nullable Integer timeoutSeconds,
        @Nullable Integer priority,
        @Nullable List<String> scenes,
        @Nullable List<String> capabilities,
        @Nullable Boolean enabled,
        @Nullable Integer costPerInputToken,
        @Nullable Integer costPerOutputToken,
        @Nullable Integer maxContextWindow,
        @Nullable Integer embeddingDimension,
        @Nullable Boolean supportsStreaming,
        @Nullable String displayName,
        @Nullable String description
) {
}

package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 模型服务厂商模板响应。
 *
 * @author zsg
 * @since 2026-03-30
 */
public record ModelServiceTemplateResponse(
        String vendorKey,
        String displayName,
        String providerType,
        String description,
        String defaultApiUrl,
        List<String> supportedKinds,
        int defaultTimeoutSeconds,
        List<String> defaultCapabilities,
        List<String> defaultScenes,
        boolean defaultSupportsStreaming,
        @Nullable Integer defaultMaxContextWindow,
        List<ModelServiceTemplateModelResponse> modelOptions
) {
}

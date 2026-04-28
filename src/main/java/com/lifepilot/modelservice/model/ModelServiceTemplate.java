package com.lifepilot.modelservice.model;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 模型服务厂商模板。
 *
 * <p>{@code providerType} 字段保留为字符串标签（来源 model_service_vendor_templates.provider_type）；
 * 仅用于 UI 展示与基础分类，运行时协议特性由各模型服务记录自身的 {@code profileId} 决定。
 *
 * @author zsg
 * @since 2026-03-30
 */
public record ModelServiceTemplate(
        String vendorKey,
        String displayName,
        String providerType,
        String description,
        String defaultApiUrl,
        List<ModelServiceKind> supportedKinds,
        int defaultTimeoutSeconds,
        List<String> defaultCapabilities,
        List<String> defaultScenes,
        boolean defaultSupportsStreaming,
        @Nullable Integer defaultMaxContextWindow,
        List<ModelServiceTemplateModel> modelOptions
) {

    public ModelServiceTemplate {
        Objects.requireNonNull(vendorKey, "vendorKey 不能为空");
        Objects.requireNonNull(displayName, "displayName 不能为空");
        Objects.requireNonNull(providerType, "providerType 不能为空");
        Objects.requireNonNull(description, "description 不能为空");
        Objects.requireNonNull(defaultApiUrl, "defaultApiUrl 不能为空");
        supportedKinds = supportedKinds != null ? List.copyOf(supportedKinds) : List.of();
        defaultCapabilities = defaultCapabilities != null ? List.copyOf(defaultCapabilities) : List.of();
        defaultScenes = defaultScenes != null ? List.copyOf(defaultScenes) : List.of();
        modelOptions = modelOptions != null ? List.copyOf(modelOptions) : List.of();
        if (defaultTimeoutSeconds <= 0) {
            defaultTimeoutSeconds = 60;
        }
    }
}

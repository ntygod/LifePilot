package com.lifepilot.modelservice.model;

import com.lifepilot.llm.config.ProviderType;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 模型服务厂商模板。
 *
 * @author zsg
 * @since 2026-03-30
 */
public record ModelServiceTemplate(
        String vendorKey,
        String displayName,
        ProviderType providerType,
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

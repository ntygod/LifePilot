package com.lifepilot.modelservice.model;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 模型服务模板中的模型预设。
 *
 * @author zsg
 * @since 2026-03-30
 */
public record ModelServiceTemplateModel(
        String vendorKey,
        ModelServiceKind kind,
        String value,
        String label,
        boolean recommended,
        List<String> capabilities,
        List<String> scenes,
        boolean supportsStreaming,
        @Nullable Integer maxContextWindow,
        @Nullable Integer embeddingDimension,
        int sortOrder
) {

    public ModelServiceTemplateModel {
        Objects.requireNonNull(vendorKey, "vendorKey 不能为空");
        Objects.requireNonNull(kind, "kind 不能为空");
        Objects.requireNonNull(value, "模型值不能为空");
        Objects.requireNonNull(label, "模型标签不能为空");
        capabilities = capabilities != null ? List.copyOf(capabilities) : List.of();
        scenes = scenes != null ? List.copyOf(scenes) : List.of();
        if (sortOrder < 0) {
            sortOrder = 0;
        }
    }
}

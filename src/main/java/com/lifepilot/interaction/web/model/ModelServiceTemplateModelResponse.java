package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 模型服务模板模型预设响应。
 *
 * @author zsg
 * @since 2026-03-30
 */
public record ModelServiceTemplateModelResponse(
        String kind,
        String value,
        String label,
        boolean recommended,
        List<String> capabilities,
        List<String> scenes,
        boolean supportsStreaming,
        @Nullable Integer maxContextWindow,
        @Nullable Integer embeddingDimension
) {
}

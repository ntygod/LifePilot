package com.lifepilot.modelservice.model;

import org.springframework.lang.Nullable;

/**
 * 向量化设置实体。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record EmbeddingSettingsEntity(
        String id,
        @Nullable String defaultServiceId,
        @Nullable String knowledgeBaseServiceId,
        @Nullable String memoryServiceId
) {
}

package com.lifepilot.modelservice.model;

import org.springframework.lang.Nullable;

/**
 * 精排设置实体。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record RerankSettingsEntity(
        String id,
        boolean enabled,
        RerankExecutionMode mode,
        @Nullable String nativeServiceId,
        @Nullable String llmServiceId,
        int knowledgeTopK,
        boolean memoryEnabled,
        int memoryTopK
) {

    /**
     * 紧凑构造器，补齐默认值与边界。
     */
    public RerankSettingsEntity {
        if (mode == null) {
            mode = RerankExecutionMode.DISABLED;
        }
        if (knowledgeTopK <= 0) {
            knowledgeTopK = 5;
        }
        if (memoryTopK <= 0) {
            memoryTopK = 10;
        }
    }
}

package com.lifepilot.modelservice.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 生成路由设置实体。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record GenerationSettingsEntity(
        String id,
        @Nullable String defaultServiceId,
        Map<String, String> sceneServiceBindings
) {

    /**
     * 紧凑构造器，确保 Map 不可变。
     */
    public GenerationSettingsEntity {
        sceneServiceBindings = sceneServiceBindings != null ? Map.copyOf(sceneServiceBindings) : Map.of();
    }
}

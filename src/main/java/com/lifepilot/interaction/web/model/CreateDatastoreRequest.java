package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Datastore 创建请求。
 *
 * @author zsg
 * @since 2026-04-11
 */
public record CreateDatastoreRequest(
        String name,
        String type,
        @Nullable String description,
        @Nullable List<PropertyDefinitionDto> properties,
        @Nullable String projectionConfigJson
) {

    /**
     * 属性定义 DTO。
     */
    public record PropertyDefinitionDto(
            String name,
            String type,
            boolean required,
            @Nullable String description
    ) {
    }
}

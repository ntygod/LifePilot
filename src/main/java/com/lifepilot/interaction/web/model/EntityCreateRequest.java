package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * 实体创建请求 DTO。
 *
 * @author zsg
 * @since 2026-03-13
 */
public record EntityCreateRequest(
        String name,
        String type,
        @Nullable String description,
        @Nullable Map<String, Object> properties,
        @Nullable Float importanceScore
) {}

package com.lifepilot.datastore.model;

import org.springframework.lang.Nullable;

/**
 * 属性定义 — 声明集合中文档的属性名称、类型和约束。
 *
 * <p>属性定义为可选项，声明后可获得类型校验、Generated Column 索引加速
 * 和工具描述增强。</p>
 *
 * @param name        属性名称
 * @param type        属性类型
 * @param required    是否必填
 * @param description 属性描述（可选）
 * @author zsg
 * @since 2026-03-10
 */
public record PropertyDefinition(
        String name,
        PropertyType type,
        boolean required,
        @Nullable String description
) {
}

package com.lifepilot.datastore.adapter;

import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.PropertyDefinition;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * CRUD 适配器配置 — 描述领域实体与 DataStore Collection 的映射关系。
 *
 * <p>每个 {@link DataStoreCrudAdapter} 实例绑定一份配置，
 * 包含集合名称、类型、实体 Class（反序列化用）和可选的属性定义（索引加速）。</p>
 *
 * @param <T>                 领域实体类型
 * @param domain              领域名称（如 "todo"、"schedule"、"habit"），用于 createdBy 标识
 * @param collectionName      DataStore 集合名称
 * @param collectionType      集合类型（DOCUMENT / NOTE / METRIC）
 * @param entityClass         实体 Class（反序列化用）
 * @param propertyDefinitions 属性定义列表（可选，用于索引加速和属性校验）
 * @param description         集合描述（可选）
 * @author zsg
 * @since 2026-03-16
 */
public record CrudAdapterConfig<T>(
        String domain,
        String collectionName,
        CollectionType collectionType,
        Class<T> entityClass,
        @Nullable List<PropertyDefinition> propertyDefinitions,
        @Nullable String description
) {
}

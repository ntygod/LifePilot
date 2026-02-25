package com.lifepilot.skill.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 记忆读权限声明 — 定义 Skill 可读取的记忆层和实体类型。
 *
 * <p>通过 {@code entityTypes} 指定允许读取的实体类型列表，
 * 支持通配符 {@code "*"} 表示所有类型。
 * 可选的 {@code timeRange} 约束查询的时间范围。</p>
 *
 * @param layer       记忆层标识（如 L2_EPISODIC、L3_SEMANTIC）
 * @param entityTypes 允许读取的实体类型列表，支持 "*" 通配符
 * @param timeRange   时间范围约束（如 "30d"），可为 null 表示不限制
 * @author zsg
 * @since 2026-07-28
 */
public record MemoryReadPermission(String layer, List<String> entityTypes, @Nullable String timeRange) {

    /** 紧凑构造器 — 防御性拷贝。 */
    public MemoryReadPermission {
        entityTypes = List.copyOf(entityTypes);
    }
}

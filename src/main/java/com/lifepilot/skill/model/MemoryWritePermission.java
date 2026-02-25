package com.lifepilot.skill.model;

import java.util.List;

/**
 * 记忆写权限声明 — 定义 Skill 可写入的记忆层和实体类型。
 *
 * <p>通过 {@code entityTypes} 指定允许写入的实体类型列表，
 * 支持通配符 {@code "*"} 表示所有类型。
 * {@code requireApproval} 为 true 时，写操作需要用户确认。</p>
 *
 * @param layer           记忆层标识（如 L2_EPISODIC、L3_SEMANTIC）
 * @param entityTypes     允许写入的实体类型列表，支持 "*" 通配符
 * @param requireApproval 是否需要用户确认
 * @author zsg
 * @since 2026-07-28
 */
public record MemoryWritePermission(String layer, List<String> entityTypes, boolean requireApproval) {

    /** 紧凑构造器 — 防御性拷贝。 */
    public MemoryWritePermission {
        entityTypes = List.copyOf(entityTypes);
    }
}

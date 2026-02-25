package com.lifepilot.skill.model;

import java.util.List;

/**
 * 声明式记忆访问策略 — 默认全部拒绝。
 *
 * <p>每个 Skill 通过此策略声明可读写的记忆层和实体类型。
 * 未声明的访问将被 {@code MemoryAccessEnforcer} 拦截并拒绝。</p>
 *
 * @param read  读权限列表
 * @param write 写权限列表
 * @author zsg
 * @since 2026-07-28
 */
public record MemoryAccessPolicy(
        List<MemoryReadPermission> read,
        List<MemoryWritePermission> write
) {

    /** 紧凑构造器 — 防御性拷贝。 */
    public MemoryAccessPolicy {
        read = List.copyOf(read);
        write = List.copyOf(write);
    }

    /**
     * 无任何记忆访问权限。
     *
     * @return 空策略实例
     */
    public static MemoryAccessPolicy none() {
        return new MemoryAccessPolicy(List.of(), List.of());
    }

    /**
     * 判断是否可读指定记忆层的指定实体类型。
     *
     * @param layer      记忆层标识
     * @param entityType 实体类型
     * @return 是否允许读取
     */
    public boolean canRead(String layer, String entityType) {
        return read.stream().anyMatch(p ->
                p.layer().equals(layer) && (p.entityTypes().contains("*") || p.entityTypes().contains(entityType)));
    }

    /**
     * 判断是否可写指定记忆层的指定实体类型。
     *
     * @param layer      记忆层标识
     * @param entityType 实体类型
     * @return 是否允许写入
     */
    public boolean canWrite(String layer, String entityType) {
        return write.stream().anyMatch(p ->
                p.layer().equals(layer) && (p.entityTypes().contains("*") || p.entityTypes().contains(entityType)));
    }
}

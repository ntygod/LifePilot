package com.lifepilot.sync.model;

import java.util.List;

/**
 * 本地变更集 record，包含本地数据库中自上次同步以来的新增、修改、删除实体。
 *
 * @param created 本地新增的实体列表
 * @param updated 本地修改的实体列表
 * @param deleted 本地已删除的实体列表
 * @author zsg
 * @since 2026-02-26
 */
public record LocalChangeSet(
        List<LocalEntity> created,
        List<LocalEntity> updated,
        List<LocalEntity> deleted
) {

    /**
     * 创建 LocalChangeSet 实例，对列表进行防御性拷贝。
     */
    public LocalChangeSet {
        created = List.copyOf(created);
        updated = List.copyOf(updated);
        deleted = List.copyOf(deleted);
    }

    /**
     * 本地实体 record，描述本地数据库中的单个实体数据。
     *
     * @param localId    本地实体 ID
     * @param entityType 实体类型（"TodoItem" / "ScheduleItem" / "HabitItem"）
     * @param entity     实体对象（TodoItem / ScheduleItem / HabitItem）
     */
    public record LocalEntity(
            String localId,
            String entityType,
            Object entity
    ) {
    }
}

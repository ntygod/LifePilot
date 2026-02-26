package com.lifepilot.sync.model;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 远程变更集 record，包含从外部服务拉取的新增、修改、删除实体及新的增量同步令牌。
 *
 * @param created          远程新增的实体列表
 * @param updated          远程修改的实体列表
 * @param deletedRemoteIds 远程已删除的实体 ID 列表
 * @param newSyncToken     新的增量同步令牌，可为 null
 * @author zsg
 * @since 2026-02-26
 */
public record RemoteChangeSet(
        List<RemoteEntity> created,
        List<RemoteEntity> updated,
        List<String> deletedRemoteIds,
        @Nullable String newSyncToken
) {

    /**
     * 创建 RemoteChangeSet 实例，对列表进行防御性拷贝。
     */
    public RemoteChangeSet {
        created = List.copyOf(created);
        updated = List.copyOf(updated);
        deletedRemoteIds = List.copyOf(deletedRemoteIds);
    }

    /**
     * 远程实体 record，描述从外部服务获取的单个实体数据。
     *
     * @param remoteId   远程实体 ID
     * @param entityType 实体类型（"TodoItem" / "ScheduleItem" / "HabitItem"）
     * @param fields     实体字段键值对
     * @param etag       实体 ETag，可为 null
     * @param updatedAt  远程更新时间（ISO 8601），可为 null
     */
    public record RemoteEntity(
            String remoteId,
            String entityType,
            Map<String, Object> fields,
            @Nullable String etag,
            @Nullable String updatedAt
    ) {

        /**
         * 创建 RemoteEntity 实例，对 fields 进行防御性拷贝。
         */
        public RemoteEntity {
            fields = Map.copyOf(fields);
        }
    }
}

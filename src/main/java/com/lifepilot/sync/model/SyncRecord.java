package com.lifepilot.sync.model;

import org.springframework.lang.Nullable;

/**
 * 同步映射 record，记录每个本地实体与远程实体的 ID 映射关系及同步状态。
 *
 * @param id              唯一标识
 * @param profileId       所属同步配置 ID
 * @param localEntityType 本地实体类型（"TodoItem" / "ScheduleItem" / "HabitItem"）
 * @param localEntityId   本地实体 ID
 * @param remoteEntityId  远程实体 ID
 * @param etag            远程实体的 ETag（用于条件更新），可为 null
 * @param remoteUpdatedAt 远程实体的最后更新时间，可为 null
 * @param lastSyncAt      最后同步时间（ISO 8601）
 * @param createdAt       创建时间（ISO 8601）
 * @param updatedAt       更新时间（ISO 8601）
 * @author zsg
 * @since 2026-02-26
 */
public record SyncRecord(
        String id,
        String profileId,
        String localEntityType,
        String localEntityId,
        String remoteEntityId,
        @Nullable String etag,
        @Nullable String remoteUpdatedAt,
        String lastSyncAt,
        String createdAt,
        String updatedAt
) {
}

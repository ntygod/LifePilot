package com.lifepilot.sync.model;

import org.springframework.lang.Nullable;

/**
 * 同步冲突 record，描述同一实体在本地和远程同时被修改的冲突情况。
 *
 * <p>包含双方版本快照（JSON 格式），无论采用何种冲突策略均保留，以便用户审查和回滚。
 *
 * @param id                 唯一标识
 * @param profileId          所属同步配置 ID
 * @param localEntityType    本地实体类型
 * @param localEntityId      本地实体 ID
 * @param localSnapshotJson  本地版本快照（JSON）
 * @param remoteSnapshotJson 远程版本快照（JSON）
 * @param status             冲突状态
 * @param resolvedAt         解决时间（ISO 8601），未解决时为 null
 * @param createdAt          创建时间（ISO 8601）
 * @author zsg
 * @since 2026-02-26
 */
public record SyncConflict(
        String id,
        String profileId,
        String localEntityType,
        String localEntityId,
        String localSnapshotJson,
        String remoteSnapshotJson,
        ConflictStatus status,
        @Nullable String resolvedAt,
        String createdAt
) {

    /**
     * 冲突状态枚举。
     */
    public enum ConflictStatus {

        /** 未解决。 */
        UNRESOLVED,

        /** 已解决。 */
        RESOLVED
    }
}

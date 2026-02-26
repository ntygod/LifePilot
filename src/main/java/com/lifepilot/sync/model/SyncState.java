package com.lifepilot.sync.model;

import org.springframework.lang.Nullable;

/**
 * 同步状态 record，记录某个同步配置的增量同步令牌和最近一次同步的结果。
 *
 * @param id               唯一标识
 * @param profileId        所属同步配置 ID
 * @param syncToken        增量同步令牌，可为 null（首次同步前）
 * @param lastSyncAt       最后同步时间（ISO 8601），可为 null
 * @param lastSyncStatus   最近一次同步状态
 * @param lastErrorMessage 最近一次同步的错误消息，可为 null
 * @param createdAt        创建时间（ISO 8601）
 * @param updatedAt        更新时间（ISO 8601）
 * @author zsg
 * @since 2026-02-26
 */
public record SyncState(
        String id,
        String profileId,
        @Nullable String syncToken,
        @Nullable String lastSyncAt,
        SyncStatus lastSyncStatus,
        @Nullable String lastErrorMessage,
        String createdAt,
        String updatedAt
) {
}

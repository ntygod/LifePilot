package com.lifepilot.sync.model;

import org.springframework.lang.Nullable;

/**
 * 同步结果 record，包含一次同步操作的统计信息。
 *
 * @param profileId         同步配置 ID
 * @param pulledCount       从远程拉取并应用到本地的实体数量
 * @param pushedCount       从本地推送到远程的实体数量
 * @param conflictsDetected 检测到的冲突总数
 * @param conflictsResolved 已解决的冲突数量
 * @param status            同步最终状态
 * @param errorMessage      错误消息，成功时为 null
 * @param syncedAt          同步完成时间（ISO 8601）
 * @author zsg
 * @since 2026-02-26
 */
public record SyncResult(
        String profileId,
        int pulledCount,
        int pushedCount,
        int conflictsDetected,
        int conflictsResolved,
        SyncStatus status,
        @Nullable String errorMessage,
        String syncedAt
) {
}

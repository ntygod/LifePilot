package com.lifepilot.agent.task.reminder.tracking;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 统一追踪条目 — 快递/航班/行程等外部数据源的追踪注册项。
 *
 * @param id            条目 ID
 * @param userId        用户 ID
 * @param type          追踪类型
 * @param trackingKey   追踪标识（运单号/航班号/车次）
 * @param title         显示标题（如"顺丰 SF123456789"）
 * @param status        当前状态
 * @param lastCheckedAt 上次查询时间
 * @param relevantAt    相关时间（出发时间/预计送达等）
 * @param metadata      额外元数据（JSON）
 * @param active        是否仍需追踪
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @author zsg
 * @since 2026-04-05
 */
public record TrackingEntry(
        String id,
        String userId,
        TrackingType type,
        String trackingKey,
        String title,
        @Nullable String status,
        @Nullable Instant lastCheckedAt,
        @Nullable Instant relevantAt,
        @Nullable String metadata,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}

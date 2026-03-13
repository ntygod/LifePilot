package com.lifepilot.notification;

import java.time.Instant;
import java.util.List;

/**
 * 通知设置数据载体。
 *
 * <p>对应 {@code notification_settings} 数据库表，记录用户对特定通知类型的偏好设置。
 *
 * @param id        设置唯一标识
 * @param userId    用户标识
 * @param typeId    通知类型标识
 * @param enabled   是否启用该类型通知
 * @param channels  启用的渠道名称列表
 * @param minUrgency 最低紧急程度阈值
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @author zsg
 * @since 2026-03-13
 */
public record NotificationSettingRecord(
        String id,
        String userId,
        String typeId,
        boolean enabled,
        List<String> channels,
        Urgency minUrgency,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 紧凑构造器：对 channels 做防御性拷贝。
     */
    public NotificationSettingRecord {
        channels = channels != null ? List.copyOf(channels) : List.of();
    }
}

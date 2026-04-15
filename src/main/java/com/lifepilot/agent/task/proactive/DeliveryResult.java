package com.lifepilot.agent.task.proactive;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 投递结果。
 *
 * @param notificationId 通知 ID（NOTIFY/INTERRUPT 时非空）
 * @param level          实际投递级别
 * @param deliveredAt    投递时间
 * @author zsg
 * @since 2026-04-14
 */
public record DeliveryResult(
        @Nullable String notificationId,
        DeliveryLevel level,
        Instant deliveredAt
) {
    public DeliveryResult {
        Objects.requireNonNull(level, "level 不能为空");
        deliveredAt = Objects.requireNonNullElse(deliveredAt, Instant.now());
    }
}

package com.lifepilot.agent.task.proactive.signal;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 隐式信号记录。
 *
 * @author zsg
 * @since 2026-04-14
 */
public record ImplicitSignal(
        String id,
        String userId,
        @Nullable String notificationId,
        ImplicitSignalType signalType,
        @Nullable String behaviorName,
        @Nullable String topicKey,
        float signalValue,
        @Nullable String evidence,
        Instant createdAt
) {
    public ImplicitSignal {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(signalType, "signalType 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }
}

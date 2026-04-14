package com.lifepilot.agent.task.proactive.intent;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 意图记录。
 *
 * @param id               唯一标识
 * @param userId           用户 ID
 * @param intentType       意图类型
 * @param goal             目标描述
 * @param triggerCondition 触发条件（结构化或自然语言）
 * @param sourceSessionId  提取来源会话 ID
 * @param status           状态
 * @param checkCount       已检查次数（用于衰减）
 * @param createdAt        创建时间
 * @param expiresAt        过期时间
 * @param triggeredAt      触发时间
 * @param fulfilledAt      完成时间
 * @param updatedAt        更新时间
 * @author zsg
 * @since 2026-04-14
 */
public record IntentRecord(
        String id,
        String userId,
        IntentType intentType,
        String goal,
        @Nullable String triggerCondition,
        @Nullable String sourceSessionId,
        IntentStatus status,
        int checkCount,
        Instant createdAt,
        @Nullable Instant expiresAt,
        @Nullable Instant triggeredAt,
        @Nullable Instant fulfilledAt,
        Instant updatedAt
) {
    public IntentRecord {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(intentType, "intentType 不能为空");
        Objects.requireNonNull(goal, "goal 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        checkCount = Math.max(0, checkCount);
    }

    /** 判断是否已过期。 */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}

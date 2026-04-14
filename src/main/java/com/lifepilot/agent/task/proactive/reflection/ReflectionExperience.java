package com.lifepilot.agent.task.proactive.reflection;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 反思经验记录 — 周频自省产出，供下周决策参考。
 *
 * @author zsg
 * @since 2026-04-14
 */
public record ReflectionExperience(
        String id,
        String userId,
        String weekStart,
        @Nullable String usefulPatterns,
        @Nullable String missedOpportunities,
        @Nullable String strategyAdjustments,
        @Nullable String rawReflection,
        Instant createdAt
) {
    public ReflectionExperience {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(weekStart, "weekStart 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }
}

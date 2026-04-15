package com.lifepilot.agent.task.proactive;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 目标视图 — 合并 L3 语义记忆实体与引擎追踪状态。
 *
 * <p>L3 提供实体数据（名称、描述、重要度、创建时间、属性）；
 * 引擎侧 {@code proactive_goal_tracking} 表提供追踪状态
 * （追问次数、上次追问时间）。</p>
 *
 * @author zsg
 * @since 2026-04-15
 */
public record GoalView(
        String entityId,
        String goal,
        @Nullable String description,
        float importanceScore,
        int accessCount,
        Instant createdAt,
        int checkCount,
        @Nullable Instant lastFollowUpAt,
        Map<String, Object> properties
) {
    public GoalView {
        Objects.requireNonNull(entityId, "entityId 不能为空");
        Objects.requireNonNull(goal, "goal 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(properties, "properties 不能为空");
        importanceScore = Math.max(0f, Math.min(1f, importanceScore));
    }
}

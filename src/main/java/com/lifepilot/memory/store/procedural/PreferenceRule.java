package com.lifepilot.memory.store.procedural;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * 偏好规则 — 从用户行为中学习到的个人偏好。
 *
 * <p>按类别组织，带置信度评分。同一 {@code category} 和 {@code key} 组合下
 * 偏好规则唯一，重复观察会递增 {@code observationCount} 并提升 {@code confidence}。</p>
 *
 * <p>V15 起加入 {@code sourceEntityId}（指向 L3 源 PREFERENCE 实体）与
 * {@code deactivatedReason}（失活原因，非 null 表示已失活）。
 * {@code L4SyncListener} 通过 {@code source_entity_id} 反查规则并置
 * {@code deactivated_reason}，实现 L3 失活 → L4 级联失活。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public record PreferenceRule(
        String ruleId,
        String category,
        String key,
        String value,
        float confidence,
        String learnedFrom,
        int observationCount,
        Instant createdAt,
        Instant updatedAt,
        @Nullable String sourceEntityId,
        @Nullable String deactivatedReason
) {

    /**
     * 判断偏好规则是否高置信度。
     *
     * @return 当 confidence >= 0.7f 时返回 true
     */
    public boolean isHighConfidence() {
        return confidence >= 0.7f;
    }
}

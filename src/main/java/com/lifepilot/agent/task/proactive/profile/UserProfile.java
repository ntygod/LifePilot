package com.lifepilot.agent.task.proactive.profile;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 自然语言用户画像 — 三维理解：工作节奏、偏好风格、当前目标。
 *
 * <p>不是数值特征向量，而是 LLM 可读的自然语言描述，
 * 注入到所有 behavior 的 prompt context 中让 LLM "懂"这个人。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public record UserProfile(
        String userId,
        @Nullable String workRhythm,
        @Nullable String preferences,
        @Nullable String goals,
        @Nullable String fullPortrait,
        int conversationCount,
        @Nullable Instant lastConsolidatedAt,
        Instant updatedAt
) {
    public UserProfile {
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        conversationCount = Math.max(0, conversationCount);
    }

    /** 画像是否为空（还没做过巩固）。 */
    public boolean isEmpty() {
        return fullPortrait == null || fullPortrait.isBlank();
    }

    /** 返回完整画像文本，为空时返回默认描述。 */
    public String getPortraitOrDefault() {
        if (!isEmpty()) return fullPortrait;
        return "新用户，尚无足够信息建立画像。";
    }

    public static UserProfile empty(String userId) {
        return new UserProfile(userId, null, null, null, null, 0, null, Instant.now());
    }
}

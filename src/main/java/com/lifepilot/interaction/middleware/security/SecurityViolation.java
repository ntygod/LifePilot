package com.lifepilot.interaction.middleware.security;

import org.springframework.lang.Nullable;

/**
 * 安全违规记录。
 *
 * @param type           违规类型（如 "prompt_injection", "sensitive_data"）
 * @param severity       严重程度（CRITICAL / HIGH / MEDIUM / LOW）
 * @param description    违规描述
 * @param matchedPattern 匹配的模式（可空）
 * @author zsg
 * @since 2026-02-25
 */
public record SecurityViolation(
        String type,
        String severity,
        String description,
        @Nullable String matchedPattern
) {
}

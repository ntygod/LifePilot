package com.lifepilot.interaction.middleware.security;

import lombok.Builder;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 安全检查结果。
 *
 * @param violations      违规列表
 * @param blocked         是否被阻断
 * @param redactedContent 脱敏后的内容（可空）
 * @param trustScore      信任分数
 * @author zsg
 * @since 2026-02-25
 */
@Builder(toBuilder = true)
public record SecurityCheckResult(
        List<SecurityViolation> violations,
        boolean blocked,
        @Nullable String redactedContent,
        double trustScore
) {

    /**
     * 紧凑构造函数，确保 violations 不可变。
     */
    public SecurityCheckResult {
        violations = List.copyOf(violations);
    }

    /**
     * 是否存在违规。
     */
    public boolean hasViolations() {
        return !violations.isEmpty();
    }
}

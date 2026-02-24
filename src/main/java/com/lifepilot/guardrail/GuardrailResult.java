package com.lifepilot.guardrail;

import jakarta.annotation.Nullable;

/**
 * 护栏检查结果。
 *
 * @param blocked 是否被拦截
 * @param reason 拦截原因
 * @param requiresConfirmation 是否需要用户确认
 * @param confirmationMessage 确认消息
 * @param requiresVerification 是否需要二次验证
 * @author zsg
 * @since 2026-02-24
 */
public record GuardrailResult(
        boolean blocked,
        @Nullable String reason,
        boolean requiresConfirmation,
        @Nullable String confirmationMessage,
        boolean requiresVerification
) {
    /** 允许执行。 */
    public static GuardrailResult allowed() {
        return new GuardrailResult(false, null, false, null, false);
    }

    /** 拦截执行。 */
    public static GuardrailResult blocked(String reason) {
        return new GuardrailResult(true, reason, false, null, false);
    }

    /** 需要用户确认。 */
    public static GuardrailResult requiresConfirmation(String message, boolean verification) {
        return new GuardrailResult(false, null, true, message, verification);
    }
}

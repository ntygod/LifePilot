package com.lifepilot.memory.governance.security;

import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * 注入检测结果。
 *
 * @param status     检测状态（PASS / SUSPICIOUS / BLOCKED）
 * @param reason     具体原因
 * @param confidence 置信度 [0, 1]
 * @param details    可读解释
 * @author zsg
 * @since 2026-05-09
 */
public record InjectionDetectionResult(
        Status status,
        InjectionReason reason,
        float confidence,
        @Nullable String details
) {

    public enum Status {
        /** 通过检测。 */
        PASS,
        /** 有可疑特征（默认不阻断，由调用方决定）。 */
        SUSPICIOUS,
        /** 明确阻断。 */
        BLOCKED
    }

    public InjectionDetectionResult {
        status = Objects.requireNonNull(status, "status 不能为空");
        reason = Objects.requireNonNull(reason, "reason 不能为空");
        if (!(confidence >= 0.0f && confidence <= 1.0f)) {
            throw new IllegalArgumentException("注入检测置信度必须在 [0,1] 范围内: " + confidence);
        }
    }

    public boolean isBlocked() { return status == Status.BLOCKED; }
    public boolean isSuspicious() { return status == Status.SUSPICIOUS; }
    public boolean isPass() { return status == Status.PASS; }

    public static InjectionDetectionResult pass() {
        return new InjectionDetectionResult(Status.PASS, InjectionReason.CLEAN, 1.0f, null);
    }

    public static InjectionDetectionResult blocked(InjectionReason reason, float confidence, String details) {
        return new InjectionDetectionResult(Status.BLOCKED, reason, confidence, details);
    }

    public static InjectionDetectionResult suspicious(InjectionReason reason, float confidence, String details) {
        return new InjectionDetectionResult(Status.SUSPICIOUS, reason, confidence, details);
    }
}

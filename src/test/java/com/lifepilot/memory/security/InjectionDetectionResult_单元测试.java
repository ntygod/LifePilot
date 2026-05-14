package com.lifepilot.memory.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InjectionDetectionResult 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class InjectionDetectionResult_单元测试 {

    @Test
    void pass便捷构造() {
        var r = InjectionDetectionResult.pass();
        assertThat(r.isPass()).isTrue();
        assertThat(r.status()).isEqualTo(InjectionDetectionResult.Status.PASS);
        assertThat(r.reason()).isEqualTo(InjectionReason.CLEAN);
    }

    @Test
    void blocked便捷构造() {
        var r = InjectionDetectionResult.blocked(
                InjectionReason.PROMPT_INJECTION_PATTERN, 0.9f, "命中模式 X");
        assertThat(r.isBlocked()).isTrue();
        assertThat(r.confidence()).isEqualTo(0.9f);
        assertThat(r.details()).isEqualTo("命中模式 X");
    }

    @Test
    void suspicious便捷构造() {
        var r = InjectionDetectionResult.suspicious(
                InjectionReason.TRUST_SCORE_OUTLIER, 0.6f, "距离过大");
        assertThat(r.isSuspicious()).isTrue();
    }

    @Test
    void confidence自动clamp() {
        var r1 = InjectionDetectionResult.blocked(InjectionReason.MANUAL_BLACKLIST, 1.5f, null);
        var r2 = InjectionDetectionResult.blocked(InjectionReason.MANUAL_BLACKLIST, -0.3f, null);
        assertThat(r1.confidence()).isEqualTo(1.0f);
        assertThat(r2.confidence()).isEqualTo(0.0f);
    }

    @Test
    void null状态抛异常() {
        assertThatThrownBy(() -> new InjectionDetectionResult(null, InjectionReason.CLEAN, 0.5f, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InjectionDetectionResult(
                InjectionDetectionResult.Status.PASS, null, 0.5f, null))
                .isInstanceOf(NullPointerException.class);
    }
}

package com.lifepilot.agent.task.proactive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProactivePreferenceGuard 单元测试 — 文档级静态工具。
 *
 * @author zsg
 * @since 2026-05-09
 */
class ProactivePreferenceGuard_单元测试 {

    @Test
    void isProactiveCategory_前缀正确时返回true() {
        assertThat(ProactivePreferenceGuard.isProactiveCategory("proactive-domain")).isTrue();
        assertThat(ProactivePreferenceGuard.isProactiveCategory("proactive-timing")).isTrue();
        assertThat(ProactivePreferenceGuard.isProactiveCategory("proactive-style")).isTrue();
        assertThat(ProactivePreferenceGuard.isProactiveCategory("proactive-custom-whatever")).isTrue();
    }

    @Test
    void isProactiveCategory_非前缀返回false() {
        assertThat(ProactivePreferenceGuard.isProactiveCategory("user-preference")).isFalse();
        assertThat(ProactivePreferenceGuard.isProactiveCategory("schedule")).isFalse();
        assertThat(ProactivePreferenceGuard.isProactiveCategory("output")).isFalse();
    }

    @Test
    void isProactiveCategory_空值返回false() {
        assertThat(ProactivePreferenceGuard.isProactiveCategory(null)).isFalse();
        assertThat(ProactivePreferenceGuard.isProactiveCategory("")).isFalse();
        assertThat(ProactivePreferenceGuard.isProactiveCategory("  ")).isFalse();
    }

    @Test
    void isRecognizedCategory_白名单非主动引擎类别() {
        assertThat(ProactivePreferenceGuard.isRecognizedCategory("user-preference")).isTrue();
        assertThat(ProactivePreferenceGuard.isRecognizedCategory("schedule")).isTrue();
        assertThat(ProactivePreferenceGuard.isRecognizedCategory("output")).isTrue();
    }

    @Test
    void isRecognizedCategory_主动引擎类别() {
        assertThat(ProactivePreferenceGuard.isRecognizedCategory("proactive-domain")).isTrue();
    }

    @Test
    void isRecognizedCategory_未知类别返回false() {
        assertThat(ProactivePreferenceGuard.isRecognizedCategory("unknown-category")).isFalse();
        assertThat(ProactivePreferenceGuard.isRecognizedCategory(null)).isFalse();
    }
}

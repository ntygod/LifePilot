package com.lifepilot.sandbox.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Language#fromString(String)} 别名解析测试。
 *
 * <p>验证各种常用语言别名（js/bash/sh/py/python3/node）能正确映射到标准枚举值，
 * 以及 null 和未知语言返回 empty。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
class Language_别名解析测试 {

    // ─────────────────────────────────────────────
    //  JavaScript 别名
    // ─────────────────────────────────────────────

    @Test
    void fromString_js应返回JAVASCRIPT() {
        assertThat(Language.fromString("js")).hasValue(Language.JAVASCRIPT);
    }

    @Test
    void fromString_node应返回JAVASCRIPT() {
        assertThat(Language.fromString("node")).hasValue(Language.JAVASCRIPT);
    }

    @Test
    void fromString_javascript应返回JAVASCRIPT() {
        assertThat(Language.fromString("javascript")).hasValue(Language.JAVASCRIPT);
    }

    // ─────────────────────────────────────────────
    //  Shell 别名
    // ─────────────────────────────────────────────

    @Test
    void fromString_bash应返回SHELL() {
        assertThat(Language.fromString("bash")).hasValue(Language.SHELL);
    }

    @Test
    void fromString_sh应返回SHELL() {
        assertThat(Language.fromString("sh")).hasValue(Language.SHELL);
    }

    @Test
    void fromString_shell应返回SHELL() {
        assertThat(Language.fromString("shell")).hasValue(Language.SHELL);
    }

    // ─────────────────────────────────────────────
    //  Python 别名
    // ─────────────────────────────────────────────

    @Test
    void fromString_py应返回PYTHON() {
        assertThat(Language.fromString("py")).hasValue(Language.PYTHON);
    }

    @Test
    void fromString_python3应返回PYTHON() {
        assertThat(Language.fromString("python3")).hasValue(Language.PYTHON);
    }

    @Test
    void fromString_python应返回PYTHON() {
        assertThat(Language.fromString("python")).hasValue(Language.PYTHON);
    }

    // ─────────────────────────────────────────────
    //  大小写不敏感
    // ─────────────────────────────────────────────

    @Test
    void fromString_大写JS应返回JAVASCRIPT() {
        assertThat(Language.fromString("JS")).hasValue(Language.JAVASCRIPT);
    }

    @Test
    void fromString_混合大小写Python应返回PYTHON() {
        assertThat(Language.fromString("Python")).hasValue(Language.PYTHON);
    }

    // ─────────────────────────────────────────────
    //  边界场景
    // ─────────────────────────────────────────────

    @Test
    void fromString_null应返回empty() {
        assertThat(Language.fromString(null)).isEmpty();
    }

    @Test
    void fromString_unknown应返回empty() {
        assertThat(Language.fromString("unknown")).isEmpty();
    }

    @Test
    void fromString_空字符串应返回empty() {
        assertThat(Language.fromString("")).isEmpty();
    }

    @Test
    void fromString_ruby不支持应返回empty() {
        assertThat(Language.fromString("ruby")).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  枚举名直接匹配回退
    // ─────────────────────────────────────────────

    @Test
    void fromString_枚举名PYTHON应返回PYTHON() {
        assertThat(Language.fromString("PYTHON")).hasValue(Language.PYTHON);
    }

    @Test
    void fromString_枚举名JAVASCRIPT应返回JAVASCRIPT() {
        assertThat(Language.fromString("JAVASCRIPT")).hasValue(Language.JAVASCRIPT);
    }

    @Test
    void fromString_枚举名SHELL应返回SHELL() {
        assertThat(Language.fromString("SHELL")).hasValue(Language.SHELL);
    }
}

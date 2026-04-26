package com.lifepilot.sandbox.guard;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommandNormalizer 归一化测试 — 验证 ANSI / NFKC / null 三重归一化能力。
 *
 * @author zsg
 * @since 2026-04-26
 */
class CommandGuard_归一化测试 {

    @Test
    void 剥离ANSI转义序列() {
        String input = "\u001B[31mrm -rf /\u001B[0m";
        assertThat(CommandNormalizer.normalize(input)).isEqualTo("rm -rf /");
    }

    @Test
    void NFKC归一化全角字符() {
        String input = "ｒｍ －ｒｆ ／";  // 全角
        assertThat(CommandNormalizer.normalize(input)).isEqualTo("rm -rf /");
    }

    @Test
    void 剥离null字符() {
        String input = "rm \u0000-rf /";
        assertThat(CommandNormalizer.normalize(input)).isEqualTo("rm -rf /");
    }

    @Test
    void 多重组合绕过尝试() {
        String input = "\u001B[31mｒｍ\u0000 -rf /\u001B[0m";
        assertThat(CommandNormalizer.normalize(input)).isEqualTo("rm -rf /");
    }

    @Test
    void 全角左括号CSI形式应被正确归一化() {
        // 攻击向量：用全角 ［（U+FF3B）替换半角 [，绕过 ANSI strip 后被 NFKC 折叠还原成 CSI
        String input = "\u001B\uFF3B31mrm -rf /\u001B\uFF3B0m";
        assertThat(CommandNormalizer.normalize(input)).isEqualTo("rm -rf /");
    }
}

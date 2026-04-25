package com.lifepilot.meta.infra.file;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PathExpander} 单元测试 —— 验证 {@code ~} 前缀展开为 {@code user.home}。
 *
 * <p>覆盖：单 {@code ~}、{@code ~/} 前缀、{@code ~\} 前缀、绝对路径原样、null/空原样、
 * 非前缀形式（如 {@code ~user}）原样。</p>
 *
 * @author zsg
 * @since 2026-04-25
 */
class PathExpander_展开测试 {

    private static final String HOME = System.getProperty("user.home");

    @Test
    void 单个波浪号展开为_home() {
        assertThat(PathExpander.expand("~")).isEqualTo(HOME);
    }

    @Test
    void 波浪号斜杠前缀展开为_home_拼接子路径() {
        assertThat(PathExpander.expand("~/foo/bar"))
                .isEqualTo(HOME + "/foo/bar");
    }

    @Test
    void 波浪号反斜杠前缀展开为_home_拼接子路径() {
        assertThat(PathExpander.expand("~\\foo"))
                .isEqualTo(HOME + "\\foo");
    }

    @Test
    void 绝对路径原样返回() {
        assertThat(PathExpander.expand("/abs/path")).isEqualTo("/abs/path");
        assertThat(PathExpander.expand("C:\\Users\\foo")).isEqualTo("C:\\Users\\foo");
    }

    @Test
    void null_与空字符串原样返回() {
        assertThat(PathExpander.expand(null)).isNull();
        assertThat(PathExpander.expand("")).isEqualTo("");
        assertThat(PathExpander.expand("   ")).isEqualTo("   ");
    }

    @Test
    void 非斜杠紧跟波浪号的形式不展开() {
        // ~user 不属于 ~/ 前缀，保持原样（不解析为其它用户 home）
        assertThat(PathExpander.expand("~user")).isEqualTo("~user");
        assertThat(PathExpander.expand("~root/foo")).isEqualTo("~root/foo");
    }

    @Test
    void 中间出现波浪号不展开() {
        // 仅前缀展开，路径中部的 ~ 字面保留
        assertThat(PathExpander.expand("/tmp/~/foo")).isEqualTo("/tmp/~/foo");
    }
}

package com.lifepilot.meta.infra.code.kernel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProcessKernelBase} 静态工具方法和状态控制测试。
 *
 * <p>ProcessKernelBase 是 sealed class，构造时会启动真实进程，
 * 因此仅测试其 package-private 静态方法（如 truncate）。
 * tryRestart 的状态守卫逻辑通过 {@link PersistentKernelManagerTest} 间接覆盖。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
class ProcessKernelBase_截断与状态测试 {

    // ─────────────────────────────────────────────
    //  truncate 静态方法测试
    // ─────────────────────────────────────────────

    @Test
    void truncate_短文本不截断() {
        String result = ProcessKernelBase.truncate("hello", 100);
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void truncate_超长文本截断并标记() {
        String longText = "a".repeat(200);
        String result = ProcessKernelBase.truncate(longText, 50);
        assertThat(result).startsWith("a".repeat(50));
        assertThat(result).contains("输出已截断");
    }

    @Test
    void truncate_null输入返回空字符串() {
        String result = ProcessKernelBase.truncate(null, 100);
        assertThat(result).isEmpty();
    }

    @Test
    void truncate_刚好等于最大长度不截断() {
        String text = "a".repeat(50);
        String result = ProcessKernelBase.truncate(text, 50);
        assertThat(result).isEqualTo(text);
    }

    @Test
    void truncate_超过最大长度一个字符时截断() {
        String text = "a".repeat(51);
        String result = ProcessKernelBase.truncate(text, 50);
        assertThat(result).startsWith("a".repeat(50));
        assertThat(result).endsWith("...[输出已截断]");
    }
}

package com.lifepilot.meta.infra.browser;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 带超时的同步任务执行器测试。
 *
 * <p>用于包装 Playwright 的 {@code page.evaluate()} 调用，
 * 防止长时间阻塞。超时抛 {@link TimeoutException}。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
class TimeoutExecutor_超时执行测试 {

    @Test
    void 正常返回时直接产出结果() throws Exception {
        String result = TimeoutExecutor.callWithTimeout(() -> "hello", 2, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void 超时时抛出TimeoutException() {
        assertThatThrownBy(() -> TimeoutExecutor.callWithTimeout(
                () -> {
                    Thread.sleep(3000);
                    return "too-late";
                },
                1, TimeUnit.SECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    @Test
    void 原始异常透传保留类型() {
        // ExecutionException 被剥壳 → 直接抛出任务本身抛的异常
        assertThatThrownBy(() -> TimeoutExecutor.callWithTimeout(
                () -> {
                    throw new IllegalStateException("目标任务失败");
                },
                1, TimeUnit.SECONDS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("目标任务失败");
    }

    @Test
    void 超时后线程应被中断以便Playwright任务尽快释放() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        try {
            TimeoutExecutor.callWithTimeout(() -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return null;
            }, 1, TimeUnit.SECONDS);
        } catch (TimeoutException ignored) {
            // 预期
        }
        // 给被中断的 worker 一点时间把标志写回
        Thread.sleep(200);
        assertThat(interrupted.get()).isTrue();
    }
}

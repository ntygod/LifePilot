package com.lifepilot.meta.infra.browser;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 带超时的同步任务执行器。
 *
 * <p>用于包装 Playwright 的 {@code page.evaluate()} 等阻塞调用，
 * 防止 JS 死循环或长任务耗尽线程。超时后会尝试中断任务线程，
 * 让底层 Playwright 尽快释放资源。</p>
 *
 * <p>使用 virtual thread executor（Java 22 原生），
 * 避免为每次调用占用平台线程。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
final class TimeoutExecutor {

    private TimeoutExecutor() {
        // 工具类禁止实例化
    }

    /**
     * 执行 callable，超过 timeout 则抛 {@link TimeoutException}。
     *
     * @param callable 被执行的任务
     * @param timeout  超时时长
     * @param unit     时间单位
     * @param <T>      返回类型
     * @return 任务结果
     * @throws TimeoutException 超时
     * @throws Exception        任务原始异常（{@link ExecutionException} 的根因）
     */
    static <T> T callWithTimeout(Callable<T> callable, long timeout, TimeUnit unit)
            throws Exception {
        // 单任务专用 executor，任务结束立即回收
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<T> future = executor.submit(callable);
            try {
                return future.get(timeout, unit);
            } catch (TimeoutException e) {
                future.cancel(true); // 中断任务线程，Playwright 调用可中止等待
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw e;
            } catch (CancellationException e) {
                throw new TimeoutException("任务已取消");
            }
        }
    }
}

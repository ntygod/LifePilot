package com.lifepilot.meta.infra.browser;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
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
 * <p>使用静态单例 virtual thread executor（Java 22 原生），
 * 避免每次调用都 new 一个 executor 带来的对象创建开销。
 * Virtual thread 是 daemon 友好的 — JVM 退出时无需显式 close，
 * 所有未完成的任务会自然被回收，因此此类不需要 {@code @PreDestroy} 清理。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
final class TimeoutExecutor {

    /**
     * 共享的 virtual thread executor。
     *
     * <p>Virtual thread 不占用平台线程，高并发场景下比 newFixedThreadPool 更高效；
     * 同时 virtual thread 是 daemon 线程，JVM 退出时会自动终止，无需显式 shutdown。</p>
     */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

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
        // 提交到共享 virtual thread executor，不 close（daemon 友好，JVM 退出自然回收）
        Future<T> future = EXECUTOR.submit(callable);
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

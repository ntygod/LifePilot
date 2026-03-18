package com.lifepilot.config.threadpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.TimeUnit;

/**
 * 线程池生命周期管理器 — 实现 SmartLifecycle，统一优雅关闭所有线程池。
 *
 * <p>phase = Integer.MAX_VALUE：最后启动、最先关闭。
 * 关闭时按注册逆序遍历 ThreadPoolRegistry，依次 shutdown → awaitTermination → shutdownNow。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class ThreadPoolLifecycleManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolLifecycleManager.class);

    private final ThreadPoolRegistry registry;
    private final int shutdownTimeoutSeconds;
    private volatile boolean running = false;

    public ThreadPoolLifecycleManager(ThreadPoolRegistry registry,
                                       ThreadPoolProperties properties) {
        this.registry = registry;
        this.shutdownTimeoutSeconds = properties.getShutdownTimeoutSeconds();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void start() {
        running = true;
        log.info("线程池生命周期管理器已启动");
    }

    @Override
    public void stop() {
        log.info("开始关闭所有线程池...");
        var entries = registry.orderedEntriesReversed();

        for (var entry : entries) {
            var name = entry.getKey();
            var executor = entry.getValue();
            try {
                executor.shutdown();
                if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    log.warn("线程池超时强制关闭: name={}", name);
                } else {
                    log.info("线程池正常关闭: name={}", name);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                log.warn("线程池关闭被中断: name={}", name);
            } catch (Exception e) {
                log.error("线程池关闭异常: name={}", name, e);
            }
        }

        running = false;
        log.info("所有线程池关闭完成");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

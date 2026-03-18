package com.lifepilot.config.threadpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 共享定时调度器 — 按功能分组提供 ScheduledExecutorService。
 *
 * <p>三个分组：cleanup（定时清理）、debounce（防抖）、heartbeat（心跳/健康检查）。
 * 每个分组使用 Virtual Thread 工厂，自动注册到 {@link ThreadPoolRegistry}。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class SharedScheduler {

    private static final Logger log = LoggerFactory.getLogger(SharedScheduler.class);

    private final ScheduledExecutorService cleanupScheduler;
    private final ScheduledExecutorService debounceScheduler;
    private final ScheduledExecutorService heartbeatScheduler;

    public SharedScheduler(ThreadPoolRegistry registry, ThreadPoolProperties properties) {
        var shared = properties.getShared();

        this.cleanupScheduler = createAndRegister(
                registry, "shared-cleanup", shared.getCleanupCorePoolSize());
        this.debounceScheduler = createAndRegister(
                registry, "shared-debounce", shared.getDebounceCorePoolSize());
        this.heartbeatScheduler = createAndRegister(
                registry, "shared-heartbeat", shared.getHeartbeatCorePoolSize());

        log.info("共享调度器初始化完成: cleanup={}, debounce={}, heartbeat={}",
                shared.getCleanupCorePoolSize(),
                shared.getDebounceCorePoolSize(),
                shared.getHeartbeatCorePoolSize());
    }

    /** 获取 cleanup 分组调度器（定时清理任务）。 */
    public ScheduledExecutorService cleanup() {
        return cleanupScheduler;
    }

    /** 获取 debounce 分组调度器（防抖任务）。 */
    public ScheduledExecutorService debounce() {
        return debounceScheduler;
    }

    /** 获取 heartbeat 分组调度器（心跳/健康检查任务）。 */
    public ScheduledExecutorService heartbeat() {
        return heartbeatScheduler;
    }

    private static ScheduledExecutorService createAndRegister(
            ThreadPoolRegistry registry, String name, int corePoolSize) {
        var factory = Thread.ofVirtual().name(name + "-", 0).factory();
        var scheduler = Executors.newScheduledThreadPool(corePoolSize, factory);
        registry.register(name, scheduler);
        return scheduler;
    }
}

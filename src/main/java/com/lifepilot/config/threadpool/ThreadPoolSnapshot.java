package com.lifepilot.config.threadpool;

/**
 * 线程池状态快照。
 *
 * @param name           线程池名称
 * @param type           类型（ScheduledExecutorService / ExecutorService）
 * @param shutdown       是否已关闭
 * @param activeCount    活跃任务数（仅 ScheduledThreadPoolExecutor 有值）
 * @param completedCount 已完成任务数（仅 ScheduledThreadPoolExecutor 有值）
 *
 * @author zsg
 * @since 2026-03-18
 */
public record ThreadPoolSnapshot(
        String name,
        String type,
        boolean shutdown,
        int activeCount,
        long completedCount
) {}

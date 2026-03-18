package com.lifepilot.config.threadpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * 线程池注册中心 — 集中管理所有 ExecutorService 实例的注册、查询和状态快照。
 *
 * <p>内部使用 ConcurrentHashMap 快速查找 + LinkedHashMap 保持注册顺序（用于逆序关闭）。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class ThreadPoolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolRegistry.class);

    // 快速查找
    private final ConcurrentHashMap<String, ExecutorService> registry = new ConcurrentHashMap<>();
    // 保持注册顺序（用于逆序关闭）
    private final LinkedHashMap<String, ExecutorService> orderedRegistry = new LinkedHashMap<>();
    private final Object orderLock = new Object();

    /**
     * 注册线程池，名称不可重复。
     *
     * @param name     线程池唯一名称
     * @param executor 线程池实例
     * @throws IllegalArgumentException 名称已存在时抛出
     */
    public void register(String name, ExecutorService executor) {
        Objects.requireNonNull(name, "线程池名称不能为 null");
        Objects.requireNonNull(executor, "ExecutorService 不能为 null");

        synchronized (orderLock) {
            if (registry.containsKey(name)) {
                throw new IllegalArgumentException("线程池名称已存在: name=" + name);
            }
            registry.put(name, executor);
            orderedRegistry.put(name, executor);
        }
        log.info("线程池已注册: name={}", name);
    }

    /**
     * 按名称查询线程池。
     *
     * @param name 线程池名称
     * @return 线程池实例，不存在时返回 empty
     */
    public Optional<ExecutorService> find(String name) {
        return Optional.ofNullable(registry.get(name));
    }

    /**
     * 列出所有已注册线程池名称。
     *
     * @return 不可变名称列表
     */
    public List<String> listNames() {
        synchronized (orderLock) {
            return List.copyOf(orderedRegistry.keySet());
        }
    }

    /**
     * 获取所有线程池状态快照。
     *
     * @return 快照列表
     */
    public List<ThreadPoolSnapshot> snapshot() {
        synchronized (orderLock) {
            var snapshots = new ArrayList<ThreadPoolSnapshot>(orderedRegistry.size());
            for (var entry : orderedRegistry.entrySet()) {
                var executor = entry.getValue();
                int activeCount = 0;
                long completedCount = 0;
                String type = executor.getClass().getSimpleName();

                if (executor instanceof ScheduledThreadPoolExecutor stpe) {
                    activeCount = stpe.getActiveCount();
                    completedCount = stpe.getCompletedTaskCount();
                    type = "ScheduledExecutorService";
                }

                snapshots.add(new ThreadPoolSnapshot(
                        entry.getKey(),
                        type,
                        executor.isShutdown(),
                        activeCount,
                        completedCount
                ));
            }
            return List.copyOf(snapshots);
        }
    }

    /**
     * 按注册逆序返回所有线程池（用于关闭）。
     *
     * @return 逆序的 name→executor 条目列表
     */
    List<Map.Entry<String, ExecutorService>> orderedEntriesReversed() {
        synchronized (orderLock) {
            var entries = new ArrayList<>(orderedRegistry.entrySet());
            Collections.reverse(entries);
            return entries;
        }
    }
}

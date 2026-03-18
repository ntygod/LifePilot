package com.lifepilot.config.threadpool;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Virtual Thread 执行器工厂 — 统一创建带命名的 ExecutorService。
 *
 * <p>提供两种创建模式：
 * <ul>
 *   <li>{@link #create(String)} — 全局执行器，注册到 Registry，由 LifecycleManager 统一关闭</li>
 *   <li>{@link #createLocal(String)} — 方法级局部执行器，不注册，调用方自行关闭</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class VirtualThreadExecutorFactory {

    private final ThreadPoolRegistry registry;

    public VirtualThreadExecutorFactory(ThreadPoolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 创建全局执行器（注册到 Registry，由 LifecycleManager 统一关闭）。
     *
     * @param name 执行器名称，线程命名为 "{name}-vt-0", "{name}-vt-1", ...
     * @return ExecutorService
     */
    public ExecutorService create(String name) {
        var factory = Thread.ofVirtual().name(name + "-vt-", 0).factory();
        var executor = Executors.newThreadPerTaskExecutor(factory);
        registry.register(name, executor);
        return executor;
    }

    /**
     * 创建方法级局部执行器（不注册到 Registry，调用方自行关闭）。
     *
     * @param name 执行器名称
     * @return ExecutorService
     */
    public ExecutorService createLocal(String name) {
        var factory = Thread.ofVirtual().name(name + "-vt-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}

package com.lifepilot.config.threadpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ThreadPoolLifecycleManager 单元测试。
 *
 * @author zsg
 * @since 2026-03-18
 */
class ThreadPoolLifecycleManagerTest {

    private ThreadPoolRegistry registry;
    private ThreadPoolLifecycleManager manager;

    @BeforeEach
    void setUp() {
        registry = new ThreadPoolRegistry();
        var props = new ThreadPoolProperties();
        props.setShutdownTimeoutSeconds(2);
        manager = new ThreadPoolLifecycleManager(registry, props);
    }

    @Test
    void phase值为MAX_VALUE() {
        assertEquals(Integer.MAX_VALUE, manager.getPhase());
    }

    @Test
    void start后isRunning为true() {
        manager.start();
        assertTrue(manager.isRunning());
    }

    @Test
    void stop后所有线程池已关闭() {
        var e1 = Executors.newSingleThreadExecutor();
        var e2 = Executors.newSingleThreadExecutor();
        registry.register("pool-a", e1);
        registry.register("pool-b", e2);

        manager.start();
        manager.stop();

        assertTrue(e1.isShutdown());
        assertTrue(e2.isShutdown());
        assertFalse(manager.isRunning());
    }

    @Test
    void stop后isRunning为false() {
        manager.start();
        manager.stop();
        assertFalse(manager.isRunning());
    }
}

package com.lifepilot.config.threadpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ThreadPoolRegistry 单元测试。
 *
 * @author zsg
 * @since 2026-03-18
 */
class ThreadPoolRegistryTest {

    private ThreadPoolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ThreadPoolRegistry();
    }

    @Test
    void 注册后可通过find查询() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        registry.register("test-pool", executor);
        var found = registry.find("test-pool");
        assertTrue(found.isPresent());
        assertSame(executor, found.get());
        executor.shutdownNow();
    }

    @Test
    void 注册后listNames包含名称() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        registry.register("pool-a", executor);
        assertTrue(registry.listNames().contains("pool-a"));
        executor.shutdownNow();
    }

    @Test
    void 重复名称注册抛出异常() {
        ExecutorService e1 = Executors.newSingleThreadExecutor();
        ExecutorService e2 = Executors.newSingleThreadExecutor();
        registry.register("dup", e1);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("dup", e2));
        assertSame(e1, registry.find("dup").orElseThrow());
        e1.shutdownNow();
        e2.shutdownNow();
    }


    @Test
    void 查询不存在的名称返回empty() {
        assertTrue(registry.find("nonexistent").isEmpty());
    }

    @Test
    void snapshot返回正确数量和状态() {
        var stpe = new ScheduledThreadPoolExecutor(1);
        registry.register("scheduled", stpe);
        var snapshots = registry.snapshot();
        assertEquals(1, snapshots.size());
        var snap = snapshots.getFirst();
        assertEquals("scheduled", snap.name());
        assertEquals("ScheduledExecutorService", snap.type());
        assertFalse(snap.shutdown());
        stpe.shutdownNow();
    }

    @Test
    void snapshot_关闭后状态正确() {
        var executor = Executors.newSingleThreadExecutor();
        registry.register("to-close", executor);
        executor.shutdownNow();
        var snap = registry.snapshot().getFirst();
        assertTrue(snap.shutdown());
    }

    @Test
    void orderedEntriesReversed_逆序返回() {
        var e1 = Executors.newSingleThreadExecutor();
        var e2 = Executors.newSingleThreadExecutor();
        var e3 = Executors.newSingleThreadExecutor();
        registry.register("first", e1);
        registry.register("second", e2);
        registry.register("third", e3);

        var reversed = registry.orderedEntriesReversed();
        assertEquals("third", reversed.get(0).getKey());
        assertEquals("second", reversed.get(1).getKey());
        assertEquals("first", reversed.get(2).getKey());

        e1.shutdownNow();
        e2.shutdownNow();
        e3.shutdownNow();
    }
}

package com.lifepilot.config.threadpool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SharedScheduler 单元测试。
 *
 * @author zsg
 * @since 2026-03-18
 */
class SharedSchedulerTest {

    private ThreadPoolRegistry registry;
    private SharedScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry = new ThreadPoolRegistry();
        var props = new ThreadPoolProperties();
        scheduler = new SharedScheduler(registry, props);
    }

    @AfterEach
    void tearDown() {
        scheduler.cleanup().shutdownNow();
        scheduler.debounce().shutdownNow();
        scheduler.heartbeat().shutdownNow();
    }

    @Test
    void 三个分组返回非null() {
        assertNotNull(scheduler.cleanup());
        assertNotNull(scheduler.debounce());
        assertNotNull(scheduler.heartbeat());
    }

    @Test
    void 三个分组已注册到Registry() {
        assertTrue(registry.find("shared-cleanup").isPresent());
        assertTrue(registry.find("shared-debounce").isPresent());
        assertTrue(registry.find("shared-heartbeat").isPresent());
    }

    @Test
    void 三个分组是不同实例() {
        assertNotSame(scheduler.cleanup(), scheduler.debounce());
        assertNotSame(scheduler.cleanup(), scheduler.heartbeat());
        assertNotSame(scheduler.debounce(), scheduler.heartbeat());
    }

    @Test
    void Registry中实例与访问方法返回一致() {
        assertSame(scheduler.cleanup(),
                registry.find("shared-cleanup").orElseThrow());
        assertSame(scheduler.debounce(),
                registry.find("shared-debounce").orElseThrow());
        assertSame(scheduler.heartbeat(),
                registry.find("shared-heartbeat").orElseThrow());
    }
}

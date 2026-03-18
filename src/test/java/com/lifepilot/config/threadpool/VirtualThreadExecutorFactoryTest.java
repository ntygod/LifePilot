package com.lifepilot.config.threadpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VirtualThreadExecutorFactory 单元测试。
 *
 * @author zsg
 * @since 2026-03-18
 */
class VirtualThreadExecutorFactoryTest {

    private ThreadPoolRegistry registry;
    private VirtualThreadExecutorFactory factory;

    @BeforeEach
    void setUp() {
        registry = new ThreadPoolRegistry();
        factory = new VirtualThreadExecutorFactory(registry);
    }

    @Test
    void create_注册到Registry() {
        var executor = factory.create("workflow");
        assertTrue(registry.find("workflow").isPresent());
        assertSame(executor, registry.find("workflow").orElseThrow());
        executor.shutdownNow();
    }

    @Test
    void createLocal_不注册到Registry() {
        var executor = factory.createLocal("temp-task");
        assertTrue(registry.find("temp-task").isEmpty());
        executor.shutdownNow();
    }

    @Test
    void create_重复名称抛出异常() {
        var e1 = factory.create("dup-pool");
        assertThrows(IllegalArgumentException.class,
                () -> factory.create("dup-pool"));
        e1.shutdownNow();
    }
}

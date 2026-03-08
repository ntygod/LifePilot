package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BrowserSessionManager 单元测试。
 *
 * <p>测试环境可能没有 Playwright，因此重点测试可用性检测和降级行为。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class BrowserSessionManagerTest {

    private MetaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
    }

    @Test
    void isAvailable_返回布尔值不抛异常() {
        var manager = new BrowserSessionManager(properties);
        // 不论 Playwright 是否在 classpath 上，isAvailable() 都不应抛异常
        boolean available = manager.isAvailable();
        assertThat(available).isIn(true, false);
    }

    @Test
    void getUnavailableMessage_返回中文提示() {
        var manager = new BrowserSessionManager(properties);
        assertThat(manager.getUnavailableMessage()).contains("Playwright");
    }

    @Test
    void getActiveSessionCount_初始为零() {
        var manager = new BrowserSessionManager(properties);
        assertThat(manager.getActiveSessionCount()).isZero();
    }

    @Test
    void close_无活跃会话时不抛异常() {
        var manager = new BrowserSessionManager(properties);
        // close() 在无活跃会话时应安全执行
        manager.close();
        assertThat(manager.getActiveSessionCount()).isZero();
    }

    @Test
    void isAvailable_禁用浏览器时返回false() {
        properties.getInfra().getBrowser().setEnabled(false);
        var manager = new BrowserSessionManager(properties);
        assertThat(manager.isAvailable()).isFalse();
    }

    @Test
    void cleanupIdleSessions_无会话时不抛异常() {
        var manager = new BrowserSessionManager(properties);
        manager.cleanupIdleSessions();
        assertThat(manager.getActiveSessionCount()).isZero();
    }
}

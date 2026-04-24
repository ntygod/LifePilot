package com.lifepilot.meta.infra.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * BrowserSessionScheduler 调度行为测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class BrowserSessionScheduler_调度测试 {

    @Mock
    BrowserSessionManager sessionManager;

    @Test
    void 定时任务应调用清理方法() {
        var scheduler = new BrowserSessionScheduler(sessionManager);
        scheduler.cleanup();
        verify(sessionManager).cleanupIdleSessions();
    }
}

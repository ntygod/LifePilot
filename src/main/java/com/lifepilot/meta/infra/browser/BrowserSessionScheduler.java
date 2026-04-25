package com.lifepilot.meta.infra.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 浏览器会话空闲清理调度器。
 *
 * <p>定时调用 {@link BrowserSessionManager#cleanupIdleSessions()} 清理
 * 超过 {@code lifepilot.meta.infra.browser.idle-timeout-seconds} 的空闲会话，
 * 防止 Chromium 会话累积和资源泄漏。</p>
 *
 * <p>清理间隔通过 {@code lifepilot.meta.infra.browser.cleanup-interval-seconds}
 * 配置，默认 60 秒，建议设置为 {@code idle-timeout-seconds} 的 1/5 左右。</p>
 *
 * <p>由 {@code MetaAutoConfiguration} 在 {@link BrowserSessionManager} Bean
 * 可用时注册，依托已启用的 {@code @EnableScheduling} 触发定时任务。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class BrowserSessionScheduler {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionScheduler.class);

    private final BrowserSessionManager sessionManager;

    public BrowserSessionScheduler(BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 定时清理空闲浏览器会话。
     *
     * <p>间隔取自配置 {@code lifepilot.meta.infra.browser.cleanup-interval-seconds}
     * （单位：秒，默认 60），SpEL 表达式换算为毫秒传给 {@code fixedDelayString}。
     * 清理动作本身由 BrowserSessionManager 根据 {@code idle-timeout-seconds} 判定超时。</p>
     */
    @Scheduled(fixedDelayString =
            "#{${lifepilot.meta.infra.browser.cleanup-interval-seconds:60} * 1000}")
    public void cleanup() {
        try {
            sessionManager.cleanupIdleSessions();
        } catch (Exception e) {
            log.warn("浏览器空闲会话清理失败: error={}", e.getMessage());
        }
    }
}

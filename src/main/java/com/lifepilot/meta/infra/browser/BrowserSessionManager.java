package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浏览器会话管理器 — 单例管理 Playwright Browser 实例。
 *
 * <p>核心职责：
 * <ul>
 *   <li>懒初始化 Playwright Browser 实例</li>
 *   <li>会话级 Page 复用（sessionId → Page）</li>
 *   <li>空闲超时自动关闭</li>
 *   <li>Playwright 未安装时优雅降级</li>
 * </ul>
 *
 * <p>Playwright 是可选依赖，通过反射检测可用性。当 Playwright 不在 classpath 时，
 * {@link #isAvailable()} 返回 false，所有浏览器工具返回优雅降级提示。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class BrowserSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionManager.class);
    private static final String PLAYWRIGHT_CLASS = "com.microsoft.playwright.Playwright";
    private static final String UNAVAILABLE_MESSAGE = "浏览器功能未配置，请安装 Playwright";

    private final MetaProperties.Infra.Browser browserConfig;
    private final boolean playwrightAvailable;

    /** Playwright 实例（懒初始化），仅在 Playwright 可用时非 null。 */
    @Nullable
    private volatile Object playwrightInstance;

    /** Browser 实例（懒初始化）。 */
    @Nullable
    private volatile Object browserInstance;

    /** 会话级 Page 复用：sessionId → PlaywrightPageWrapper。 */
    private final ConcurrentHashMap<String, PlaywrightPageWrapper> sessions = new ConcurrentHashMap<>();

    public BrowserSessionManager(MetaProperties properties) {
        this.browserConfig = properties.getInfra().getBrowser();
        this.playwrightAvailable = detectPlaywright();
        if (playwrightAvailable) {
            log.info("Playwright 检测成功，浏览器自动化功能可用");
        } else {
            log.warn("Playwright 未检测到，浏览器自动化功能不可用");
        }
    }

    /**
     * 检测 Playwright 是否在 classpath 上。
     *
     * @return true 表示 Playwright 可用
     */
    private boolean detectPlaywright() {
        try {
            Class.forName(PLAYWRIGHT_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Playwright 是否可用。
     *
     * @return true 表示 Playwright 在 classpath 上且可初始化
     */
    public boolean isAvailable() {
        return playwrightAvailable && browserConfig.isEnabled();
    }

    /**
     * 获取不可用时的降级提示消息。
     *
     * @return 降级提示消息
     */
    public String getUnavailableMessage() {
        return UNAVAILABLE_MESSAGE;
    }

    /**
     * 获取或创建指定会话的 Page。
     *
     * <p>首次调用时懒初始化 Playwright 和 Browser 实例。
     * 同一 sessionId 复用已有 Page，并更新最后访问时间。</p>
     *
     * @param sessionId 会话 ID
     * @return Page 包装器
     * @throws IllegalStateException 如果 Playwright 不可用
     */
    public PlaywrightPageWrapper getOrCreatePage(String sessionId) {
        if (!isAvailable()) {
            throw new IllegalStateException(UNAVAILABLE_MESSAGE);
        }
        return sessions.compute(sessionId, (id, existing) -> {
            if (existing != null && !existing.isClosed()) {
                existing.touch();
                return existing;
            }
            // 创建新 Page
            var browser = ensureBrowser();
            var page = PlaywrightBridge.createPage(browser);
            log.debug("创建浏览器 Page: sessionId={}", sessionId);
            return new PlaywrightPageWrapper(page);
        });
    }

    /**
     * 关闭指定会话的 Page。
     *
     * @param sessionId 会话 ID
     */
    public void closePage(String sessionId) {
        var wrapper = sessions.remove(sessionId);
        if (wrapper != null) {
            wrapper.close();
            log.debug("关闭浏览器 Page: sessionId={}", sessionId);
        }
    }

    /**
     * 清理所有资源 — 关闭所有 Page、Browser 和 Playwright 实例。
     */
    public void close() {
        // 关闭所有 Page
        sessions.forEach((id, wrapper) -> {
            try {
                wrapper.close();
            } catch (Exception e) {
                log.warn("关闭浏览器 Page 失败: sessionId={}, error={}", id, e.getMessage());
            }
        });
        sessions.clear();

        // 关闭 Browser
        if (browserInstance != null) {
            try {
                PlaywrightBridge.closeBrowser(browserInstance);
            } catch (Exception e) {
                log.warn("关闭 Browser 失败: error={}", e.getMessage());
            }
            browserInstance = null;
        }

        // 关闭 Playwright
        if (playwrightInstance != null) {
            try {
                PlaywrightBridge.closePlaywright(playwrightInstance);
            } catch (Exception e) {
                log.warn("关闭 Playwright 失败: error={}", e.getMessage());
            }
            playwrightInstance = null;
        }

        log.info("浏览器会话管理器已关闭");
    }

    /**
     * 清理空闲超时的 Page。
     */
    public void cleanupIdleSessions() {
        long idleTimeoutMs = browserConfig.getIdleTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();

        sessions.entrySet().removeIf(entry -> {
            var wrapper = entry.getValue();
            if (now - wrapper.getLastAccessTime() > idleTimeoutMs) {
                wrapper.close();
                log.debug("空闲超时关闭浏览器 Page: sessionId={}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 确保 Browser 实例已初始化。
     *
     * @return Browser 实例
     */
    private synchronized Object ensureBrowser() {
        if (browserInstance == null) {
            playwrightInstance = PlaywrightBridge.createPlaywright();
            browserInstance = PlaywrightBridge.launchBrowser(playwrightInstance, browserConfig.isHeadless());
            log.info("Playwright Browser 懒初始化完成: headless={}", browserConfig.isHeadless());
        }
        return browserInstance;
    }

    /**
     * 获取当前活跃会话数。
     *
     * @return 活跃会话数
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
}

package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 浏览器会话管理器 — 单例管理 Playwright Browser 实例。
 *
 * <p>核心职责：
 * <ul>
 *   <li>懒初始化 Playwright Browser 实例</li>
 *   <li>会话级多标签页管理（sessionId → SessionPages）</li>
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
    private final BrowserRuntime browserRuntime;

    /** Playwright 实例（懒初始化），仅在 Playwright 可用时非 null。 */
    @Nullable
    private volatile Object playwrightInstance;

    /** Browser 实例（懒初始化）。 */
    @Nullable
    private volatile Object browserInstance;

    /** 会话级多标签页管理：sessionId → SessionPages。 */
    private final ConcurrentHashMap<String, SessionPages> sessions = new ConcurrentHashMap<>();

    /** 会话内多标签页容器。 */
    private static class SessionPages {
        final Object browserContext;
        final ConcurrentHashMap<String, PlaywrightPageWrapper> pages = new ConcurrentHashMap<>();
        volatile String activeTabId;

        SessionPages(Object browserContext, String tabId, PlaywrightPageWrapper page) {
            this.browserContext = browserContext;
            this.pages.put(tabId, page);
            this.activeTabId = tabId;
        }
    }

    public BrowserSessionManager(MetaProperties properties) {
        this(properties, detectPlaywright(), new DefaultBrowserRuntime());
    }

    BrowserSessionManager(MetaProperties properties, boolean playwrightAvailable, BrowserRuntime browserRuntime) {
        this.browserConfig = properties.getInfra().getBrowser();
        this.playwrightAvailable = playwrightAvailable;
        this.browserRuntime = browserRuntime;
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
    private static boolean detectPlaywright() {
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
     * 同一 sessionId 复用已有活跃标签页的 Page，并更新最后访问时间。</p>
     *
     * @param sessionId 会话 ID
     * @return 活跃标签页的 Page 包装器
     * @throws IllegalStateException 如果 Playwright 不可用
     */
    public PlaywrightPageWrapper getOrCreatePage(String sessionId) {
        if (!isAvailable()) {
            throw new IllegalStateException(UNAVAILABLE_MESSAGE);
        }
        var sessionPages = sessions.compute(sessionId, this::ensureSessionPages);
        return sessionPages.pages.get(sessionPages.activeTabId);
    }

    /**
     * 关闭指定会话的所有 Page。
     *
     * @param sessionId 会话 ID
     */
    public void closePage(String sessionId) {
        var sessionPages = sessions.remove(sessionId);
        if (sessionPages != null) {
            closeSessionPages(sessionId, sessionPages);
        }
    }

    /**
     * 在指定会话中打开新标签页并导航到 URL。
     *
     * @param sessionId 会话 ID
     * @param url 目标 URL
     * @return 新标签页 ID
     */
    public String openNewPage(String sessionId, String url) {
        if (!isAvailable()) {
            throw new IllegalStateException(UNAVAILABLE_MESSAGE);
        }
        var createdSession = new AtomicBoolean(false);
        var sp = sessions.compute(sessionId, (id, existing) -> {
            if (existing == null) {
                createdSession.set(true);
                return createSessionPages(id);
            }
            return ensureSessionPages(id, existing);
        });
        if (createdSession.get()) {
            var wrapper = sp.pages.get(sp.activeTabId);
            wrapper.navigate(url);
            log.debug("创建首个标签页并导航: sessionId={}, tabId={}, url={}", sessionId, sp.activeTabId, url);
            return sp.activeTabId;
        }
        var page = browserRuntime.createPage(sp.browserContext);
        var wrapper = new PlaywrightPageWrapper(page);
        wrapper.navigate(url);
        String tabId = UUID.randomUUID().toString().substring(0, 8);
        sp.pages.put(tabId, wrapper);
        sp.activeTabId = tabId;
        log.debug("打开新标签页: sessionId={}, tabId={}, url={}", sessionId, tabId, url);
        return tabId;
    }

    /**
     * 切换活跃标签页。
     *
     * @param sessionId 会话 ID
     * @param tabId 目标标签页 ID
     * @throws IllegalArgumentException tabId 不存在时
     */
    public void switchPage(String sessionId, String tabId) {
        var sp = sessions.get(sessionId);
        if (sp == null || !sp.pages.containsKey(tabId)) {
            throw new IllegalArgumentException("标签页不存在: sessionId=%s, tabId=%s".formatted(sessionId, tabId));
        }
        sp.activeTabId = tabId;
        sp.pages.get(tabId).touch();
        log.debug("切换标签页: sessionId={}, tabId={}", sessionId, tabId);
    }

    /**
     * 关闭指定标签页。
     *
     * @param sessionId 会话 ID
     * @param tabId 要关闭的标签页 ID
     * @throws IllegalArgumentException tabId 不存在时
     */
    public void closeTab(String sessionId, String tabId) {
        var sp = sessions.get(sessionId);
        if (sp == null || !sp.pages.containsKey(tabId)) {
            throw new IllegalArgumentException("标签页不存在: sessionId=%s, tabId=%s".formatted(sessionId, tabId));
        }
        var wrapper = sp.pages.remove(tabId);
        if (wrapper != null) {
            wrapper.close();
        }
        // 如果关闭的是活跃标签页，切换到另一个
        if (tabId.equals(sp.activeTabId) && !sp.pages.isEmpty()) {
            sp.activeTabId = sp.pages.keys().nextElement();
        }
        // 如果所有标签页都关闭了，移除整个会话
        if (sp.pages.isEmpty()) {
            sessions.remove(sessionId);
            closeBrowserContext(sp.browserContext);
        }
        log.debug("关闭标签页: sessionId={}, tabId={}", sessionId, tabId);
    }

    /**
     * 列出指定会话的所有标签页信息。
     *
     * @param sessionId 会话 ID
     * @return 标签页信息列表
     */
    public List<TabInfo> listPages(String sessionId) {
        var sp = sessions.get(sessionId);
        if (sp == null) {
            return List.of();
        }
        return sp.pages.entrySet().stream()
                .map(e -> new TabInfo(e.getKey(), e.getValue().url(), e.getValue().title()))
                .toList();
    }

    /**
     * 清理所有资源 — 关闭所有 Page、Browser 和 Playwright 实例。
     */
    public void close() {
        // 关闭所有 Page
        sessions.forEach((id, sp) -> {
            closeSessionPages(id, sp);
        });
        sessions.clear();

        // 关闭 Browser
        if (browserInstance != null) {
            try {
                browserRuntime.closeBrowser(browserInstance);
            } catch (Exception e) {
                log.warn("关闭 Browser 失败: error={}", e.getMessage());
            }
            browserInstance = null;
        }

        // 关闭 Playwright
        if (playwrightInstance != null) {
            try {
                browserRuntime.closePlaywright(playwrightInstance);
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
            var sp = entry.getValue();
            // 检查所有 page 是否都已超时
            boolean allIdle = sp.pages.values().stream()
                    .allMatch(w -> now - w.getLastAccessTime() > idleTimeoutMs);
            if (allIdle) {
                closeSessionPages(entry.getKey(), sp);
                return true;
            }
            return false;
        });
    }

    /**
     * 确保 Browser 实例已初始化。
     *
     * <p>首次调用时创建 Playwright 和 Browser 实例。
     * 如果浏览器二进制未安装，抛出 {@link BrowserNotInstalledException}。</p>
     *
     * @return Browser 实例
     * @throws BrowserNotInstalledException 浏览器二进制未安装
     */
    private synchronized Object ensureBrowser() {
        if (browserInstance == null) {
            playwrightInstance = browserRuntime.createPlaywright();
            try {
                browserInstance = browserRuntime.launchBrowser(playwrightInstance, browserConfig.isHeadless());
            } catch (Exception e) {
                // Playwright 浏览器二进制未安装时，launch() 会抛出异常
                String msg = e.getMessage();
                if (msg != null && (msg.contains("install") || msg.contains("executable doesn't exist")
                        || msg.contains("browserType.launch"))) {
                    throw new BrowserNotInstalledException(
                            "Playwright 浏览器二进制未安装，请运行安装命令", e);
                }
                throw e;
            }
            log.info("Playwright Browser 懒初始化完成: headless={}", browserConfig.isHeadless());
        }
        return browserInstance;
    }

    private SessionPages ensureSessionPages(String sessionId, @Nullable SessionPages existing) {
        if (existing == null) {
            return createSessionPages(sessionId);
        }
        var activePage = existing.pages.get(existing.activeTabId);
        if (activePage != null && !activePage.isClosed()) {
            activePage.touch();
            return existing;
        }
        for (var entry : existing.pages.entrySet()) {
            if (!entry.getValue().isClosed()) {
                existing.activeTabId = entry.getKey();
                entry.getValue().touch();
                return existing;
            }
        }
        closeSessionPages(sessionId, existing);
        return createSessionPages(sessionId);
    }

    private SessionPages createSessionPages(String sessionId) {
        var browser = ensureBrowser();
        var browserContext = browserRuntime.createContext(browser);
        var page = browserRuntime.createPage(browserContext);
        String tabId = UUID.randomUUID().toString().substring(0, 8);
        log.debug("创建浏览器会话上下文: sessionId={}, tabId={}", sessionId, tabId);
        return new SessionPages(browserContext, tabId, new PlaywrightPageWrapper(page));
    }

    private void closeSessionPages(String sessionId, SessionPages sessionPages) {
        sessionPages.pages.values().forEach(wrapper -> {
            try {
                wrapper.close();
            } catch (Exception e) {
                log.warn("关闭浏览器 Page 失败: sessionId={}, error={}", sessionId, e.getMessage());
            }
        });
        closeBrowserContext(sessionPages.browserContext);
        log.debug("关闭浏览器会话所有 Page: sessionId={}", sessionId);
    }

    private void closeBrowserContext(Object browserContext) {
        try {
            browserRuntime.closeContext(browserContext);
        } catch (Exception e) {
            log.warn("关闭 BrowserContext 失败: error={}", e.getMessage());
        }
    }

    /**
     * 获取当前活跃会话数。
     *
     * @return 活跃会话数
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    interface BrowserRuntime {
        Object createPlaywright();

        Object launchBrowser(Object playwrightObj, boolean headless);

        Object createContext(Object browserObj);

        Object createPage(Object browserContextObj);

        void closeContext(Object browserContextObj);

        void closeBrowser(Object browserObj);

        void closePlaywright(Object playwrightObj);
    }

    private static final class DefaultBrowserRuntime implements BrowserRuntime {

        @Override
        public Object createPlaywright() {
            return PlaywrightBridge.createPlaywright();
        }

        @Override
        public Object launchBrowser(Object playwrightObj, boolean headless) {
            return PlaywrightBridge.launchBrowser(playwrightObj, headless);
        }

        @Override
        public Object createContext(Object browserObj) {
            return PlaywrightBridge.createContext(browserObj);
        }

        @Override
        public Object createPage(Object browserContextObj) {
            return PlaywrightBridge.createPage(browserContextObj);
        }

        @Override
        public void closeContext(Object browserContextObj) {
            PlaywrightBridge.closeContext(browserContextObj);
        }

        @Override
        public void closeBrowser(Object browserObj) {
            PlaywrightBridge.closeBrowser(browserObj);
        }

        @Override
        public void closePlaywright(Object playwrightObj) {
            PlaywrightBridge.closePlaywright(playwrightObj);
        }
    }
}

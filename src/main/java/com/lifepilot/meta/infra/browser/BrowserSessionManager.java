package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>通过反射检测 Playwright API 与 driver-bundle 的可用性。任一缺失时
 * {@link #isAvailable()} 返回 false，所有浏览器工具返回可操作的降级提示。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class BrowserSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionManager.class);
    private static final String PLAYWRIGHT_CLASS = "com.microsoft.playwright.Playwright";
    // 注意：DriverJar 是 Playwright 内部类，升级 Playwright 版本时需回归验证此路径。
    // 当前适用版本：playwright 1.58.0
    private static final String DRIVER_JAR_CLASS = "com.microsoft.playwright.impl.driver.jar.DriverJar";
    private static final String MSG_NO_API =
            "浏览器功能未配置，请安装 Playwright（在 pom.xml 中添加 com.microsoft.playwright:playwright 依赖）";
    private static final String MSG_NO_DRIVER =
            "Playwright driver-bundle 不在 classpath 中，浏览器自动化不可用。";

    private final MetaProperties.Infra.Browser browserConfig;
    private final boolean playwrightAvailable;
    @Nullable
    private final String unavailableReason;
    private final BrowserRuntime browserRuntime;
    private final BrowserAcquisitionMode acquisitionMode;

    /** Playwright 实例（懒初始化），仅在 Playwright 可用时非 null。 */
    @Nullable
    private volatile Object playwrightInstance;

    /** Browser 实例（懒初始化）。PERSISTENT 模式下为 null。 */
    @Nullable
    private volatile Object browserInstance;

    /** 共享 BrowserContext — CDP 模式为默认上下文，PERSISTENT 模式为持久上下文。 */
    @Nullable
    private volatile Object sharedBrowserContext;

    /** 共享上下文的 stealth 脚本注入守卫，保证只注入一次。 */
    private final AtomicBoolean stealthInjected = new AtomicBoolean(false);

    /** 会话级多标签页管理：sessionId → SessionPages。 */
    private final ConcurrentHashMap<String, SessionPages> sessions = new ConcurrentHashMap<>();

    /** 会话级模式覆盖：首次创建会话时使用，后续复用已有会话。 */
    private final ConcurrentHashMap<String, SessionModeOverride> sessionModeOverrides = new ConcurrentHashMap<>();

    /**
     * 会话级浏览器模式覆盖参数。
     *
     * @param mode        浏览器获取模式
     * @param cdpUrl      CDP 模式的远程调试端口 URL（仅 CDP 模式需要）
     * @param userDataDir PERSISTENT 模式的用户数据目录（仅 PERSISTENT 模式需要）
     */
    public record SessionModeOverride(BrowserAcquisitionMode mode,
                                      @Nullable String cdpUrl,
                                      @Nullable String userDataDir) {}


    /** 会话内多标签页容器。 */
    private static class SessionPages {
        final Object browserContext;
        /** true 表示上下文为 CDP/PERSISTENT 共享，关闭会话时不关闭上下文。 */
        final boolean sharedContext;
        final ConcurrentHashMap<String, PlaywrightPageWrapper> pages = new ConcurrentHashMap<>();
        volatile String activeTabId;

        SessionPages(Object browserContext, boolean sharedContext, String tabId, PlaywrightPageWrapper page) {
            this.browserContext = browserContext;
            this.sharedContext = sharedContext;
            this.pages.put(tabId, page);
            this.activeTabId = tabId;
        }
    }

    public BrowserSessionManager(MetaProperties properties) {
        this(properties, detectPlaywright(), new DefaultBrowserRuntime());
    }

    /**
     * 包级构造器，供测试注入 BrowserRuntime。
     *
     * @param unavailableReason null 表示 Playwright 可用；非 null 为不可用原因
     */
    BrowserSessionManager(MetaProperties properties, @Nullable String unavailableReason, BrowserRuntime browserRuntime) {
        this.browserConfig = properties.getInfra().getBrowser();
        this.unavailableReason = unavailableReason;
        this.playwrightAvailable = unavailableReason == null;
        this.browserRuntime = browserRuntime;
        this.acquisitionMode = browserConfig.getAcquisitionMode();
        if (playwrightAvailable) {
            log.info("Playwright 检测成功，浏览器自动化功能可用: mode={}", acquisitionMode);
        } else {
            log.warn("浏览器自动化功能不可用: {}", unavailableReason);
        }
    }

    /**
     * 检测 Playwright 可用性。
     *
     * <p>分两步：先检测 API JAR，再检测 driver-bundle。两者都存在才视为可用。</p>
     *
     * @return null 表示完全可用；非 null 为具体缺失原因，可直接展示给用户
     */
    @Nullable
    private static String detectPlaywright() {
        try {
            Class.forName(PLAYWRIGHT_CLASS);
        } catch (ClassNotFoundException e) {
            return MSG_NO_API;
        }
        try {
            Class.forName(DRIVER_JAR_CLASS);
        } catch (ClassNotFoundException e) {
            return MSG_NO_DRIVER;
        }
        return null;
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
        return unavailableReason != null ? unavailableReason : "浏览器功能未配置";
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
            throw new IllegalStateException(getUnavailableMessage());
        }
        var sessionPages = sessions.compute(sessionId, this::ensureSessionPages);
        return sessionPages.pages.get(sessionPages.activeTabId);
    }

    /**
     * 注册会话级模式覆盖（不立即创建会话）。
     *
     * <p>下次为该 sessionId 创建新会话时使用指定模式；会话已存在时忽略。
     * 使用 putIfAbsent 避免 check-then-act 竞态。</p>
     *
     * @param sessionId    会话 ID
     * @param modeOverride 模式覆盖参数
     */
    public void registerSessionMode(String sessionId, SessionModeOverride modeOverride) {
        if (!sessions.containsKey(sessionId)) {
            sessionModeOverrides.putIfAbsent(sessionId, modeOverride);
        }
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
            throw new IllegalStateException(getUnavailableMessage());
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
        var wrapper = new PlaywrightPageWrapper(page, browserConfig.getHumanDelayMinMs(), browserConfig.getHumanDelayMaxMs());
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
            if (!sp.sharedContext) {
                closeBrowserContext(sp.browserContext);
            }
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

        // PERSISTENT 模式：关闭持久上下文（包含浏览器进程）
        // CDP 模式：默认上下文由远程 Chrome 管理，不主动关闭（browser.close() 已断开连接）
        if (sharedBrowserContext != null && acquisitionMode == BrowserAcquisitionMode.PERSISTENT) {
            try {
                browserRuntime.closeContext(sharedBrowserContext);
            } catch (Exception e) {
                log.warn("关闭持久 BrowserContext 失败: error={}", e.getMessage());
            }
        }
        sharedBrowserContext = null;

        // LAUNCH / CDP 模式：关闭 Browser（CDP 仅断开连接，不杀 Chrome 进程）
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

        stealthInjected.set(false);
        log.info("浏览器会话管理器已关闭: mode={}", acquisitionMode);
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
     * 确保浏览器环境已初始化（按获取模式分派）。
     *
     * <p>LAUNCH 模式返回 Browser 实例；CDP 模式返回 CDP 连接的 Browser 实例；
     * PERSISTENT 模式无 Browser 对象，返回 null。
     * 三种模式均保证 {@link #sharedBrowserContext} 或 browserInstance 在调用后可用。</p>
     *
     * @return Browser 实例（PERSISTENT 模式下为 null）
     */
    private synchronized Object ensureBrowser() {
        return switch (acquisitionMode) {
            case LAUNCH -> ensureLaunchBrowser();
            case CDP -> ensureCdpBrowser();
            case PERSISTENT -> {
                ensurePersistentContext();
                yield null;
            }
        };
    }

    /** LAUNCH 模式 — 启动新 Chromium 实例（原有逻辑）。 */
    private Object ensureLaunchBrowser() {
        if (browserInstance == null) {
            playwrightInstance = browserRuntime.createPlaywright();
            try {
                browserInstance = browserRuntime.launchBrowser(playwrightInstance,
                        browserConfig.isHeadless(), browserConfig.getExtraLaunchArgs());
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("install") || msg.contains("executable doesn't exist")
                        || msg.contains("browserType.launch"))) {
                    throw new BrowserNotInstalledException(
                            "Playwright 浏览器二进制未安装，请运行安装命令", e);
                }
                throw e;
            }
            log.info("Playwright Browser 懒初始化完成: mode=LAUNCH, headless={}", browserConfig.isHeadless());
        }
        return browserInstance;
    }

    /** CDP 模式 — 连接到用户已运行的 Chrome，获取其默认上下文。 */
    private Object ensureCdpBrowser() {
        if (browserInstance == null) {
            String cdpUrl = browserConfig.getCdpUrl();
            if (cdpUrl == null || cdpUrl.isBlank()) {
                throw new IllegalStateException("CDP 模式需要配置 cdp-url（如 http://localhost:9222）");
            }
            playwrightInstance = browserRuntime.createPlaywright();
            try {
                browserInstance = browserRuntime.connectOverCDP(playwrightInstance, cdpUrl);
            } catch (Exception e) {
                browserRuntime.closePlaywright(playwrightInstance);
                playwrightInstance = null;
                throw new IllegalStateException("CDP 连接失败: " + cdpUrl + " — " + e.getMessage(), e);
            }
            var contexts = browserRuntime.getContexts(browserInstance);
            if (!contexts.isEmpty()) {
                sharedBrowserContext = contexts.getFirst();
            } else {
                // 远程浏览器没有上下文（极端情况），创建一个新的
                sharedBrowserContext = browserRuntime.createContext(browserInstance,
                        browserConfig.getUserAgent(), browserConfig.getViewportWidth(),
                        browserConfig.getViewportHeight(), browserConfig.getLocale(),
                        browserConfig.getTimezoneId(), null);
            }
            log.info("已通过 CDP 连接到浏览器: cdpUrl={}", cdpUrl);
        }
        return browserInstance;
    }

    /** PERSISTENT 模式 — 使用 userDataDir 启动持久化 BrowserContext。 */
    private void ensurePersistentContext() {
        if (sharedBrowserContext == null) {
            String dir = browserConfig.getUserDataDir();
            if (dir == null || dir.isBlank()) {
                throw new IllegalStateException("PERSISTENT 模式需要配置 user-data-dir");
            }
            playwrightInstance = browserRuntime.createPlaywright();
            try {
                sharedBrowserContext = browserRuntime.launchPersistentContext(
                        playwrightInstance, Path.of(dir), browserConfig.isHeadless(),
                        browserConfig.getExtraLaunchArgs(), browserConfig.getUserAgent(),
                        browserConfig.getViewportWidth(), browserConfig.getViewportHeight(),
                        browserConfig.getLocale(), browserConfig.getTimezoneId());
            } catch (Exception e) {
                browserRuntime.closePlaywright(playwrightInstance);
                playwrightInstance = null;
                String msg = e.getMessage();
                if (msg != null && (msg.contains("install") || msg.contains("executable doesn't exist"))) {
                    throw new BrowserNotInstalledException(
                            "Playwright 浏览器二进制未安装，请运行安装命令", e);
                }
                throw e;
            }
            log.info("持久化浏览器上下文已创建: mode=PERSISTENT, userDataDir={}, headless={}",
                    dir, browserConfig.isHeadless());
        }
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
        var override = sessionModeOverrides.remove(sessionId);
        var effectiveMode = override != null ? override.mode() : acquisitionMode;
        return switch (effectiveMode) {
            case LAUNCH -> createLaunchSessionPages(sessionId);
            case CDP -> {
                ensureCdpBrowserForSession(override);
                yield createSharedContextSessionPages(sessionId);
            }
            case PERSISTENT -> {
                ensurePersistentContextForSession(override);
                yield createSharedContextSessionPages(sessionId);
            }
        };
    }

    /**
     * CDP 模式初始化 — 支持会话级覆盖参数。
     * 直接使用覆盖参数连接，不修改共享 browserConfig。
     */
    private synchronized void ensureCdpBrowserForSession(@Nullable SessionModeOverride override) {
        if (browserInstance != null) {
            return;
        }
        String cdpUrl = (override != null && override.cdpUrl() != null && !override.cdpUrl().isBlank())
                ? override.cdpUrl()
                : browserConfig.getCdpUrl();
        if (cdpUrl == null || cdpUrl.isBlank()) {
            throw new IllegalStateException("CDP 模式需要配置 cdp-url（如 http://localhost:9222）");
        }
        playwrightInstance = browserRuntime.createPlaywright();
        try {
            browserInstance = browserRuntime.connectOverCDP(playwrightInstance, cdpUrl);
        } catch (Exception e) {
            browserRuntime.closePlaywright(playwrightInstance);
            playwrightInstance = null;
            throw new IllegalStateException("CDP 连接失败: " + cdpUrl + " — " + e.getMessage(), e);
        }
        var contexts = browserRuntime.getContexts(browserInstance);
        if (!contexts.isEmpty()) {
            sharedBrowserContext = contexts.getFirst();
        } else {
            sharedBrowserContext = browserRuntime.createContext(browserInstance,
                    browserConfig.getUserAgent(), browserConfig.getViewportWidth(),
                    browserConfig.getViewportHeight(), browserConfig.getLocale(),
                    browserConfig.getTimezoneId(), null);
        }
        log.info("已通过 CDP 连接到浏览器（会话级覆盖）: cdpUrl={}", cdpUrl);
    }

    /**
     * PERSISTENT 模式初始化 — 支持会话级覆盖参数。
     * 直接使用覆盖参数创建持久化上下文，不修改共享 browserConfig。
     */
    private synchronized void ensurePersistentContextForSession(@Nullable SessionModeOverride override) {
        if (sharedBrowserContext != null) {
            return;
        }
        String dir = (override != null && override.userDataDir() != null && !override.userDataDir().isBlank())
                ? override.userDataDir()
                : browserConfig.getUserDataDir();
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("PERSISTENT 模式需要配置 user-data-dir");
        }
        playwrightInstance = browserRuntime.createPlaywright();
        try {
            sharedBrowserContext = browserRuntime.launchPersistentContext(
                    playwrightInstance, Path.of(dir), browserConfig.isHeadless(),
                    browserConfig.getExtraLaunchArgs(), browserConfig.getUserAgent(),
                    browserConfig.getViewportWidth(), browserConfig.getViewportHeight(),
                    browserConfig.getLocale(), browserConfig.getTimezoneId());
        } catch (Exception e) {
            browserRuntime.closePlaywright(playwrightInstance);
            playwrightInstance = null;
            String msg = e.getMessage();
            if (msg != null && (msg.contains("install") || msg.contains("executable doesn't exist"))) {
                throw new BrowserNotInstalledException(
                        "Playwright 浏览器二进制未安装，请运行安装命令", e);
            }
            throw e;
        }
        log.info("持久化浏览器上下文已创建（会话级覆盖）: userDataDir={}, headless={}",
                dir, browserConfig.isHeadless());
    }

    /** LAUNCH 模式 — 每个会话独立 BrowserContext（原有逻辑）。 */
    private SessionPages createLaunchSessionPages(String sessionId) {
        var browser = ensureBrowser();
        Path storageStatePath = resolveStorageStatePath(sessionId);
        var browserContext = browserRuntime.createContext(
                browser,
                browserConfig.getUserAgent(),
                browserConfig.getViewportWidth(),
                browserConfig.getViewportHeight(),
                browserConfig.getLocale(),
                browserConfig.getTimezoneId(),
                storageStatePath);
        if (browserConfig.isStealthMode()) {
            browserRuntime.injectStealthScripts(browserContext, browserConfig.getLocale());
        }
        var page = browserRuntime.createPage(browserContext);
        String tabId = UUID.randomUUID().toString().substring(0, 8);
        log.debug("创建浏览器会话上下文: sessionId={}, tabId={}, mode=LAUNCH, stealth={}",
                sessionId, tabId, browserConfig.isStealthMode());
        return new SessionPages(browserContext, false, tabId,
                new PlaywrightPageWrapper(page, browserConfig.getHumanDelayMinMs(), browserConfig.getHumanDelayMaxMs()));
    }

    /** CDP / PERSISTENT 模式 — 所有会话共享同一 BrowserContext。 */
    private SessionPages createSharedContextSessionPages(String sessionId) {
        ensureBrowser();
        // stealth 脚本在共享上下文上只注入一次
        if (browserConfig.isStealthMode() && stealthInjected.compareAndSet(false, true)) {
            browserRuntime.injectStealthScripts(sharedBrowserContext, browserConfig.getLocale());
        }
        var page = browserRuntime.createPage(sharedBrowserContext);
        String tabId = UUID.randomUUID().toString().substring(0, 8);
        log.debug("创建浏览器会话上下文: sessionId={}, tabId={}, mode={}, sharedContext=true",
                sessionId, tabId, acquisitionMode);
        return new SessionPages(sharedBrowserContext, true, tabId,
                new PlaywrightPageWrapper(page, browserConfig.getHumanDelayMinMs(), browserConfig.getHumanDelayMaxMs()));
    }

    private void closeSessionPages(String sessionId, SessionPages sessionPages) {
        // storageState 持久化仅在 LAUNCH 模式下有意义（CDP/PERSISTENT 自带持久化）
        if (!sessionPages.sharedContext && browserConfig.isPersistStorageState()) {
            Path storageStatePath = resolveStorageStatePath(sessionId);
            if (storageStatePath != null) {
                try {
                    Files.createDirectories(storageStatePath.getParent());
                    browserRuntime.saveStorageState(sessionPages.browserContext, storageStatePath);
                    log.debug("已保存 storageState: sessionId={}, path={}", sessionId, storageStatePath);
                } catch (Exception e) {
                    log.warn("保存 storageState 失败: sessionId={}, error={}", sessionId, e.getMessage());
                }
            }
        }
        sessionPages.pages.values().forEach(wrapper -> {
            try {
                wrapper.close();
            } catch (Exception e) {
                log.warn("关闭浏览器 Page 失败: sessionId={}, error={}", sessionId, e.getMessage());
            }
        });
        // 共享上下文不随会话关闭，由 close() 统一清理
        if (!sessionPages.sharedContext) {
            closeBrowserContext(sessionPages.browserContext);
        }
        log.debug("关闭浏览器会话所有 Page: sessionId={}, sharedContext={}", sessionId, sessionPages.sharedContext);
    }

    private void closeBrowserContext(Object browserContext) {
        try {
            browserRuntime.closeContext(browserContext);
        } catch (Exception e) {
            log.warn("关闭 BrowserContext 失败: error={}", e.getMessage());
        }
    }

    /**
     * 解析 storageState 持久化路径（带路径穿越防护）。
     *
     * @param sessionId 会话 ID
     * @return 持久化路径，storageStateDir 为空时返回 null
     */
    @Nullable
    private Path resolveStorageStatePath(String sessionId) {
        String dir = browserConfig.getStorageStateDir();
        if (dir == null || dir.isBlank()) return null;
        String safeId = sessionId.replaceAll("[/\\\\:*?\"<>|]", "_");
        return Path.of(dir, safeId + ".json");
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

        Object launchBrowser(Object playwrightObj, boolean headless, List<String> extraArgs);

        /** 通过 CDP 连接到已运行的 Chrome 实例。 */
        Object connectOverCDP(Object playwrightObj, String cdpUrl);

        /** 启动带持久用户配置文件的 BrowserContext（无独立 Browser 对象）。 */
        Object launchPersistentContext(Object playwrightObj, Path userDataDir, boolean headless,
                                       List<String> extraArgs, String userAgent,
                                       int viewportWidth, int viewportHeight,
                                       String locale, String timezoneId);

        /** 获取 Browser 的所有 BrowserContext 列表。 */
        List<Object> getContexts(Object browserObj);

        Object createContext(Object browserObj, String userAgent, int viewportWidth, int viewportHeight,
                             String locale, String timezoneId, @Nullable Path storageStatePath);

        /** 向 BrowserContext 注入反检测指纹脚本。 */
        void injectStealthScripts(Object browserContextObj, String locale);

        /** 保存 BrowserContext 的 storageState。 */
        void saveStorageState(Object browserContextObj, Path path);

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
        public Object launchBrowser(Object playwrightObj, boolean headless, List<String> extraArgs) {
            return PlaywrightBridge.launchBrowser(playwrightObj, headless, extraArgs);
        }

        @Override
        public Object connectOverCDP(Object playwrightObj, String cdpUrl) {
            return PlaywrightBridge.connectOverCDP(playwrightObj, cdpUrl);
        }

        @Override
        public Object launchPersistentContext(Object playwrightObj, Path userDataDir, boolean headless,
                                              List<String> extraArgs, String userAgent,
                                              int viewportWidth, int viewportHeight,
                                              String locale, String timezoneId) {
            return PlaywrightBridge.launchPersistentContext(playwrightObj, userDataDir, headless, extraArgs,
                    userAgent, viewportWidth, viewportHeight, locale, timezoneId);
        }

        @Override
        public List<Object> getContexts(Object browserObj) {
            return PlaywrightBridge.getContexts(browserObj);
        }

        @Override
        public Object createContext(Object browserObj, String userAgent, int viewportWidth, int viewportHeight,
                                    String locale, String timezoneId, @Nullable Path storageStatePath) {
            return PlaywrightBridge.createContext(browserObj, userAgent, viewportWidth, viewportHeight,
                    locale, timezoneId, storageStatePath);
        }

        @Override
        public void injectStealthScripts(Object browserContextObj, String locale) {
            PlaywrightBridge.injectStealthScripts(browserContextObj, locale);
        }

        @Override
        public void saveStorageState(Object browserContextObj, Path path) {
            PlaywrightBridge.saveStorageState(browserContextObj, path);
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

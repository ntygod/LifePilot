package com.lifepilot.meta.infra.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Playwright 桥接工具类 — 隔离 Playwright API 调用。
 *
 * <p>此类直接引用 Playwright 类型，仅在 Playwright 可用时被加载。
 * {@link BrowserSessionManager} 通过反射检测确保此类不会在 Playwright 缺失时被加载。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
final class PlaywrightBridge {

    private PlaywrightBridge() {
        // 工具类禁止实例化
    }

    /**
     * 创建 Playwright 实例。
     *
     * @return Playwright 实例
     */
    static Object createPlaywright() {
        return Playwright.create();
    }

    /** puppeteer-stealth 标准启动参数，抑制自动化特征。 */
    private static final List<String> STEALTH_ARGS = List.of(
            "--disable-blink-features=AutomationControlled",
            "--disable-background-networking",
            "--disable-component-update",
            "--disable-default-apps",
            "--disable-extensions",
            "--disable-hang-monitor",
            "--disable-popup-blocking",
            "--disable-prompt-on-repost",
            "--disable-sync",
            "--metrics-recording-only",
            "--no-service-autorun",
            "--password-store=basic"
    );

    /**
     * 启动 Chromium 浏览器，包含反检测启动参数和额外自定义参数。
     *
     * @param playwrightObj Playwright 实例
     * @param headless      是否无头模式
     * @param extraArgs     额外 Chromium 启动参数
     * @return Browser 实例
     */
    static Object launchBrowser(Object playwrightObj, boolean headless, List<String> extraArgs) {
        Playwright playwright = (Playwright) playwrightObj;
        var args = new ArrayList<>(STEALTH_ARGS);
        if (extraArgs != null) args.addAll(extraArgs);
        return playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless).setArgs(args));
    }

    /**
     * 创建带完整配置的 BrowserContext。
     *
     * @param browserObj       Browser 实例
     * @param userAgent        自定义 User-Agent，为 null 或空白时不设置
     * @param viewportWidth    视口宽度
     * @param viewportHeight   视口高度
     * @param locale           浏览器语言区域
     * @param timezoneId       时区 ID
     * @param storageStatePath storageState 持久化路径，为 null 或不存在时忽略
     * @return BrowserContext 实例
     */
    static Object createContext(Object browserObj, @Nullable String userAgent,
                                int viewportWidth, int viewportHeight,
                                String locale, String timezoneId,
                                @Nullable Path storageStatePath) {
        Browser browser = (Browser) browserObj;
        var options = new Browser.NewContextOptions()
                .setViewportSize(viewportWidth, viewportHeight)
                .setLocale(locale)
                .setTimezoneId(timezoneId);
        if (userAgent != null && !userAgent.isBlank()) {
            options.setUserAgent(userAgent);
        }
        if (storageStatePath != null && Files.exists(storageStatePath)) {
            options.setStorageStatePath(storageStatePath);
        }
        return browser.newContext(options);
    }

    /**
     * 向 BrowserContext 注入反检测指纹脚本。
     *
     * <p>覆盖常见的浏览器自动化检测点：
     * <ul>
     *   <li>navigator.webdriver — 隐藏自动化标志</li>
     *   <li>window.chrome — 伪造 Chrome 运行时对象</li>
     *   <li>navigator.languages — 从 locale 动态构建语言偏好</li>
     *   <li>navigator.permissions — 修正 notifications 查询行为</li>
     *   <li>navigator.connection — 伪造网络连接信息</li>
     *   <li>navigator.hardwareConcurrency / deviceMemory — 伪造硬件指纹</li>
     *   <li>window.outerWidth/outerHeight — headless 下窗口尺寸修正</li>
     *   <li>WebGL renderer — 隐藏 SwiftShader 特征</li>
     *   <li>iframe contentWindow — 同步覆盖子框架的 webdriver 属性</li>
     * </ul>
     *
     * @param browserContextObj BrowserContext 实例
     * @param locale            浏览器语言区域
     */
    static void injectStealthScripts(Object browserContextObj, String locale) {
        BrowserContext ctx = (BrowserContext) browserContextObj;

        String lang = locale != null && locale.contains("-") ? locale.split("-")[0] : "zh";
        String safeLocale = locale != null ? locale : "zh-CN";

        // 基础反检测：webdriver + chrome + languages（从 locale 动态构建）
        ctx.addInitScript("""
                Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                window.chrome = {runtime: {}, loadTimes: () => ({}), csi: () => ({})};
                Object.defineProperty(navigator, 'languages', {get: () => ['%s', '%s', 'en-US', 'en']});
                """.formatted(safeLocale, lang));

        // 高级指纹（注意：不 mock navigator.plugins — Chromium 已有真实值，mock 反而暴露自动化）
        ctx.addInitScript("""
                const originalQuery = window.navigator.permissions.query.bind(window.navigator.permissions);
                window.navigator.permissions.query = (parameters) =>
                    parameters.name === 'notifications'
                        ? Promise.resolve({state: Notification.permission})
                        : originalQuery(parameters);

                Object.defineProperty(navigator, 'connection', {
                    get: () => ({rtt: 50, downlink: 10, effectiveType: '4g', saveData: false})
                });
                Object.defineProperty(navigator, 'hardwareConcurrency', {get: () => 8});
                Object.defineProperty(navigator, 'deviceMemory', {get: () => 8});

                if (window.outerWidth === 0) {
                    Object.defineProperty(window, 'outerWidth', {get: () => window.innerWidth});
                    Object.defineProperty(window, 'outerHeight', {get: () => window.innerHeight + 85});
                }

                const getParameter = WebGLRenderingContext.prototype.getParameter;
                WebGLRenderingContext.prototype.getParameter = function(parameter) {
                    if (parameter === 37445) return 'Google Inc. (Intel)';
                    if (parameter === 37446) return 'ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)';
                    return getParameter.call(this, parameter);
                };

                const observer = new MutationObserver((mutations) => {
                    for (const mutation of mutations) {
                        for (const node of mutation.addedNodes) {
                            if (node.tagName === 'IFRAME' && node.contentWindow) {
                                try {
                                    Object.defineProperty(node.contentWindow.navigator, 'webdriver', {get: () => undefined});
                                } catch(e) {}
                            }
                        }
                    }
                });
                observer.observe(document.documentElement, {childList: true, subtree: true});
                """);
    }

    /**
     * 通过 Chrome DevTools Protocol 连接到已运行的 Chrome 实例。
     *
     * <p>用户需预先以 {@code --remote-debugging-port=9222} 启动 Chrome，
     * Agent 通过 CDP 接管该浏览器，复用其登录态和 Cookie。
     * 调用 {@code browser.close()} 仅断开连接，不会杀死 Chrome 进程。</p>
     *
     * @param playwrightObj Playwright 实例
     * @param cdpUrl        CDP 端点 URL（如 {@code http://localhost:9222}）
     * @return Browser 实例（CDP 连接）
     */
    static Object connectOverCDP(Object playwrightObj, String cdpUrl) {
        Playwright playwright = (Playwright) playwrightObj;
        return playwright.chromium().connectOverCDP(cdpUrl);
    }

    /**
     * 启动带持久用户配置文件的 BrowserContext。
     *
     * <p>与 {@link #launchBrowser} + {@link #createContext} 不同，此方法返回的
     * BrowserContext 绑定到磁盘上的 Chrome profile 目录，会持久化 Cookie、
     * IndexedDB、Service Worker、缓存等完整浏览器状态。</p>
     *
     * @param playwrightObj  Playwright 实例
     * @param userDataDir    用户数据目录路径
     * @param headless       是否无头模式
     * @param extraArgs      额外 Chromium 启动参数
     * @param userAgent      自定义 User-Agent，为 null 或空白时不设置
     * @param viewportWidth  视口宽度
     * @param viewportHeight 视口高度
     * @param locale         浏览器语言区域
     * @param timezoneId     时区 ID
     * @return BrowserContext 实例（持久上下文）
     */
    static Object launchPersistentContext(Object playwrightObj, Path userDataDir,
                                          boolean headless, List<String> extraArgs,
                                          @Nullable String userAgent,
                                          int viewportWidth, int viewportHeight,
                                          String locale, String timezoneId) {
        Playwright playwright = (Playwright) playwrightObj;
        var args = new ArrayList<>(STEALTH_ARGS);
        if (extraArgs != null) args.addAll(extraArgs);
        var options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(headless)
                .setArgs(args)
                .setViewportSize(viewportWidth, viewportHeight)
                .setLocale(locale)
                .setTimezoneId(timezoneId);
        if (userAgent != null && !userAgent.isBlank()) {
            options.setUserAgent(userAgent);
        }
        return playwright.chromium().launchPersistentContext(userDataDir, options);
    }

    /**
     * 获取 Browser 的所有 BrowserContext 列表。
     *
     * <p>CDP 模式下用于获取默认上下文（index 0），该上下文包含用户的登录态。</p>
     *
     * @param browserObj Browser 实例
     * @return BrowserContext 列表（Object 类型以保持抽象层一致）
     */
    static List<Object> getContexts(Object browserObj) {
        Browser browser = (Browser) browserObj;
        return new ArrayList<>(browser.contexts());
    }

    /**
     * 获取 BrowserContext 当前打开的 page 数量。
     *
     * <p>用于 CDP 模式下选择最活跃的 context：page 数多的更可能是用户正在使用的窗口。</p>
     *
     * @param browserContextObj BrowserContext 实例
     * @return 当前 page 数量
     */
    static int getPageCount(Object browserContextObj) {
        return ((BrowserContext) browserContextObj).pages().size();
    }

    /**
     * 保存 BrowserContext 的 storageState 到指定路径。
     *
     * @param browserContextObj BrowserContext 实例
     * @param path              保存路径
     */
    static void saveStorageState(Object browserContextObj, Path path) {
        BrowserContext ctx = (BrowserContext) browserContextObj;
        ctx.storageState(new BrowserContext.StorageStateOptions().setPath(path));
    }

    /**
     * 在指定 BrowserContext 中创建新 Page。
     *
     * @param browserContextObj BrowserContext 实例
     * @return Page 实例
     */
    static Object createPage(Object browserContextObj) {
        BrowserContext browserContext = (BrowserContext) browserContextObj;
        return browserContext.newPage();
    }

    /**
     * 读取 Browser 的 Chromium 版本号（如 {@code 135.0.7000.0}）。
     *
     * <p>用于动态拼接 User-Agent，避免硬编码版本号漂移导致的指纹识别。
     * PERSISTENT 模式下 Playwright 不暴露独立 Browser 对象，调用方需处理此场景。</p>
     *
     * @param browserObj Browser 实例
     * @return Chromium 版本号字符串
     */
    static String getBrowserVersion(Object browserObj) {
        return ((Browser) browserObj).version();
    }

    /**
     * 关闭 BrowserContext。
     *
     * @param browserContextObj BrowserContext 实例
     */
    static void closeContext(Object browserContextObj) {
        ((BrowserContext) browserContextObj).close();
    }

    /**
     * 关闭 Browser。
     *
     * @param browserObj Browser 实例
     */
    static void closeBrowser(Object browserObj) {
        ((Browser) browserObj).close();
    }

    /**
     * 关闭 Playwright。
     *
     * @param playwrightObj Playwright 实例
     */
    static void closePlaywright(Object playwrightObj) {
        ((Playwright) playwrightObj).close();
    }
}

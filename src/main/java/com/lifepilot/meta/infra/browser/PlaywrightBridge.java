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

    /**
     * 启动 Chromium 浏览器（兼容入口）。
     *
     * @param playwrightObj Playwright 实例
     * @param headless      是否无头模式
     * @return Browser 实例
     */
    static Object launchBrowser(Object playwrightObj, boolean headless) {
        return launchBrowser(playwrightObj, headless, null);
    }

    /**
     * 启动 Chromium 浏览器，支持反检测参数和自定义启动参数。
     *
     * @param playwrightObj Playwright 实例
     * @param headless      是否无头模式
     * @param extraArgs     额外 Chromium 启动参数，可为 null
     * @return Browser 实例
     */
    static Object launchBrowser(Object playwrightObj, boolean headless, @Nullable List<String> extraArgs) {
        Playwright playwright = (Playwright) playwrightObj;
        var args = new ArrayList<>(List.of(
                "--disable-blink-features=AutomationControlled",
                "--disable-infobars",
                "--no-first-run",
                "--no-default-browser-check"
        ));
        if (extraArgs != null) args.addAll(extraArgs);
        return playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless).setArgs(args)
        );
    }

    /**
     * 创建新 BrowserContext（兼容入口）。
     *
     * @param browserObj Browser 实例
     * @return BrowserContext 实例
     */
    static Object createContext(Object browserObj) {
        return createContext(browserObj, null, 1920, 1080, "zh-CN", "Asia/Shanghai", null);
    }

    /**
     * 创建新 BrowserContext，支持 UA、视口、语言、时区、storageState 配置。
     *
     * @param browserObj       Browser 实例
     * @param userAgent        自定义 User-Agent，为 null 时使用浏览器默认值
     * @param viewportWidth    视口宽度（像素）
     * @param viewportHeight   视口高度（像素）
     * @param locale           浏览器语言区域
     * @param timezoneId       时区 ID
     * @param storageStatePath storageState 文件路径，为 null 或不存在时忽略
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
     * 注入反指纹脚本 — 隐藏 Playwright 自动化特征。
     *
     * <p>当 stealthMode=true 时由 {@link BrowserSessionManager} 在创建上下文后调用。</p>
     *
     * @param browserContextObj BrowserContext 实例
     */
    static void injectStealthScripts(Object browserContextObj) {
        BrowserContext ctx = (BrowserContext) browserContextObj;
        ctx.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});");
        ctx.addInitScript("""
                window.chrome = { runtime: {}, loadTimes: function(){}, csi: function(){} };
                Object.defineProperty(navigator, 'plugins', {
                    get: () => [1, 2, 3, 4, 5]
                });
                Object.defineProperty(navigator, 'languages', {
                    get: () => ['zh-CN', 'zh', 'en-US', 'en']
                });
                """);
    }

    /**
     * 导出 cookie/localStorage 等存储状态到指定路径。
     *
     * @param browserContextObj BrowserContext 实例
     * @param path              导出文件路径
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

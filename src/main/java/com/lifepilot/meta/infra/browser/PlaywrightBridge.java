package com.lifepilot.meta.infra.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

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
     * 启动 Chromium 浏览器。
     *
     * @param playwrightObj Playwright 实例
     * @param headless 是否无头模式
     * @return Browser 实例
     */
    static Object launchBrowser(Object playwrightObj, boolean headless) {
        Playwright playwright = (Playwright) playwrightObj;
        return playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless)
        );
    }

    /**
     * 创建新 Page。
     *
     * @param browserObj Browser 实例
     * @return Page 实例
     */
    static Object createPage(Object browserObj) {
        Browser browser = (Browser) browserObj;
        return browser.newPage();
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

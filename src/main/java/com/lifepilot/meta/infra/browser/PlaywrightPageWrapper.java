package com.lifepilot.meta.infra.browser;

import com.microsoft.playwright.Page;
import jakarta.annotation.Nullable;

import java.util.Base64;

/**
 * Playwright Page 包装器 — 封装 Page 操作并跟踪最后访问时间。
 *
 * <p>此类直接引用 Playwright 类型，仅在 Playwright 可用时被加载。
 * {@link BrowserSessionManager} 通过反射检测确保此类不会在 Playwright 缺失时被加载。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class PlaywrightPageWrapper {

    private final Page page;
    private volatile long lastAccessTime;
    private volatile boolean closed;

    PlaywrightPageWrapper(Object pageObj) {
        this.page = (Page) pageObj;
        this.lastAccessTime = System.currentTimeMillis();
        this.closed = false;
    }

    /** 更新最后访问时间。 */
    void touch() {
        this.lastAccessTime = System.currentTimeMillis();
    }

    /** 获取最后访问时间。 */
    long getLastAccessTime() {
        return lastAccessTime;
    }

    /** Page 是否已关闭。 */
    boolean isClosed() {
        return closed;
    }

    /**
     * 导航到指定 URL。
     *
     * @param url 目标 URL
     * @return 页面标题
     */
    public String navigate(String url) {
        touch();
        page.navigate(url);
        return page.title();
    }

    /**
     * 获取页面文本快照。
     *
     * @return 页面可见文本内容
     */
    public String textContent() {
        touch();
        return page.textContent("body");
    }

    /**
     * 获取页面标题。
     *
     * @return 页面标题
     */
    public String title() {
        touch();
        return page.title();
    }

    /**
     * 点击指定选择器的元素。
     *
     * @param selector CSS 选择器
     */
    public void click(String selector) {
        touch();
        page.click(selector);
    }

    /**
     * 填充表单字段。
     *
     * @param selector CSS 选择器
     * @param value 填充值
     */
    public void fill(String selector, String value) {
        touch();
        page.fill(selector, value);
    }

    /**
     * 截取页面截图并返回 Base64 编码。
     *
     * @param fullPage 是否截取整页
     * @return Base64 编码的截图
     */
    public String screenshot(boolean fullPage) {
        touch();
        byte[] bytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(fullPage));
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 获取当前页面 URL。
     *
     * @return 当前 URL
     */
    public String url() {
        return page.url();
    }

    /** 关闭 Page。 */
    void close() {
        if (!closed) {
            try {
                page.close();
            } catch (Exception ignored) {
                // Page 可能已被 Browser 关闭
            }
            closed = true;
        }
    }
}

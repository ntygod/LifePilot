package com.lifepilot.meta.infra.browser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import jakarta.annotation.Nullable;

import java.util.Base64;
import java.util.List;
import java.util.Map;

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

    /**
     * 滚动页面。
     *
     * @param direction 滚动方向："up" 向上，"down" 向下
     * @param pixels    滚动像素数（正数）
     * @return 滚动后的位置
     */
    @SuppressWarnings("unchecked")
    public ScrollResult scroll(String direction, int pixels) {
        touch();
        ensureOpen();
        int scrollPixels = "up".equalsIgnoreCase(direction) ? -pixels : pixels;
        List<Number> pos = (List<Number>) page.evaluate(
                "([px]) => { window.scrollBy(0, px); return [window.scrollX, window.scrollY]; }",
                List.of(scrollPixels)
        );
        return new ScrollResult(pos.get(0).doubleValue(), pos.get(1).doubleValue());
    }

    /**
     * 滚动到指定元素可见。
     *
     * @param selector CSS 选择器
     * @return 滚动后的位置
     */
    @SuppressWarnings("unchecked")
    public ScrollResult scrollToElement(String selector) {
        touch();
        ensureOpen();
        page.locator(selector).scrollIntoViewIfNeeded();
        List<Number> pos = (List<Number>) page.evaluate("[window.scrollX, window.scrollY]");
        return new ScrollResult(pos.get(0).doubleValue(), pos.get(1).doubleValue());
    }

    /**
     * 悬停到指定元素。
     *
     * @param selector CSS 选择器
     * @return 包含 tagName 和 textContent 的 Map
     */
    public Map<String, String> hover(String selector) {
        touch();
        ensureOpen();
        page.hover(selector);
        String tagName = (String) page.locator(selector).evaluate("el => el.tagName");
        String textContent = page.locator(selector).textContent();
        return Map.of("tagName", tagName, "textContent", textContent != null ? textContent : "");
    }

    /**
     * 下拉选择。
     *
     * @param selector CSS 选择器
     * @param value    选项值或可见文本
     * @param byLabel  true 时按可见文本匹配，false 时按 value 匹配
     * @return 包含 selectedValue 和 selectedLabel 的 Map
     */
    public Map<String, String> selectOption(String selector, String value, boolean byLabel) {
        touch();
        ensureOpen();
        List<String> selected;
        if (byLabel) {
            selected = page.selectOption(selector, new SelectOption().setLabel(value));
        } else {
            selected = page.selectOption(selector, value);
        }
        String selectedValue = selected.getFirst();
        String selectedLabel = page.locator(selector + " option:checked").textContent();
        return Map.of("selectedValue", selectedValue, "selectedLabel", selectedLabel != null ? selectedLabel : "");
    }

    /**
     * 按键操作，支持单键和组合键。
     *
     * @param key 键名，如 "Enter"、"Tab"、"Control+A"
     */
    public void pressKey(String key) {
        touch();
        ensureOpen();
        page.keyboard().press(key);
    }

    /**
     * 逐字符输入文本。
     *
     * @param text 要输入的文本
     */
    public void typeText(String text) {
        touch();
        ensureOpen();
        page.keyboard().type(text);
    }

    /**
     * 执行 JavaScript 表达式，返回 JSON 序列化结果。
     *
     * @param expression JavaScript 表达式
     * @return JSON 序列化后的结果字符串，null 结果返回 "null"
     */
    public String evaluate(String expression) {
        touch();
        ensureOpen();
        Object result = page.evaluate(expression);
        if (result == null) {
            return "null";
        }
        try {
            return new ObjectMapper().writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return result.toString();
        }
    }

    /**
     * 获取无障碍树快照（ARIA snapshot）。
     *
     * <p>使用 Playwright 1.49+ 的 {@code locator.ariaSnapshot()} API，
     * 返回 YAML 格式的无障碍树表示。</p>
     *
     * @param rootSelector 子树根节点 CSS 选择器，为 null 时返回整页快照
     * @param maxDepth     最大深度（保留参数，供未来扩展）
     * @return YAML 格式的无障碍树快照字符串
     */
    public String accessibilitySnapshot(@Nullable String rootSelector, int maxDepth) {
        touch();
        ensureOpen();
        try {
            var locator = rootSelector != null
                    ? page.locator(rootSelector)
                    : page.locator("body");
            return locator.ariaSnapshot();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 等待指定选择器的元素满足条件。
     *
     * @param selector  CSS 选择器
     * @param state     等待状态："visible"、"hidden"、"attached"
     * @param timeoutMs 超时时间（毫秒）
     */
    public void waitForSelector(String selector, String state, int timeoutMs) {
        touch();
        ensureOpen();
        WaitForSelectorState wsState = switch (state.toLowerCase()) {
            case "hidden" -> WaitForSelectorState.HIDDEN;
            case "attached" -> WaitForSelectorState.ATTACHED;
            default -> WaitForSelectorState.VISIBLE;
        };
        page.waitForSelector(selector, new Page.WaitForSelectorOptions()
                .setState(wsState)
                .setTimeout(timeoutMs));
    }

    /** 检查 Page 是否已关闭，已关闭时抛出异常。 */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Page 已关闭，请重新创建会话");
        }
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

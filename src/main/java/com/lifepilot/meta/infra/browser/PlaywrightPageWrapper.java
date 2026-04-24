package com.lifepilot.meta.infra.browser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.Nullable;

import java.util.Base64;
import java.util.LinkedHashMap;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final Page page;
    private volatile long lastAccessTime;
    private volatile boolean closed;
    private final int humanDelayMinMs;
    private final int humanDelayMaxMs;

    PlaywrightPageWrapper(Object pageObj, int humanDelayMinMs, int humanDelayMaxMs) {
        this.page = (Page) pageObj;
        this.lastAccessTime = System.currentTimeMillis();
        this.closed = false;
        this.humanDelayMinMs = Math.min(humanDelayMinMs, humanDelayMaxMs);
        this.humanDelayMaxMs = Math.max(humanDelayMinMs, humanDelayMaxMs);
    }

    PlaywrightPageWrapper(Object pageObj) {
        this(pageObj, 0, 0);
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
     * 模拟人工操作延迟 — 在关键操作前引入随机等待。
     * 在 virtual thread 环境下 Thread.sleep 会自动 unmount，不阻塞平台线程。
     */
    private void humanDelay() {
        if (humanDelayMinMs <= 0) return;
        try {
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(humanDelayMinMs, humanDelayMaxMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 默认导航超时（毫秒）。 */
    private static final int DEFAULT_NAVIGATE_TIMEOUT_MS = 30_000;

    /**
     * 导航到指定 URL（使用默认超时）。
     *
     * @param url 目标 URL
     * @return 页面标题
     */
    public String navigate(String url) {
        return navigate(url, DEFAULT_NAVIGATE_TIMEOUT_MS);
    }

    /**
     * 导航到指定 URL。
     *
     * <p>使用 {@code domcontentloaded} 等待策略，避免等待所有资源（图片、广告脚本等）
     * 加载完成导致超时，尤其对电商等 JS 重度页面更可靠。</p>
     *
     * @param url       目标 URL
     * @param timeoutMs 超时时间（毫秒）
     * @return 页面标题
     */
    public String navigate(String url, int timeoutMs) {
        touch();
        humanDelay();
        page.navigate(url, new Page.NavigateOptions()
                .setTimeout(timeoutMs)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        return page.title();
    }

    /** 导航结果 — 包含标题、URL 和是否部分加载。 */
    public record NavigateResult(String title, String url, boolean partial) {}

    /**
     * 导航到指定 URL，返回包含部分加载信息的结构化结果。
     *
     * <p>超时时捕获 {@link com.microsoft.playwright.TimeoutError}，标记为部分加载而非失败。</p>
     *
     * @param url       目标 URL
     * @param timeoutMs 超时时间（毫秒）
     * @return 导航结果
     */
    public NavigateResult navigateWithResult(String url, int timeoutMs) {
        touch();
        humanDelay();
        boolean partial = false;
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(timeoutMs)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (com.microsoft.playwright.TimeoutError e) {
            partial = true;
        }
        return new NavigateResult(page.title(), page.url(), partial);
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
        humanDelay();
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
        humanDelay();
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
        humanDelay();
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
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return result.toString();
        }
    }

    /**
     * 执行 JavaScript 表达式，带超时保护。
     *
     * <p>当 {@code timeoutSeconds > 0} 时，通过 {@link TimeoutExecutor}
     * 在 virtual thread 上运行，超时抛异常而非无限阻塞。
     * {@code timeoutSeconds <= 0} 时退化为无超时的 {@link #evaluate(String)}。</p>
     *
     * @param expression     JavaScript 表达式
     * @param timeoutSeconds 超时秒数
     * @return JSON 序列化后的结果字符串
     * @throws RuntimeException 超时或执行失败
     */
    public String evaluate(String expression, long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return evaluate(expression);
        }
        try {
            return TimeoutExecutor.callWithTimeout(
                    () -> evaluate(expression),
                    timeoutSeconds,
                    java.util.concurrent.TimeUnit.SECONDS
            );
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("JS 执行超过 " + timeoutSeconds + " 秒超时", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取无障碍树快照（ARIA snapshot）。
     *
     * <p>使用 Playwright 1.49+ 的 {@code locator.ariaSnapshot()} API 拿到 YAML，
     * 再通过 {@link AccessibilityYamlTrimmer} 按 {@code maxDepth} 裁剪，
     * 防止复杂页面产出超大上下文污染 LLM。</p>
     *
     * @param rootSelector 子树根节点 CSS 选择器，为 null 时返回整页快照
     * @param maxDepth     最大深度（从 1 起算），{@code <= 0} 表示不裁剪
     * @return YAML 格式的无障碍树快照字符串
     */
    public String accessibilitySnapshot(@Nullable String rootSelector, int maxDepth) {
        touch();
        ensureOpen();
        try {
            var locator = rootSelector != null
                    ? page.locator(rootSelector)
                    : page.locator("body");
            String full = locator.ariaSnapshot();
            return AccessibilityYamlTrimmer.trim(full, maxDepth);
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

    /**
     * 获取当前页面所有 Cookie。
     *
     * @return Cookie 列表，每个 Cookie 为包含 name、value、domain、path、expires、httpOnly、secure、sameSite 的 Map
     */
    public List<Map<String, Object>> getCookies() {
        touch();
        ensureOpen();
        List<Cookie> cookies = page.context().cookies();
        return cookies.stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", c.name);
            map.put("value", c.value);
            map.put("domain", c.domain);
            map.put("path", c.path);
            map.put("expires", c.expires);
            map.put("httpOnly", c.httpOnly);
            map.put("secure", c.secure);
            map.put("sameSite", c.sameSite != null ? c.sameSite.name() : "");
            return map;
        }).toList();
    }

    /**
     * 设置 Cookie。
     *
     * @param name   Cookie 名称
     * @param value  Cookie 值
     * @param domain Cookie 域名，为 null 时不设置
     * @param path   Cookie 路径，为 null 时不设置
     */
    public void setCookie(String name, String value, @Nullable String domain, @Nullable String path) {
        touch();
        ensureOpen();
        var cookie = new Cookie(name, value).setUrl(page.url());
        if (domain != null) {
            cookie.setDomain(domain);
        }
        if (path != null) {
            cookie.setPath(path);
        }
        page.context().addCookies(List.of(cookie));
    }

    /**
     * 清除当前上下文的所有 Cookie。
     */
    public void clearCookies() {
        touch();
        ensureOpen();
        page.context().clearCookies();
    }

    /**
     * 获取 localStorage 中指定 key 的值。
     *
     * @param key localStorage 键名
     * @return 对应的值，不存在时返回 null
     */
    public @Nullable String getLocalStorage(String key) {
        touch();
        ensureOpen();
        return (String) page.evaluate("key => localStorage.getItem(key)", key);
    }

    /**
     * 设置 localStorage 中指定 key 的值。
     *
     * @param key   localStorage 键名
     * @param value 要设置的值
     */
    public void setLocalStorage(String key, String value) {
        touch();
        ensureOpen();
        page.evaluate("([k, v]) => localStorage.setItem(k, v)", List.of(key, value));
    }

    /**
     * 清除 localStorage 中的所有数据。
     */
    public void clearLocalStorage() {
        touch();
        ensureOpen();
        page.evaluate("() => localStorage.clear()");
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

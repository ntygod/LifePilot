package com.lifepilot.meta.infra.web;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.browser.BrowserSessionManager;
import com.lifepilot.meta.infra.browser.PlaywrightPageWrapper;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Web 抓取工具执行器 — 使用 Jsoup 解析 HTML 并提取正文内容。
 *
 * <p>支持通过 CSS 选择器提取特定区域内容，超时和内容长度均可配置。
 * 当静态抓取内容过短或显式指定 {@code renderJs=true} 时，
 * 自动回退到 Playwright 浏览器渲染以支持 JS 动态页面。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class WebFetchToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebFetchToolExecutor.class);

    private final MetaProperties properties;
    @Nullable
    private final BrowserSessionManager browserSessionManager;

    /**
     * 仅 Jsoup 静态抓取构造器（向后兼容）。
     *
     * @param properties 配置属性
     */
    public WebFetchToolExecutor(MetaProperties properties) {
        this(properties, null);
    }

    /**
     * 支持浏览器渲染回退的构造器。
     *
     * @param properties            配置属性
     * @param browserSessionManager 浏览器会话管理器，为 null 时不启用浏览器回退
     */
    public WebFetchToolExecutor(MetaProperties properties,
                                @Nullable BrowserSessionManager browserSessionManager) {
        this.properties = properties;
        this.browserSessionManager = browserSessionManager;
    }

    /**
     * 执行 Web 页面抓取。
     *
     * <p>首先使用 Jsoup 进行静态抓取；若内容过短且浏览器可用，则回退到 Playwright 渲染。
     * 当 {@code renderJs=true} 时强制走浏览器渲染路径。</p>
     *
     * @param input 工具输入，必需参数 url，可选参数 selector（CSS 选择器）、renderJs（强制浏览器渲染）
     * @return 包含页面标题和正文内容的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String url = input.getParam("url", String.class);
            var selector = input.getOptionalParam("selector", String.class);
            var renderJs = input.getOptionalParam("renderJs", Boolean.class);

            var config = properties.getInfra().getWebFetch();
            boolean forceRenderJs = renderJs.isPresent() && renderJs.get();

            // 强制浏览器渲染时直接走浏览器路径
            if (forceRenderJs) {
                log.info("强制浏览器渲染: url={}", url);
                return fetchWithBrowserOrFallback(url, selector.orElse(null), config);
            }

            // 先尝试 Jsoup 静态抓取
            return fetchWithJsoup(url, selector.orElse(null), config);

        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("Web 抓取异常: {}", e.getMessage(), e);
            return ToolResult.error("Web 抓取异常: " + e.getMessage());
        }
    }

    /**
     * 使用 Jsoup 进行静态抓取。内容过短时自动尝试浏览器回退。
     */
    private ToolResult fetchWithJsoup(String url, @Nullable String selector,
                                      MetaProperties.Infra.WebFetch config) throws IOException {
        int timeoutMillis = config.getTimeoutSeconds() * 1000;
        int maxContentLength = config.getMaxContentLength();
        int minStaticContentLength = config.getMinStaticContentLength();

        Document doc;
        try {
            doc = Jsoup.connect(url)
                    .timeout(timeoutMillis)
                    .userAgent("ZhiWei/1.0 (Web Fetch Tool)")
                    .followRedirects(true)
                    .get();
        } catch (SocketTimeoutException e) {
            log.warn("Web 抓取超时: {}", e.getMessage());
            return ToolResult.error("请求超时（%d 秒），请稍后重试"
                    .formatted(config.getTimeoutSeconds()));
        }

        String title = doc.title();
        String content;
        boolean truncated = false;

        if (selector != null && !selector.isBlank()) {
            Element selected = doc.selectFirst(selector);
            if (selected == null) {
                return ToolResult.error(
                        "CSS 选择器 '%s' 未匹配到任何元素".formatted(selector));
            }
            content = selected.text();
        } else {
            content = extractMainContent(doc);
        }

        // 静态内容过短时尝试浏览器渲染回退
        if (content.length() < minStaticContentLength && isBrowserAvailable()) {
            log.info("静态抓取内容过短（{} 字符 < {} 阈值），尝试浏览器渲染回退: url={}",
                    content.length(), minStaticContentLength, url);
            return fetchWithBrowser(url, selector, config);
        }

        // 截断超长内容
        if (content.length() > maxContentLength) {
            content = content.substring(0, maxContentLength);
            truncated = true;
        }

        return ToolResult.success(Map.of(
                "title", title,
                "url", url,
                "content", content,
                "contentLength", content.length(),
                "truncated", truncated,
                "renderMode", "static"
        ));
    }

    /**
     * 尝试浏览器渲染，不可用时回退到 Jsoup 结果并附加警告。
     */
    private ToolResult fetchWithBrowserOrFallback(String url, @Nullable String selector,
                                                  MetaProperties.Infra.WebFetch config) {
        if (!isBrowserAvailable()) {
            log.warn("浏览器不可用，回退到 Jsoup 静态抓取: url={}", url);
            try {
                return fetchWithJsoup(url, selector, config);
            } catch (IOException e) {
                log.error("Jsoup 回退抓取失败: {}", e.getMessage(), e);
                return ToolResult.error("浏览器不可用且 Jsoup 回退失败: " + e.getMessage());
            }
        }
        return fetchWithBrowser(url, selector, config);
    }

    /**
     * 使用 Playwright 浏览器渲染页面并提取内容。
     *
     * <p>创建临时浏览器会话，导航到目标 URL，等待页面加载后提取文本内容。
     * 会话在 finally 块中可靠关闭。</p>
     *
     * @param url      目标 URL
     * @param selector CSS 选择器（可选）
     * @param config   Web 抓取配置
     * @return 抓取结果
     */
    private ToolResult fetchWithBrowser(String url, @Nullable String selector,
                                        MetaProperties.Infra.WebFetch config) {
        String fetchSessionId = "web-fetch-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            PlaywrightPageWrapper page = browserSessionManager.getOrCreatePage(fetchSessionId);
            int renderTimeoutMs = config.getRenderTimeoutSeconds() * 1000;
            page.navigate(url, renderTimeoutMs);

            // 等待页面 JS 渲染完成
            waitForPageReady(page, renderTimeoutMs);

            String title = page.title();
            String content;
            boolean truncated = false;

            if (selector != null && !selector.isBlank()) {
                // 使用选择器提取特定区域
                try {
                    page.waitForSelector(selector, "visible", renderTimeoutMs);
                    content = page.evaluate("document.querySelector('%s')?.textContent || ''"
                            .formatted(selector.replace("'", "\\'")));
                } catch (Exception e) {
                    log.warn("浏览器渲染中选择器等待失败: selector={}, error={}", selector, e.getMessage());
                    content = "";
                }
            } else {
                content = page.textContent();
            }

            if (content == null) {
                content = "";
            }

            // 截断超长内容
            int maxContentLength = config.getMaxContentLength();
            if (content.length() > maxContentLength) {
                content = content.substring(0, maxContentLength);
                truncated = true;
            }

            log.info("浏览器渲染抓取完成: url={}, contentLength={}", url, content.length());
            return ToolResult.success(Map.of(
                    "title", title != null ? title : "",
                    "url", url,
                    "content", content,
                    "contentLength", content.length(),
                    "truncated", truncated,
                    "renderMode", "browser"
            ));

        } catch (Exception e) {
            log.error("浏览器渲染抓取失败: url={}, error={}", url, e.getMessage(), e);
            return ToolResult.error("浏览器渲染失败: " + e.getMessage());
        } finally {
            try {
                browserSessionManager.closePage(fetchSessionId);
            } catch (Exception e) {
                log.warn("关闭浏览器抓取会话失败: sessionId={}, error={}", fetchSessionId, e.getMessage());
            }
        }
    }

    /**
     * 等待页面 JS 渲染就绪。
     *
     * <p>通过轮询 {@code document.readyState} 判断页面是否完成加载，
     * 加载完成后额外等待 500ms 让异步渲染内容就位。</p>
     *
     * @param page      Playwright Page 包装器
     * @param timeoutMs 最大等待毫秒数
     */
    private void waitForPageReady(PlaywrightPageWrapper page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                String readyState = page.evaluate("document.readyState");
                if ("\"complete\"".equals(readyState)) {
                    // 额外等待 500ms 让异步渲染的内容就位
                    Thread.sleep(500);
                    return;
                }
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.debug("等待页面就绪时发生异常: {}", e.getMessage());
                return;
            }
        }
        log.debug("等待页面就绪超时（{}ms）", timeoutMs);
    }

    /**
     * 检查浏览器渲染是否可用。
     *
     * @return true 表示 BrowserSessionManager 已注入且 Playwright 可用
     */
    private boolean isBrowserAvailable() {
        return browserSessionManager != null && browserSessionManager.isAvailable();
    }

    /**
     * 提取页面主体正文。
     *
     * <p>优先尝试 {@code <article>}、{@code <main>}、{@code [role=main]} 等语义标签，
     * 回退到 {@code <body>} 全文。</p>
     */
    private String extractMainContent(Document doc) {
        // 移除脚本、样式、导航等非正文元素
        doc.select("script, style, nav, header, footer, aside, .sidebar, .menu, .nav").remove();

        // 优先尝试语义标签
        String[] mainSelectors = {"article", "main", "[role=main]", ".content", "#content", ".post-content"};
        for (String sel : mainSelectors) {
            Element main = doc.selectFirst(sel);
            if (main != null && !main.text().isBlank()) {
                return main.text();
            }
        }

        // 回退到 body 全文
        Element body = doc.body();
        return body.text();
    }
}

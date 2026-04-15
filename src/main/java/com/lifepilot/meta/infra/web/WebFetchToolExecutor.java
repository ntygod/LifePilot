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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Web 抓取工具执行器 — 支持 HTML/JSON/XML/纯文本多种响应类型。
 *
 * <p>执行流程：
 * <ol>
 *   <li>HEAD 请求探测 Content-Type</li>
 *   <li>非 HTML 类型（JSON/XML/纯文本）→ HttpClient 直连获取原始文本</li>
 *   <li>HTML 类型 → Jsoup 静态解析；内容过短时回退 Playwright 浏览器渲染</li>
 *   <li>强制 {@code renderJs=true} → 直接走浏览器路径</li>
 * </ol>
 * </p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class WebFetchToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebFetchToolExecutor.class);

    /** 可直接作为原始文本返回的 MIME 类型前缀/子类型。 */
    private static final Set<String> DIRECT_FETCH_TYPES = Set.of(
            "application/json", "application/xml", "application/rss+xml",
            "application/atom+xml", "application/feed+json",
            "text/plain", "text/csv", "text/xml"
    );

    private final MetaProperties properties;
    @Nullable
    private final BrowserSessionManager browserSessionManager;
    private final HttpClient httpClient;

    public WebFetchToolExecutor(MetaProperties properties) {
        this(properties, null);
    }

    public WebFetchToolExecutor(MetaProperties properties,
                                @Nullable BrowserSessionManager browserSessionManager) {
        this.properties = properties;
        this.browserSessionManager = browserSessionManager;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 执行 Web 页面抓取。
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

            // 先探测 Content-Type，非 HTML 走直连路径
            String contentType = probeContentType(url, config);
            if (contentType != null && isDirectFetchType(contentType)) {
                log.debug("非 HTML 响应，走 HttpClient 直连: url={}, contentType={}", url, contentType);
                return fetchDirect(url, selector.orElse(null), config, contentType);
            }

            // HTML 走 Jsoup 静态抓取（含浏览器回退）
            return fetchWithJsoup(url, selector.orElse(null), config);

        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("Web 抓取异常: {}", e.getMessage(), e);
            return ToolResult.error("Web 抓取异常: " + e.getMessage());
        }
    }

    /**
     * HEAD 请求探测 Content-Type。
     *
     * @return MIME 类型（如 {@code application/json}），探测失败返回 null
     */
    @Nullable
    private String probeContentType(String url, MetaProperties.Infra.WebFetch config) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "ZhiWei/1.0 (Web Fetch Tool)")
                    .timeout(Duration.ofSeconds(Math.min(config.getTimeoutSeconds(), 5)))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            String ct = response.headers().firstValue("Content-Type").orElse(null);
            if (ct != null) {
                // 去掉 charset 等参数，只保留 MIME 类型
                int semicolon = ct.indexOf(';');
                return semicolon > 0 ? ct.substring(0, semicolon).strip() : ct.strip();
            }
        } catch (Exception e) {
            log.debug("HEAD 探测 Content-Type 失败，回退到默认路径: url={}, error={}", url, e.getMessage());
        }
        return null;
    }

    /**
     * 判断是否为可直连获取的非 HTML 类型。
     */
    private boolean isDirectFetchType(String mimeType) {
        String lower = mimeType.toLowerCase();
        return DIRECT_FETCH_TYPES.contains(lower)
                || lower.endsWith("+json")
                || lower.endsWith("+xml");
    }

    /**
     * HttpClient 直连获取原始文本 — 用于 JSON/XML/纯文本响应。
     *
     * <p>不经过 Jsoup 和浏览器，直接返回原始响应体。
     * 支持 CSS 选择器时降级为不支持提示。</p>
     */
    private ToolResult fetchDirect(String url, @Nullable String selector,
                                   MetaProperties.Infra.WebFetch config,
                                   String contentType) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .header("User-Agent", "ZhiWei/1.0 (Web Fetch Tool)")
                    .header("Accept", contentType + ", */*")
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return ToolResult.error("HTTP %d: %s".formatted(response.statusCode(), url));
            }

            String content = response.body();
            if (content == null) {
                content = "";
            }

            // CSS 选择器对非 HTML 无意义，但如果是 XML 可尝试 Jsoup 解析
            if (selector != null && !selector.isBlank() && contentType.contains("xml")) {
                try {
                    Document doc = Jsoup.parse(content, url, org.jsoup.parser.Parser.xmlParser());
                    Element selected = doc.selectFirst(selector);
                    if (selected != null) {
                        content = selected.text();
                    }
                } catch (Exception e) {
                    log.debug("XML 选择器提取失败，返回原始内容: selector={}", selector);
                }
            }

            boolean truncated = false;
            int maxContentLength = config.getMaxContentLength();
            if (content.length() > maxContentLength) {
                content = content.substring(0, maxContentLength);
                truncated = true;
            }

            return ToolResult.success(Map.of(
                    "title", "",
                    "url", url,
                    "content", content,
                    "contentLength", content.length(),
                    "contentType", contentType,
                    "truncated", truncated,
                    "renderMode", "direct"
            ));

        } catch (SocketTimeoutException e) {
            return ToolResult.transientError("请求超时（%d 秒）".formatted(config.getTimeoutSeconds()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("请求被中断");
        } catch (Exception e) {
            log.warn("HttpClient 直连抓取失败: url={}, error={}", url, e.getMessage());
            return ToolResult.error("抓取失败: " + e.getMessage());
        }
    }

    /**
     * 使用 Jsoup 进行静态抓取。内容过短且 Content-Type 为 HTML 时自动尝试浏览器回退。
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
        } catch (org.jsoup.UnsupportedMimeTypeException e) {
            // Jsoup 不支持此 Content-Type（如 application/json），回退到直连
            log.debug("Jsoup 不支持的 MIME 类型，回退 HttpClient 直连: url={}, mimeType={}", url, e.getMimeType());
            return fetchDirect(url, selector, config, e.getMimeType());
        } catch (SocketTimeoutException e) {
            log.warn("Web 抓取超时: {}", e.getMessage());
            return ToolResult.transientError("请求超时（%d 秒）"
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

        // 仅 HTML 页面内容过短时才考虑浏览器回退（非 HTML 内容不需要 JS 渲染）
        if (content.length() < minStaticContentLength && isBrowserAvailable()) {
            // 检查实际 Content-Type：非 text/html 不回退浏览器
            String docContentType = doc.connection().response().contentType();
            if (docContentType != null && !docContentType.toLowerCase().contains("text/html")) {
                log.debug("非 HTML 响应内容过短但不回退浏览器: url={}, contentType={}, length={}",
                        url, docContentType, content.length());
            } else {
                log.info("HTML 静态抓取内容过短（{} 字符 < {} 阈值），尝试浏览器渲染回退: url={}",
                        content.length(), minStaticContentLength, url);
                return fetchWithBrowser(url, selector, config);
            }
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
     * 为每次浏览器抓取生成唯一会话 ID，避免并发请求共享 Page 导致竞态。
     */
    private String fetchSessionId() {
        return "web-fetch-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * 使用 Playwright 浏览器渲染页面并提取内容。
     *
     * <p>每次抓取创建独立 Page，用完即关，避免并发竞态。</p>
     */
    private ToolResult fetchWithBrowser(String url, @Nullable String selector,
                                        MetaProperties.Infra.WebFetch config) {
        String sessionId = fetchSessionId();
        try {
            PlaywrightPageWrapper page = browserSessionManager.getOrCreatePage(sessionId);
            int renderTimeoutMs = config.getRenderTimeoutSeconds() * 1000;
            var navResult = page.navigateWithResult(url, renderTimeoutMs);
            if (navResult.partial()) {
                log.info("浏览器渲染导航超时，尝试提取已加载内容: url={}", url);
            }
            if (!navResult.partial()) {
                waitForPageReady(page, renderTimeoutMs);
            }

            String title = navResult.title();
            String content;
            boolean truncated = false;

            if (selector != null && !selector.isBlank()) {
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
            // 每次抓取独立 Page，用完即关
            try {
                browserSessionManager.closePage(sessionId);
            } catch (Exception e) {
                log.warn("关闭浏览器抓取会话失败: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 等待页面 JS 渲染就绪。
     */
    private void waitForPageReady(PlaywrightPageWrapper page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                String readyState = page.evaluate("document.readyState");
                if ("\"complete\"".equals(readyState)) {
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

    private boolean isBrowserAvailable() {
        return browserSessionManager != null && browserSessionManager.isAvailable();
    }

    /**
     * 提取页面主体正文。
     */
    private String extractMainContent(Document doc) {
        doc.select("script, style, nav, header, footer, aside, .sidebar, .menu, .nav").remove();

        String[] mainSelectors = {"article", "main", "[role=main]", ".content", "#content", ".post-content"};
        for (String sel : mainSelectors) {
            Element main = doc.selectFirst(sel);
            if (main != null && !main.text().isBlank()) {
                return main.text();
            }
        }

        Element body = doc.body();
        return body.text();
    }
}

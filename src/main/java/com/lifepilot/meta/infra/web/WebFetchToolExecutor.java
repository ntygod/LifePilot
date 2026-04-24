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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Web 抓取工具执行器 — 支持 HTML/JSON/XML/纯文本多种响应类型。
 *
 * <p>GET 执行流程：
 * <ol>
 *   <li>HEAD 请求探测 Content-Type</li>
 *   <li>非 HTML 类型（JSON/XML/纯文本）→ HttpClient 直连获取原始文本</li>
 *   <li>HTML 类型 → Jsoup 静态解析；内容过短时回退 Playwright 浏览器渲染</li>
 *   <li>强制 {@code renderJs=true} → 直接走浏览器路径</li>
 * </ol>
 * </p>
 *
 * <p>非 GET（POST/PUT/DELETE/PATCH）统一走 {@link #fetchHttp} 通用 HttpClient 路径：
 * 跳过 HEAD 探测、Jsoup 解析和浏览器回退，直接返回响应体原文。
 * {@code renderJs} 对非 GET 方法无效（浏览器仅能 GET 渲染页面）。</p>
 *
 * <p>可选参数 {@code method}/{@code headers}/{@code body}/{@code timeoutSeconds} 与
 * {@link WebToolProvider#buildWebFetchTool} 宣告的 schema 完全对齐。</p>
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

    /** 合法 HTTP 方法集合 —— 其他方法视为参数错误。 */
    private static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

    private final MetaProperties properties;
    @Nullable
    private final BrowserSessionManager browserSessionManager;
    private final SsrfGuard ssrfGuard;
    private final HttpClient httpClient;

    /**
     * 测试便利构造器 — 默认禁用 SSRF 防护，用于本地 127.0.0.1 测试服务器场景。
     * <p>生产请使用 {@link #WebFetchToolExecutor(MetaProperties, BrowserSessionManager, SsrfGuard)}。</p>
     */
    public WebFetchToolExecutor(MetaProperties properties) {
        this(properties, null, SsrfGuard.disabled());
    }

    /**
     * 测试便利构造器 — 默认禁用 SSRF 防护。
     */
    public WebFetchToolExecutor(MetaProperties properties,
                                @Nullable BrowserSessionManager browserSessionManager) {
        this(properties, browserSessionManager, SsrfGuard.disabled());
    }

    /**
     * 生产构造器 — 注入 {@link SsrfGuard} 对外部 URL 做 SSRF 拦截。
     */
    public WebFetchToolExecutor(MetaProperties properties,
                                @Nullable BrowserSessionManager browserSessionManager,
                                SsrfGuard ssrfGuard) {
        this.properties = properties;
        this.browserSessionManager = browserSessionManager;
        this.ssrfGuard = ssrfGuard;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 执行 Web 页面抓取。
     *
     * <p>GET 保留原 HEAD 探测 → Jsoup/直连 → 浏览器回退流程；
     * 非 GET 方法直接使用 {@link #fetchHttp} 通用 HTTP 路径。</p>
     */
    public ToolResult execute(ToolInput input) {
        try {
            String url = input.getParam("url", String.class);
            var selector = input.getOptionalParam("selector", String.class);
            var renderJs = input.getOptionalParam("renderJs", Boolean.class);

            // 读取 method（默认 GET），非法值直接报错
            String method = input.getOptionalParam("method", String.class)
                    .map(String::toUpperCase)
                    .orElse("GET");
            if (!ALLOWED_METHODS.contains(method)) {
                return ToolResult.error("不支持的 HTTP 方法: " + method + "，支持: " + ALLOWED_METHODS);
            }

            // SSRF 防护 — 入口统一拦截，覆盖所有后续分支（HEAD 探测 / fetchDirect / fetchWithJsoup / fetchHttp / 浏览器路径）
            try {
                ssrfGuard.check(url);
            } catch (SsrfBlockedException e) {
                log.warn("SSRF 策略拦截 web.fetch 请求: url={}, reason={}", url, e.getReason());
                return ToolResult.error("目标地址被 SSRF 策略拦截: " + e.getReason());
            }

            // 读取可选 headers / body / timeoutSeconds
            @SuppressWarnings("unchecked")
            Map<String, String> headers = input.getOptionalParam("headers", Map.class)
                    .orElse(Map.of());
            String body = input.getOptionalParam("body", String.class).orElse(null);

            var config = properties.getInfra().getWebFetch();
            // timeoutSeconds 允许 LLM 传入 Integer/Long/Double，统一按 Number 读取
            int timeoutSec = input.getOptionalParam("timeoutSeconds", Number.class)
                    .map(Number::intValue)
                    .filter(n -> n > 0)
                    .orElse(config.getTimeoutSeconds());

            // 非 GET 方法统一走通用 HttpClient 路径：不做 HEAD 探测、不经 Jsoup、不回退浏览器
            if (!"GET".equals(method)) {
                if (renderJs.isPresent() && renderJs.get()) {
                    log.warn("renderJs 对非 GET 方法无效，将忽略: method={}, url={}", method, url);
                }
                return fetchHttp(url, method, headers, body, timeoutSec, config);
            }

            boolean forceRenderJs = renderJs.isPresent() && renderJs.get();

            // 强制浏览器渲染时直接走浏览器路径（仅 GET）
            if (forceRenderJs) {
                log.info("强制浏览器渲染: url={}", url);
                return fetchWithBrowserOrFallback(url, selector.orElse(null), config);
            }

            // 先探测 Content-Type，非 HTML 走直连路径
            String contentType = probeContentType(url, headers, timeoutSec);
            if (contentType != null && isDirectFetchType(contentType)) {
                log.debug("非 HTML 响应，走 HttpClient 直连: url={}, contentType={}", url, contentType);
                return fetchDirect(url, selector.orElse(null), headers, timeoutSec, config, contentType);
            }

            // HTML 走 Jsoup 静态抓取（含浏览器回退）
            return fetchWithJsoup(url, selector.orElse(null), headers, timeoutSec, config);

        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (HttpTimeoutException e) {
            log.warn("Web 抓取请求超时: {}", e.getMessage());
            return ToolResult.transientError("请求超时: " + e.getMessage());
        } catch (SocketTimeoutException e) {
            log.warn("Web 抓取连接超时: {}", e.getMessage());
            return ToolResult.transientError("连接超时: " + e.getMessage());
        } catch (Exception e) {
            log.error("Web 抓取异常: {}", e.getMessage(), e);
            return ToolResult.error("Web 抓取异常: " + e.getMessage());
        }
    }

    /**
     * 通用 HTTP 请求路径 — 用于非 GET 方法或 schema 显式指定的 HTTP 场景。
     *
     * <p>不经过 HEAD 探测、Jsoup 解析和浏览器回退，直接返回响应体原文。</p>
     *
     * @param url 目标 URL
     * @param method HTTP 方法（已大写）
     * @param headers 自定义请求头
     * @param body 请求体，GET 时允许为 null
     * @param timeoutSec 请求超时秒数（含连接和读取）
     * @param config WebFetch 配置（用于内容截断阈值）
     */
    private ToolResult fetchHttp(String url, String method, Map<String, String> headers,
                                 @Nullable String body, int timeoutSec,
                                 MetaProperties.Infra.WebFetch config) {
        try {
            HttpRequest.BodyPublisher bodyPublisher = (body != null)
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody();

            var requestBuilder = HttpRequest.newBuilder(URI.create(url))
                    .method(method, bodyPublisher)
                    .timeout(Duration.ofSeconds(timeoutSec));
            // 默认 User-Agent 可被自定义 headers 覆盖
            if (headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("User-Agent"))) {
                requestBuilder.header("User-Agent", "ZhiWei/1.0 (Web Fetch Tool)");
            }
            headers.forEach(requestBuilder::header);

            var response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String responseBody = response.body() != null ? response.body() : "";
            String contentType = response.headers().firstValue("Content-Type").orElse("");

            boolean truncated = false;
            int maxContentLength = config.getMaxContentLength();
            if (responseBody.length() > maxContentLength) {
                responseBody = responseBody.substring(0, maxContentLength);
                truncated = true;
            }

            if (statusCode >= 500) {
                return ToolResult.transientError(
                        "HTTP %d: %s".formatted(statusCode, truncateForError(responseBody)));
            }
            if (statusCode >= 400) {
                return ToolResult.error(
                        "HTTP %d: %s".formatted(statusCode, truncateForError(responseBody)));
            }

            return ToolResult.success(Map.of(
                    "title", "",
                    "url", url,
                    "method", method,
                    "statusCode", statusCode,
                    "content", responseBody,
                    "contentLength", responseBody.length(),
                    "contentType", contentType,
                    "truncated", truncated,
                    "renderMode", "http"
            ));
        } catch (HttpTimeoutException e) {
            return ToolResult.transientError("请求超时（%d 秒）".formatted(timeoutSec));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.transientError("请求被中断");
        } catch (IOException e) {
            log.warn("HTTP 抓取失败: url={}, method={}, error={}", url, method, e.getMessage());
            return ToolResult.transientError("HTTP 抓取失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("URL 非法: " + e.getMessage());
        }
    }

    /** 错误消息中的响应体预览截断，避免把超长 body 塞进 error 字段。 */
    private String truncateForError(String body) {
        return body.length() > 500 ? body.substring(0, 500) + "...[truncated]" : body;
    }

    /**
     * HEAD 请求探测 Content-Type —— 仅 GET 路径使用。
     *
     * <p>服务器返回非 2xx（如部分 HttpServer 对 HEAD 返回 501）时视为探测失败，
     * 由调用方回退至 Jsoup/直连路径。</p>
     *
     * @param url 目标 URL
     * @param headers 透传的自定义请求头（合并至 HEAD 请求）
     * @param timeoutSec 超时秒数，HEAD 探测内部会取 min(timeoutSec, 5)
     * @return MIME 类型（如 {@code application/json}），探测失败返回 null
     */
    @Nullable
    private String probeContentType(String url, Map<String, String> headers, int timeoutSec) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(Math.min(timeoutSec, 5)));
            if (headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("User-Agent"))) {
                builder.header("User-Agent", "ZhiWei/1.0 (Web Fetch Tool)");
            }
            headers.forEach(builder::header);
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                log.debug("HEAD 返回非成功状态，跳过探测: url={}, status={}", url, response.statusCode());
                return null;
            }
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
     * HttpClient 直连获取原始文本 — 用于 GET + JSON/XML/纯文本响应。
     *
     * <p>不经过 Jsoup 和浏览器，直接返回原始响应体。
     * 支持 CSS 选择器时降级为不支持提示。</p>
     *
     * @param headers 透传的自定义请求头
     * @param timeoutSec 请求超时秒数
     */
    private ToolResult fetchDirect(String url, @Nullable String selector,
                                   Map<String, String> headers, int timeoutSec,
                                   MetaProperties.Infra.WebFetch config,
                                   String contentType) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(timeoutSec));
            if (headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("User-Agent"))) {
                builder.header("User-Agent", "ZhiWei/1.0 (Web Fetch Tool)");
            }
            if (headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("Accept"))) {
                builder.header("Accept", contentType + ", */*");
            }
            headers.forEach(builder::header);
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

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

        } catch (HttpTimeoutException e) {
            return ToolResult.transientError("请求超时（%d 秒）".formatted(timeoutSec));
        } catch (SocketTimeoutException e) {
            return ToolResult.transientError("请求超时（%d 秒）".formatted(timeoutSec));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("请求被中断");
        } catch (Exception e) {
            log.warn("HttpClient 直连抓取失败: url={}, error={}", url, e.getMessage());
            return ToolResult.error("抓取失败: " + e.getMessage());
        }
    }

    /**
     * 向后兼容重载：无 headers / 默认 timeout 的 Jsoup 抓取。
     */
    private ToolResult fetchWithJsoup(String url, @Nullable String selector,
                                      MetaProperties.Infra.WebFetch config) throws IOException {
        return fetchWithJsoup(url, selector, Map.of(), config.getTimeoutSeconds(), config);
    }

    /**
     * 使用 Jsoup 进行静态抓取。内容过短且 Content-Type 为 HTML 时自动尝试浏览器回退。
     *
     * @param headers 透传的自定义请求头
     * @param timeoutSec 请求超时秒数
     */
    private ToolResult fetchWithJsoup(String url, @Nullable String selector,
                                      Map<String, String> headers, int timeoutSec,
                                      MetaProperties.Infra.WebFetch config) throws IOException {
        int timeoutMillis = timeoutSec * 1000;
        int maxContentLength = config.getMaxContentLength();
        int minStaticContentLength = config.getMinStaticContentLength();

        Document doc;
        try {
            var connection = Jsoup.connect(url)
                    .timeout(timeoutMillis)
                    .userAgent("ZhiWei/1.0 (Web Fetch Tool)")
                    .followRedirects(true);
            headers.forEach(connection::header);
            doc = connection.get();
        } catch (org.jsoup.UnsupportedMimeTypeException e) {
            // Jsoup 不支持此 Content-Type（如 application/json），回退到直连
            log.debug("Jsoup 不支持的 MIME 类型，回退 HttpClient 直连: url={}, mimeType={}", url, e.getMimeType());
            return fetchDirect(url, selector, headers, timeoutSec, config, e.getMimeType());
        } catch (SocketTimeoutException e) {
            log.warn("Web 抓取超时: {}", e.getMessage());
            return ToolResult.transientError("请求超时（%d 秒）"
                    .formatted(timeoutSec));
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

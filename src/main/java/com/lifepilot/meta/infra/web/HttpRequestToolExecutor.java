package com.lifepilot.meta.infra.web;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 请求工具 — 支持 GET/POST/PUT/DELETE/PATCH 方法。
 *
 * <p>允许 Agent 直接调用外部 API，获取或提交数据。
 * 安全限制：通过 {@link SsrfGuard} 统一拦截内网 / 云 metadata / 非法协议 URL，
 * 和 {@link WebFetchToolExecutor} 共享同一套策略（含 DNS rebinding 防御、allowlist 等）。</p>
 *
 * @author zsg
 * @since 2026-03-22
 */
public class HttpRequestToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestToolExecutor.class);

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_RESPONSE_LENGTH = 50000;

    private final HttpClient httpClient;
    private final SsrfGuard ssrfGuard;

    /**
     * 测试便利构造器 — 默认禁用 SSRF 防护，放行本地 127.0.0.1 测试服务器。
     * <p>生产请使用 {@link #HttpRequestToolExecutor(SsrfGuard)}。</p>
     */
    public HttpRequestToolExecutor() {
        this(SsrfGuard.disabled());
    }

    /**
     * 生产构造器 — 注入 {@link SsrfGuard} 对所有外部 URL 做 SSRF 拦截。
     */
    public HttpRequestToolExecutor(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 执行 HTTP 请求。
     *
     * @param input 工具输入，必需参数 url 和 method，可选 headers、body、timeoutSeconds
     * @return 包含 statusCode、headers、body 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String url;
        try {
            url = input.getParam("url", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: url");
        }

        String method = input.getOptionalParam("method", String.class)
                .orElse("GET").toUpperCase();

        if (!ALLOWED_METHODS.contains(method)) {
            return ToolResult.error("不支持的 HTTP 方法: " + method + "，支持: " + ALLOWED_METHODS);
        }

        // SSRF 防护 — 与 WebFetchToolExecutor 共用同一套策略
        try {
            ssrfGuard.check(url);
        } catch (SsrfBlockedException e) {
            log.warn("SSRF 策略拦截 http.request: url={}, reason={}", url, e.getReason());
            return ToolResult.error("目标地址被 SSRF 策略拦截: " + e.getReason());
        }

        String body = input.getOptionalParam("body", String.class).orElse(null);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = input.getOptionalParam("headers", Map.class)
                .orElse(Map.of());

        int timeoutSeconds = input.getOptionalParam("timeoutSeconds", Number.class)
                .map(Number::intValue)
                .orElse(DEFAULT_TIMEOUT_SECONDS);

        try {
            return doRequest(url, method, headers, body, timeoutSeconds);
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("HTTP 请求超时: url={}, method={}", url, method);
            return ToolResult.transientError("HTTP 请求超时: " + url);
        } catch (IOException e) {
            log.error("HTTP 请求失败: url={}, method={}, error={}", url, method, e.getMessage());
            return ToolResult.transientError("HTTP 请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.transientError("HTTP 请求被中断");
        }
    }

    private ToolResult doRequest(String url, String method, Map<String, String> headers,
                                  String body, int timeoutSeconds)
            throws IOException, InterruptedException {

        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        // 设置请求头
        headers.forEach(requestBuilder::header);
        if (!headers.containsKey("User-Agent")) {
            requestBuilder.header("User-Agent", "ZhiWei-Agent/1.0");
        }

        // 设置方法和请求体
        HttpRequest.BodyPublisher bodyPublisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();

        requestBuilder.method(method, bodyPublisher);

        log.debug("发送 HTTP 请求: method={}, url={}", method, url);

        HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        int statusCode = response.statusCode();
        String responseBody = response.body();

        // 截断过长的响应体
        if (responseBody != null && responseBody.length() > MAX_RESPONSE_LENGTH) {
            responseBody = responseBody.substring(0, MAX_RESPONSE_LENGTH)
                    + "...[响应已截断，原始长度: " + response.body().length() + " 字符]";
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("statusCode", statusCode);
        data.put("body", responseBody);

        // 提取关键响应头
        var responseHeaders = new LinkedHashMap<String, String>();
        response.headers().map().forEach((key, values) -> {
            if (!values.isEmpty()) {
                responseHeaders.put(key, values.getFirst());
            }
        });
        data.put("headers", responseHeaders);

        log.debug("HTTP 响应: statusCode={}, bodyLen={}", statusCode,
                responseBody != null ? responseBody.length() : 0);

        if (statusCode >= 500) {
            return ToolResult.transientError("HTTP " + statusCode + ": " + responseBody);
        }
        if (statusCode >= 400) {
            return ToolResult.error("HTTP " + statusCode + ": " + responseBody);
        }

        return ToolResult.success(Map.copyOf(data));
    }

}

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
 * 安全限制：禁止访问内网地址（127.0.0.1、localhost、10.x、172.16-31.x、192.168.x）。</p>
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

    public HttpRequestToolExecutor() {
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

        // 安全检查：禁止访问内网地址
        if (isInternalAddress(url)) {
            return ToolResult.error("安全策略禁止访问内网地址: " + url);
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
        } catch (IOException e) {
            log.error("HTTP 请求失败: url={}, method={}, error={}", url, method, e.getMessage());
            return ToolResult.error("HTTP 请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("HTTP 请求被中断");
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

        if (statusCode >= 400) {
            return ToolResult.error("HTTP " + statusCode + ": " + responseBody);
        }

        return ToolResult.success(Map.copyOf(data));
    }

    /**
     * 检查 URL 是否指向内网地址。
     */
    private boolean isInternalAddress(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return true;
            host = host.toLowerCase();
            return host.equals("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1")
                    || host.equals("0.0.0.0")
                    || host.startsWith("10.")
                    || host.startsWith("192.168.")
                    || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
        } catch (Exception e) {
            return true; // 解析失败视为不安全
        }
    }
}

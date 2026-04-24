package com.lifepilot.meta.infra.web;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebFetchToolExecutor 对 method/headers/body/timeoutSeconds 的支持测试。
 *
 * <p>使用本地 {@link HttpServer} 作为测试后端，避免依赖外部网络。
 * 覆盖以下用例：</p>
 * <ul>
 *   <li>GET 请求携带自定义 header 透传到服务器</li>
 *   <li>POST/PUT/DELETE 等非 GET 方法能够发送并回显请求体</li>
 *   <li>{@code timeoutSeconds} 能将慢响应截断为瞬态失败</li>
 *   <li>非 GET 方法响应体能够完整返回，不被 Jsoup 或浏览器路径劫持</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
class WebFetchToolExecutor_HTTP方法扩展测试 {

    /** 本地测试 HTTP 服务端口。 */
    private static HttpServer server;

    /** 最近一次请求的方法、headers、body 快照，供断言使用。 */
    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    private static String baseUrl;

    /**
     * 启动本地 HTTP 测试服务器 — 注册 echo/slow 两类端点。
     */
    @BeforeAll
    static void 启动本地测试服务器() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // echo 端点：回显请求方法 + body + 指定 header 值
        // 注：响应强制 Connection: close，避免 HttpClient + HttpServer 的 keep-alive 竞态
        server.createContext("/echo", exchange -> {
            捕获请求快照(exchange);
            String response = """
                    {"method":"%s","body":"%s","xZhiwei":"%s"}""".formatted(
                    exchange.getRequestMethod(),
                    lastBody.get().replace("\"", "\\\""),
                    exchange.getRequestHeaders().getFirst("X-Zhiwei-Test"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().add("Connection", "close");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
                os.flush();
            }
        });
        // slow 端点：响应前睡 3 秒，用于超时测试
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "late".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.getResponseHeaders().add("Connection", "close");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
                os.flush();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void 停止本地测试服务器() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void 捕获请求快照(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastHeaders.clear();
        exchange.getRequestHeaders().forEach((k, v) -> {
            if (!v.isEmpty()) lastHeaders.put(k, v.getFirst());
        });
        lastBody.set(读取请求体(exchange));
    }

    private static String 读取请求体(HttpExchange exchange) throws IOException {
        try (var is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 构造 executor，不注入浏览器管理器（仅测 HTTP 路径）。 */
    private WebFetchToolExecutor 构造执行器() {
        return new WebFetchToolExecutor(new MetaProperties(), null);
    }

    private ToolInput 构造输入(Map<String, Object> params) {
        return new ToolInput("web.fetch", params, JsonSchema.empty(), null, null);
    }

    @Test
    void GET请求_自定义header透传到服务器() {
        ToolResult result = 构造执行器().execute(构造输入(Map.of(
                "url", baseUrl + "/echo",
                "method", "GET",
                "headers", Map.of("X-Zhiwei-Test", "hello")
        )));

        assertThat(result.ok())
                .as("GET 请求应成功")
                .isTrue();
        assertThat(lastMethod.get()).isEqualTo("GET");
        // HttpServer 将 header 键规范为 "X-Zhiwei-Test" 首字母大写格式，
        // 以响应 body 中被回显的值作为 header 透传的确认依据（不受键大小写影响）。
        assertThat((String) result.data().get("content"))
                .as("响应体应回显 X-Zhiwei-Test 的值")
                .contains("\"xZhiwei\":\"hello\"");
    }

    @Test
    void POST请求_携带JSON_body并回显() {
        ToolResult result = 构造执行器().execute(构造输入(Map.of(
                "url", baseUrl + "/echo",
                "method", "POST",
                "headers", Map.of("Content-Type", "application/json"),
                "body", "{\"test\":1}"
        )));

        assertThat(result.ok())
                .as("POST 请求应成功，实际错误: %s", result.error())
                .isTrue();
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastBody.get()).contains("\"test\":1");
        // 响应体应包含回显内容
        assertThat((String) result.data().get("content")).contains("\"method\":\"POST\"");
    }

    @Test
    void PUT和DELETE方法支持() {
        var executor = 构造执行器();

        var put = executor.execute(构造输入(Map.of(
                "url", baseUrl + "/echo",
                "method", "PUT",
                "body", "put-body"
        )));
        assertThat(put.ok()).as("PUT 应成功").isTrue();
        assertThat(lastMethod.get()).isEqualTo("PUT");
        assertThat(lastBody.get()).isEqualTo("put-body");

        var del = executor.execute(构造输入(Map.of(
                "url", baseUrl + "/echo",
                "method", "DELETE"
        )));
        assertThat(del.ok()).as("DELETE 应成功").isTrue();
        assertThat(lastMethod.get()).isEqualTo("DELETE");
    }

    @Test
    void timeoutSeconds_生效_慢响应返回瞬态失败() {
        // 服务器延迟 3 秒，超时设 1 秒，应失败
        ToolResult result = 构造执行器().execute(构造输入(Map.of(
                "url", baseUrl + "/slow",
                "method", "GET",
                "timeoutSeconds", 1
        )));

        assertThat(result.ok())
                .as("1 秒超时应触发失败")
                .isFalse();
    }

    @Test
    void 默认method为GET_未传method时按GET处理() {
        ToolResult result = 构造执行器().execute(构造输入(Map.of(
                "url", baseUrl + "/echo"
        )));

        assertThat(result.ok()).isTrue();
        assertThat(lastMethod.get()).isEqualTo("GET");
    }
}

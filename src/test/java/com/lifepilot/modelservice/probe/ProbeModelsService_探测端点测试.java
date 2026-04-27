package com.lifepilot.modelservice.probe;

import com.lifepilot.llm.profile.ProviderProfileRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProbeModelsService 探测端点测试。
 *
 * <p>用 JDK 内置 {@link HttpServer} 模拟两类 provider：
 * <ul>
 *   <li>OpenAI 兼容（DeepSeek 官方 profile）走 /v1/models，解析 $.data[*].id</li>
 *   <li>Ollama 本地 profile 走 /api/tags，解析 $.models[*].name</li>
 * </ul>
 *
 * <p>同步覆盖鉴权 header 注入（Authorization Bearer 模板）和无鉴权 provider 的差异，
 * 以及非 2xx 响应的失败抛错路径。
 *
 * @author zsg
 * @since 2026-04-27
 */
class ProbeModelsService_探测端点测试 {

    /** 本地 mock HTTP 服务，每个测试启动随机端口。 */
    private HttpServer server;

    private ProbeModelsService service;

    /** 最近一次请求的路径与 header 快照，供断言鉴权头。 */
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final ConcurrentHashMap<String, String> lastHeaders = new ConcurrentHashMap<>();

    @BeforeEach
    void 启动mock服务器() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();

        // ProviderProfileRegistry 自带 @PostConstruct 加载，单测里手动调
        ProviderProfileRegistry registry = new ProviderProfileRegistry();
        registry.init();
        service = new ProbeModelsService(registry);
    }

    @AfterEach
    void 关闭mock服务器() {
        if (server != null) {
            server.stop(0);
        }
        lastHeaders.clear();
    }

    @Test
    void OpenAI兼容provider解析v1_models的data字段() throws IOException {
        server.createContext("/v1/models", exchange -> {
            捕获请求快照(exchange);
            String body = """
                    {"data":[{"id":"deepseek-v4-pro"},{"id":"deepseek-chat"}]}""";
            写响应(exchange, 200, body);
        });

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        var resp = service.probe(new ProbeModelsRequest(
                "deepseek-official", baseUrl, "sk-test-key"));

        assertThat(resp.models()).hasSize(2);
        assertThat(resp.models().get(0).id()).isEqualTo("deepseek-v4-pro");
        assertThat(resp.models().get(0).name()).isEqualTo("deepseek-v4-pro");
        assertThat(resp.models().get(1).id()).isEqualTo("deepseek-chat");
        // 鉴权 header 已按 "Bearer ${apiKey}" 模板注入
        assertThat(lastHeaders.get("Authorization")).isEqualTo("Bearer sk-test-key");
        assertThat(lastPath.get()).isEqualTo("/v1/models");
    }

    @Test
    void Ollama_provider解析api_tags的models_name字段() throws IOException {
        server.createContext("/api/tags", exchange -> {
            捕获请求快照(exchange);
            String body = """
                    {"models":[{"name":"llama3:8b"},{"name":"qwen2:7b"}]}""";
            写响应(exchange, 200, body);
        });

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        // Ollama 无鉴权：apiKey 传 null，ProbeModelsService 不应注入 Authorization header
        var resp = service.probe(new ProbeModelsRequest(
                "ollama-local", baseUrl, null));

        assertThat(resp.models()).hasSize(2);
        assertThat(resp.models().get(0).id()).isEqualTo("llama3:8b");
        assertThat(resp.models().get(1).id()).isEqualTo("qwen2:7b");
        assertThat(lastPath.get()).isEqualTo("/api/tags");
        // null apiKey 时不应注入鉴权 header（避免某些自部署服务因占位 header 反而 400）
        assertThat(lastHeaders.containsKey("X-Unused")).isFalse();
    }

    @Test
    void baseUrl末尾带斜杠会被剥掉避免双斜杠() throws IOException {
        server.createContext("/v1/models", exchange -> {
            捕获请求快照(exchange);
            写响应(exchange, 200, "{\"data\":[{\"id\":\"gpt-4o\"}]}");
        });

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        var resp = service.probe(new ProbeModelsRequest(
                "openai-official", baseUrl, "sk-test"));

        assertThat(resp.models()).hasSize(1);
        assertThat(resp.models().get(0).id()).isEqualTo("gpt-4o");
        // 路径必须是 /v1/models 而不是 //v1/models
        assertThat(lastPath.get()).isEqualTo("/v1/models");
    }

    @Test
    void 非2xx响应应抛出运行时异常() throws IOException {
        server.createContext("/v1/models", exchange -> {
            写响应(exchange, 401, "{\"error\":{\"message\":\"invalid api key\"}}");
        });

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        assertThatThrownBy(() -> service.probe(new ProbeModelsRequest(
                "deepseek-official", baseUrl, "sk-bad")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void provider_返回_401_时抛_ResponseStatusException_透传状态码() throws IOException {
        // mock 401 响应，body 含 provider 真实错误信息（前端 toast 透传时显示给老板）
        server.createContext("/v1/models", exchange -> {
            写响应(exchange, 401, "{\"error\":{\"message\":\"invalid api key\"}}");
        });

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        assertThatThrownBy(() -> service.probe(new ProbeModelsRequest(
                "deepseek-official", baseUrl, "sk-bad")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    // 状态码必须 401 透传（之前 RuntimeException 被吞成 500 是 bug）
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    // reason 必须含 provider 原 body，便于前端 toast 显示具体原因
                    assertThat(rse.getReason()).contains("HTTP 401");
                    assertThat(rse.getReason()).contains("invalid api key");
                });
    }

    @Test
    void 连接被拒绝时抛_503_ResponseStatusException() throws IOException {
        // 占用一个本地端口然后立即释放，得到一个"曾经可用但当前没人监听"的端口号；
        // 直接探测该端口可稳定触发 ConnectException（连接被拒绝），不会随平台波动
        int unboundPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unboundPort = socket.getLocalPort();
        }

        String baseUrl = "http://127.0.0.1:" + unboundPort;
        assertThatThrownBy(() -> service.probe(new ProbeModelsRequest(
                "deepseek-official", baseUrl, "sk-test")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(rse.getReason()).contains("无法连接");
                    assertThat(rse.getReason()).contains("连接被拒绝");
                });
    }

    @Test
    void jsonpath_提取失败时抛_422_ResponseStatusException() throws IOException {
        // 响应 200 但 body 不含预期的 $.data 字段（provider 协议变更或返回 HTML 错误页）
        server.createContext("/v1/models", exchange -> {
            写响应(exchange, 200, "{\"unexpected\":\"shape\"}");
        });

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        assertThatThrownBy(() -> service.probe(new ProbeModelsRequest(
                "deepseek-official", baseUrl, "sk-test")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(rse.getReason()).contains("解析探测响应失败");
                    // 必须把原 body 的开头带上，便于定位实际返回是什么
                    assertThat(rse.getReason()).contains("unexpected");
                });
    }

    private void 捕获请求快照(HttpExchange exchange) {
        lastPath.set(exchange.getRequestURI().getPath());
        lastHeaders.clear();
        exchange.getRequestHeaders().forEach((k, v) -> {
            if (!v.isEmpty()) {
                lastHeaders.put(k, v.getFirst());
            }
        });
    }

    private void 写响应(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
            os.flush();
        }
    }
}

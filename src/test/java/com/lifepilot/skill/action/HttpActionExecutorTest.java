package com.lifepilot.skill.action;

import com.lifepilot.skill.config.SkillConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HttpActionExecutor} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
@ExtendWith(MockitoExtension.class)
class HttpActionExecutorTest {

    @Mock
    private RestClient restClient;

    private VariableResolver variableResolver;
    private SkillConfigProperties config;
    private HttpActionExecutor executor;

    @BeforeEach
    void setUp() {
        variableResolver = new VariableResolver();
        config = new SkillConfigProperties();
        executor = new HttpActionExecutor(restClient, variableResolver, config);
    }

    // ─────────────────────────────────────────────
    //  isSsrfTarget — SSRF 防护检测
    // ─────────────────────────────────────────────

    @Test
    void isSsrfTarget_检测localhost() {
        assertThat(executor.isSsrfTarget("http://localhost:8080/api")).isTrue();
        assertThat(executor.isSsrfTarget("http://localhost/api")).isTrue();
        assertThat(executor.isSsrfTarget("https://localhost:443/api")).isTrue();
        assertThat(executor.isSsrfTarget("http://localhost")).isTrue();
    }

    @Test
    void isSsrfTarget_检测127段地址() {
        assertThat(executor.isSsrfTarget("http://127.0.0.1:8080/api")).isTrue();
        assertThat(executor.isSsrfTarget("http://127.0.0.1/api")).isTrue();
        assertThat(executor.isSsrfTarget("http://127.255.255.255/api")).isTrue();
    }

    @Test
    void isSsrfTarget_检测10段地址() {
        assertThat(executor.isSsrfTarget("http://10.0.0.1/api")).isTrue();
        assertThat(executor.isSsrfTarget("http://10.255.255.255:9090/api")).isTrue();
    }

    @Test
    void isSsrfTarget_检测172_16到31段地址() {
        assertThat(executor.isSsrfTarget("http://172.16.0.1/api")).isTrue();
        assertThat(executor.isSsrfTarget("http://172.20.0.1/api")).isTrue();
        assertThat(executor.isSsrfTarget("http://172.31.255.255/api")).isTrue();
        // 172.15 和 172.32 不在范围内
        assertThat(executor.isSsrfTarget("http://172.15.0.1/api")).isFalse();
        assertThat(executor.isSsrfTarget("http://172.32.0.1/api")).isFalse();
    }

    @Test
    void isSsrfTarget_检测192_168段地址() {
        assertThat(executor.isSsrfTarget("http://192.168.0.1/api")).isTrue();
        assertThat(executor.isSsrfTarget("http://192.168.1.100:3000/api")).isTrue();
    }

    @Test
    void isSsrfTarget_允许外部URL() {
        assertThat(executor.isSsrfTarget("https://api.example.com/v1/data")).isFalse();
        assertThat(executor.isSsrfTarget("https://httpbin.org/get")).isFalse();
        assertThat(executor.isSsrfTarget("http://8.8.8.8/dns")).isFalse();
    }

    @Test
    void isSsrfTarget_空URL返回false() {
        assertThat(executor.isSsrfTarget(null)).isFalse();
        assertThat(executor.isSsrfTarget("")).isFalse();
        assertThat(executor.isSsrfTarget("  ")).isFalse();
    }

    // ─────────────────────────────────────────────
    //  execute — SSRF 防护
    // ─────────────────────────────────────────────

    @Test
    void execute_SSRF目标返回错误结果() {
        var action = new SkillAction.HttpAction(
                "GET", "http://localhost:8080/api", Map.of(), Map.of(), null);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("SSRF");
    }

    @Test
    void execute_SSRF防护关闭时允许内网地址() {
        config.getHttpAction().setSsrfProtectionEnabled(false);

        // 需要 mock RestClient 链式调用
        mockRestClientChain("内网响应");

        var action = new SkillAction.HttpAction(
                "GET", "http://localhost:8080/api", Map.of(), Map.of(), null);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("内网响应");
    }

    // ─────────────────────────────────────────────
    //  execute — 变量替换
    // ─────────────────────────────────────────────

    @Test
    void execute_替换URL中的变量() {
        mockRestClientChain("ok");

        var action = new SkillAction.HttpAction(
                "GET", "https://api.example.com/${params.path}",
                Map.of(), Map.of(), null);

        ActionResult result = executor.execute(action, Map.of("path", "users"));

        assertThat(result.success()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  execute — 成功响应
    // ─────────────────────────────────────────────

    @Test
    void execute_2xx响应返回成功结果() {
        mockRestClientChain("{\"status\":\"ok\"}");

        var action = new SkillAction.HttpAction(
                "GET", "https://api.example.com/data",
                Map.of("Accept", "application/json"),
                Map.of("page", "1"),
                null);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("{\"status\":\"ok\"}");
    }

    // ─────────────────────────────────────────────
    //  execute — 错误响应
    // ─────────────────────────────────────────────

    @Test
    void execute_4xx响应返回错误结果() {
        mockRestClientChainWithError(404, "Not Found");

        var action = new SkillAction.HttpAction(
                "GET", "https://api.example.com/missing",
                Map.of(), Map.of(), null);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("404");
    }

    @Test
    void execute_5xx响应返回错误结果() {
        mockRestClientChainWithError(500, "Internal Server Error");

        var action = new SkillAction.HttpAction(
                "GET", "https://api.example.com/error",
                Map.of(), Map.of(), null);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("500");
    }

    // ─────────────────────────────────────────────
    //  execute — 超时
    // ─────────────────────────────────────────────

    @Test
    void execute_超时返回超时错误() {
        mockRestClientChainWithTimeout();

        var action = new SkillAction.HttpAction(
                "GET", "https://api.example.com/slow",
                Map.of(), Map.of(), null);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("超时");
    }

    // ─────────────────────────────────────────────
    //  辅助方法 — Mock RestClient 链式调用
    // ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private RestClient.RequestBodyUriSpec mockRestClientChain(String responseBody) {
        var requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        var responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.header(anyString(), any(String[].class))).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.body(any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.body(eq(String.class))).thenReturn(responseBody);

        return requestBodyUriSpec;
    }

    @SuppressWarnings("unchecked")
    private void mockRestClientChainWithError(int statusCode, String errorBody) {
        var requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        var responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.header(anyString(), any(String[].class))).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.body(any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.retrieve()).thenReturn(responseSpec);

        // 模拟 onStatus 回调触发异常
        when(responseSpec.onStatus(any(), any())).thenAnswer(invocation -> {
            var statusPredicate = invocation.getArgument(0, java.util.function.Predicate.class);
            var handler = invocation.getArgument(1, RestClient.ResponseSpec.ErrorHandler.class);

            // 创建 mock response
            var mockResponse = mock(ClientHttpResponse.class);
            when(mockResponse.getStatusCode()).thenReturn(HttpStatusCode.valueOf(statusCode));
            when(mockResponse.getBody()).thenReturn(
                    new ByteArrayInputStream(errorBody.getBytes(StandardCharsets.UTF_8)));

            // 检查状态码是否匹配谓词
            if (statusPredicate.test(HttpStatusCode.valueOf(statusCode))) {
                try {
                    handler.handle(null, mockResponse);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return responseSpec;
        });
    }

    @SuppressWarnings("unchecked")
    private void mockRestClientChainWithTimeout() {
        var requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);

        when(restClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.header(anyString(), any(String[].class))).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.body(any())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.retrieve()).thenThrow(
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));
    }
}

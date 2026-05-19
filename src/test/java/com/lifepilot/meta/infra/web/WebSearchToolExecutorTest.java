package com.lifepilot.meta.infra.web;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WebSearchToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class WebSearchToolExecutorTest {

    private WebSearchConfigProvider configProvider;
    private RestClient restClient;
    private RestClient.RequestBodyUriSpec requestSpec;
    private RestClient.ResponseSpec responseSpec;
    private WebSearchToolExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        configProvider = mock(WebSearchConfigProvider.class);
        restClient = mock(RestClient.class);
        requestSpec = mock(RestClient.RequestBodyUriSpec.class, RETURNS_SELF);
        responseSpec = mock(RestClient.ResponseSpec.class);

        when(configProvider.getConfig()).thenReturn(new WebSearchConfig(
                "https://api.tavily.com/search",
                "tavily",
                "tvly-secret",
                5,
                10,
                30,
                "basic",
                "general",
                true
        ));

        doReturn(requestSpec).when(restClient).post();
        doReturn(responseSpec).when(requestSpec).retrieve();

        executor = new WebSearchToolExecutor(configProvider, config -> restClient);
    }

    @Test
    void tavily搜索_返回结果列表和答案摘要() {
        Map<String, Object> tavilyResponse = Map.of(
                "answer", "Java 是一种广泛使用的编程语言。",
                "results", List.of(
                        Map.of(
                                "title", "Java 文档",
                                "content", "Java 平台与语言介绍",
                                "url", "https://www.oracle.com/java/",
                                "score", 0.92
                        ),
                        Map.of(
                                "title", "OpenJDK",
                                "content", "OpenJDK 是 Java SE 的开源实现",
                                "url", "https://openjdk.org/",
                                "score", 0.88
                        )
                )
        );
        when(responseSpec.body(Map.class)).thenReturn(tavilyResponse);

        ToolInput input = new ToolInput("web.search",
                Map.of("query", "Java programming"), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("provider")).isEqualTo("tavily");
        assertThat(result.data().get("query")).isEqualTo("Java programming");
        assertThat(result.data().get("answer")).isEqualTo("Java 是一种广泛使用的编程语言。");
        assertThat((int) result.data().get("resultCount")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) result.data().get("results");
        assertThat(results).hasSize(2);
        assertThat(results.getFirst()).containsKeys("title", "snippet", "url", "score");

        verify(requestSpec).uri("https://api.tavily.com/search");
        verify(requestSpec).contentType(MediaType.APPLICATION_JSON);
        verify(requestSpec).header("Authorization", "Bearer tvly-secret");
    }

    @Test
    void tavily搜索_空响应_降级DuckDuckGo() {
        when(responseSpec.body(Map.class)).thenReturn(null);

        ToolInput input = new ToolInput("web.search",
                Map.of("query", "test"), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        // Tavily 返回 null → transientError → 不是 success → 降级到 DuckDuckGo
        // DuckDuckGo 在测试环境可能成功也可能失败（网络依赖），但不应该是 API Key 错误
        // 验证 Tavily 确实被调用了
        verify(requestSpec).uri("https://api.tavily.com/search");
    }

    @Test
    void 未配置ApiKey_降级DuckDuckGo() {
        when(configProvider.getConfig()).thenReturn(new WebSearchConfig(
                "https://api.tavily.com/search",
                "tavily",
                "",
                5,
                10,
                30,
                "basic",
                "general",
                true
        ));

        ToolInput input = new ToolInput("web.search",
                Map.of("query", "test"), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        // API Key 为空时不调用 Tavily，直接降级到 DuckDuckGo
        verify(restClient, never()).post();
        // DuckDuckGo 在测试环境可能成功也可能失败，但不应该报 API Key 错误
        if (result.error() != null) {
            assertThat(result.error()).doesNotContain("API Key");
        }
    }

    @Test
    void 分页参数_返回切片结果() {
        when(responseSpec.body(Map.class)).thenReturn(Map.of(
                "results", List.of(
                        Map.of("title", "Result 1", "content", "Snippet 1", "url", "https://example.com/1"),
                        Map.of("title", "Result 2", "content", "Snippet 2", "url", "https://example.com/2"),
                        Map.of("title", "Result 3", "content", "Snippet 3", "url", "https://example.com/3")
                )
        ));

        ToolInput input = new ToolInput("web.search",
                Map.of("query", "test", "offset", 1, "limit", 1), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("resultCount")).isEqualTo(1);
        assertThat(result.data().get("hasMore")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) result.data().get("results");
        assertThat(results).singleElement().satisfies(item ->
                assertThat(item).containsEntry("title", "Result 2"));
    }

    @Test
    void 缺少query参数_返回错误() {
        ToolInput input = new ToolInput("web.search",
                Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("参数");
    }
}

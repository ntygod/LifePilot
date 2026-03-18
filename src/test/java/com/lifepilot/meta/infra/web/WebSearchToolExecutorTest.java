package com.lifepilot.meta.infra.web;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WebSearchToolExecutor 单元测试�?
 *
 * @author zsg
 * @since 2026-03-08
 */
class WebSearchToolExecutorTest {

    private MetaProperties properties;
    private RestClient restClient;
    private RestClient.Builder restClientBuilder;
    private WebSearchToolExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new MetaProperties();
        restClient = mock(RestClient.class);
        restClientBuilder = mock(RestClient.Builder.class);

        // 使用 doReturn 避免泛型类型推断问题
        var requestSpec = mock(RestClient.RequestHeadersUriSpec.class);
        var responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClientBuilder.build()).thenReturn(restClient);
        doReturn(requestSpec).when(restClient).get();
        doReturn(requestSpec).when(requestSpec).uri(anyString());
        doReturn(responseSpec).when(requestSpec).retrieve();

        this.responseSpec = responseSpec;
        executor = new WebSearchToolExecutor(properties, restClientBuilder);
    }

    private RestClient.ResponseSpec responseSpec;

    @Test
    void duckduckgo搜索_返回结果列表() {
        // 配置 DuckDuckGo 提供商（默认�?
        properties.getInfra().getWebSearch().setProvider("duckduckgo");

        // Mock DuckDuckGo API 响应
        Map<String, Object> ddgResponse = Map.of(
                "Heading", "Java Programming",
                "AbstractText", "Java is a programming language.",
                "AbstractURL", "https://en.wikipedia.org/wiki/Java",
                "RelatedTopics", List.of(
                        Map.of("Text", "Java SE - Standard Edition", "FirstURL", "https://example.com/java-se"),
                        Map.of("Text", "Java EE - Enterprise Edition", "FirstURL", "https://example.com/java-ee")
                )
        );
        when(responseSpec.body(Map.class)).thenReturn(ddgResponse);

        ToolInput input = new ToolInput("builtin.web.search",
                Map.of("query", "Java programming"), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("provider")).isEqualTo("duckduckgo");
        assertThat(result.data().get("query")).isEqualTo("Java programming");
        assertThat((int) result.data().get("resultCount")).isGreaterThan(0);

        @SuppressWarnings("unchecked")
        var results = (List<Map<String, String>>) result.data().get("results");
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst()).containsKeys("title", "snippet", "url");
    }

    @Test
    void duckduckgo搜索_空响应返回错�?) {
        when(responseSpec.body(Map.class)).thenReturn(null);

        ToolInput input = new ToolInput("builtin.web.search",
                Map.of("query", "test"), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("空响�?);
    }

    @Test
    void google搜索_未配置ApiKey_返回错误() {
        properties.getInfra().getWebSearch().setProvider("google");
        properties.getInfra().getWebSearch().setApiKey("");

        ToolInput input = new ToolInput("builtin.web.search",
                Map.of("query", "test"), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("API Key");
    }

    @Test
    void bing搜索_未配置ApiKey_返回错误() {
        properties.getInfra().getWebSearch().setProvider("bing");
        properties.getInfra().getWebSearch().setApiKey("");

        ToolInput input = new ToolInput("builtin.web.search",
                Map.of("query", "test"), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("API Key");
    }

    @Test
    void 不支持的搜索引擎_返回错误() {
        properties.getInfra().getWebSearch().setProvider("yahoo");

        ToolInput input = new ToolInput("builtin.web.search",
                Map.of("query", "test"), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("不支持的搜索引擎");
    }

    @Test
    void maxResults参数_限制返回数量() {
        properties.getInfra().getWebSearch().setProvider("duckduckgo");

        // Mock 大量 RelatedTopics
        var topics = List.<Object>of(
                Map.of("Text", "Topic 1", "FirstURL", "https://example.com/1"),
                Map.of("Text", "Topic 2", "FirstURL", "https://example.com/2"),
                Map.of("Text", "Topic 3", "FirstURL", "https://example.com/3"),
                Map.of("Text", "Topic 4", "FirstURL", "https://example.com/4"),
                Map.of("Text", "Topic 5", "FirstURL", "https://example.com/5")
        );
        Map<String, Object> ddgResponse = Map.of(
                "Heading", "",
                "AbstractText", "",
                "AbstractURL", "",
                "RelatedTopics", topics
        );
        when(responseSpec.body(Map.class)).thenReturn(ddgResponse);

        ToolInput input = new ToolInput("builtin.web.search",
                Map.of("query", "test", "maxResults", 2), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        var results = (List<Map<String, String>>) result.data().get("results");
        assertThat(results).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void 缺少query参数_返回错误() {
        ToolInput input = new ToolInput("builtin.web.search",
                Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("参数");
    }
}

package com.lifepilot.meta.infra.web;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Web 搜索工具执行器。
 *
 * <p>当前统一通过 Tavily Search API 执行联网搜索。
 *
 * @author zsg
 * @since 2026-03-08
 */
public class WebSearchToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebSearchToolExecutor.class);
    private static final String TAVILY_SEARCH_URL = "https://api.tavily.com/search";

    private final WebSearchConfigProvider configProvider;
    private final Function<WebSearchConfig, RestClient> restClientFactory;

    public WebSearchToolExecutor(WebSearchConfigProvider configProvider) {
        this(configProvider, WebSearchToolExecutor::createRestClient);
    }

    WebSearchToolExecutor(WebSearchConfigProvider configProvider,
                          Function<WebSearchConfig, RestClient> restClientFactory) {
        this.configProvider = configProvider;
        this.restClientFactory = restClientFactory;
    }

    /**
     * 执行 Web 搜索。
     *
     * @param input 工具输入，必须包含 query，可选 maxResults / offset / limit
     * @return 结构化搜索结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String query = input.getParam("query", String.class);
            if (query.isBlank()) {
                return ToolResult.error("参数错误: query 不能为空");
            }

            var config = configProvider.getConfig();
            int configuredMaxResults = config.maxResults();
            int maxResults = input.getOptionalParam("maxResults", Integer.class)
                    .map(value -> clamp(value, 1, 20))
                    .orElse(configuredMaxResults);
            int offset = input.getOptionalParam("offset", Integer.class)
                    .map(value -> Math.max(value, 0))
                    .orElse(0);
            int limit = input.getOptionalParam("limit", Integer.class)
                    .filter(value -> value >= 1)
                    .map(value -> clamp(value, 1, 20))
                    .orElse(maxResults);
            int requestSize = clamp(Math.max(maxResults, offset + limit), 1, 20);

            return searchTavily(query.trim(), config, requestSize, offset, limit);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("Web 搜索失败: {}", e.getMessage(), e);
            return ToolResult.transientError("Web 搜索失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResult searchTavily(String query,
                                    WebSearchConfig config,
                                    int requestSize,
                                    int offset,
                                    int limit) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            return ToolResult.error("Web 搜索未配置 API Key, 改用 web.fetch 直接抓取目标页面");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("query", query);
        requestBody.put("search_depth", config.searchDepth());
        requestBody.put("topic", config.topic());
        requestBody.put("max_results", requestSize);
        requestBody.put("include_answer", config.includeAnswer());
        requestBody.put("include_images", false);
        requestBody.put("include_raw_content", false);

        var response = restClientFactory.apply(config).post()
                .uri(TAVILY_SEARCH_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            return ToolResult.transientError("Tavily API 返回空响应");
        }

        List<Map<String, Object>> normalizedResults = new ArrayList<>();
        Object rawResults = response.get("results");
        if (rawResults instanceof List<?> results) {
            for (Object item : results) {
                if (!(item instanceof Map<?, ?> resultMap)) {
                    continue;
                }
                String title = readString(resultMap, "title");
                String snippet = firstNonBlank(
                        readString(resultMap, "content"),
                        readString(resultMap, "raw_content"),
                        readString(resultMap, "description")
                );
                String url = readString(resultMap, "url");

                var normalized = new LinkedHashMap<String, Object>();
                normalized.put("title", title);
                normalized.put("snippet", snippet);
                normalized.put("url", url);

                Double score = readDouble(resultMap.get("score"));
                if (score != null) {
                    normalized.put("score", score);
                }

                String publishedDate = readString(resultMap, "published_date");
                if (!publishedDate.isBlank()) {
                    normalized.put("publishedDate", publishedDate);
                }

                normalizedResults.add(normalized);
            }
        }

        int totalEstimate = normalizedResults.size();
        int fromIndex = Math.min(offset, totalEstimate);
        int toIndex = Math.min(fromIndex + limit, totalEstimate);
        List<Map<String, Object>> pagedResults = List.copyOf(normalizedResults.subList(fromIndex, toIndex));

        var payload = new LinkedHashMap<String, Object>();
        payload.put("provider", config.provider());
        payload.put("query", query);
        payload.put("topic", config.topic());
        payload.put("searchDepth", config.searchDepth());
        payload.put("resultCount", pagedResults.size());
        payload.put("totalEstimate", totalEstimate);
        payload.put("hasMore", toIndex < totalEstimate);

        String answer = readString(response, "answer");
        if (!answer.isBlank()) {
            payload.put("answer", answer);
        }

        payload.put("results", pagedResults);
        return ToolResult.success(payload);
    }

    private static RestClient createRestClient(WebSearchConfig config) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(config.connectTimeoutSeconds() * 1000);
        requestFactory.setReadTimeout(config.readTimeoutSeconds() * 1000);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String readString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof String text) {
            return text;
        }
        return "";
    }

    private static Double readDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}

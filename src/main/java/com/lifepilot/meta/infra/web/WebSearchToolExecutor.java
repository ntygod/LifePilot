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
     * <p>搜索降级链：Tavily（主） → DuckDuckGo HTML（兜底）。
     * Tavily 不可用（无 API key 或请求失败）时自动降级到 DuckDuckGo。</p>
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

            // 尝试 Tavily
            if (config.apiKey() != null && !config.apiKey().isBlank()) {
                ToolResult tavilyResult = searchTavily(query.trim(), config, requestSize, offset, limit);
                if (tavilyResult.isSuccess()) {
                    return tavilyResult;
                }
                // Tavily 失败，降级到 DuckDuckGo
                log.warn("Tavily 搜索失败，降级到 DuckDuckGo: query={}, error={}", query, tavilyResult.error());
            } else {
                log.info("Tavily API Key 未配置，使用 DuckDuckGo 搜索: query={}", query);
            }

            // 降级：DuckDuckGo HTML 搜索
            return searchDuckDuckGo(query.trim(), requestSize, offset, limit);
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
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("query", query);
        requestBody.put("search_depth", config.searchDepth());
        requestBody.put("topic", config.topic());
        requestBody.put("max_results", requestSize);
        requestBody.put("include_answer", config.includeAnswer());
        requestBody.put("include_images", false);
        requestBody.put("include_raw_content", false);

        var response = restClientFactory.apply(config).post()
                .uri(config.apiUrl())
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

    /**
     * DuckDuckGo HTML 搜索 — 零 API key 兜底方案。
     *
     * <p>通过 DuckDuckGo 的 HTML 版本（html.duckduckgo.com）获取搜索结果，
     * 解析 HTML 提取标题、摘要和 URL。不需要 API key，但质量略低于 Tavily。</p>
     */
    private ToolResult searchDuckDuckGo(String query, int requestSize, int offset, int limit) {
        try {
            String searchUrl = "https://html.duckduckgo.com/html/?q=" +
                    java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);

            var requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(10_000);
            requestFactory.setReadTimeout(15_000);
            var client = RestClient.builder().requestFactory(requestFactory).build();

            String html = client.get()
                    .uri(searchUrl)
                    .header(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36")
                    .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8")
                    .retrieve()
                    .body(String.class);

            if (html == null || html.isBlank()) {
                return ToolResult.transientError("DuckDuckGo 返回空响应");
            }

            // 解析 HTML 搜索结果
            List<Map<String, Object>> results = parseDuckDuckGoHtml(html);

            int totalEstimate = results.size();
            int fromIndex = Math.min(offset, totalEstimate);
            int toIndex = Math.min(fromIndex + limit, totalEstimate);
            List<Map<String, Object>> pagedResults = List.copyOf(results.subList(fromIndex, toIndex));

            var payload = new LinkedHashMap<String, Object>();
            payload.put("provider", "duckduckgo");
            payload.put("query", query);
            payload.put("resultCount", pagedResults.size());
            payload.put("totalEstimate", totalEstimate);
            payload.put("hasMore", toIndex < totalEstimate);
            payload.put("results", pagedResults);

            log.info("DuckDuckGo 搜索完成: query={}, resultCount={}", query, pagedResults.size());
            return ToolResult.success(payload);
        } catch (Exception e) {
            log.error("DuckDuckGo 搜索失败: query={}, error={}", query, e.getMessage());
            return ToolResult.transientError("搜索失败（Tavily 和 DuckDuckGo 均不可用）: " + e.getMessage());
        }
    }

    /**
     * 解析 DuckDuckGo HTML 搜索结果页。
     *
     * <p>DuckDuckGo HTML 版的结果结构：
     * {@code <div class="result"> <a class="result__a" href="...">title</a> <a class="result__snippet">snippet</a> </div>}
     * </p>
     */
    private List<Map<String, Object>> parseDuckDuckGoHtml(String html) {
        List<Map<String, Object>> results = new ArrayList<>();
        // 使用简单的正则提取，避免引入额外 HTML 解析依赖（Jsoup 在 web 模块不一定可用）
        // 匹配 result__a 链接和 result__snippet
        var linkPattern = java.util.regex.Pattern.compile(
                "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>");
        var snippetPattern = java.util.regex.Pattern.compile(
                "<a[^>]+class=\"result__snippet\"[^>]*>([^<]*(?:<[^>]+>[^<]*)*)</a>");

        var linkMatcher = linkPattern.matcher(html);
        var snippetMatcher = snippetPattern.matcher(html);

        while (linkMatcher.find()) {
            String url = linkMatcher.group(1);
            String title = linkMatcher.group(2).trim();

            // 跳过 DuckDuckGo 内部链接
            if (url.startsWith("//duckduckgo.com")) continue;

            String snippet = "";
            if (snippetMatcher.find()) {
                snippet = snippetMatcher.group(1)
                        .replaceAll("<[^>]+>", "") // 去除 HTML 标签
                        .trim();
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("title", unescapeHtml(title));
            result.put("snippet", unescapeHtml(snippet));
            result.put("url", url);
            results.add(result);
        }
        return results;
    }

    /** 简单 HTML 实体反转义。 */
    private static String unescapeHtml(String text) {
        return text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'");
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

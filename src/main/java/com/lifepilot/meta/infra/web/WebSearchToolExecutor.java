package com.lifepilot.meta.infra.web;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web 搜索工具执行器 — 通过搜索引擎 API 检索信息。
 *
 * <p>支持 google / bing / duckduckgo 三种搜索引擎，通过配置切换。
 * DuckDuckGo 使用免费 Instant Answer API（无需 API Key），
 * Google / Bing 需要配置 API Key。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class WebSearchToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebSearchToolExecutor.class);

    private final MetaProperties properties;
    private final RestClient restClient;

    public WebSearchToolExecutor(MetaProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    /**
     * 执行 Web 搜索。
     *
     * @param input 工具输入，必需参数 query，可选参数 maxResults
     * @return 包含搜索结果列表的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String query = input.getParam("query", String.class);
            int maxResults = input.getOptionalParam("maxResults", Integer.class)
                    .orElse(properties.getInfra().getWebSearch().getMaxResults());
            int offset = input.getOptionalParam("offset", Integer.class)
                    .map(o -> Math.max(o, 0))
                    .orElse(0);
            int limit = input.getOptionalParam("limit", Integer.class)
                    .filter(l -> l >= 1)
                    .orElse(maxResults);

            var config = properties.getInfra().getWebSearch();
            String provider = config.getProvider().toLowerCase();

            return switch (provider) {
                case "duckduckgo" -> searchDuckDuckGo(query, maxResults, offset, limit);
                case "google" -> searchWithApiKey(provider, query, maxResults, config.getApiKey());
                case "bing" -> searchWithApiKey(provider, query, maxResults, config.getApiKey());
                default -> ToolResult.error("不支持的搜索引擎: " + provider + "，支持 google/bing/duckduckgo");
            };
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("Web 搜索失败: {}", e.getMessage(), e);
            return ToolResult.error("Web 搜索失败: " + e.getMessage());
        }
    }

    /**
     * DuckDuckGo 搜索 — 使用免费 Instant Answer API，无需 API Key。
     *
     * <p>调用 {@code https://api.duckduckgo.com/?q={query}&format=json} 获取即时答案，
     * 从 RelatedTopics 中提取搜索结果。</p>
     */
    @SuppressWarnings({"unchecked", "null"})
    private ToolResult searchDuckDuckGo(String query, int maxResults, int offset, int limit) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://api.duckduckgo.com/?q=%s&format=json&no_html=1&skip_disambig=1"
                .formatted(encodedQuery);

        var response = restClient.get()
                .uri(url)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            return ToolResult.error("DuckDuckGo API 返回空响应");
        }

        var results = new ArrayList<Map<String, String>>();

        // 提取 Abstract（摘要）
        String abstractText = (String) response.get("AbstractText");
        String abstractUrl = (String) response.get("AbstractURL");
        String heading = (String) response.get("Heading");
        if (abstractText != null && !abstractText.isBlank()) {
            results.add(Map.of(
                    "title", heading != null ? heading : query,
                    "snippet", abstractText,
                    "url", abstractUrl != null ? abstractUrl : ""
            ));
        }

        // 提取 RelatedTopics
        var relatedTopics = (List<Object>) response.getOrDefault("RelatedTopics", List.of());
        for (Object topic : relatedTopics) {
            if (results.size() >= maxResults) break;
            if (topic instanceof Map<?, ?> topicMap) {
                String text = (String) topicMap.get("Text");
                String firstUrl = (String) topicMap.get("FirstURL");
                if (text != null && !text.isBlank() && firstUrl != null) {
                    results.add(Map.of(
                            "title", extractTitle(text),
                            "snippet", text,
                            "url", firstUrl
                    ));
                }
            }
        }

        // 分页切片
        int totalEstimate = results.size();
        int fromIndex = Math.min(offset, totalEstimate);
        int toIndex = Math.min(fromIndex + limit, totalEstimate);
        var paged = results.subList(fromIndex, toIndex);
        boolean hasMore = toIndex < totalEstimate;

        return ToolResult.success(Map.of(
                "provider", "duckduckgo",
                "query", query,
                "resultCount", paged.size(),
                "totalEstimate", totalEstimate,
                "hasMore", hasMore,
                "results", List.copyOf(paged)
        ));
    }

    /** Google / Bing 搜索 — 需要 API Key。 */
    private ToolResult searchWithApiKey(String provider, String query, int maxResults, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return ToolResult.error(
                    "%s 搜索需要配置 API Key，请在 lifepilot.meta.infra.web-search.api-key 中设置"
                            .formatted(provider));
        }

        // Google / Bing API 调用预留，当前返回提示信息
        return ToolResult.error(
                "%s 搜索引擎 API 集成尚未实现，请使用 duckduckgo（无需 API Key）"
                        .formatted(provider));
    }

    /** 从 DuckDuckGo RelatedTopics 文本中提取标题（取第一句或前 50 字符）。 */
    private String extractTitle(String text) {
        int dashIndex = text.indexOf(" - ");
        if (dashIndex > 0 && dashIndex < 80) {
            return text.substring(0, dashIndex);
        }
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}
